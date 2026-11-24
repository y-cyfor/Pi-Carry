//! 冷热快照的消息 id 必须完全一致（缺陷 M13 / N-1 / U-8 / P1-1 的回归门禁）。
//!
//! 背景：pi 的 `AgentMessage` 本身没有 id，只有会话文件的行级 entry 才有 id。
//! 曾经的实现里热路径用 `get_messages`（无 id，客户端只能随机兜底）、冷路径用
//! 会话文件（有 id），同一条消息在「重开/重连」后会换 id，客户端 LazyColumn 全体
//! churn、用户消息成对重复。现在两条路径统一来自 entry id，这里把这个约束钉住。

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
    config.node.name = "snapshot-test".into();
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

fn ids(messages: &[Value]) -> Vec<String> {
    messages
        .iter()
        .map(|m| {
            m.get("id")
                .and_then(Value::as_str)
                .unwrap_or_else(|| panic!("消息缺少 id：{m}"))
                .to_string()
        })
        .collect()
}

#[tokio::test]
async fn hot_and_cold_snapshot_share_entry_ids() {
    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();

    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "快照一致性".into(),
                mode: "auto".into(),
                client_id: "snap-1".into(),
                images: vec![],
            },
            "device-snap",
        )
        .await
        .unwrap();

    // 等这一轮彻底结束，否则热快照可能读到半截消息。
    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    loop {
        let left = deadline.saturating_duration_since(tokio::time::Instant::now());
        assert!(!left.is_zero(), "等待会话空闲超时");
        match tokio::time::timeout(left, events.recv()).await {
            Ok(Ok(ev))
                if ev.event["type"] == "run.state"
                    && ev.event["state"].as_str() == Some("idle") =>
            {
                break;
            }
            Ok(Ok(_)) => {}
            Ok(Err(e)) => panic!("事件流失败: {e}"),
            Err(_) => panic!("等待会话空闲超时"),
        }
    }

    let hot = bridge.sessions.snapshot_async(&summary.id).await.unwrap();
    assert!(
        !hot.messages.is_empty(),
        "热快照必须包含用户与助手消息：{}",
        serde_json::to_string_pretty(&hot.messages).unwrap()
    );
    let hot_ids = ids(&hot.messages);

    // 关掉实时会话，走纯磁盘冷路径。
    bridge.sessions.close(&summary.id).await.unwrap();
    let cold = bridge.sessions.snapshot(&summary.id).unwrap();
    let cold_ids = ids(&cold.messages);

    assert_eq!(
        hot_ids, cold_ids,
        "冷热快照的消息 id 必须逐条一致，否则客户端重连后会重复气泡"
    );
    // entry id 是 8 位十六进制短串；随机 UUID 兜底会破坏可去重性。
    for id in &hot_ids {
        assert_eq!(id.len(), 8, "entry id 应为 8 位短 id，实际 {id}");
        assert!(
            id.chars().all(|c| c.is_ascii_hexdigit()),
            "entry id 应为十六进制，实际 {id}"
        );
    }
    // 热快照里的每条消息都必须能在会话文件里找到同名 entry。
    // 直接扫 sessions 目录下的所有 jsonl，把 entry id 收集起来比对：
    // 这样即使将来目录层级变化，测试也不会变成只验证一个空断言。
    let mut entry_ids: Vec<String> = Vec::new();
    let mut stack = vec![std::path::PathBuf::from(&bridge.config.sessions.dir)];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                stack.push(p);
                continue;
            }
            if p.extension().and_then(|e| e.to_str()) != Some("jsonl") {
                continue;
            }
            let Ok(text) = std::fs::read_to_string(&p) else {
                continue;
            };
            for line in text.lines() {
                let Ok(v) = serde_json::from_str::<Value>(line) else {
                    continue;
                };
                if v.get("type").and_then(Value::as_str) == Some("message") {
                    if let Some(id) = v.get("id").and_then(Value::as_str) {
                        entry_ids.push(id.to_string());
                    }
                }
            }
        }
    }
    assert!(
        !entry_ids.is_empty(),
        "会话文件里应该有带 id 的 message entry"
    );
    for id in &hot_ids {
        assert!(
            entry_ids.contains(id),
            "快照消息 id {id} 必须来自会话文件的 entry id，实际 entries={entry_ids:?}"
        );
    }

    bridge.shutdown().await;
}
