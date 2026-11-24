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
async fn rejected_prompt_reports_error_and_returns_idle() {
    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();

    // fake-pi 对 `!fail` 开头的 prompt 返回错误（模拟真 pi 拒绝）。
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "!fail".into(),
                mode: "auto".into(),
                client_id: "c-fail".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();

    let err = wait_event(&mut events, "error", 5).await;
    assert_eq!(err["source"], "prompt");
    assert!(err["message"].as_str().unwrap().contains("拒绝"));
    // 拒绝后状态必须回到 idle，而不是永久 streaming。
    assert_eq!(bridge.sessions.summary(&summary.id).unwrap().status, "idle");

    // writer 已释放，可继续正常发送。
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "你好".into(),
                mode: "auto".into(),
                client_id: "c-ok".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();
    let delta = wait_event(&mut events, "message.delta", 5).await;
    assert_eq!(delta["kind"], "text");
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
async fn approval_allow_always_skips_following_approval() {
    let (bridge, _tmp) = bridge_with("approval-allow").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "第一条".into(),
                mode: "auto".into(),
                client_id: "c-aa1".into(),
                images: vec![],
            },
            "device-aa",
        )
        .await
        .unwrap();
    let req = wait_event(&mut events, "approval.requested", 5).await;
    let approval_id = req["approval"]["id"].as_str().unwrap().to_string();
    bridge
        .approvals
        .resolve(&approval_id, "allow_always", "device-aa", false)
        .unwrap();
    wait_event(&mut events, "message.final", 5).await;
    wait_event(&mut events, "run.state", 5).await;

    // 第二条 prompt：同会话普通工具直接放行，不再产生审批请求。
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "第二条".into(),
                mode: "auto".into(),
                client_id: "c-aa2".into(),
                images: vec![],
            },
            "device-aa",
        )
        .await
        .unwrap();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    loop {
        let ev = tokio::time::timeout_at(deadline, events.recv())
            .await
            .unwrap()
            .unwrap();
        match ev.event["type"].as_str().unwrap_or("") {
            "approval.requested" => panic!("本对话都允许后不应再次询问"),
            "message.final" => break,
            _ => {}
        }
    }
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

#[tokio::test]
async fn session_refuses_to_open_when_approval_guard_is_missing() {
    // 审批扩展没加载 = 所有工具调用无审批直通。必须在建会话时就失败，
    // 而不是让用户以为“手机上没弹审批就是放行”。
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "guard-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.node.pi_args = vec!["--no-guard-ext".into()];
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

    let err = bridge.sessions.create("").await.unwrap_err();
    let msg = err.to_string();
    assert!(
        msg.contains("审批扩展未加载"),
        "错误必须明确指向审批网关缺失，实际: {msg}"
    );
    bridge.shutdown().await;
}

#[tokio::test]
async fn invalid_mode_does_not_steal_writer_from_running_device() {
    // S-2：非法 mode 必须在占用 writer 之前被拒绝。旧实现在该分支
    // 无条件 `*writer = None`，设备 A 一次拼错的 mode 就能让设备 B 接管
    // A 正在跑的会话（abort / steer / 换模型全部绕过 SESSION_BUSY）。
    let (bridge, _tmp) = bridge_with("slow").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();

    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "先跑起来".into(),
                mode: "auto".into(),
                client_id: "c-run".into(),
                images: vec![],
            },
            "device-a",
        )
        .await
        .unwrap();
    let running = wait_event(&mut events, "run.state", 5).await;
    assert_eq!(running["state"], "streaming");

    let bad = bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "拼错的模式".into(),
                mode: "queueing".into(),
                client_id: "c-bad".into(),
                images: vec![],
            },
            "device-a",
        )
        .await
        .unwrap_err();
    assert!(
        bad.to_string().contains("未知发送模式"),
        "应报非法模式，实际: {bad}"
    );

    // writer 仍属于 device-a：别的设备不能插手，运行状态也不能被拨回 idle。
    let other = bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "我也想发".into(),
                mode: "auto".into(),
                client_id: "c-other".into(),
                images: vec![],
            },
            "device-b",
        )
        .await
        .unwrap_err();
    assert_eq!(
        other.code(),
        pi_carry_protocol::ErrorCode::SessionBusy,
        "非法 mode 后 writer 不应被释放，实际: {other}"
    );
    assert_eq!(
        bridge.sessions.summary(&summary.id).unwrap().status,
        "streaming"
    );
    bridge.shutdown().await;
}
