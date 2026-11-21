//! 受控端组合根：把配置、存储、配对、审批、会话与 iroh 服务接在一起。

use crate::approval::{ApprovalEvent, ApprovalManager};
use crate::clock::{Clock, IdGen, SeqIdGen, SystemClock, UlidGen};
use crate::config::{Config, Secrets};
use crate::error::{CoreError, Result};
use crate::guard;
use crate::pairing::PairingSession;
use crate::paths::Paths;
use crate::pi::resolve_pi;
use crate::server::Server;
use crate::sessions::SessionManager;
use crate::store::Store;
use pi_carry_protocol::events as ev;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::broadcast;

/// audit 日志保留天数（与 journal 的配置化保留期解耦）。
const AUDIT_RETENTION_DAYS: i64 = 30;

pub struct BridgeOptions {
    pub config: Config,
    pub paths: Paths,
    /// 测试注入；None 使用系统时钟。
    pub clock: Option<Arc<dyn Clock>>,
}

impl BridgeOptions {
    pub fn load() -> Result<Self> {
        let paths = Paths::discover();
        let config = Config::load(&paths)?;
        Ok(Self {
            config,
            paths,
            clock: None,
        })
    }
}

pub struct Bridge {
    pub config: Config,
    pub paths: Paths,
    pub store: Store,
    pub secrets: Secrets,
    pub pairing: crate::pairing::PairingManager,
    pub approvals: ApprovalManager,
    pub sessions: Arc<SessionManager>,
    pub server: Arc<Server>,
    pub clock: Arc<dyn Clock>,
}

impl Bridge {
    pub async fn start(options: BridgeOptions) -> Result<Arc<Self>> {
        let BridgeOptions {
            config,
            paths,
            clock,
        } = options;
        config.validate()?;
        paths
            .ensure()
            .map_err(|e| CoreError::Config(format!("创建 {} 失败: {e}", paths.home.display())))?;
        std::fs::create_dir_all(&config.sessions.dir).map_err(|e| {
            CoreError::Config(format!("创建会话目录 {} 失败: {e}", config.sessions.dir))
        })?;
        let guard_path = guard::ensure(&paths)?;
        let store = Store::open(&paths.db())?;
        // 上一次进程退出时遗留的 online=1 不能带到本次运行。
        store.mark_all_devices_offline()?;
        let secrets = Secrets::load_or_create(&paths)?;
        let clock: Arc<dyn Clock> = clock.unwrap_or_else(|| Arc::new(SystemClock));
        let ids: Arc<dyn IdGen> = Arc::new(UlidGen);
        let pairing = crate::pairing::PairingManager::new(clock.clone());
        let (approval_tx, _) = broadcast::channel::<ApprovalEvent>(512);
        let approvals = ApprovalManager::new(
            store.clone(),
            config.approvals.clone(),
            clock.clone(),
            Arc::new(SeqIdGen::new()),
            approval_tx,
        );
        let pi_path = resolve_pi(Some(&config.node.pi_path)).unwrap_or_else(|e| {
            tracing::warn!(error = %e, "pi 未找到，会话操作将失败");
            PathBuf::from(&config.node.pi_path)
        });
        let sessions = SessionManager::new(
            config.clone(),
            paths.clone(),
            store.clone(),
            clock.clone(),
            ids,
            approvals.clone(),
            pi_path,
            guard_path,
        );
        let server = Server::start(
            config.clone(),
            paths.clone(),
            store.clone(),
            sessions.clone(),
            pairing.clone(),
            secrets.clone(),
            clock.clone(),
        )
        .await?;
        let bridge = Arc::new(Self {
            config,
            paths,
            store,
            secrets,
            pairing,
            approvals,
            sessions,
            server,
            clock,
        });
        bridge.start_approval_forwarder();
        bridge.start_expiry_ticker();
        Ok(bridge)
    }

    fn start_approval_forwarder(self: &Arc<Self>) {
        let approvals = self.approvals.clone();
        let sessions = self.sessions.clone();
        tokio::spawn(async move {
            let mut rx = approvals.subscribe();
            loop {
                match rx.recv().await {
                    Ok(ev) => {
                        if let Err(e) = sessions.emit_raw(&ev.session_id, ev.event) {
                            tracing::warn!(
                                session_id = %ev.session_id,
                                error = %e,
                                "审批事件写入 journal 失败"
                            );
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!(skipped = n, "审批事件订阅落后");
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        });
    }

    fn start_expiry_ticker(self: &Arc<Self>) {
        let approvals = self.approvals.clone();
        let sessions = self.sessions.clone();
        let bridge = self.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(Duration::from_secs(5));
            tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let mut ticks: u64 = 0;
            loop {
                tick.tick().await;
                if let Err(e) = approvals.expire_due() {
                    tracing::warn!(error = %e, "审批过期扫描失败");
                }
                // 保持会话摘要的 updated_at 与磁盘一致，成本很低。
                let _ = sessions.list();
                ticks += 1;
                // 保留策略只需每 5 分钟跑一次。
                if ticks % 60 == 0 {
                    if let Err(e) = bridge.retention_sweep() {
                        tracing::warn!(error = %e, "journal/audit 清理失败");
                    }
                }
            }
        });
    }

    /// 按配置的保留天数与每会话事件上限清理 journal，并清理超过 30 天的 audit。
    /// 只操作 SQL 行，不读取会话文件内容。
    fn retention_sweep(&self) -> Result<()> {
        let now = self.clock.now();
        let cfg = &self.config.sessions;
        let journal_cutoff =
            crate::rfc3339(now - chrono::Duration::days(cfg.journal_retention_days));
        self.store.prune_journal_before(&journal_cutoff)?;
        for id in self.store.journal_session_ids()? {
            self.store.prune_journal(&id, cfg.journal_max_events)?;
        }
        let audit_cutoff = crate::rfc3339(now - chrono::Duration::days(AUDIT_RETENTION_DAYS));
        self.store.prune_audit_before(&audit_cutoff)?;
        Ok(())
    }

    pub fn endpoint_id(&self) -> String {
        self.server.endpoint_id()
    }

    /// 当前传输模式下应写进二维码的 relay 提示。
    pub fn relay_hint(&self) -> Option<String> {
        match self.config.transport.relay_mode.as_str() {
            "disabled" => None,
            "custom" => self.config.transport.relay_urls.first().cloned(),
            _ => Some("n0".into()),
        }
    }

    pub fn begin_pairing(&self, relay: Option<String>) -> PairingSession {
        let relay = relay.or_else(|| self.relay_hint());
        self.pairing.begin_with_addrs(
            &self.endpoint_id(),
            relay,
            self.server.lan_addrs(),
            self.pairing_token(),
            self.pairing_code(),
        )
    }

    fn pairing_token(&self) -> String {
        use rand::Rng;
        (0..16)
            .map(|_| format!("{:02x}", rand::thread_rng().gen::<u8>()))
            .collect()
    }

    fn pairing_code(&self) -> String {
        use rand::Rng;
        format!("{:06}", rand::thread_rng().gen_range(0..1_000_000u32))
    }

    pub fn node_status(&self) -> Value {
        let pairing = self.pairing.status();
        json!({
            "endpointId": self.endpoint_id(),
            "nodeName": self.config.node.name,
            "relayMode": self.config.transport.relay_mode,
            "directAddresses": self.server.endpoint().addr().addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>(),
            "pairing": pairing,
            "devices": self.store.list_devices().unwrap_or_default(),
            "sessions": self.sessions.list().unwrap_or_default(),
            "piInstalled": crate::pi::resolve_pi(Some(&self.config.node.pi_path)).is_ok(),
            "version": crate::VERSION,
        })
    }

    /// 本地诊断事件：不经过网络，只写 journal。
    pub fn local_event(&self, session_id: &str, event: Value) -> Result<u64> {
        self.sessions.emit_raw(session_id, event)
    }

    pub async fn shutdown(&self) {
        self.server.shutdown().await;
        self.sessions.shutdown().await;
    }

    /// 阻塞运行，直到 Ctrl+C。
    pub async fn run_until_signal(self: Arc<Self>) -> Result<()> {
        let pairing = self.begin_pairing(None);
        tracing::info!(
            endpoint = %self.endpoint_id(),
            code = %pairing.code,
            "pi-carry 已启动"
        );
        println!(
            "{}",
            serde_json::to_string_pretty(&json!({
                "endpointId": self.endpoint_id(),
                "nodeName": self.config.node.name,
                "pairUri": pairing.uri,
                "pairCode": pairing.code,
                "expiresAt": pairing.expires_at,
            }))?
        );
        tokio::signal::ctrl_c()
            .await
            .map_err(|e| CoreError::Internal(format!("监听 Ctrl+C 失败: {e}")))?;
        self.shutdown().await;
        Ok(())
    }

    /// 供冒烟测试使用：触发一条本地通知事件。
    pub async fn smoke_ping(&self) -> Result<()> {
        self.local_event(
            "smoke",
            json!({"type": ev::NOTIFICATION, "level": "info", "message": "smoke ok"}),
        )?;
        Ok(())
    }
}
