//! 假 pi：严格按 pi RPC 的 JSONL 协议说话，用于离线测试会话、流式与审批。
//!
//! 用法：fake-pi --session <path> [--scenario echo|slow|approval-allow|approval-deny]

use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, BufWriter};
use tokio::sync::{mpsc, oneshot};

struct State {
    session_path: PathBuf,
    session_id: String,
    scenario: String,
    streaming: AtomicBool,
    abort: AtomicBool,
    model: Mutex<Value>,
    thinking: Mutex<String>,
    message_count: AtomicU64,
    pending: Mutex<HashMap<String, oneshot::Sender<Value>>>,
}

type Out = mpsc::UnboundedSender<Value>;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let mut session = String::new();
    let mut scenario = std::env::var("FAKE_PI_SCENARIO").unwrap_or_else(|_| "echo".into());
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--session" | "--session-path" => {
                i += 1;
                session = args.get(i).cloned().unwrap_or_default();
            }
            "--scenario" => {
                i += 1;
                scenario = args.get(i).cloned().unwrap_or_default();
            }
            "--session-id" | "--cwd" | "--name" => i += 1,
            _ => {}
        }
        i += 1;
    }
    if session.is_empty() {
        session = std::env::temp_dir()
            .join(format!("fake-pi-{}.jsonl", std::process::id()))
            .display()
            .to_string();
    }
    let path = PathBuf::from(&session);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let (session_id, _cwd) = ensure_session(&path)?;
    let state = Arc::new(State {
        session_path: path,
        session_id,
        scenario,
        streaming: AtomicBool::new(false),
        abort: AtomicBool::new(false),
        model: Mutex::new(json!({"provider": "fake", "id": "fake-1", "name": "Fake 1"})),
        thinking: Mutex::new("medium".into()),
        message_count: AtomicU64::new(0),
        pending: Mutex::new(HashMap::new()),
    });

    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<Value>();
    let writer = tokio::spawn(async move {
        let mut out = BufWriter::new(tokio::io::stdout());
        while let Some(v) = out_rx.recv().await {
            let line = serde_json::to_vec(&v).expect("序列化");
            if out.write_all(&line).await.is_err()
                || out.write_all(b"\n").await.is_err()
                || out.flush().await.is_err()
            {
                break;
            }
        }
    });

    let mut lines = BufReader::new(tokio::io::stdin()).split(b'\n');
    while let Ok(Some(line)) = lines.next_segment().await {
        if line.is_empty() {
            continue;
        }
        let v: Value = match serde_json::from_slice(&line) {
            Ok(v) => v,
            Err(e) => {
                let _ = out_tx.send(json!({
                    "type": "response", "success": false, "error": format!("非法 JSON: {e}")
                }));
                continue;
            }
        };
        let ty = v.get("type").and_then(Value::as_str).unwrap_or("");
        if ty == "extension_ui_response" {
            let id = v.get("id").and_then(Value::as_str).unwrap_or("");
            if let Some(tx) = state.pending.lock().unwrap().remove(id) {
                let _ = tx.send(v);
            }
            continue;
        }
        let id = v.get("id").cloned().unwrap_or(Value::Null);
        let resp = handle(&state, &out_tx, ty, &v).await;
        let mut r = json!({"type": "response", "success": resp.is_ok()});
        if !id.is_null() {
            r["id"] = id;
        }
        r["command"] = json!(ty);
        match resp {
            Ok(data) => r["data"] = data,
            Err(e) => r["error"] = json!(e),
        }
        let _ = out_tx.send(r);
    }
    state.abort.store(true, Ordering::SeqCst);
    drop(out_tx);
    let _ = writer.await;
    Ok(())
}

fn ensure_session(path: &Path) -> anyhow::Result<(String, String)> {
    if path.exists() {
        let text = std::fs::read_to_string(path)?;
        if let Some(first) = text.lines().next() {
            if let Ok(v) = serde_json::from_str::<Value>(first) {
                if v.get("type").and_then(Value::as_str) == Some("session") {
                    return Ok((
                        v.get("id")
                            .and_then(Value::as_str)
                            .unwrap_or("fake-session")
                            .into(),
                        v.get("cwd").and_then(Value::as_str).unwrap_or(".").into(),
                    ));
                }
            }
        }
    }
    let id = format!("fake-{}", std::process::id());
    let cwd = std::env::current_dir()?.display().to_string();
    let header = json!({
        "type": "session", "version": 3, "id": id, "timestamp": now(), "cwd": cwd
    });
    std::fs::write(path, format!("{}\n", serde_json::to_string(&header)?))?;
    Ok((id, cwd))
}

fn now() -> String {
    let d = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    format!("{}.{:03}Z", d.as_secs(), d.subsec_millis())
}

fn append(path: &Path, v: Value) {
    use std::io::Write;
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .append(true)
        .create(true)
        .open(path)
    {
        let _ = writeln!(f, "{}", serde_json::to_string(&v).unwrap_or_default());
    }
}

async fn handle(state: &Arc<State>, out: &Out, ty: &str, v: &Value) -> Result<Value, String> {
    match ty {
        "get_state" => Ok(json!({
            "model": state.model.lock().unwrap().clone(),
            "thinkingLevel": state.thinking.lock().unwrap().clone(),
            "isStreaming": state.streaming.load(Ordering::SeqCst),
            "isCompacting": false,
            "steeringMode": "all",
            "followUpMode": "one-at-a-time",
            "sessionFile": state.session_path,
            "sessionId": state.session_id,
            "sessionName": "假 pi 会话",
            "autoCompactionEnabled": true,
            "messageCount": state.message_count.load(Ordering::SeqCst),
            "pendingMessageCount": 0
        })),
        "get_messages" => Ok(json!({"messages": read_messages(&state.session_path)})),
        "set_model" => {
            *state.model.lock().unwrap() = json!({
                "provider": v.get("provider"), "id": v.get("modelId")
            });
            Ok(json!({"model": state.model.lock().unwrap().clone()}))
        }
        "set_thinking_level" => {
            *state.thinking.lock().unwrap() = v
                .get("level")
                .and_then(Value::as_str)
                .unwrap_or("medium")
                .into();
            Ok(json!({"level": state.thinking.lock().unwrap().clone()}))
        }
        "get_available_models" => Ok(json!({"models": [
            {"provider":"fake","id":"fake-1","name":"Fake 1","contextWindow":128000},
            {"provider":"fake","id":"fake-2","name":"Fake 2","contextWindow":256000}
        ]})),
        "get_available_thinking_levels" => {
            Ok(json!({"levels": ["off","minimal","low","medium","high"]}))
        }
        "get_commands" => Ok(json!({"commands": [
            {"name":"compact","description":"压缩上下文","source":"builtin"},
            {"name":"model","description":"切换模型","source":"builtin"}
        ]})),
        "get_entries" => Ok(json!({"entries": [], "leafId": null})),
        "get_tree" => Ok(json!({"tree": []})),
        "get_last_assistant_text" => Ok(json!({"text": last_assistant(&state.session_path)})),
        "get_session_stats" => Ok(json!({"messages": state.message_count.load(Ordering::SeqCst)})),
        "set_session_name" => Ok(json!({"name": v.get("name")})),
        "set_steering_mode" | "set_follow_up_mode" | "set_auto_compaction" | "set_auto_retry" => {
            Ok(json!({"ok": true}))
        }
        "abort" => {
            state.abort.store(true, Ordering::SeqCst);
            Ok(json!({}))
        }
        "abort_retry" | "abort_bash" => Ok(json!({})),
        "compact" => {
            let _ = out.send(json!({
                "type": "compaction_start", "reason": "manual"
            }));
            let _ = out.send(json!({
                "type": "compaction_end", "result": {}, "aborted": false, "willRetry": false
            }));
            Ok(json!({"summary": "已压缩"}))
        }
        "clear_queue" => Ok(json!({"steering": [], "followUp": []})),
        "prompt" | "steer" | "follow_up" => {
            let message = v
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            // `!fail` 前缀模拟真 pi 拒绝 prompt（消息非法/队列已满）。
            if message.starts_with("!fail") {
                return Err("fake-pi 拒绝该 prompt".to_string());
            }
            let images = v.get("images").cloned().unwrap_or_else(|| json!([]));
            spawn_run(state.clone(), out.clone(), message, images);
            Ok(json!({}))
        }
        "fork" => Ok(json!({"text": "forked", "cancelled": false})),
        "switch_session" | "clone" | "new_session" => Ok(json!({})),
        "bash" => Ok(json!({"exitCode": 0, "output": ""})),
        other => Err(format!("fake-pi 不支持 {other}")),
    }
}

fn read_messages(path: &Path) -> Vec<Value> {
    let Ok(text) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    text.lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .filter(|v| v.get("type").and_then(Value::as_str) == Some("message"))
        .filter_map(|v| v.get("message").cloned())
        .collect()
}

fn last_assistant(path: &Path) -> String {
    read_messages(path)
        .into_iter()
        .rev()
        .find(|m| m.get("role").and_then(Value::as_str) == Some("assistant"))
        .and_then(|m| {
            m.get("content").and_then(Value::as_array).map(|a| {
                a.iter()
                    .filter_map(|c| c.get("text").and_then(Value::as_str))
                    .collect::<Vec<_>>()
                    .join("")
            })
        })
        .unwrap_or_default()
}

fn spawn_run(state: Arc<State>, out: Out, message: String, images: Value) {
    state.abort.store(false, Ordering::SeqCst);
    state.streaming.store(true, Ordering::SeqCst);
    tokio::spawn(async move {
        let run_id = format!("run-{}", now());
        let msg_id = format!(
            "msg-{}-{}",
            std::process::id(),
            state.message_count.load(Ordering::SeqCst)
        );
        state.message_count.fetch_add(1, Ordering::SeqCst);
        let user = json!({
            "type": "message", "id": format!("{msg_id}-u"), "timestamp": now(),
            "message": {"role": "user", "content": [{"type": "text", "text": message}], "images": images}
        });
        append(&state.session_path, user);
        let _ = out.send(json!({"type": "agent_start"}));
        let _ = out.send(json!({
            "type": "message_start", "message": {"role": "assistant", "id": msg_id}
        }));
        let _ = out.send(json!({"type": "turn_start"}));
        let pieces = ["收到：", &message, "。这是 fake-pi 的回复。"];
        for piece in pieces {
            if state.abort.load(Ordering::SeqCst) {
                break;
            }
            let _ = out.send(json!({
                "type": "message_update",
                "message": {"role": "assistant", "id": msg_id},
                "assistantMessageEvent": {"type": "text_delta", "contentIndex": 0, "delta": piece}
            }));
            let delay = if state.scenario == "slow" { 200 } else { 5 };
            tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
        }
        let wants_approval = state.scenario.starts_with("approval");
        if wants_approval && !state.abort.load(Ordering::SeqCst) {
            let ui_id = format!("ui-{}", now());
            let (tx, rx) = oneshot::channel();
            state.pending.lock().unwrap().insert(ui_id.clone(), tx);
            let _ = out.send(json!({
                "type": "extension_ui_request", "id": ui_id, "method": "confirm",
                "title": "pi-carry 审批", "message": format!("PI_CARRY_APPROVAL:{}", json!({
                    "toolName": "bash", "toolCallId": "tool-1",
                    "input": {"command": "echo fake"}
                })),
                "timeout": 120000
            }));
            let allowed = match tokio::time::timeout(std::time::Duration::from_secs(5), rx).await {
                Ok(Ok(v)) => v.get("confirmed").and_then(Value::as_bool).unwrap_or(false),
                _ => false,
            };
            let _ = out.send(json!({
                "type": "tool_execution_start", "toolCallId": "tool-1",
                "toolName": "bash", "args": {"command": "echo fake"}
            }));
            let _ = out.send(json!({
                "type": "tool_execution_end", "toolCallId": "tool-1",
                "toolName": "bash", "result": {"output": "fake"},
                "isError": false, "durationMs": 3
            }));
            let text = if allowed {
                "已执行。"
            } else {
                "已取消。"
            };
            let _ = out.send(json!({
                "type": "message_update", "message": {"role": "assistant", "id": msg_id},
                "assistantMessageEvent": {"type": "text_delta", "contentIndex": 0, "delta": text}
            }));
        }
        let aborted = state.abort.load(Ordering::SeqCst);
        let content = if aborted { "已中断" } else { "完成" };
        let assistant = json!({
            "type": "message", "id": msg_id, "timestamp": now(),
            "message": {
                "role": "assistant", "id": msg_id,
                "content": [{"type": "text", "text": content}],
                "stopReason": if aborted {"aborted"} else {"end_turn"}
            }
        });
        let assistant_msg = assistant["message"].clone();
        append(&state.session_path, assistant);
        let _ = out.send(json!({"type": "message_end", "message": assistant_msg.clone()}));
        let _ = out.send(json!({
            "type": "turn_end", "message": assistant_msg, "toolResults": []
        }));
        let _ = out.send(json!({"type": "agent_end", "messages": [], "willRetry": false}));
        state.streaming.store(false, Ordering::SeqCst);
        let _ = out.send(json!({"type": "agent_settled", "aborted": aborted}));
        let _ = run_id;
    });
}
