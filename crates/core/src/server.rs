//! iroh 传输层：监听、认证、控制流、事件流与权限。

use crate::approval::ApprovalManager;
use crate::clock::Clock;
use crate::config::{Config, Secrets};
use crate::error::{CoreError, Result};
use crate::pairing::PairingManager;
use crate::paths::Paths;
use crate::sessions::SessionManager;
use crate::store::{DeviceRecord, Store};
use chrono::Utc;
use iroh::endpoint::presets;
use iroh::{Endpoint, RelayMode, SecretKey};
use pi_carry_protocol::{
    encode_frame, read_frame, write_frame, ApprovalDecision, ClientFrame, DeviceInfo, ErrorCode,
    HelloPayload, HelloResponse, NodeInfo, PromptPayload, ProtocolError, ServerFrame,
    UiResponsePayload, ALPN, MAX_CONNECTIONS_PER_DEVICE, MAX_CONTROL_FRAME,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{broadcast, mpsc};

pub struct Server {
    endpoint: Endpoint,
    sessions: Arc<SessionManager>,
    store: Store,
    config: Config,
    pairing: PairingManager,
    secrets: Secrets,
    pi_installed: bool,
    /// `pi --version` 的探测结果；空串表示未探测到（不影响连接，只是客户端看不到版本）。
    pi_version: String,
    /// 同一设备可能短暂存在新旧两条连接（重连竞态）；只有最后一条断开才算离线。
    active_connections: Mutex<HashMap<String, usize>>,
    /// 每台设备最近一次连接方式（连接因设备而异，不能走全局广播）。
    conn_infos: Mutex<HashMap<String, crate::conninfo::ConnInfo>>,
    /// 本进程内刚被吊销的设备：控制循环每帧检查，让既有连接主动断开。
    revoked_devices: Mutex<HashSet<String>>,
}

struct Auth {
    device: DeviceRecord,
}

impl Server {
    pub async fn start(
        config: Config,
        _paths: Paths,
        store: Store,
        sessions: Arc<SessionManager>,
        pairing: PairingManager,
        secrets: Secrets,
        clock: Arc<dyn Clock>,
    ) -> Result<Arc<Self>> {
        let key_bytes = secrets.endpoint_key()?;
        let key: [u8; 32] = key_bytes
            .try_into()
            .map_err(|_| CoreError::Config("endpoint secret 必须是 32 字节".into()))?;
        let secret = SecretKey::from_bytes(&key);
        let mut builder = match config.transport.relay_mode.as_str() {
            "disabled" => Endpoint::builder(presets::Minimal).relay_mode(RelayMode::Disabled),
            // 与配置校验保持同一集合：`default` 是 `n0` 的别名。
            "n0" | "default" => Endpoint::builder(presets::N0),
            "custom" => {
                let urls = config
                    .transport
                    .relay_urls
                    .iter()
                    .map(|u| {
                        u.parse::<iroh::RelayUrl>()
                            .map_err(|e| CoreError::Config(format!("relay URL 非法 {u}: {e}")))
                    })
                    .collect::<Result<Vec<_>>>()?;
                Endpoint::builder(presets::Minimal).relay_mode(RelayMode::custom(urls))
            }
            other => {
                return Err(CoreError::Config(format!(
                    "relayMode 必须是 n0/disabled/custom，实际 {other}"
                )))
            }
        };
        builder = builder.secret_key(secret).alpns(vec![ALPN.to_vec()]);
        if let Some(addr) = &config.transport.bind_addr {
            let addr: SocketAddr = addr
                .parse()
                .map_err(|e| CoreError::Config(format!("bindAddr 非法 {addr}: {e}")))?;
            builder = builder
                .bind_addr(addr)
                .map_err(|e| CoreError::Config(format!("bindAddr 非法: {e}")))?;
        }
        let endpoint = builder
            .bind()
            .await
            .map_err(|e| CoreError::Internal(format!("iroh 绑定失败: {e}")))?;
        if config.transport.relay_mode != "disabled" {
            // 不阻塞启动：后台等待 relay 上线。
            let ep = endpoint.clone();
            tokio::spawn(async move {
                ep.online().await;
            });
        }
        // 启动时探测一次并缓存：`hello` 就要报版本，且客户端会用它判断
        // `get_state`/`prompt` 是否可用（低于 1.5.0 要警告）。
        let mut pi_version = String::new();
        let pi_installed = match crate::pi::resolve_pi(Some(&config.node.pi_path)) {
            Ok(path) => {
                pi_version = crate::pi::detect_version(&path);
                if pi_version.is_empty() {
                    tracing::warn!(pi = %path.display(), "未能探测 pi 版本，节点将报告空版本");
                }
                true
            }
            Err(_) => false,
        };
        let server = Arc::new(Self {
            endpoint,
            sessions,
            store,
            config,
            pairing,
            secrets,
            pi_installed,
            pi_version,
            active_connections: Mutex::new(HashMap::new()),
            conn_infos: Mutex::new(HashMap::new()),
            revoked_devices: Mutex::new(HashSet::new()),
        });
        let _ = clock.now();
        let s = server.clone();
        tokio::spawn(async move {
            s.accept_loop().await;
        });
        Ok(server)
    }

    /// 拼一个直连地址：IPv6 必须加方括号，否则 `host:port` 解析会把末尾段当成端口。
    fn format_addr(ip: IpAddr, port: u16) -> String {
        if ip.is_ipv6() {
            format!("[{ip}]:{port}")
        } else {
            format!("{ip}:{port}")
        }
    }

    /// 局域网可直连地址，供配对 URI 携带；包含回环和本机出口 IP。
    pub fn lan_addrs(&self) -> Vec<String> {
        use std::net::{Ipv4Addr, Ipv6Addr};

        let sockets = self.endpoint.bound_sockets();
        let mut ips: Vec<IpAddr> = vec![
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            IpAddr::V6(Ipv6Addr::LOCALHOST),
        ];
        // 枚举所有物理/虚拟网卡，而不是只取默认路由出口。
        if let Ok(interfaces) = if_addrs::get_if_addrs() {
            for iface in interfaces {
                if iface.is_loopback() {
                    continue;
                }
                let ip = iface.ip();
                // 链路本地地址（fe80::/10）不带 zone id，Android 拨不通，广告它只会制造超时。
                if !ip.is_unspecified()
                    && !crate::conninfo::skip_for_advertisement(ip)
                    && !ips.contains(&ip)
                {
                    ips.push(ip);
                }
            }
        }

        let mut out = Vec::new();
        for ip in ips {
            for sock in &sockets {
                // IPv4 地址只配 IPv4 socket，IPv6 同理，避免生成不可达组合。
                if sock.is_ipv4() != ip.is_ipv4() {
                    continue;
                }
                let port = sock.port();
                out.push(Self::format_addr(ip, port));
            }
        }
        out.sort();
        out.dedup();
        out
    }

    pub fn endpoint_id(&self) -> String {
        self.endpoint.id().to_string()
    }

    pub fn pairing(&self) -> &PairingManager {
        &self.pairing
    }

    pub fn endpoint(&self) -> &Endpoint {
        &self.endpoint
    }

    pub async fn shutdown(&self) {
        self.endpoint.close().await;
        self.sessions.shutdown().await;
    }

    fn node_info(&self) -> NodeInfo {
        let addr = self.endpoint.addr();
        let direct: Vec<String> = addr
            .addrs
            .iter()
            .filter_map(|a| match a {
                iroh::TransportAddr::Ip(ip) => Some(ip.to_string()),
                iroh::TransportAddr::Relay(r) => Some(format!("relay:{r}")),
                _ => None,
            })
            .collect();
        NodeInfo {
            id: self.endpoint.id().to_string(),
            name: self.config.node.name.clone(),
            platform: std::env::consts::OS.to_string(),
            app_version: crate::VERSION.to_string(),
            pi_version: self.pi_version.clone(),
            pi_installed: self.pi_installed,
            cwd: std::env::current_dir()
                .map(|p| p.display().to_string())
                .unwrap_or_default(),
            relay_mode: self.config.transport.relay_mode.clone(),
            direct_addresses: direct,
            capabilities: vec![
                "chat".into(),
                "images".into(),
                "approvals".into(),
                "models".into(),
                "commands".into(),
                "fork".into(),
                "self-hosted-relay".into(),
                // 0.1.4 新增：目录浏览、本轮总结、连接方式。老客户端按特征串判断能力，
                // 新客户端据此降级（缺 fs-browse 只给默认目录，缺 connection-info 不显示连接方式）。
                "fs-browse".into(),
                "run-summary".into(),
                "connection-info".into(),
            ],
        }
    }

    async fn accept_loop(self: Arc<Self>) {
        while let Some(incoming) = self.endpoint.accept().await {
            let me = self.clone();
            tokio::spawn(async move {
                match incoming.await {
                    Ok(conn) => {
                        if let Err(e) = me.handle_connection(conn).await {
                            tracing::warn!(error = %e, "连接处理失败");
                        }
                    }
                    Err(e) => tracing::warn!(error = %e, "iroh 握手失败"),
                }
            });
        }
    }

    // 收 `&Arc<Self>` 而不是 `&self`：连接方式轮询任务需要在连接存续期间回写
    // `conn_infos`（见 conn_watch），而它被 spawn 出去后 `&self` 不够活。
    async fn handle_connection(self: &Arc<Self>, conn: iroh::endpoint::Connection) -> Result<()> {
        let remote_id = conn.remote_id().to_string();
        let (mut send, mut recv) = conn
            .accept_bi()
            .await
            .map_err(|e| CoreError::Internal(format!("接受控制流失败: {e}")))?;
        let first = tokio::time::timeout(
            std::time::Duration::from_secs(20),
            read_frame(&mut recv, MAX_CONTROL_FRAME),
        )
        .await
        .map_err(|_| CoreError::Protocol(ProtocolError::new(ErrorCode::AuthFailed, "握手超时")))?
        .map_err(|e| {
            CoreError::Protocol(ProtocolError::new(
                ErrorCode::InvalidArgument,
                e.to_string(),
            ))
        })?
        .ok_or_else(|| {
            CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthFailed,
                "连接在握手前关闭",
            ))
        })?;
        let frame: ClientFrame = serde_json::from_slice(&first).map_err(|e| {
            CoreError::Protocol(ProtocolError::new(
                ErrorCode::InvalidArgument,
                e.to_string(),
            ))
        })?;
        let ClientFrame::Request { id, op, payload } = frame else {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthFailed,
                "首帧必须是 hello 请求",
            )));
        };
        if op != "hello" {
            let err = ServerFrame::err(
                id,
                ProtocolError::new(ErrorCode::AuthRequired, "首帧必须是 hello"),
            );
            let _ = write_frame(&mut send, &err, MAX_CONTROL_FRAME).await;
            return Ok(());
        }
        let hello: HelloPayload = serde_json::from_value(payload).map_err(|e| {
            CoreError::Protocol(ProtocolError::new(
                ErrorCode::InvalidArgument,
                format!("hello 载荷非法: {e}"),
            ))
        })?;
        let auth = match self.authenticate(&remote_id, &hello).await {
            Ok(a) => a,
            Err(e) => {
                let _ = write_frame(
                    &mut send,
                    &ServerFrame::err(id, e.to_protocol()),
                    MAX_CONTROL_FRAME,
                )
                .await;
                // 给客户端一点时间读完错误响应再关闭连接。
                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                return Ok(());
            }
        };

        let node = self.node_info();
        let device_info = device_info(&auth.device, true);
        // 认证后立即取一次实测路径：客户端扫码连上时就能显示连接方式，
        // 不必等到第一个 path_events 变化。
        let initial_conn = Self::conn_info_for(&conn);
        let resp = ServerFrame::ok(
            id,
            serde_json::to_value(HelloResponse {
                protocol: pi_carry_protocol::PROTOCOL_VERSION.into(),
                node: node.clone(),
                device: device_info,
                session_id: None,
                // 服务端时间是权威时钟：手机端的审批倒计时与「最近活跃」都应以它为准（R20）。
                server_time: Some(crate::rfc3339(Utc::now())),
                server_version: Some(crate::VERSION.to_string()),
                connection: Some(initial_conn.to_json()),
            })?,
        );
        write_frame(&mut send, &resp, MAX_CONTROL_FRAME)
            .await
            .map_err(|e| CoreError::Internal(format!("发送 hello 响应失败: {e}")))?;
        // 连接数上限在 hello 之后检查：此时已经认证成功，才能按设备计数；
        // 超限时先把错误帧写回去再断开，客户端能看到明确原因而不是莫名重连。
        if let Err(e) = self.connection_opened(&auth.device.id) {
            let _ = write_frame(
                &mut send,
                &ServerFrame::err("conn-limit", e.to_protocol()),
                MAX_CONTROL_FRAME,
            )
            .await;
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            return Ok(());
        }
        // 从这里开始计数已生效，后面的所有早退路径都必须走到 connection_closed。

        // 事件和控制响应共用同一条双向控制流：
        // 1) 顺序有保证，客户端不需要额外 accept_uni；
        // 2) 避免 iroh 单向流在某些网络路径上的缓冲/交付差异；
        // 3) 客户端只维护一个读取循环，响应按 id 关联，事件直接分发。
        let (out_tx, mut out_rx) = mpsc::channel::<ServerFrame>(512);
        let writer = tokio::spawn(async move {
            while let Some(frame) = out_rx.recv().await {
                if let Err(e) = write_frame(&mut send, &frame, 4 * 1024 * 1024).await {
                    tracing::warn!(error = %e, "控制流写入结束");
                    break;
                }
            }
            // 在连接关闭前显式 finish 并等待对端确认：直接 conn.close() 会丢弃
            // 尚未交付的数据，吊销通知、最后的错误响应这类“临别帧”会静默丢失。
            let _ = send.finish();
            let _ = tokio::time::timeout(std::time::Duration::from_secs(2), send.stopped()).await;
        });
        // 连接方式因设备而异，必须直接发给本连接，不能走全局广播：
        // 同一台节点可能一条连接走局域网、另一条走 relay。
        self.set_conn_info(&auth.device.id, initial_conn.clone());
        let conn_watch = {
            let out = out_tx.clone();
            let conn = conn.clone();
            let me = Arc::clone(self);
            let device_id = auth.device.id.clone();
            tokio::spawn(async move {
                let mut last = Self::conn_info_for(&conn);
                // 不在这里补发首帧：hello 响应里的 `connection` 已经是同一份数据，
                // 开机就再推一条会让客户端在 hello 响应之前先收到事件，
                // 既有调用方按「请求 → 响应」解析就会错位。只有真正切换路径时才推。
                //
                // 轮询 `paths()` 而不是订阅 `path_events()`：前者不需要引入
                // StreamExt 依赖，也不持有连接引用跨 await；路径切换是秒级事件，
                // 1s 的采样延迟对「2 秒内切到 relay」的验收要求完全够用。
                let mut tick = tokio::time::interval(Duration::from_secs(1));
                tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
                loop {
                    tick.tick().await;
                    if conn.close_reason().is_some() {
                        break;
                    }
                    let now = Self::conn_info_for(&conn);
                    // 只有类型或远端地址变化才重发：rtt 每秒都在抖，不能当变化。
                    if now.same_route(&last) {
                        continue;
                    }
                    last = now;
                    // 同一份实测路径必须同时回写服务端快照：桌面端徽章读的是
                    // `node_status()` 里的 `conn_infos`，只发事件会让移动端（走事件）
                    // 与桌面端（走快照）在 LAN↔relay 切换后长期不一致。
                    me.set_conn_info(&device_id, last.clone());
                    let frame = ServerFrame::Event {
                        session_id: None,
                        seq: 0,
                        event: last.event(),
                    };
                    if out.send(frame).await.is_err() {
                        break;
                    }
                }
            })
        };
        let forwarder = {
            let sessions = self.sessions.clone();
            let out = out_tx.clone();
            let sid = auth.device.id.clone();
            let cap = auth.device.capability.clone();
            tokio::spawn(async move {
                let mut rx = sessions.subscribe();
                loop {
                    match rx.recv().await {
                        Ok(ev) => {
                            // 全局广播会把每个会话的事件送到所有设备；敏感事件
                            // （含完整工具参数/命令明文）只发给能处理它们的控制端。
                            // read 档不是整条丢弃，而是发一份裁掉隐私字段的版本。
                            let Some(event) = forward_event_for(&cap, &ev.event) else {
                                continue;
                            };
                            let frame = ServerFrame::Event {
                                session_id: Some(ev.session_id),
                                seq: ev.seq,
                                event,
                            };
                            if out.send(frame).await.is_err() {
                                break;
                            }
                        }
                        Err(broadcast::error::RecvError::Lagged(n)) => {
                            tracing::warn!(device = %sid, skipped = n, "事件订阅落后，要求快照");
                            let frame = ServerFrame::Event {
                                session_id: None,
                                seq: 0,
                                event: json!({
                                    "type": "error",
                                    "code": "SNAPSHOT_REQUIRED",
                                    "message": format!("事件流落后 {n} 条，请重新拉取快照"),
                                }),
                            };
                            let _ = out.send(frame).await;
                        }
                        Err(broadcast::error::RecvError::Closed) => break,
                    }
                }
            })
        };

        let result = self
            .control_loop(&conn, &mut recv, &out_tx, &auth, &node)
            .await;
        drop(out_tx);
        conn_watch.abort();
        forwarder.abort();
        let _ = writer.await;
        // 旧连接清理不能把刚建立的新连接误标为离线：减计数、清 conn_info、标离线
        // 三步在 `connection_closed` 的同一把锁内完成（时序见该方法注释）。
        self.connection_closed(&auth.device.id);
        conn.close(0u32.into(), b"bye");
        result
    }

    /// 设备是否已失效（被吊销、已删除，或本进程内刚吊销）。
    fn device_is_revoked(&self, device_id: &str) -> bool {
        if self
            .revoked_devices
            .lock()
            .expect("revoked poisoned")
            .contains(device_id)
        {
            return true;
        }
        match self.store.get_device(device_id) {
            // 读库失败时宁可断开（fail-closed），也不让已吊销设备继续收发。
            Ok(Some(d)) => d.revoked_at.is_some(),
            Ok(None) => true,
            Err(e) => {
                tracing::warn!(device = %device_id, error = %e, "读取设备状态失败");
                true
            }
        }
    }

    /// 本连接当前实测的连接方式。
    ///
    /// 只读 `paths()` 的快照：没样本时 rtt 为 0，转成 `None` 而不是伪造 0ms。
    /// 选中路径优先；没有选中路径时退回第一条 IP 路径（刚建连时选中标记可能尚未落定）。
    pub fn conn_info_for(conn: &iroh::endpoint::Connection) -> crate::conninfo::ConnInfo {
        use crate::conninfo::{classify, ConnInfo, ConnType};

        let paths = conn.paths();
        let selected = paths.iter().find(|p| p.is_selected());
        let path = selected.or_else(|| paths.iter().find(|p| p.is_ip()));
        let Some(path) = path else {
            return ConnInfo::new(ConnType::P2p, None, None, None);
        };
        let remote = path.remote_addr();
        let kind = match remote {
            iroh::TransportAddr::Relay(_) => classify(Some(&crate::conninfo::AddrKind::Relay)),
            iroh::TransportAddr::Ip(addr) => {
                classify(Some(&crate::conninfo::AddrKind::Ip(addr.ip())))
            }
            _ => classify(Some(&crate::conninfo::AddrKind::Custom)),
        };
        let rtt = path.rtt();
        let rtt_ms = if rtt.is_zero() {
            None
        } else {
            Some(rtt.as_millis() as u64)
        };
        ConnInfo::new(
            kind,
            Some(remote.to_string()),
            Some(local_addr_string(path.local_addr())),
            rtt_ms,
        )
    }

    /// 记录设备的连接方式；同一设备多连接以最新一条为准。
    fn set_conn_info(&self, device_id: &str, info: crate::conninfo::ConnInfo) {
        self.conn_infos
            .lock()
            .expect("conn_infos poisoned")
            .insert(device_id.to_string(), info);
    }

    /// 设备已无连接时清除连接方式，避免桌面端拿旧快照显示「v6 直连」。
    fn clear_conn_info(&self, device_id: &str) {
        self.conn_infos
            .lock()
            .expect("conn_infos poisoned")
            .remove(device_id);
    }

    /// 每台设备最近一次连接方式（桌面设备列表用）。
    pub fn conn_infos(&self) -> HashMap<String, crate::conninfo::ConnInfo> {
        self.conn_infos.lock().expect("conn_infos poisoned").clone()
    }

    /// 登记一条新连接。返回 `Err` 表示该设备并发连接已超上限，
    /// 调用方必须拒绝这条连接并把错误回给客户端。
    ///
    /// 这把锁也是收尾时序的锚点：`connection_closed` 在同一把锁内完成
    /// 「计数归零 → 清 conn_info → 标离线」，本函数因此无法插进那个临界区。
    /// 锁内不取 `conn_infos`/store，不会与收尾路径互为锁等待。
    fn connection_opened(&self, device_id: &str) -> Result<()> {
        let mut active = self.active_connections.lock().expect("active poisoned");
        let count = active.entry(device_id.to_string()).or_insert(0);
        if *count >= MAX_CONNECTIONS_PER_DEVICE {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::RateLimited,
                format!(
                    "该设备已有 {count} 条连接（上限 {MAX_CONNECTIONS_PER_DEVICE}），请先关闭旧连接"
                ),
            )));
        }
        *count += 1;
        Ok(())
    }

    /// 连接收尾：「减计数 → 清 conn_info → 标离线」全部在同一把
    /// `active_connections` 锁内完成，中途不让出临界区。
    ///
    /// 时序（V-03 竞态）：新连接必须先在 `connection_opened` 里拿同一把锁登记，
    /// 因此它与这里的清理互斥。若不互斥，会出现：旧连接减到 0 ⇒ 新连接登记 +
    /// `set_conn_info` ⇒ 旧连接接着 `clear_conn_info` 抹掉新连接的快照、再把设备
    /// 标成离线（`online=true` 只在认证时写入，不会自愈）。
    ///
    /// 死锁分析：锁序固定为 `active_connections → revoked_devices / conn_infos →
    /// store`。全局没有任何反方向获取——`set_conn_info`/`conn_infos()` 只取
    /// `conn_infos`，`connection_opened` 只取 `active_connections`，store 方法只取
    /// 自己的 sqlite 连接，两者都不回头取 `active_connections`。
    fn connection_closed(&self, device_id: &str) {
        let mut active = self.active_connections.lock().expect("active poisoned");
        match active.get_mut(device_id) {
            // 还有别的连接在：既不算离线，也不能动共享的 conn_info。
            Some(count) if *count > 1 => {
                *count -= 1;
                return;
            }
            Some(_) => {
                active.remove(device_id);
            }
            None => {}
        }
        // 最后一条连接已关，吊销标记不必再留着（已经没连接可踢）。
        self.revoked_devices
            .lock()
            .expect("revoked poisoned")
            .remove(device_id);
        // 连接方式快照必须一并清掉，否则桌面设备列表会长期展示一条已不存在路径的「v6 直连」。
        self.clear_conn_info(device_id);
        // 在线标记同理：只有与新连接登记互斥，才不会把刚连上的设备误标成离线。
        if let Err(e) = self
            .store
            .touch_device(device_id, &crate::rfc3339(Utc::now()), false)
        {
            tracing::warn!(device = %device_id, error = %e, "标记设备离线失败");
        }
    }

    async fn authenticate(&self, remote_id: &str, hello: &HelloPayload) -> Result<Auth> {
        if hello.protocol != pi_carry_protocol::PROTOCOL_VERSION {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::ProtocolVersionUnsupported,
                format!(
                    "协议版本 {} 不支持，需要 {}",
                    hello.protocol,
                    pi_carry_protocol::PROTOCOL_VERSION
                ),
            )));
        }
        if let Some(d) = self.store.get_device(remote_id)? {
            if d.revoked_at.is_some() {
                return Err(CoreError::Protocol(ProtocolError::new(
                    ErrorCode::AuthFailed,
                    "设备已被吊销",
                )));
            }
            // 已配对设备可选提供密码作为二次认证。
            // 现状：全仓无任何写入 password_hash 的入口（CLI/桌面/手机都没有），
            // 也没有客户端会发 password，所以这是个尚未接线的可选能力。
            // 这里刻意保持「可选」而不是「强制」：一旦有人在 secrets.json 里
            // 手写密码，强制模式会让所有现有客户端直接连不上（无 UI 可填），
            // 把节点锁死。要真正启用它，必须先把「设置密码 + 客户端填写」
            // 一并做完再改成必填。
            if let Some(pw) = &hello.password {
                self.verify_password(pw)?;
            }
            let now = crate::rfc3339(Utc::now());
            let mut d = d;
            // 设备重连时刷新展示信息；手机型号/系统名可能升级后变化。
            if !hello.client.name.trim().is_empty() {
                d.name = hello.client.name.clone();
            }
            if !hello.client.platform.trim().is_empty() {
                d.platform = hello.client.platform.clone();
            }
            if !hello.client.device_class.trim().is_empty() {
                d.device_class = hello.client.device_class.clone();
            }
            d.online = true;
            d.last_seen = now.clone();
            self.store.upsert_device(&d)?;
            self.store
                .audit(&now, remote_id, "device.connect", &json!({"name": d.name}))?;
            return Ok(Auth { device: d });
        }

        // 新设备：必须带 token/code 或正确密码（密码不能单独完成首次配对）。
        let verified = if let Some(token) = &hello.token {
            self.pairing.verify_token(token)?
        } else if let Some(code) = &hello.code {
            self.pairing.verify_code(code)?
        } else {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthRequired,
                "设备未配对：请扫描二维码或输入 6 位码",
            )));
        };
        if !verified {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthFailed,
                "配对码错误",
            )));
        }
        // 先查设备数上限再烧配对码：否则超限时用户会得到一个已失效的码，
        // 只能重新开始配对。（以前 confirm() 在前，错误提示是“上限”但码已经用掉。）
        let count = self
            .store
            .list_devices()?
            .into_iter()
            .filter(|d| d.revoked_at.is_none())
            .count();
        if count >= self.config.transport.max_devices {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::RateLimited,
                format!(
                    "设备数已达上限 {}，请先在桌面端吊销旧设备",
                    self.config.transport.max_devices
                ),
            )));
        }
        self.pairing.confirm()?;
        // 新设备能力完全由服务端策略决定：客户端自报的 deviceClass 不可信，
        // 不能凭它拿到高于 defaultCapability 的能力。
        let capability = self.config.sessions.default_capability.clone();
        let now = crate::rfc3339(Utc::now());
        let device = DeviceRecord {
            id: remote_id.to_string(),
            name: hello.client.name.clone(),
            platform: hello.client.platform.clone(),
            device_class: hello.client.device_class.clone(),
            capability,
            paired_at: now.clone(),
            last_seen: now.clone(),
            revoked_at: None,
            online: true,
        };
        self.store.upsert_device(&device)?;
        self.store.audit(
            &now,
            remote_id,
            "device.paired",
            &json!({
                "name": device.name,
                "platform": device.platform,
                "capability": device.capability,
            }),
        )?;
        Ok(Auth { device })
    }

    fn verify_password(&self, password: &str) -> Result<()> {
        if self.secrets.password_hash.is_empty() {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthFailed,
                "节点未设置密码",
            )));
        }
        let hash = &self.secrets.password_hash;
        use argon2::password_hash::{PasswordHash, PasswordVerifier};
        let parsed =
            PasswordHash::new(hash).map_err(|e| CoreError::Config(format!("密码哈希损坏: {e}")))?;
        argon2::Argon2::default()
            .verify_password(password.as_bytes(), &parsed)
            .map_err(|_| CoreError::Protocol(ProtocolError::new(ErrorCode::AuthFailed, "密码错误")))
    }

    async fn control_loop(
        &self,
        conn: &iroh::endpoint::Connection,
        recv: &mut iroh::endpoint::RecvStream,
        out: &mpsc::Sender<ServerFrame>,
        auth: &Auth,
        node: &NodeInfo,
    ) -> Result<()> {
        loop {
            // 手机前台每 15 秒、后台每 30 秒发一次 ping/ACK；超过
            // `CONNECTION_IDLE_SEC` 收不到任何帧说明连接已半开，必须主动结束
            // 并把设备标记离线，否则电脑端会一直显示“在线”。
            // 常量统一取协议 crate，不在服务端另写一个魔数。
            let read = tokio::time::timeout(
                Duration::from_secs(pi_carry_protocol::CONNECTION_IDLE_SEC),
                read_frame(recv, MAX_CONTROL_FRAME),
            )
            .await;
            let frame = match read {
                Ok(Ok(Some(b))) => b,
                Ok(Ok(None)) => break,
                Ok(Err(e)) => {
                    tracing::warn!(device = %auth.device.id, error = %e, "控制流读取失败");
                    break;
                }
                Err(_) => {
                    tracing::warn!(
                        device = %auth.device.id,
                        idle_secs = pi_carry_protocol::CONNECTION_IDLE_SEC,
                        "控制流空闲超时，判定离线"
                    );
                    break;
                }
            };

            let frame: ClientFrame = match serde_json::from_slice(&frame) {
                Ok(f) => f,
                Err(e) => {
                    let _ = out
                        .send(ServerFrame::err(
                            "unknown",
                            ProtocolError::new(
                                ErrorCode::InvalidArgument,
                                format!("控制帧非法: {e}"),
                            ),
                        ))
                        .await;
                    continue;
                }
            };
            // 每帧都重读设备：吊销后既有连接最多再活一个心跳周期就断，
            // 而不是只能挡住新请求。Ping 也走这条检查。
            if self.device_is_revoked(&auth.device.id) {
                tracing::info!(device = %auth.device.id, "设备已吊销，关闭既有连接");
                let frame = ServerFrame::Event {
                    session_id: None,
                    seq: 0,
                    // 用协议构造器而不是内联 json!：字段名与 schema 必须与其它事件一致，
                    // 否则客户端按构造器解析时拿不到 deviceId/reason。
                    event: pi_carry_protocol::events::device_revoked(&auth.device.id, "revoked"),
                };
                let _ = out.send(frame).await;
                break;
            }
            match frame {
                ClientFrame::Ack { session_id, seq } => {
                    let _ = self.store.set_ack(&auth.device.id, &session_id, seq);
                }
                ClientFrame::Ping { nonce } => {
                    // 空闲连接只有心跳，所以心跳必须同样验证设备仍在授权名单内。
                    let _ = out
                        .send(ServerFrame::pong(nonce, crate::rfc3339(Utc::now())))
                        .await;
                }
                ClientFrame::Request { id, op, payload } => {
                    let result = self.dispatch(&auth.device, &op, payload, node, conn).await;
                    let frame = match result {
                        Ok(v) => ServerFrame::ok(id, v),
                        Err(e) => ServerFrame::err(id, e.to_protocol()),
                    };
                    if out.send(frame).await.is_err() {
                        break;
                    }
                }
            }
        }
        Ok(())
    }

    /// `fs.roots` 的 `recent`：会话 cwd 去重后按最近更新倒序，已删除的目录跳过。
    fn recent_cwds(&self) -> Vec<(String, String)> {
        let mut seen: HashSet<String> = HashSet::new();
        let mut out: Vec<(String, String)> = Vec::new();
        let sessions = match self.sessions.list() {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(error = %e, "读取会话列表失败，recent 为空");
                return out;
            }
        };
        // list() 已按 updatedAt 倒序；仍显式排序一次，不把展示顺序托付给调用方。
        let mut sorted = sessions;
        sorted.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
        for s in sorted {
            if s.cwd.trim().is_empty() {
                continue;
            }
            let key = crate::fsbrowse::normalize_cwd(&s.cwd);
            if !seen.insert(key) {
                continue;
            }
            // 目录可能已被删除或已移出允许根：两者都会导致点进去就被拒，直接不展示。
            // 只查 `is_dir()` 漏了后半句（注释里的承诺），移出允许根的目录仍会出现在
            // recent 里、点了就报 PermissionDenied，所以再跑一次与 fs.list 同源的允许根判定。
            if !std::path::Path::new(&s.cwd).is_dir()
                || crate::fsbrowse::ensure_allowed(&self.config, &s.cwd).is_err()
            {
                continue;
            }
            out.push((s.cwd.clone(), s.updated_at.clone()));
            if out.len() >= 8 {
                break;
            }
        }
        out
    }

    async fn dispatch(
        &self,
        device: &DeviceRecord,
        op: &str,
        payload: Value,
        node: &NodeInfo,
        _conn: &iroh::endpoint::Connection,
    ) -> Result<Value> {
        // 每次请求都重新读设备，吊销立即生效。
        let current = self.store.get_device(&device.id)?.ok_or_else(|| {
            CoreError::Protocol(ProtocolError::new(ErrorCode::AuthFailed, "设备不存在"))
        })?;
        if current.revoked_at.is_some() {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::AuthFailed,
                "设备已被吊销",
            )));
        }

        let cap = current.capability.as_str();
        match op {
            "ping" => Ok(json!({"pong": true, "serverTime": crate::rfc3339(Utc::now())})),
            "node.status" => Ok(json!({"node": node})),
            "session.list" => {
                require(cap, "read")?;
                Ok(json!({"sessions": self.sessions.list()?}))
            }
            "session.open" | "session.attach" => {
                require(cap, "read")?;
                let id = sid(&payload)?;
                let s = self.sessions.open(&id).await?;
                if op == "session.attach" {
                    let after = payload.get("lastSeq").and_then(Value::as_u64).unwrap_or(0);
                    let (events, snapshot_required) = self.sessions.replay(&id, after, 1000)?;
                    // journal 里存的是完整事件体；回放出口必须与实时事件流用同一套裁剪，
                    // 否则 read 档只要重连一次就能拿到本不该看到的命令原文与工具入参。
                    let events: Vec<Value> = if forwards_raw(cap) {
                        events
                    } else {
                        events
                            .iter()
                            .filter_map(|e| forward_event_for(cap, e))
                            .collect()
                    };
                    return Ok(json!({
                        "session": s,
                        "events": events,
                        "snapshotRequired": snapshot_required,
                    }));
                }
                Ok(json!({"session": s}))
            }
            "session.create" => {
                require(cap, "control")?;
                let cwd = payload.get("cwd").and_then(Value::as_str).unwrap_or("");
                Ok(json!({"session": self.sessions.create(cwd).await?}))
            }
            "session.snapshot" => {
                require(cap, "read")?;
                let id = sid(&payload)?;
                let snap = self.sessions.snapshot_async(&id).await?;
                let mut v = serde_json::to_value(snap)?;
                // 快照的 `pendingApprovals` 与 approval.list 是同一个载荷，
                // read 档下必须同口径裁剪，否则快照成了一条旁路。
                if !forwards_raw(cap) {
                    if let Some(list) = v.get_mut("pendingApprovals").and_then(Value::as_array_mut)
                    {
                        for approval in list {
                            redact_approval_fields(approval);
                        }
                    }
                }
                Ok(v)
            }
            "session.close" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                self.sessions.approvals().cancel_session(&id)?;
                self.sessions.close(&id).await?;
                Ok(json!({"closed": true}))
            }
            "prompt.send" => {
                require(cap, "control")?;
                let p: PromptPayload = serde_json::from_value(payload)
                    .map_err(|e| CoreError::InvalidArgument(format!("prompt 载荷非法: {e}")))?;
                let client_id = p.client_id.clone();
                let session_id = p.session_id.clone();
                // 先原子抢占幂等键再执行：并发重试时只有一方能抢占成功。
                if !client_id.is_empty()
                    && !self.store.claim_client_id(
                        &device.id,
                        &client_id,
                        &session_id,
                        &crate::rfc3339(Utc::now()),
                    )?
                {
                    return Ok(json!({"accepted": true, "duplicate": true}));
                }
                match self.sessions.prompt(p, &device.id).await {
                    Ok(result) => Ok(result),
                    Err(e) => {
                        // 执行失败释放幂等键，允许客户端重试同一 clientId。
                        if !client_id.is_empty() {
                            self.store.release_client_id(&device.id, &client_id)?;
                        }
                        Err(e)
                    }
                }
            }
            "prompt.abort" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                self.sessions.abort(&id, &device.id).await?;
                // 中止后没人会再等这批审批：置 denied 并唤醒等待方，
                // 否则工具调用会挂到超时才结束，审批列表也残留幽灵卡片。
                let cancelled = self.sessions.approvals().cancel_session(&id)?;
                Ok(json!({"aborted": true, "cancelledApprovals": cancelled}))
            }
            "queue.clear" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                self.sessions.clear_queue(&id, &device.id).await
            }
            "approval.list" => {
                require(cap, "read")?;
                let id = payload.get("sessionId").and_then(Value::as_str);
                let mut v = serde_json::to_value(self.sessions.approvals().pending(id)?)?;
                // 审批视图里 `command`/`args`/`path` 是隐私内容（docs/protocol.md 能力语义）：
                // read 档仍看得到「有待审批」和摘要，但拿不到命令原文与完整工具入参。
                if !forwards_raw(cap) {
                    if let Some(list) = v.as_array_mut() {
                        for approval in list {
                            redact_approval_fields(approval);
                        }
                    }
                }
                Ok(json!({"approvals": v}))
            }
            "approval.resolve" => {
                require(cap, "control")?;
                let d: ApprovalDecision = serde_json::from_value(payload)
                    .map_err(|e| CoreError::InvalidArgument(format!("审批载荷非法: {e}")))?;
                let view = self.sessions.approvals().resolve(
                    &d.approval_id,
                    &d.decision,
                    &device.id,
                    d.remember,
                )?;
                Ok(json!({"approval": view}))
            }
            "approval.extend" => {
                require(cap, "control")?;
                let id = payload
                    .get("approvalId")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 approvalId".into()))?;
                let secs = payload
                    .get("seconds")
                    .and_then(Value::as_u64)
                    .unwrap_or(120);
                Ok(json!({"approval": self.sessions.approvals().extend(id, secs)?}))
            }
            "ui.resolve" => {
                require(cap, "control")?;
                let session_id = payload
                    .get("sessionId")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string();
                let p: UiResponsePayload = serde_json::from_value(payload)
                    .map_err(|e| CoreError::InvalidArgument(format!("ui 载荷非法: {e}")))?;
                self.sessions.resolve_ui(&session_id, p)?;
                Ok(json!({"resolved": true}))
            }
            "models.list" => {
                require(cap, "read")?;
                let id = sid(&payload)?;
                let value = self.sessions.available_models(&id).await?;
                Ok(value)
            }
            "thinking.levels" => {
                require(cap, "read")?;
                let id = sid(&payload)?;
                let value = self.sessions.available_thinking_levels(&id).await?;
                Ok(value)
            }
            "model.set" => {
                require(cap, "full")?;
                let id = sid(&payload)?;
                let provider = payload
                    .get("provider")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 provider".into()))?;
                let model = payload
                    .get("modelId")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 modelId".into()))?;
                let v = self.sessions.set_model(&id, provider, model).await?;
                Ok(json!({"model": v}))
            }
            "thinking.set" => {
                require(cap, "full")?;
                let id = sid(&payload)?;
                let level = payload
                    .get("level")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 level".into()))?;
                let v = self.sessions.set_thinking(&id, level).await?;
                Ok(json!({"thinking": v}))
            }
            "session.rename" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                let name = payload
                    .get("name")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 name".into()))?;
                Ok(json!({ "session": self.sessions.rename(&id, name).await? }))
            }
            "session.compact" => {
                require(cap, "full")?;
                let id = sid(&payload)?;
                let instructions = payload
                    .get("instructions")
                    .and_then(Value::as_str)
                    .map(ToOwned::to_owned);
                Ok(json!({"result": self.sessions.compact(&id, instructions).await?}))
            }
            "session.fork" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                let entry = payload
                    .get("entryId")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 entryId".into()))?;
                Ok(json!({"session": self.sessions.fork(&id, entry).await?}))
            }
            "commands.list" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                Ok(json!({"commands": self.sessions.commands(&id).await?}))
            }
            "session.entries" => {
                require(cap, "read")?;
                let id = sid(&payload)?;
                let since = payload
                    .get("since")
                    .and_then(Value::as_str)
                    .map(ToOwned::to_owned);
                Ok(self.sessions.get_entries(&id, since).await?)
            }
            "device.list" => {
                require(cap, "read")?;
                let list: Vec<DeviceInfo> = self
                    .store
                    .list_devices()?
                    .iter()
                    .map(|d| device_info(d, d.id == device.id))
                    .collect();
                Ok(json!({"devices": list}))
            }
            "device.revoke" => {
                // 吊销属于 owner（本机）职责；full 设备可改模型/设置，但不能互相吊销。
                require(cap, "owner")?;
                let id = payload
                    .get("deviceId")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CoreError::InvalidArgument("缺少 deviceId".into()))?;
                if id == device.id {
                    return Err(CoreError::InvalidArgument("不能吊销当前设备".into()));
                }
                let now = crate::rfc3339(Utc::now());
                let ok = self.store.revoke_device(id, &now)?;
                if ok {
                    // 本进程内立即让该设备的既有连接在下一帧断开；
                    // 事件通知各端 UI 清掉这台设备。
                    self.revoked_devices
                        .lock()
                        .expect("revoked poisoned")
                        .insert(id.to_string());
                    self.sessions.emit_raw(
                        "",
                        pi_carry_protocol::events::device_revoked(id, &format!("by:{}", device.id)),
                    )?;
                }
                Ok(json!({"revoked": ok}))
            }
            "node.settings" => {
                require(cap, "read")?;
                Ok(json!({
                    "nodeName": self.config.node.name,
                    "relayMode": self.config.transport.relay_mode,
                    "autoAllowLowRisk": self.config.approvals.auto_allow_low_risk,
                }))
            }
            // 目录选择：两处都要求 control（能改工作目录 = 能在任意允许目录里跑命令）。
            "fs.roots" => {
                require(cap, "control")?;
                Ok(crate::fsbrowse::roots_payload(
                    &self.config,
                    self.recent_cwds(),
                ))
            }
            "fs.list" => {
                require(cap, "control")?;
                let path = payload.get("path").and_then(Value::as_str).unwrap_or("");
                // 已存在会话的目录要在列表里带小圆点，所以需要当前全部会话的 cwd。
                let cwds: Vec<String> = self.sessions.list()?.into_iter().map(|s| s.cwd).collect();
                crate::fsbrowse::list_payload(&self.config, path, &cwds)
            }
            other => Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::Unsupported,
                format!("不支持的操作 {other}"),
            ))),
        }
    }
}

fn sid(payload: &Value) -> Result<String> {
    payload
        .get("sessionId")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(ToOwned::to_owned)
        .ok_or_else(|| CoreError::InvalidArgument("缺少 sessionId".into()))
}

fn capability_rank(cap: &str) -> u8 {
    match cap {
        "read" => 1,
        "control" => 2,
        "full" => 3,
        "owner" => 4,
        _ => 0,
    }
}

fn require(cap: &str, min: &str) -> Result<()> {
    if capability_rank(cap) < capability_rank(min) {
        return Err(CoreError::Protocol(ProtocolError::new(
            ErrorCode::PermissionDenied,
            format!("需要 {min} 权限，当前 {cap}"),
        )));
    }
    Ok(())
}

/// 审批视图里属于隐私的三个字段：命令原文、完整工具入参、目标路径。
///
/// 这是 `docs/protocol.md` 能力语义里「read 档不该看到命令原文」的落地位置：
/// 事件流、`approval.list`、`session.snapshot`、`session.attach` 回放四个出口
/// 都走同一份裁剪，避免修了一处又留一处旁路。
const APPROVAL_PRIVATE_FIELDS: [&str; 3] = ["command", "args", "path"];

/// 原地删掉审批视图的隐私字段。删键而不是置 null：`ApprovalView` 三个字段都标了
/// `#[serde(default)]`，客户端拿不到键就当 None，与「无此项」语义一致。
fn redact_approval_fields(approval: &mut Value) {
    if let Some(obj) = approval.as_object_mut() {
        for key in APPROVAL_PRIVATE_FIELDS {
            obj.remove(key);
        }
    }
}

/// control 及以上可以收到原始事件：这些能力本来就能发 prompt、能点审批。
fn forwards_raw(cap: &str) -> bool {
    capability_rank(cap) >= capability_rank("control")
}

/// 按接收方能力裁剪一条事件：返回 `None` 表示整条不发，`Some` 则是要发的内容。
///
/// 事件总线是全局广播，不裁剪等于把所有会话的敏感载荷（完整工具参数、命令行
/// 明文）推给每一台配对设备。语义：
/// - rank 0（未配对/被吊销）：什么都不发；
/// - control 及以上：原样转发；
/// - read：`ui.requested` 整条不发（交互确认等于交出控制权），其余事件仍发，
///   但带 `approval` 对象时删掉 command/args/path——客户端得知道「有待审批」
///   「已延长」，只是看不到命令原文。`approval.updated` 也因此不再落到兜底放行。
fn forward_event_for(cap: &str, event: &Value) -> Option<Value> {
    if capability_rank(cap) == 0 {
        return None;
    }
    if forwards_raw(cap) {
        return Some(event.clone());
    }
    if event.get("type").and_then(Value::as_str) == Some("ui.requested") {
        return None;
    }
    let mut out = event.clone();
    if let Some(approval) = out.get_mut("approval") {
        redact_approval_fields(approval);
    }
    Some(out)
}

/// 事件转发前的权限过滤。保留为薄包装：既有测试只看「发不发」，
/// 真正的内容裁剪在 `forward_event_for`。生产路径已直接调后者，
/// 这里只在测试里使用，所以非测试构建下允许它「未被使用」。
#[cfg_attr(not(test), allow(dead_code))]
fn event_forward_allowed(cap: &str, event: &Value) -> bool {
    forward_event_for(cap, event).is_some()
}

/// 本地地址转字符串。`LocalTransportAddr` 没实现 `Display`，只能手动展开。
fn local_addr_string(addr: &iroh::endpoint::LocalTransportAddr) -> String {
    match addr {
        iroh::endpoint::LocalTransportAddr::Ip(Some(ip)) => ip.to_string(),
        iroh::endpoint::LocalTransportAddr::Relay(url) => format!("relay:{url}"),
        iroh::endpoint::LocalTransportAddr::Custom(Some(a)) => format!("custom:{a}"),
        _ => "unknown".to_string(),
    }
}

fn device_info(d: &DeviceRecord, current: bool) -> DeviceInfo {
    DeviceInfo {
        id: d.id.clone(),
        name: d.name.clone(),
        platform: d.platform.clone(),
        device_class: d.device_class.clone(),
        capability: d.capability.clone(),
        paired_at: d.paired_at.clone(),
        last_seen: d.last_seen.clone(),
        online: d.online,
        current,
    }
}

/// 测试/桌面用：生成本机直连监听地址提示。
pub fn direct_hint(bind: Option<&str>) -> String {
    bind.unwrap_or("0.0.0.0:0").to_string()
}

/// 保持 encode_frame 在公开 API 中可用（客户端测试会用到）。
pub fn hello_frame(id: &str, hello: &HelloPayload) -> Result<Vec<u8>> {
    let frame = ClientFrame::Request {
        id: id.into(),
        op: "hello".into(),
        payload: serde_json::to_value(hello)?,
    };
    encode_frame(&frame, MAX_CONTROL_FRAME).map_err(|e| CoreError::Internal(e.to_string()))
}

/// 避免未使用导入告警：路径类型在桌面壳中复用。
pub type ServerPaths = PathBuf;

#[allow(dead_code)]
fn _assert_send<T: Send>() {}
#[allow(dead_code)]
fn _assert_approval(_: &ApprovalManager) {}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn direct_addr_wraps_only_ipv6_in_brackets() {
        // 直连地址直接进配对二维码/端点地址：IPv6 不带方括号会被当成 host:port 解析错。
        use std::net::{Ipv4Addr, Ipv6Addr};
        assert_eq!(
            Server::format_addr(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 7)), 4433),
            "192.168.1.7:4433"
        );
        assert_eq!(
            Server::format_addr(IpAddr::V6(Ipv6Addr::LOCALHOST), 4433),
            "[::1]:4433"
        );
        // ULA（fc00::/7）与公网 v6 同样加方括号，不允许出现 zone id。
        assert_eq!(
            Server::format_addr(IpAddr::V6("fd12:3456:789a::1".parse().unwrap()), 80),
            "[fd12:3456:789a::1]:80"
        );
        assert_eq!(
            Server::format_addr(IpAddr::V6("2408:8000::1".parse().unwrap()), 4433),
            "[2408:8000::1]:4433"
        );
    }

    #[test]
    fn rank_zero_device_receives_no_events() {
        // 未知/被降级的能力值一律 rank 0：宁可不发，也不能泄露其他会话的内容。
        for ev in [
            json!({ "type": "message.delta", "delta": "x" }),
            json!({ "type": "ui.requested", "title": "t" }),
            json!({ "type": "approval.requested", "approval": { "command": "rm -rf /" } }),
        ] {
            assert!(!event_forward_allowed("", &ev));
            assert!(!event_forward_allowed("guest", &ev));
        }
    }

    #[test]
    fn read_device_loses_ui_and_approval_command() {
        // read 只读设备可以看会话文本，但不能看交互确认与命令行明文。
        assert!(event_forward_allowed(
            "read",
            &json!({ "type": "message.delta", "delta": "hi" })
        ));
        assert!(!event_forward_allowed(
            "read",
            &json!({ "type": "ui.requested", "title": "t" })
        ));
        // 审批事件仍下发（只读设备也该知道“有待审批”），但命令原文/参数/路径必须删掉。
        for ty in ["approval.requested", "approval.updated"] {
            let ev = forward_event_for(
                "read",
                &json!({
                    "type": ty,
                    "approval": {
                        "id": "a1",
                        "toolName": "bash",
                        "summary": "删除临时目录",
                        "command": "rm -rf /tmp/x",
                        "args": { "file": "密钥内容" },
                        "path": "C:/secret.txt",
                    }
                }),
            )
            .unwrap_or_else(|| panic!("{ty} 应仍对 read 档可见"));
            let a = &ev["approval"];
            for field in ["command", "args", "path"] {
                assert!(a.get(field).is_none(), "{ty} 的 {field} 必须裁剪掉: {ev:?}");
            }
            // 非隐私字段保留：客户端靠它们渲染审批卡。
            assert_eq!(a["toolName"], "bash");
            assert_eq!(a["summary"], "删除临时目录");
        }
    }

    #[test]
    fn control_device_receives_raw_approval_payload() {
        // control 及以上本来就能点审批，裁剪它们只会把审批卡变成不可用的空白。
        let raw = json!({
            "type": "approval.updated",
            "approval": { "command": "ls", "args": [1], "path": "/tmp" }
        });
        for cap in ["control", "full", "owner"] {
            let ev = forward_event_for(cap, &raw).expect("控制档必须收到事件");
            assert_eq!(ev, raw, "{cap} 不应被裁剪");
        }
    }

    #[test]
    fn redact_approval_fields_removes_only_private_fields() {
        // approval.list / session.snapshot / session.attach 三个出口都走这个函数，
        // 单独钉住它：少删一个就是一条隐私旁路，多删一个会让审批卡渲染不全。
        let mut view = json!({
            "id": "a1",
            "toolName": "write",
            "summary": "写文件",
            "command": "echo hi > f",
            "args": { "content": "秘密" },
            "path": "/tmp/f",
            "state": "pending",
        });
        redact_approval_fields(&mut view);
        for field in APPROVAL_PRIVATE_FIELDS {
            assert!(view.get(field).is_none(), "{field} 应被删除: {view:?}");
        }
        assert_eq!(view["id"], "a1");
        assert_eq!(view["toolName"], "write");
        assert_eq!(view["state"], "pending");
    }

    #[test]
    fn control_and_above_receive_everything() {
        let events = [
            json!({ "type": "ui.requested", "title": "t" }),
            json!({ "type": "approval.requested", "approval": { "command": "ls" } }),
            json!({ "type": "session.updated", "session": {} }),
            json!({ "type": "unknown.future.event" }),
        ];
        for cap in ["control", "full", "owner"] {
            for ev in &events {
                assert!(event_forward_allowed(cap, ev), "{cap} 应收到 {ev:?}");
            }
        }
    }

    #[test]
    fn unknown_event_types_are_forwarded_to_read_devices() {
        // 白名单思维不可取：未知事件默认放行，只对已知敏感类型做屏蔽。
        assert!(event_forward_allowed(
            "read",
            &json!({ "type": "queue.updated" })
        ));
    }
}
