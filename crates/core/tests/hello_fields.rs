//! `hello` 响应必须携带服务端权威时间与版本（缺陷 R20 的回归门禁）。
//!
//! 手机端用 `serverTime` 校准审批倒计时与「最近活跃」：一旦服务端省略它，
//! 客户端只能退回本机时钟，设备时间偏差会让 120 秒倒计时明显错位。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_core::server::hello_frame;
use pi_carry_protocol::frame::read_frame;
use pi_carry_protocol::model::ServerFrame;
use pi_carry_protocol::{HelloPayload, MAX_CONTROL_FRAME};
use serde_json::Value;
use std::sync::Arc;

mod common;

use common::fake_pi;

#[tokio::test]
async fn hello_response_carries_server_time_and_version() {
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "hello-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = tmp.path().join("sessions").display().to_string();
    let paths = Paths::at(tmp.path().join("home"));
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths,
        clock: Some(Arc::new(FixedClock::new(Utc::now()))),
    })
    .await
    .unwrap();

    let pairing = bridge.begin_pairing(None);
    let hello = HelloPayload {
        protocol: pi_carry_protocol::PROTOCOL_VERSION.into(),
        client: pi_carry_protocol::ClientInfo {
            name: "测试手机".into(),
            platform: "android".into(),
            device_class: "phone".into(),
            app_version: "0.1.3".into(),
            locale: "zh-CN".into(),
        },
        token: Some(pairing.token.clone()),
        code: None,
        password: None,
        resume_session_id: None,
    };

    let bytes = hello_frame("hello", &hello).unwrap();

    // 直用 hello_frame 产出的完整帧（含 4 字节长度头），不拆包。
    let endpoint = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .relay_mode(iroh::RelayMode::Disabled)
        .bind()
        .await
        .unwrap();
    let addr = bridge
        .server
        .endpoint()
        .addr()
        .addrs
        .iter()
        .find_map(|a| match a {
            iroh::TransportAddr::Ip(ip) => Some(*ip),
            _ => None,
        })
        .expect("disabled relay 模式必须有直连地址");
    let server_addr = iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(addr);
    let conn = endpoint
        .connect(server_addr, pi_carry_protocol::ALPN)
        .await
        .unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    use tokio::io::AsyncWriteExt;
    send.write_all(&bytes).await.unwrap();
    send.flush().await.unwrap();
    let resp = read_frame(&mut recv, MAX_CONTROL_FRAME).await.unwrap();
    let resp: ServerFrame = serde_json::from_slice(&resp.expect("服务端必须有响应")).unwrap();
    let payload = match resp {
        ServerFrame::Response {
            ok, payload, error, ..
        } => {
            assert!(ok, "hello 应成功，实际错误: {error:?}");
            payload.unwrap_or(Value::Null)
        }
        other => panic!("期望 response，实际 {other:?}"),
    };

    let server_time = payload
        .get("serverTime")
        .and_then(Value::as_str)
        .expect("hello 必须带 serverTime：客户端要靠它校准倒计时");
    assert!(
        chrono::DateTime::parse_from_rfc3339(server_time).is_ok(),
        "serverTime 必须是 RFC3339: {server_time}"
    );
    let version = payload
        .get("serverVersion")
        .and_then(Value::as_str)
        .expect("hello 必须带 serverVersion");
    assert_eq!(version, pi_carry_core::VERSION);

    // 连接方式必须在握手里就给出：手机端首屏的设备页要立刻显示
    // 「直连 / 中继」，等不到第一条 connection.updated 事件。
    let conn_obj = payload
        .get("connection")
        .and_then(Value::as_object)
        .expect("hello 必须带 connection");
    let kind = conn_obj
        .get("type")
        .and_then(Value::as_str)
        .expect("connection.type 必填");
    assert!(
        ["relay", "lan", "v4", "v6", "p2p"].contains(&kind),
        "connection.type 必须是六类之一，实际: {kind}"
    );
    // 测试环境是同机回环，必须判定为直连（lan）而不是中继。
    assert_eq!(kind, "lan", "127.0.0.1 回环应判为 lan");
    assert_eq!(conn_obj.get("direct"), Some(&Value::Bool(true)));

    conn.close(0u32.into(), b"bye");
    endpoint.close().await;
    bridge.shutdown().await;
}
