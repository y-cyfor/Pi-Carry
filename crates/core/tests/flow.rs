//! 端到端核心流程：新建会话、流式、审批、恢复。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_protocol::PromptPayload;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;

mod common;

use common::fake_pi;

async fn bridge_with(scenario: &str) -> (Arc<Bridge>, tempfile::TempDir) {
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "flow-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.node.pi_args = vec!["--scenario".into(), scenario.into()];
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
    (bridge, tmp)
}

async fn wait_event(
    rx: &mut tokio::sync::broadcast::Receiver<pi_carry_core::sessions::SessionEvent>,
    kind: &str,
    secs: u64,
) -> Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
    loop {
        match tokio::time::timeout_at(deadline, rx.recv()).await {
            Ok(Ok(ev)) if ev.event["type"] == kind => return ev.event,
            Ok(Ok(_)) => {}
            Ok(Err(e)) => panic!("事件流失败: {e}"),
            Err(_) => panic!("等待事件 {kind} 超时"),
        }
    }
}

#[tokio::test]
async fn create_prompt_and_stream() {
    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    assert!(!summary.id.is_empty());
    assert_eq!(bridge.sessions.list().unwrap().len(), 1);

    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "你好".into(),
                mode: "auto".into(),
                client_id: "c1".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();
    let delta = wait_event(&mut events, "message.delta", 5).await;
    assert_eq!(delta["kind"], "text");
    let fin = wait_event(&mut events, "message.final", 5).await;
    assert_eq!(fin["message"]["role"], "assistant");
    let idle = wait_event(&mut events, "run.state", 5).await;
    assert_eq!(idle["state"], "idle");
    bridge.shutdown().await;
}

#[tokio::test]
async fn approval_allow_completes_run() {
    let (bridge, _tmp) = bridge_with("approval-allow").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "跑个命令".into(),
                mode: "auto".into(),
                client_id: "c2".into(),
                images: vec![],
            },
            "device-2",
        )
        .await
        .unwrap();

    let req = wait_event(&mut events, "approval.requested", 5).await;
    let approval_id = req["approval"]["id"].as_str().unwrap().to_string();
    assert_eq!(req["approval"]["toolName"], "bash");
    bridge
        .approvals
        .resolve(&approval_id, "allow", "device-2", false)
        .unwrap();
    let resolved = wait_event(&mut events, "approval.resolved", 5).await;
    assert_eq!(resolved["decision"], "allowed");
    let fin = wait_event(&mut events, "message.final", 5).await;
    assert_eq!(fin["message"]["role"], "assistant");
    bridge.shutdown().await;
}
