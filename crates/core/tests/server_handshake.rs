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
