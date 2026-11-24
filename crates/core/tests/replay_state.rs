//! 快照/重放状态机的边界（N-3 回归门禁）。
//!
//! 客户端在重连时会先 `session.attach`（按 lastSeq 重放）再按需 `session.snapshot`。
//! 两者的分界只要错一步，手机就会丢消息（重放被当成空）或者重复整段历史
//! （该拉快照却给了重放）。这些行为都是纯服务端决定，必须被钉住。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use serde_json::Value;
use std::sync::Arc;

mod common;

use common::fake_pi;

async fn bridge() -> (Arc<Bridge>, tempfile::TempDir) {
    let tmp = tempfile::tempdir().unwrap();
    let mut config = Config::default();
    config.node.name = "replay-test".into();
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
    (bridge, tmp)
}

/// 写入 n 条可控事件，返回最后一条的 seq。
fn seed(bridge: &Bridge, sid: &str, n: u64) -> u64 {
    let mut last = 0;
    for i in 0..n {
        last = bridge
            .sessions
            .emit_raw(
                sid,
                serde_json::json!({"type": "notification", "level": "info", "message": format!("e{i}")}),
            )
            .unwrap();
    }
    last
}

#[tokio::test]
async fn replay_returns_events_after_cursor_and_no_gap() {
    let (bridge, _tmp) = bridge().await;
    let sid = "replay-1";
    seed(&bridge, sid, 5);
    // 游标在 3：应拿回 4、5 两条，且不需要快照。
    let (events, need_snapshot) = bridge.sessions.replay(sid, 3, 100).unwrap();
    assert!(!need_snapshot, "连续游标不应要求快照");
    assert_eq!(events.len(), 2, "应重放 seq 4..=5");
    assert_eq!(events[0]["message"], Value::from("e3"));
    assert_eq!(events[1]["message"], Value::from("e4"));
}

#[tokio::test]
async fn replay_at_head_is_empty_not_snapshot() {
    let (bridge, _tmp) = bridge().await;
    let sid = "replay-2";
    let last = seed(&bridge, sid, 3);
    // 游标正好在尾部：没有新事件，但也不需要快照（否则手机会白拉一次全量）。
    let (events, need_snapshot) = bridge.sessions.replay(sid, last, 100).unwrap();
    assert!(events.is_empty());
    assert!(!need_snapshot, "游标等于最新 seq 不该要求快照");
}

#[tokio::test]
async fn replay_future_cursor_requests_snapshot() {
    let (bridge, _tmp) = bridge().await;
    let sid = "replay-3";
    seed(&bridge, sid, 2);
    // 游标超过服务端最新 seq：说明客户端记的是别的会话/被重置过，重放无从下手。
    let (events, need_snapshot) = bridge.sessions.replay(sid, 999, 100).unwrap();
    assert!(events.is_empty());
    assert!(need_snapshot, "游标超前时必须要求快照而不是静默空转");
}

#[tokio::test]
async fn replay_requires_snapshot_when_journal_was_pruned() {
    let (bridge, _tmp) = bridge().await;
    let sid = "replay-4";
    seed(&bridge, sid, 6);
    // 裁掉前几条：客户端的游标落在被删掉的那段里，重放会出现空洞。
    // 只保留最近 3 条：客户端游标 1 落在被删掉的区间里。
    bridge.store.prune_journal(sid, 3).unwrap();
    let (events, need_snapshot) = bridge.sessions.replay(sid, 1, 100).unwrap();
    assert!(events.is_empty(), "有空洞时不能给残缺事件序列");
    assert!(need_snapshot, "journal 被裁剪后必须要求快照");
}

#[tokio::test]
async fn snapshot_carries_last_seq_and_idle_run() {
    let (bridge, _tmp) = bridge().await;
    let summary = bridge.sessions.create("").await.unwrap();
    let last = seed(&bridge, &summary.id, 4);
    let snap = bridge.sessions.snapshot_async(&summary.id).await.unwrap();
    assert_eq!(
        snap.last_seq, last,
        "快照必须带上水位，客户端据此跳过已包含的事件"
    );
    assert_eq!(snap.session.id, summary.id);
    // 没有 run 时队列必须是合法 JSON（客户端会直接渲染它）。
    assert!(snap.queue.is_object() || snap.queue.is_array());
}

#[tokio::test]
async fn watermark_only_moves_forward() {
    let (bridge, _tmp) = bridge().await;
    let summary = bridge.sessions.create("").await.unwrap();
    seed(&bridge, &summary.id, 3);
    // 正常前进。
    bridge
        .store
        .set_ack("dev-1", &summary.id, 2)
        .expect("ack 2 应成功");
    // 回退必须被吸收：客户端乱序 ACK 不能让水位倒退，否则服务端会重发整段历史。
    // 实现是 `DO UPDATE SET seq=MAX(seq,excluded.seq)`——写入成功但值不回退。
    bridge
        .store
        .set_ack("dev-1", &summary.id, 1)
        .expect("回退写入不应报错，应被吸收");
    assert_eq!(
        bridge.store.get_ack("dev-1", &summary.id).unwrap(),
        2,
        "水位不得回退"
    );
}
