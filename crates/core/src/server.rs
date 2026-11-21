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
    UiResponsePayload, ALPN, MAX_CONTROL_FRAME,
};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::net::SocketAddr;
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
    /// 同一设备可能短暂存在新旧两条连接（重连竞态）；只有最后一条断开才算离线。
    active_connections: Mutex<HashMap<String, usize>>,
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
        let pi_installed = crate::pi::resolve_pi(Some(&config.node.pi_path)).is_ok();
        let server = Arc::new(Self {
            endpoint,
            sessions,
            store,
            config,
            pairing,
            secrets,
            pi_installed,
            active_connections: Mutex::new(HashMap::new()),
        });
        let _ = clock.now();
        let s = server.clone();
        tokio::spawn(async move {
            s.accept_loop().await;
        });
        Ok(server)
    }

    /// 局域网可直连地址，供配对 URI 携带；包含回环和本机出口 IP。
    pub fn lan_addrs(&self) -> Vec<String> {
        use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

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
                if !ip.is_unspecified() && !ips.contains(&ip) {
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
                if ip.is_ipv6() {
                    out.push(format!("[{ip}]:{port}"));
                } else {
                    out.push(format!("{ip}:{port}"));
                }
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
            pi_version: String::new(),
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

    async fn handle_connection(&self, conn: iroh::endpoint::Connection) -> Result<()> {
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
        let resp = ServerFrame::ok(
            id,
            serde_json::to_value(HelloResponse {
                protocol: pi_carry_protocol::PROTOCOL_VERSION.into(),
                node: node.clone(),
                device: device_info,
                session_id: None,
            })?,
        );
        write_frame(&mut send, &resp, MAX_CONTROL_FRAME)
            .await
            .map_err(|e| CoreError::Internal(format!("发送 hello 响应失败: {e}")))?;
        self.connection_opened(&auth.device.id);

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
        });
        let forwarder = {
            let sessions = self.sessions.clone();
            let out = out_tx.clone();
            let sid = auth.device.id.clone();
            tokio::spawn(async move {
                let mut rx = sessions.subscribe();
                loop {
                    match rx.recv().await {
                        Ok(ev) => {
                            let frame = ServerFrame::Event {
                                session_id: Some(ev.session_id),
                                seq: ev.seq,
                                event: ev.event,
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
        forwarder.abort();
        let _ = writer.await;
        // 旧连接清理不能把刚建立的新连接误标为离线。
        if self.connection_closed(&auth.device.id) {
            let _ = self
                .store
                .touch_device(&auth.device.id, &crate::rfc3339(Utc::now()), false);
        }
        conn.close(0u32.into(), b"bye");
        result
    }

    fn connection_opened(&self, device_id: &str) {
        let mut active = self.active_connections.lock().expect("active poisoned");
        *active.entry(device_id.to_string()).or_insert(0) += 1;
    }

    /// 返回 true 表示这是该设备最后一条连接。
    fn connection_closed(&self, device_id: &str) -> bool {
        let mut active = self.active_connections.lock().expect("active poisoned");
        match active.get_mut(device_id) {
            Some(count) if *count > 1 => {
                *count -= 1;
                false
            }
            Some(_) => {
                active.remove(device_id);
                true
            }
            None => true,
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
            // 已配对设备也允许提供密码作为二次认证（若配置了密码）。
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
        self.pairing.confirm()?;
        let count = self
            .store
            .list_devices()?
            .into_iter()
            .filter(|d| d.revoked_at.is_none())
            .count();
        if count >= self.config.transport.max_devices {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::RateLimited,
                format!("设备数已达上限 {}", self.config.transport.max_devices),
            )));
        }
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
            // 手机每 15 秒发一次 ping/ACK；60 秒收不到任何帧说明连接已半开，
            // 必须主动结束并把设备标记离线，否则电脑端会一直显示“在线”。
            let read =
                tokio::time::timeout(Duration::from_secs(60), read_frame(recv, MAX_CONTROL_FRAME))
                    .await;
            let frame = match read {
                Ok(Ok(Some(b))) => b,
                Ok(Ok(None)) => break,
                Ok(Err(e)) => {
                    tracing::warn!(device = %auth.device.id, error = %e, "控制流读取失败");
                    break;
                }
                Err(_) => {
                    tracing::warn!(device = %auth.device.id, "控制流 60 秒无数据，判定离线");
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
            match frame {
                ClientFrame::Ack { session_id, seq } => {
                    let _ = self.store.set_ack(&auth.device.id, &session_id, seq);
                }
                ClientFrame::Ping { nonce } => {
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
                Ok(serde_json::to_value(snap)?)
            }
            "session.close" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
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
                Ok(json!({"aborted": true}))
            }
            "queue.clear" => {
                require(cap, "control")?;
                let id = sid(&payload)?;
                self.sessions.clear_queue(&id, &device.id).await
            }
            "approval.list" => {
                require(cap, "read")?;
                let id = payload.get("sessionId").and_then(Value::as_str);
                Ok(json!({"approvals": self.sessions.approvals().pending(id)?}))
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
                    self.sessions.emit_raw(
                        "",
                        json!({
                            "type": "device.revoked",
                            "deviceId": id,
                            "by": device.id,
                        }),
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

fn require(cap: &str, min: &str) -> Result<()> {
    let rank = |c: &str| match c {
        "read" => 1,
        "control" => 2,
        "full" => 3,
        "owner" => 4,
        _ => 0,
    };
    if rank(cap) < rank(min) {
        return Err(CoreError::Protocol(ProtocolError::new(
            ErrorCode::PermissionDenied,
            format!("需要 {min} 权限，当前 {cap}"),
        )));
    }
    Ok(())
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
