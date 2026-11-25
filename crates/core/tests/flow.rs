//! 端到端核心流程：新建会话、流式、审批、恢复。

use chrono::Utc;
use pi_carry_core::bridge::{Bridge, BridgeOptions};
use pi_carry_core::clock::FixedClock;
use pi_carry_core::config::Config;
use pi_carry_core::paths::Paths;
use pi_carry_protocol::{ImagePayload, PromptPayload};
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

/// 递归找第一个图片对象。
fn find_image(v: &Value) -> Option<&Value> {
    match v {
        Value::Object(map) => {
            if map.get("type").and_then(Value::as_str) == Some("image") {
                return Some(v);
            }
            map.values().find_map(find_image)
        }
        Value::Array(items) => items.iter().find_map(find_image),
        _ => None,
    }
}

/// 递归找第一个带 blobId 的图片对象。
fn find_blob_image(v: &Value) -> Option<&Value> {
    match v {
        Value::Object(map) => {
            if map.get("type").and_then(Value::as_str) == Some("image")
                && map.contains_key("blobId")
            {
                return Some(v);
            }
            map.values().find_map(find_blob_image)
        }
        Value::Array(items) => items.iter().find_map(find_blob_image),
        _ => None,
    }
}

#[tokio::test]
async fn legacy_inline_image_is_adopted_on_snapshot() {
    use base64::Engine;
    use sha2::Digest;

    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    // 必须大于快照内联图降级阈值（512 KiB），才能验证「先收编、后裁剪」的顺序。
    let mut bytes = vec![0u8; 600 * 1024];
    bytes[..8].copy_from_slice(b"\x89PNG\r\n\x1a\n");
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "旧版本的内联图".into(),
                mode: "auto".into(),
                client_id: "legacy-image-1".into(),
                images: vec![ImagePayload {
                    r#type: "image".into(),
                    data: base64::engine::general_purpose::STANDARD.encode(&bytes),
                    blob_id: None,
                    mime_type: "image/png".into(),
                    width: 1,
                    height: 1,
                    size: 0,
                }],
            },
            "device-1",
        )
        .await
        .unwrap();
    wait_event(&mut events, "message.final", 5).await;

    // 客户端没上传过 blob；服务端应从快照的 base64 收编，随后只下发 blobId。
    let snap = bridge
        .sessions
        .snapshot_async_with_blobs(&summary.id, true)
        .await
        .unwrap();
    let image = snap
        .messages
        .iter()
        .find_map(find_blob_image)
        .expect("历史内联图应收编并投影成 blobId");
    let sha = image["blobId"].as_str().unwrap();
    assert_eq!(sha, hex::encode(sha2::Sha256::digest(&bytes)));
    assert!(image.get("data").is_none());
    let usage = bridge.sessions.blobs().usage(&summary.id).unwrap();
    assert_eq!(usage.count, 1);
    assert_eq!(usage.used_bytes, bytes.len() as u64);
}

#[tokio::test]
async fn snapshot_of_closed_session_keeps_inline_images() {
    use base64::Engine;

    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    let bytes = b"\x89PNG\r\n\x1a\n-closed".to_vec();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "关闭后仍可看历史图".into(),
                mode: "auto".into(),
                client_id: "closed-image-1".into(),
                images: vec![ImagePayload {
                    r#type: "image".into(),
                    data: base64::engine::general_purpose::STANDARD.encode(&bytes),
                    blob_id: None,
                    mime_type: "image/png".into(),
                    width: 1,
                    height: 1,
                    size: 0,
                }],
            },
            "device-1",
        )
        .await
        .unwrap();
    wait_event(&mut events, "message.final", 5).await;
    bridge.sessions.close(&summary.id).await.unwrap();

    // 关闭中的会话不再收编（避免 cleanup 后的永久引用），快照应保留 base64。
    let snap = bridge
        .sessions
        .snapshot_async_with_blobs(&summary.id, true)
        .await
        .unwrap();
    let image = snap
        .messages
        .iter()
        .find_map(find_image)
        .expect("关闭后的会话仍应返回历史图");
    assert!(image.get("data").is_some(), "非 live 快照应保留 base64");
    assert!(image.get("blobId").is_none());
    assert_eq!(bridge.sessions.blobs().usage(&summary.id).unwrap().count, 0);
}

#[tokio::test]
async fn fork_migrates_session_and_broadcasts_event() {
    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "分叉前".into(),
                mode: "auto".into(),
                client_id: "fork-1".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .unwrap();
    wait_event(&mut events, "message.final", 5).await;

    let entries = bridge
        .sessions
        .get_entries(&summary.id, None)
        .await
        .unwrap();
    let entry_id = entries["entries"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e.get("type").and_then(Value::as_str) == Some("message"))
        .and_then(|e| e["id"].as_str())
        .expect("会话里应有 message entry")
        .to_string();

    let forked = bridge.sessions.fork(&summary.id, &entry_id).await.unwrap();
    assert_ne!(forked.id, summary.id, "fork 必须生成新会话 id");
    assert_eq!(forked.parent_id.as_deref(), Some(summary.id.as_str()));

    let migrated = wait_event(&mut events, "session.migrated", 5).await;
    assert_eq!(migrated["fromSessionId"], summary.id);
    assert_eq!(migrated["toSessionId"], forked.id);
    assert_eq!(migrated["reason"], "fork");

    // 旧会话从迁移成功那一刻起拒绝新 prompt，避免静默双写（B-14/B-47）。
    let rejected = bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "迁移后旧会话".into(),
                mode: "auto".into(),
                client_id: "fork-old".into(),
                images: vec![],
            },
            "device-1",
        )
        .await
        .expect_err("旧会话必须拒绝写入");
    assert!(
        rejected.to_string().contains("迁移"),
        "错误应说明会话已迁移: {rejected}"
    );

    // 新会话的文件只复制到所选 entry，快照里的消息 id 与 entry id 同源。
    let snap = bridge.sessions.snapshot_async(&forked.id).await.unwrap();
    assert!(
        snap.messages.iter().any(|m| m["id"] == entry_id),
        "快照应包含所选 entry 的消息"
    );
}

#[tokio::test]
async fn blob_image_is_forwarded_and_projected_back_to_blob_id() {
    use base64::Engine;
    use sha2::Digest;

    let (bridge, _tmp) = bridge_with("echo").await;
    let summary = bridge.sessions.create("").await.unwrap();
    let bytes = b"\x89PNG\r\n\x1a\nfake-image-for-flow";
    let sha = hex::encode(sha2::Sha256::digest(bytes));
    bridge
        .sessions
        .blobs()
        .put(
            &summary.id,
            &sha,
            "image/png",
            bytes.len() as u64,
            0,
            &base64::engine::general_purpose::STANDARD.encode(bytes),
        )
        .unwrap();

    let mut events = bridge.sessions.subscribe();
    bridge
        .sessions
        .prompt(
            PromptPayload {
                session_id: summary.id.clone(),
                message: "看图".into(),
                mode: "auto".into(),
                client_id: "blob-1".into(),
                images: vec![ImagePayload {
                    r#type: "image".into(),
                    data: String::new(),
                    blob_id: Some(sha.clone()),
                    mime_type: "image/png".into(),
                    width: 1,
                    height: 1,
                    size: bytes.len() as u64,
                }],
            },
            "device-1",
        )
        .await
        .unwrap();
    wait_event(&mut events, "message.final", 5).await;

    // 没声明 blob 能力的老客户端必须继续拿到 base64，不能只剩一个看不懂的 blobId。
    let legacy = bridge.sessions.snapshot_async(&summary.id).await.unwrap();
    let legacy_image = legacy
        .messages
        .iter()
        .find_map(|v| {
            fn find(v: &Value) -> Option<&Value> {
                match v {
                    Value::Object(map)
                        if map.get("type").and_then(Value::as_str) == Some("image") =>
                    {
                        Some(v)
                    }
                    Value::Object(map) => map.values().find_map(find),
                    Value::Array(items) => items.iter().find_map(find),
                    _ => None,
                }
            }
            find(v)
        })
        .expect("老客户端快照应保留图片");
    assert!(
        legacy_image.get("data").is_some(),
        "老客户端必须继续收 base64"
    );
    assert!(legacy_image.get("blobId").is_none());

    let snap = bridge
        .sessions
        .snapshot_async_with_blobs(&summary.id, true)
        .await
        .unwrap();
    let image = snap
        .messages
        .iter()
        .find_map(find_blob_image)
        .expect("快照里的历史图应投影成 blobId");
    assert_eq!(image["blobId"], sha);
    assert_eq!(image["size"], bytes.len() as u64);
    assert!(image.get("data").is_none(), "命中 blob 后不应再带 base64");

    // 关闭会话后引用清零，图片文件不再被任何会话使用。
    bridge.sessions.close(&summary.id).await.unwrap();
    let removed = bridge
        .sessions
        .blobs()
        .cleanup_session(&summary.id)
        .unwrap();
    assert_eq!(removed, 1);
    assert_eq!(bridge.sessions.blobs().usage(&summary.id).unwrap().count, 0);
}
