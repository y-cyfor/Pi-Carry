//! `run.summary` 的端到端门禁（需求 0.1.4-4）。
//!
//! 手机端的「本轮总结」卡片与轨迹面板都只读这一个事件：
//! - `requests` 必须等于本轮真实模型调用次数（工具循环会多调用一次）；
//! - `totalTokens`/`cost` 在没有 usage 时必须是 null 而不是 0，
//!   否则 UI 会把「拿不到」显示成「没花钱」；
//! - 事件必须在 `run.state: idle` 之前到达，UI 才不会闪动。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_core::sessions::SessionEvent;
use pi_carry_protocol::PromptPayload;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;

mod common;

use common::fake_pi;

async fn bridge_with(scenario: &str) -> (Arc<Bridge>, tempfile::TempDir) {
    bridge_with_clock(scenario, Arc::new(FixedClock::new(Utc::now()))).await
}

/// 与 [`bridge_with`] 相同，但允许测试自己持有并推进时钟。
async fn bridge_with_clock(
    scenario: &str,
    clock: Arc<FixedClock>,
) -> (Arc<Bridge>, tempfile::TempDir) {
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "run-summary-test".into();
    config.node.pi_path = fake_pi().display().to_string();
    config.node.pi_args = vec!["--scenario".into(), scenario.into()];
    config.transport.relay_mode = "disabled".into();
    config.transport.bind_addr = Some("127.0.0.1:0".into());
    config.sessions.dir = tmp.path().join("sessions").display().to_string();
    let paths = Paths::at(tmp.path().join("home"));
    let bridge = Bridge::start(BridgeOptions {
        config,
        paths,
        clock: Some(clock),
    })
    .await
    .unwrap();
    (bridge, tmp)
}

/// 等到第一个满足条件的事件；期间的其他事件丢弃。
async fn wait_until(
    rx: &mut tokio::sync::broadcast::Receiver<SessionEvent>,
    secs: u64,
    done: impl Fn(&Value) -> bool,
) -> Value {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
    loop {
        match tokio::time::timeout_at(deadline, rx.recv()).await {
            Ok(Ok(ev)) => {
                if done(&ev.event) {
                    return ev.event;
                }
            }
            Ok(Err(e)) => panic!("事件流失败: {e}"),
            Err(_) => panic!("等待目标事件超时"),
        }
    }
}

/// 收集事件直到看到 `run.state: idle`，同时记录 seen（含 run.summary 的位置）。
async fn collect_until_idle(
    rx: &mut tokio::sync::broadcast::Receiver<SessionEvent>,
    secs: u64,
) -> Vec<Value> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(secs);
    let mut out = Vec::new();
    loop {
        match tokio::time::timeout_at(deadline, rx.recv()).await {
            Ok(Ok(ev)) => {
                let idle = ev.event["type"] == "run.state" && ev.event["state"] == "idle";
                out.push(ev.event);
                if idle {
                    return out;
                }
            }
            Ok(Err(e)) => panic!("事件流失败: {e}"),
            Err(_) => panic!("等待 idle 超时，已收到: {out:#?}"),
        }
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_summary_reports_usage_and_precedes_idle() {
    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();

    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "你好".into(),
                mode: "auto".into(),
                client_id: "c-sum".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();

    let events = collect_until_idle(&mut events, 15).await;
    let idx = events
        .iter()
        .position(|e| e["type"] == "run.summary")
        .unwrap_or_else(|| panic!("本轮必须发出 run.summary，实际收到: {events:#?}"));
    let idle_idx = events
        .iter()
        .position(|e| e["type"] == "run.state" && e["state"] == "idle")
        .expect("必须有 idle");
    assert!(
        idx < idle_idx,
        "run.summary 必须先于 run.state(idle)：否则 UI 会先收起 streaming 再补总结行"
    );

    let s = &events[idx];
    assert_eq!(s["requests"], 1, "echo 场景一轮只有一次模型调用");
    // fake-pi 的 usage_fixture：input 1200 / output 340 / cacheRead 800 / total 2340。
    assert_eq!(s["inputTokens"], 1200);
    assert_eq!(s["outputTokens"], 340);
    assert_eq!(s["cacheReadTokens"], 800);
    assert_eq!(s["totalTokens"], 2340);
    let cost = s["cost"].as_f64().expect("cost 应为数字");
    assert!((cost - 0.00069).abs() < 1e-9, "cost 汇总错误: {cost}");
    assert_eq!(s["aborted"], false);
    assert!(
        s["durationMs"].as_u64().is_some(),
        "正常一轮必须有 durationMs"
    );
    assert!(
        s["firstTokenMs"].as_u64().is_some(),
        "收到过 delta 就必须有 firstTokenMs"
    );
    assert_eq!(s["model"], "fake/fake-1");

    bridge.shutdown().await;
}

/// A-5：`run.summary` 的计时必须取注入时钟，而不是 `chrono::Utc::now()`。
///
/// 在起点（`agent_start` 的 `run.state: streaming`）之后把冻结时钟推进 5 秒：
/// 若实现绕过注入时钟，`durationMs` 会等于真实耗时（slow 场景约 600ms）而不是 5000；
/// 冻结时钟下更差——差值可为负，被 `max(0)` 悄悄吞成 0。
#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_summary_duration_follows_injected_clock() {
    let clock = Arc::new(FixedClock::new(Utc::now()));
    let (bridge, _tmp) = bridge_with_clock("slow", clock.clone()).await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "计时".into(),
                mode: "auto".into(),
                client_id: "c-clock".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();

    wait_until(&mut events, 15, |e| {
        e["type"] == "run.state" && e["state"] == "streaming"
    })
    .await;
    clock.advance_secs(5);

    let events = collect_until_idle(&mut events, 20).await;
    let s = events
        .iter()
        .find(|e| e["type"] == "run.summary")
        .unwrap_or_else(|| panic!("缺少 run.summary: {events:#?}"));
    assert_eq!(
        s["durationMs"], 5000,
        "durationMs 必须是注入时钟的差值，而不是真实耗时: {s}"
    );

    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn run_summary_counts_tool_loop_requests() {
    let (bridge, _tmp) = bridge_with("tools").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "跑个工具".into(),
                mode: "auto".into(),
                client_id: "c-tools".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();

    let events = collect_until_idle(&mut events, 15).await;
    let s = events
        .iter()
        .find(|e| e["type"] == "run.summary")
        .unwrap_or_else(|| panic!("缺少 run.summary: {events:#?}"));
    // fake-pi 的 tools 场景跑两个工具但只发一条 assistant 消息，
    // 所以 requests 仍是 1；这里断言它与 message_end 次数一致，而不是硬编码工具数。
    let finals = events
        .iter()
        .filter(|e| e["type"] == "message.final")
        .count();
    assert_eq!(
        s["requests"].as_u64().unwrap() as usize,
        finals,
        "requests 必须等于本轮 assistant 消息数（每次模型调用一条）"
    );
    // 轨迹事件必须齐备，否则面板会是空的。
    let started = events
        .iter()
        .filter(|e| e["type"] == "tool.started")
        .count();
    let ended = events.iter().filter(|e| e["type"] == "tool.ended").count();
    assert_eq!(started, 2, "tools 场景应发两个 tool.started");
    assert_eq!(ended, 2, "tools 场景应发两个 tool.ended");
    assert!(
        events
            .iter()
            .any(|e| e["type"] == "tool.ended" && e["isError"] == true),
        "必须有一个失败的工具，才能回归面板的成功/失败区分"
    );

    bridge.shutdown().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn aborted_run_summary_is_marked() {
    let (bridge, _tmp) = bridge_with("slow").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "长任务".into(),
                mode: "auto".into(),
                client_id: "c-abort".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();
    // 等第一个 delta 再中断，确保 agent_start 已到（否则不会建 run 统计）。
    let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
    loop {
        match tokio::time::timeout_at(deadline, events.recv()).await {
            Ok(Ok(ev)) if ev.event["type"] == "message.delta" => break,
            Ok(Ok(_)) => {}
            Ok(Err(e)) => panic!("事件流失败: {e}"),
            Err(_) => panic!("等待首个 delta 超时"),
        }
    }
    bridge
        .sessions
        .abort(&summary.id, "device-1")
        .await
        .unwrap();

    let events = collect_until_idle(&mut events, 20).await;
    let s = events
        .iter()
        .find(|e| e["type"] == "run.summary")
        .unwrap_or_else(|| panic!("中断也必须发 run.summary: {events:#?}"));
    assert_eq!(s["aborted"], true, "中断的轮次必须标记 aborted");

    bridge.shutdown().await;
}
