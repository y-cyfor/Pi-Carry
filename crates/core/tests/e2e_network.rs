//! 真实 iroh 回环的端到端测试：配对、控制流、事件流、审批。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_protocol::{
    read_frame, write_frame, ClientFrame, ClientInfo, HelloPayload, PromptPayload, ServerFrame,
    ALPN, MAX_CONTROL_FRAME, PROTOCOL_VERSION,
};
use serde_json::{json, Value};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

mod common;

use common::fake_pi;

async fn start_bridge() -> (Arc<Bridge>, tempfile::TempDir) {
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "e2e".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.node.pi_args = vec!["--scenario".into(), "approval-allow".into()];
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = tmp.path().join("sessions").display().to_string();
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths: Paths::at(tmp.path().join("home")),
        clock: Some(Arc::new(FixedClock::new(Utc::now()))),
    })
    .await
    .unwrap();
    (bridge, tmp)
}

fn socket_of(bridge: &Bridge) -> SocketAddr {
    *bridge
        .server
        .endpoint()
        .bound_sockets()
        .first()
        .expect("没有绑定地址")
}

struct Conn {
    conn: iroh::endpoint::Connection,
    send: iroh::endpoint::SendStream,
    recv: iroh::endpoint::RecvStream,
    next: u64,
}

impl Conn {
    async fn send_request(&mut self, op: &str, payload: Value) {
        self.next += 1;
        let id = format!("r{}", self.next);
        write_frame(
            &mut self.send,
            &ClientFrame::Request {
                id,
                op: op.into(),
                payload,
            },
            MAX_CONTROL_FRAME,
        )
        .await
        .unwrap();
    }

    async fn next_event(&mut self, deadline: tokio::time::Instant) -> Option<Value> {
        loop {
            let raw =
                tokio::time::timeout_at(deadline, read_frame(&mut self.recv, 4 * 1024 * 1024))
                    .await
                    .ok()?
                    .ok()??;
            let frame: ServerFrame = serde_json::from_slice(&raw).ok()?;
            if let ServerFrame::Event { event, .. } = frame {
                return Some(event);
            }
        }
    }

    async fn request(&mut self, op: &str, payload: Value) -> Value {
        self.next += 1;
        let id = format!("r{}", self.next);
        write_frame(
            &mut self.send,
            &ClientFrame::Request {
                id: id.clone(),
                op: op.into(),
                payload,
            },
            MAX_CONTROL_FRAME,
        )
        .await
        .unwrap();
        loop {
            let raw = tokio::time::timeout(
                Duration::from_secs(10),
                read_frame(&mut self.recv, 4 * 1024 * 1024),
            )
            .await
            .expect("响应超时")
            .unwrap()
            .expect("连接关闭");
            let frame: ServerFrame = serde_json::from_slice(&raw).unwrap();
            if let ServerFrame::Response {
                id: rid,
                ok,
                payload,
                error,
            } = frame
            {
                if rid.as_deref() != Some(id.as_str()) {
                    continue;
                }
                assert!(ok, "请求 {op} 失败: {error:?}");
                return payload.unwrap_or(Value::Null);
            }
        }
    }
}

#[tokio::test]
async fn full_network_flow() {
    let (bridge, _tmp) = start_bridge().await;
    let pairing = bridge.begin_pairing(None);
    let addr =
        iroh::EndpointAddr::new(bridge.server.endpoint().id()).with_ip_addr(socket_of(&bridge));

    let client = iroh::Endpoint::builder(iroh::endpoint::presets::Minimal)
        .bind()
        .await
        .unwrap();
    let conn = client.connect(addr, ALPN).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    let hello = ClientFrame::Request {
        id: "hello".into(),
        op: "hello".into(),
        payload: serde_json::to_value(HelloPayload {
            protocol: PROTOCOL_VERSION.into(),
            client: ClientInfo {
                name: "e2e".into(),
                platform: "test".into(),
                device_class: "phone".into(),
                app_version: "0.1".into(),
                locale: "zh-CN".into(),
            },
            token: Some(pairing.token.clone()),
            code: None,
            password: None,
            resume_session_id: None,
        })
        .unwrap(),
    };
    write_frame(&mut send, &hello, MAX_CONTROL_FRAME)
        .await
        .unwrap();
    let raw = read_frame(&mut recv, MAX_CONTROL_FRAME)
        .await
        .unwrap()
        .unwrap();
    let hello_resp: ServerFrame = serde_json::from_slice(&raw).unwrap();
    let ServerFrame::Response { ok, payload, .. } = hello_resp else {
        panic!("期望 hello response");
    };
    assert!(ok);
    assert_eq!(payload.unwrap()["device"]["capability"], "full");

    let mut c = Conn {
        conn,
        send,
        recv,
        next: 0,
    };
    let node = c.request("node.status", Value::Null).await;
    assert_eq!(node["node"]["name"], "e2e");
    let created = c.request("session.create", json!({"cwd": ""})).await;
    let session_id = created["session"]["id"].as_str().unwrap().to_string();
    let renamed = c
        .request(
            "session.rename",
            json!({"sessionId": session_id, "name": "网络测试"}),
        )
        .await;
    assert_eq!(renamed["session"]["name"], "网络测试");
    let attached = c
        .request(
            "session.attach",
            json!({"sessionId": session_id, "lastSeq": 0}),
        )
        .await;
    assert!(attached["events"].is_array());
    assert_eq!(attached["snapshotRequired"], false);

    c.send_request(
        "prompt.send",
        serde_json::to_value(PromptPayload {
            session_id: session_id.clone(),
            message: "网络审批测试".into(),
            mode: "auto".into(),
            client_id: "e2e-1".into(),
            images: vec![],
        })
        .unwrap(),
    )
    .await;

    let mut approval_id = None;
    let mut got_final = false;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    while tokio::time::Instant::now() < deadline {
        let Some(v) = c.next_event(deadline).await else {
            break;
        };
        match v["type"].as_str() {
            Some("approval.requested") => {
                let id = v["approval"]["id"].as_str().unwrap().to_string();
                approval_id = Some(id.clone());
                c.request(
                    "approval.resolve",
                    json!({"approvalId": id, "decision": "allow", "remember": false}),
                )
                .await;
            }
            Some("message.final") => {
                got_final = true;
                break;
            }
            _ => {}
        }
    }
    assert!(approval_id.is_some(), "未收到审批请求");
    assert!(got_final, "未收到 message.final");
    c.send.write_all(b"").await.unwrap();
    c.conn.close(0u8.into(), b"done");
    bridge.shutdown().await;
    client.close().await;
}
