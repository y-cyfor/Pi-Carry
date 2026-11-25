//! 端到端传输冒烟：本机 iroh 回环，验证配对、hello、事件流与权限。

use pi_carry_core::{Bridge, BridgeOptions, Config, Paths};
use pi_carry_protocol::{
    read_frame, write_frame, ClientFrame, ClientInfo, HelloPayload, ServerFrame, ALPN,
    MAX_CONTROL_FRAME, PROTOCOL_VERSION,
};
use tempfile::TempDir;

async fn start_bridge() -> (TempDir, std::sync::Arc<Bridge>) {
    start_bridge_with(|_| {}).await
}

async fn start_bridge_with(
    configure: impl FnOnce(&mut Config),
) -> (TempDir, std::sync::Arc<Bridge>) {
    // 与 tests/common 一致：测试不碰真实系统钥匙串。
    static BACKEND: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    BACKEND.get_or_init(|| std::env::set_var("PI_CARRY_SECRET_BACKEND", "file"));
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "test-node".into();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = dir.path().join("sessions").display().to_string();
    configure(&mut config);
    let paths = Paths::at(dir.path().join("home"));
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths,
        clock: None,
    })
    .await
    .expect("bridge 启动失败");
    (dir, bridge)
}

async fn call(
    send: &mut iroh::endpoint::SendStream,
    recv: &mut iroh::endpoint::RecvStream,
    id: &str,
    op: &str,
    payload: serde_json::Value,
) -> ServerFrame {
    let frame = ClientFrame::Request {
        id: id.into(),
        op: op.into(),
        payload,
    };
    write_frame(send, &frame, MAX_CONTROL_FRAME).await.unwrap();
    let bytes = read_frame(recv, 4 * 1024 * 1024)
        .await
        .unwrap()
        .expect("连接被关闭");
    serde_json::from_slice(&bytes).unwrap()
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn self_reported_desktop_class_does_not_escalate() {
    // 默认能力是其余新增设备的唯一来源，客户端自报 deviceClass 不得升权。
    let (_dir, bridge) =
        start_bridge_with(|c| c.sessions.default_capability = "control".into()).await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        client.connect(addr, ALPN),
    )
    .await
    .expect("connect 超时")
    .expect("连接失败");
    let (mut send, mut recv) = conn.open_bi().await.unwrap();

    let hello = call(
        &mut send,
        &mut recv,
        "h1",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "伪桌面", "platform": "windows", "deviceClass": "desktop"},
            "token": pairing.token,
        }),
    )
    .await;
    match hello {
        ServerFrame::Response {
            ok, payload, error, ..
        } => {
            assert!(ok, "hello 失败: {error:?}");
            let resp: pi_carry_protocol::HelloResponse =
                serde_json::from_value(payload.unwrap()).unwrap();
            assert_eq!(resp.device.capability, "control");
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    conn.close(0u8.into(), b"done");
    client.close().await;
    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn pairing_hello_and_node_status() {
    let (_dir, bridge) = start_bridge().await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        client.connect(addr, ALPN),
    )
    .await
    .expect("connect 超时")
    .expect("连接失败");
    let (mut send, mut recv) =
        tokio::time::timeout(std::time::Duration::from_secs(10), conn.open_bi())
            .await
            .expect("open_bi 超时")
            .unwrap();

    let hello = ClientFrame::Request {
        id: "h1".into(),
        op: "hello".into(),
        payload: serde_json::to_value(HelloPayload {
            protocol: PROTOCOL_VERSION.into(),
            client: ClientInfo {
                name: "测试手机".into(),
                platform: "android".into(),
                app_version: "0.1.0".into(),
                device_class: "phone".into(),
                locale: "zh-CN".into(),
            },
            password: None,
            token: Some(pairing.token.clone()),
            code: None,
            resume_session_id: None,
        })
        .unwrap(),
    };
    write_frame(&mut send, &hello, MAX_CONTROL_FRAME)
        .await
        .unwrap();
    let raw = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        read_frame(&mut recv, 4 * 1024 * 1024),
    )
    .await
    .expect("hello 响应超时")
    .unwrap()
    .expect("hello 无响应");
    let frame: ServerFrame = serde_json::from_slice(&raw).unwrap();
    match frame {
        ServerFrame::Response {
            ok, payload, error, ..
        } => {
            assert!(ok, "hello 失败: {error:?}");
            let resp: pi_carry_protocol::HelloResponse =
                serde_json::from_value(payload.unwrap()).unwrap();
            assert_eq!(resp.protocol, PROTOCOL_VERSION);
            assert_eq!(resp.device.capability, "full");
            assert_eq!(resp.node.name, "test-node");
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    // 配对完成后设备已落库，再次 hello 不需要 token。
    let status = call(
        &mut send,
        &mut recv,
        "s1",
        "node.status",
        serde_json::json!({}),
    )
    .await;
    match status {
        ServerFrame::Response { ok, payload, .. } => {
            assert!(ok);
            let v = payload.unwrap();
            assert_eq!(v["node"]["name"], "test-node");
            assert!(v["node"]["piInstalled"].is_boolean());
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    // 权限：把设备降为 control 后不能改模型。
    let device_id = bridge.store.list_devices().unwrap()[0].id.clone();
    bridge.store.set_capability(&device_id, "control").unwrap();
    let denied = call(
        &mut send,
        &mut recv,
        "m1",
        "model.set",
        serde_json::json!({"sessionId": "x", "provider": "p", "modelId": "m"}),
    )
    .await;
    match denied {
        ServerFrame::Response { ok, error, .. } => {
            assert!(!ok);
            assert_eq!(
                error.unwrap().code,
                pi_carry_protocol::ErrorCode::PermissionDenied
            );
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    // 错误 token 不能配对。
    let other_client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr2 = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn2 = other_client.connect(addr2, ALPN).await.unwrap();
    let (mut send2, mut recv2) = conn2.open_bi().await.unwrap();
    let bad = ClientFrame::Request {
        id: "h2".into(),
        op: "hello".into(),
        payload: serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "坏设备", "platform": "android", "deviceClass": "phone"},
            "token": "deadbeef"
        }),
    };
    write_frame(&mut send2, &bad, MAX_CONTROL_FRAME)
        .await
        .unwrap();
    let raw = read_frame(&mut recv2, 4 * 1024 * 1024)
        .await
        .unwrap()
        .unwrap();
    let frame: ServerFrame = serde_json::from_slice(&raw).unwrap();
    match frame {
        ServerFrame::Response { ok, error, .. } => {
            assert!(!ok);
            let code = error.unwrap().code;
            assert!(
                matches!(
                    code,
                    pi_carry_protocol::ErrorCode::AuthFailed
                        | pi_carry_protocol::ErrorCode::PairingExpired
                ),
                "意外错误码 {code:?}"
            );
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    conn.close(0u8.into(), b"done");
    conn2.close(0u8.into(), b"done");
    client.close().await;
    other_client.close().await;
    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn same_client_key_reconnects_as_one_device_and_refreshes_name() {
    let (_dir, bridge) = start_bridge_with(|c| c.sessions.default_capability = "full".into()).await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let secret = iroh::SecretKey::generate();
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .secret_key(secret)
        .bind()
        .await
        .unwrap();

    let connect = || {
        let addr = addr.clone();
        let client = client.clone();
        async move {
            tokio::time::timeout(
                std::time::Duration::from_secs(10),
                client.connect(addr, ALPN),
            )
            .await
            .expect("connect 超时")
            .expect("连接失败")
        }
    };

    let conn1 = connect().await;
    let (mut send1, mut recv1) = conn1.open_bi().await.unwrap();
    let hello1 = call(
        &mut send1,
        &mut recv1,
        "h1",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "张三的手机 小米15", "platform": "android", "deviceClass": "phone"},
            "token": pairing.token,
        }),
    )
    .await;
    assert!(
        matches!(hello1, ServerFrame::Response { ok: true, .. }),
        "{hello1:?}"
    );
    conn1.close(0u8.into(), b"rotate");
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    let conn2 = connect().await;
    let (mut send2, mut recv2) = conn2.open_bi().await.unwrap();
    let hello2 = call(
        &mut send2,
        &mut recv2,
        "h2",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "李四的手机 小米15", "platform": "android", "deviceClass": "phone"},
        }),
    )
    .await;
    assert!(
        matches!(hello2, ServerFrame::Response { ok: true, .. }),
        "{hello2:?}"
    );

    let devices = call(
        &mut send2,
        &mut recv2,
        "d1",
        "device.list",
        serde_json::json!({}),
    )
    .await;
    let list = match devices {
        ServerFrame::Response {
            ok: true, payload, ..
        } => payload.unwrap(),
        other => panic!("device.list 失败: {other:?}"),
    };
    let rows = list["devices"].as_array().expect("devices 数组");
    assert_eq!(rows.len(), 1, "同一客户端密钥只能对应一台设备: {rows:?}");
    assert_eq!(rows[0]["name"], "李四的手机 小米15");
    let device_id = rows[0]["id"].as_str().unwrap().to_string();

    conn2.close(0u8.into(), b"done");
    let mut offline = false;
    for _ in 0..30 {
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        if !bridge.store.get_device(&device_id).unwrap().unwrap().online {
            offline = true;
            break;
        }
    }
    assert!(offline, "最后一条连接断开后设备必须离线");
    client.close().await;
    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn revoking_device_closes_its_existing_connection() {
    // 吊销写在库里（托盘/CLI 都可能改库），既有连接必须在下一个心跳周期内断开，
    // 而不是“连接不死、只是新请求被拒”。
    let (_dir, bridge) = start_bridge().await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn = client.connect(addr, ALPN).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();

    let hello = call(
        &mut send,
        &mut recv,
        "h1",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "待吊销手机", "platform": "android", "deviceClass": "phone"},
            "token": pairing.token,
        }),
    )
    .await;
    assert!(
        matches!(hello, ServerFrame::Response { ok: true, .. }),
        "{hello:?}"
    );
    let device_id = bridge.store.list_devices().unwrap()[0].id.clone();
    bridge
        .store
        .revoke_device(&device_id, "2026-11-19T08:00:00.000Z")
        .unwrap();

    let ping = ClientFrame::Ping { nonce: 1 };
    write_frame(&mut send, &ping, MAX_CONTROL_FRAME)
        .await
        .unwrap();

    let mut saw_revoked = false;
    let mut closed = false;
    // 不要用固定帧数：连接建立时可能已经排了若干事件帧，固定上限会在
    // 事件较多时提前退出并误判为“未关闭”。改为循环到流结束或超时。
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(15);
    while std::time::Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        let read = tokio::time::timeout(remaining, read_frame(&mut recv, 4 * 1024 * 1024)).await;
        match read {
            Err(_) => break,
            Ok(Ok(Some(bytes))) => {
                let frame: ServerFrame = serde_json::from_slice(&bytes).unwrap();
                if let ServerFrame::Event { event, .. } = frame {
                    if event["type"] == "device.revoked" {
                        saw_revoked = true;
                    }
                }
            }
            Ok(Ok(None)) | Ok(Err(_)) => {
                closed = true;
                break;
            }
        }
    }
    assert!(closed, "吊销后连接必须被服务端关闭");
    assert!(saw_revoked, "关连接前应通知 device.revoked");

    conn.close(0u8.into(), b"done");
    client.close().await;
    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn read_only_device_does_not_receive_ui_or_command_events() {
    // 事件总线是全局广播；只读设备不该看到含完整工具参数/命令明文的载荷。
    let (_dir, bridge) = start_bridge_with(|c| c.sessions.default_capability = "read".into()).await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn = client.connect(addr, ALPN).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();

    let hello = call(
        &mut send,
        &mut recv,
        "h1",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "只读平板", "platform": "android", "deviceClass": "tablet"},
            "token": pairing.token,
        }),
    )
    .await;
    match hello {
        ServerFrame::Response { ok, payload, .. } => {
            assert!(ok);
            let resp: pi_carry_protocol::HelloResponse =
                serde_json::from_value(payload.unwrap()).unwrap();
            assert_eq!(resp.device.capability, "read");
        }
        other => panic!("期望 response，得到 {other:?}"),
    }
    // 等转发任务订阅上广播，否则事件可能发生在订阅之前。
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    bridge
        .sessions
        .emit_raw(
            "s1",
            serde_json::json!({
                "type": "ui.requested",
                "requestId": "q1",
                "method": "select",
                "title": "选一个",
                "options": ["a", "b"],
            }),
        )
        .unwrap();
    // 审批事件：read 档仍应看到「有待审批」，但命令原文/参数/路径必须被裁掉。
    bridge
        .sessions
        .emit_raw(
            "s1",
            serde_json::json!({
                "type": "approval.requested",
                "approval": {
                    "id": "a1",
                    "sessionId": "s1",
                    "toolName": "bash",
                    "summary": "改配置",
                    "command": "rm -rf /etc/app",
                    "args": {"file": "/etc/app/secret.env"},
                    "path": "/etc/app/secret.env",
                },
            }),
        )
        .unwrap();
    bridge
        .sessions
        .emit_raw(
            "s1",
            serde_json::json!({"type": "message.delta", "delta": "hello"}),
        )
        .unwrap();

    let ping = ClientFrame::Request {
        id: "p1".into(),
        op: "node.status".into(),
        payload: serde_json::json!({}),
    };

    fn has_type(events: &[serde_json::Value], want: &str) -> bool {
        events.iter().any(|e| e["type"].as_str() == Some(want))
    }
    // 保留完整事件体而不只是类型：裁剪与否只能看载荷本身。
    let mut seen: Vec<serde_json::Value> = Vec::new();
    // 先把事件读出来再发请求：事件由独立的转发任务写 out_tx，控制响应由控制循环写，
    // 两者先后无保证。旧写法直接发请求，偶发会遇到「响应先到、还没读到任何事件」的失败。
    let wait_events = async {
        while !has_type(&seen, "message.delta") {
            let bytes = read_frame(&mut recv, 4 * 1024 * 1024)
                .await
                .unwrap()
                .expect("连接提前关闭");
            let frame: ServerFrame = serde_json::from_slice(&bytes).unwrap();
            if let ServerFrame::Event { event, .. } = frame {
                seen.push(event);
            }
        }
    };
    tokio::time::timeout(std::time::Duration::from_secs(10), wait_events)
        .await
        .expect("等待转发事件超时");

    write_frame(&mut send, &ping, MAX_CONTROL_FRAME)
        .await
        .unwrap();

    loop {
        let bytes = tokio::time::timeout(
            std::time::Duration::from_secs(10),
            read_frame(&mut recv, 4 * 1024 * 1024),
        )
        .await
        .expect("等待响应超时")
        .unwrap()
        .expect("连接提前关闭");
        let frame: ServerFrame = serde_json::from_slice(&bytes).unwrap();
        match frame {
            ServerFrame::Event { event, .. } => seen.push(event),
            ServerFrame::Response { id, .. } if id.as_deref() == Some("p1") => break,
            _ => {}
        }
    }
    assert!(
        has_type(&seen, "message.delta"),
        "普通事件仍应转发: {seen:?}"
    );
    assert!(
        !has_type(&seen, "ui.requested"),
        "只读设备不该收到 ui.requested: {seen:?}"
    );
    let approval = seen
        .iter()
        .find(|e| e["type"] == "approval.requested")
        .unwrap_or_else(|| panic!("read 档仍应收到审批事件，只是没有命令原文: {seen:?}"));
    for field in ["command", "args", "path"] {
        assert!(
            approval["approval"].get(field).is_none(),
            "read 档收到的审批事件不得包含 {field}: {approval:?}"
        );
    }
    assert_eq!(
        approval["approval"]["summary"], "改配置",
        "非隐私字段必须保留，否则客户端渲染不出审批卡"
    );

    conn.close(0u8.into(), b"done");
    client.close().await;
    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn hello_and_status_expose_connection_method() {
    // 需求 0.1.4-5：连接方式必须同时出现在 hello 响应与桌面端 node.status 里。
    // 手机端首屏读 hello，桌面壳读 node.status，两条路径都要能拿到同一份快照。
    let (_dir, bridge) = start_bridge().await;
    let pairing = bridge.begin_pairing(None);
    let socket = bridge.server.endpoint().bound_sockets()[0];
    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket);
    let conn = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        client.connect(addr, ALPN),
    )
    .await
    .expect("connect 超时")
    .expect("连接失败");
    let (mut send, mut recv) = conn.open_bi().await.unwrap();

    let hello = call(
        &mut send,
        &mut recv,
        "h1",
        "hello",
        serde_json::json!({
            "protocol": PROTOCOL_VERSION,
            "client": {"name": "连接方式测试机", "platform": "android", "deviceClass": "phone"},
            "token": pairing.token,
        }),
    )
    .await;
    match hello {
        ServerFrame::Response {
            ok, payload, error, ..
        } => {
            assert!(ok, "hello 失败: {error:?}");
            let resp: pi_carry_protocol::HelloResponse =
                serde_json::from_value(payload.unwrap()).unwrap();
            let c = resp.connection.expect("hello 必须带 connection");
            assert_eq!(c["type"], "lan", "回环连接应判为 lan");
            assert_eq!(c["direct"], true);
            assert!(
                c["remoteAddr"].as_str().unwrap_or("").contains("127.0.0.1"),
                "remoteAddr 应带真实对端地址: {c}"
            );
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    // 远程 node.status 只返回节点信息；设备列表走 bridge 的 node_status（桌面壳路径）。
    let status = call(
        &mut send,
        &mut recv,
        "s1",
        "node.status",
        serde_json::json!({}),
    )
    .await;
    match status {
        ServerFrame::Response { ok, payload, .. } => {
            assert!(ok);
            let v = payload.unwrap();
            assert_eq!(v["node"]["name"], "test-node");
            let caps: Vec<&str> = v["node"]["capabilities"]
                .as_array()
                .unwrap()
                .iter()
                .filter_map(|c| c.as_str())
                .collect();
            assert!(caps.contains(&"connection-info"), "能力位缺失: {caps:?}");
            assert!(caps.contains(&"fs-browse"), "能力位缺失: {caps:?}");
            assert!(caps.contains(&"run-summary"), "能力位缺失: {caps:?}");
        }
        other => panic!("期望 response，得到 {other:?}"),
    }

    // 桌面壳读的 node_status 必须把 connection 合并进 devices。
    let v = bridge.node_status();
    let devices = v["devices"].as_array().expect("devices 必须是数组");
    assert_eq!(devices.len(), 1);
    let c = &devices[0]["connection"];
    assert!(c.is_object(), "在线设备必须带 connection: {devices:?}");
    assert_eq!(c["type"], "lan");

    // 断开后连快速照必须清掉，否则桌面会长期显示一条已失效的路径。
    let device_id = bridge.store.list_devices().unwrap()[0].id.clone();
    conn.close(0u8.into(), b"done");
    client.close().await;
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    while std::time::Instant::now() < deadline {
        if !bridge.server.conn_infos().contains_key(&device_id) {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    assert!(
        !bridge.server.conn_infos().contains_key(&device_id),
        "连接关闭后必须清掉连接方式快照"
    );

    bridge.shutdown().await;
}

/// BUG-002 回归：同一个进程里「启动 → shutdown → 再启动」必须能重新拿到数据目录锁。
///
/// `start_expiry_ticker` 曾经克隆 `Arc<Bridge>` 并永不退出，Arc 计数归不了零，
/// `DirLock` 也就不析构。桌面端「重启服务」正是这条路径：旧 Bridge 被 take 掉并
/// shutdown 后新 Bridge 撞上旧锁，结果一个桥都不剩，只能重启整个应用。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn bridge_can_restart_in_same_process_after_shutdown() {
    static BACKEND: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    BACKEND.get_or_init(|| std::env::set_var("PI_CARRY_SECRET_BACKEND", "file"));
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "restart-node".into();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = dir.path().join("sessions").display().to_string();
    let paths = Paths::at(dir.path().join("home"));

    for round in 1..=2 {
        let bridge = Bridge::start(BridgeOptions {
            config: config.clone(),
            paths: paths.clone(),
            clock: None,
        })
        .await
        .unwrap_or_else(|e| panic!("同进程第 {round} 次启动失败（数据目录锁未释放）: {e}"));
        bridge.shutdown().await;
        // 必须真正释放所有 Arc：后台任务若还在跑，锁会跟着 Bridge 一起被吊住。
        drop(bridge);
    }
}

/// BUG-035 回归：`shutdown` 返回后锁必须已释放，与还有多少 `Arc<Bridge>` 克隆存活无关。
///
/// 桌面「重启服务」是在 `get_status` 之类的轮询可能持有克隆时发生的；旧实现要等最后一个
/// Arc 析构才肯放锁，那个窗口里新 Bridge 会撞上「已有一个 pi-carry 进程在使用…」。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn shutdown_releases_dir_lock_with_live_clones() {
    static BACKEND: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    BACKEND.get_or_init(|| std::env::set_var("PI_CARRY_SECRET_BACKEND", "file"));
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "clone-node".into();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = dir.path().join("sessions").display().to_string();
    let paths = Paths::at(dir.path().join("home"));

    let first = Bridge::start(BridgeOptions {
        config: config.clone(),
        paths: paths.clone(),
        clock: None,
    })
    .await
    .expect("第一次启动失败");
    // 模拟 status 轮询持有的克隆：shutdown 之后仍然存活。
    let clone = first.clone();
    first.shutdown().await;

    let second = Bridge::start(BridgeOptions {
        config: config.clone(),
        paths: paths.clone(),
        clock: None,
    })
    .await
    .expect("shutdown 后仍持有克隆时，新 Bridge 必须能拿到锁");
    second.shutdown().await;

    drop(second);
    drop(first);
    drop(clone);
}
