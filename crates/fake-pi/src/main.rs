//! 假 pi：严格按 pi RPC 的 JSONL 协议说话，用于离线测试会话、流式与审批。
//!
//! 用法：fake-pi --session <path> [--scenario echo|slow|tools|approval-allow|approval-deny]

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
    /// 会话文件 entry id 的发号器。真 pi 用 8 位十六进制短 id，
    /// fake-pi 必须同形，否则测试无法发现“快照 id 不是 entry id”的回归。
    next_entry: AtomicU64,
    /// entry 树的父指针：真 pi 写入的每条 entry 都带 parentId。
    last_entry: Mutex<Option<String>>,
    /// 模拟审批扩展未加载：`get_commands` 不返回 guard 自检命令。
    without_guard: bool,
    /// 启动时收到的信任开关（`--approve` = Some(true)，`--no-approve` = Some(false)）。
    /// 供 `get_commands` 之外的自检使用，让测试能断言 bridge 的信任决策真的落到了命令行。
    trust_flag: Option<bool>,
}

impl State {
    /// 生成一个与真 pi 同形的 entry id（8 位小写十六进制）。
    fn entry_id(&self) -> String {
        // 混入 pid 与纳秒种子，多进程/多次运行不会撞号。
        let n = self.next_entry.fetch_add(1, Ordering::SeqCst);
        format!("{:08x}", (n as u32).wrapping_mul(2_654_435_761))
    }

    /// 追加一条会话文件 entry，并自动维护 parentId 链。
    fn append_entry(&self, kind: &str, id: &str, body: Value) {
        let parent = self.last_entry.lock().unwrap().clone();
        let mut entry = json!({
            "type": kind,
            "id": id,
            "parentId": parent,
            "timestamp": now(),
        });
        if let (Some(obj), Value::Object(extra)) = (entry.as_object_mut(), body) {
            obj.extend(extra);
        }
        append(&self.session_path, entry);
        *self.last_entry.lock().unwrap() = Some(id.to_string());
    }
}

type Out = mpsc::UnboundedSender<Value>;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let mut session = String::new();
    let mut scenario = std::env::var("FAKE_PI_SCENARIO").unwrap_or_else(|_| "echo".into());
    // 模拟“审批扩展没被加载”：get_commands 不返回 guard 的自检命令。
    let mut without_guard = false;
    // 记录收到的 `--approve` / `--no-approve`，让测试能断言信任决策真的传到了子进程。
    let mut trust_flag: Option<bool> = None;
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
            "--no-guard-ext" => without_guard = true,
            "--approve" | "-a" => trust_flag = Some(true),
            "--no-approve" | "-na" => trust_flag = Some(false),
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
    let seed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(1)
        ^ (std::process::id() as u64) << 32;
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
        next_entry: AtomicU64::new(seed),
        last_entry: Mutex::new(None),
        without_guard,
        trust_flag,
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
    let existing = if path.exists() {
        Some(std::fs::read_to_string(path)?)
    } else {
        None
    };
    if let Some(text) = &existing {
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
    let header_line = format!(
        "{}\n",
        serde_json::to_string(&json!({
            "type": "session", "version": 3, "id": id, "timestamp": now(), "cwd": cwd
        }))?
    );
    // header 必须落在**首行**：bridge 的会话扫描只认首行的 session 头。
    // 首行缺失时直接在末尾追加，会让文件看起来永远没有头（NEW-6）。
    // 绝不截断已有内容：先写临时文件再原子替换，中途崩溃也不会丢历史。
    use std::io::Write;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    match &existing {
        Some(text) => {
            let tmp = path.with_extension("jsonl.tmp");
            {
                let mut f = std::fs::File::create(&tmp)?;
                f.write_all(header_line.as_bytes())?;
                f.write_all(text.as_bytes())?;
                f.sync_all()?;
            }
            std::fs::rename(&tmp, path)?;
        }
        None => {
            let mut f = std::fs::OpenOptions::new()
                .append(true)
                .create(true)
                .open(path)?;
            f.write_all(header_line.as_bytes())?;
        }
    }
    Ok((id, cwd))
}

/// 标准 RFC3339（毫秒精度，UTC）。fake-pi 不引 chrono，用 Hinnant 的
/// days->civil 算法把 unix 秒换成 Y-M-D。
fn now() -> String {
    let d = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    let secs = d.as_secs();
    let (y, mo, day) = civil_from_days((secs / 86_400) as i64);
    let rem = secs % 86_400;
    format!(
        "{y:04}-{mo:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60,
        d.subsec_millis()
    )
}

/// 从 1970-01-01 起的天数换公历年月日（Howard Hinnant, civil_from_days）。
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
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
            "pendingMessageCount": 0,
            // 暴露信任开关：让 e2e 测试能验证「默认不信任项目资源」真的传到了 pi。
            "projectTrusted": state.trust_flag
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
            {"provider":"fake","id":"fake-1","name":"Fake 1","contextWindow":128000,
             "reasoning":false,"cost":{"input":0.15,"output":0.6}},
            {"provider":"fake","id":"fake-2","name":"Fake 2","contextWindow":256000,
             "reasoning":true,"cost":{"input":1.5,"output":6.0}}
        ]})),
        "get_available_thinking_levels" => {
            Ok(json!({"levels": ["off","minimal","low","medium","high"]}))
        }
        "get_commands" => {
            let mut commands = Vec::new();
            // bridge 用它做审批网关存活校验；真实 pi 由 guard.ts 注册同名命令。
            if !state.without_guard {
                commands.push(json!({
                    "name": pi_carry_core::guard::GUARD_COMMAND,
                    "description": "pi-carry 审批网关自检",
                    "source": "extension"
                }));
            }
            // 信任决策的自检面：真 pi 不会返回它，但测试替身需要一个可通过
            // 公开 API（get_commands）观察到的信号，否则无法断言 --no-approve 真的传下去了。
            commands.push(json!({
                "name": match state.trust_flag { Some(true) => "project-trusted", Some(false) => "project-untrusted", None => "project-trust-unspecified" },
                "description": "fake-pi 信任开关回显",
                "source": "extension"
            }));
            commands.push(json!({"name":"compact","description":"压缩上下文","source":"builtin"}));
            commands.push(json!({"name":"model","description":"切换模型","source":"builtin"}));
            Ok(json!({"commands": commands}))
        }
        "get_entries" => {
            // 真 pi 的 entries 是会话文件的行级记录（带 id/parentId），message 体内没有 id。
            // fake-pi 必须与真 pi 保持同形，否则冷热快照的 id 漂移会被测试天然掩盖（T2）。
            let mut entries = Vec::new();
            if let Ok(text) = std::fs::read_to_string(&state.session_path) {
                for line in text.lines() {
                    if let Ok(v) = serde_json::from_str::<Value>(line) {
                        entries.push(v);
                    }
                }
            }
            let leaf = entries
                .iter()
                .rev()
                .find_map(|e| e.get("id").cloned())
                .unwrap_or(Value::Null);
            Ok(json!({"entries": entries, "leafId": leaf}))
        }
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
            // 局限：真 pi 在需要审批的 run 里可能晚于首帧才回 prompt 响应，
            // fake-pi 则立即响应。要模拟“迟到响应”必须把响应发送从命令循环里拆出去，
            // 而命令循环一旦阻塞就读不到 extension_ui_response（审批会死锁）。
            // 因此这里保留即时响应，并把差异记在 docs/buglist-close.md。
            Ok(json!({}))
        }
        "fork" => Ok(json!({"text": "forked", "cancelled": false})),
        "switch_session" | "clone" | "new_session" => Ok(json!({})),
        "bash" => Ok(json!({"exitCode": 0, "output": ""})),
        other => Err(format!("fake-pi 不支持 {other}")),
    }
}

fn read_messages(path: &Path) -> Vec<Value> {
    // 与真 pi 的 `get_messages` 同语义：只返回 AgentMessage 本体，**不带行级 id**
    // （真 pi 的 agent.state.messages 没有 id 字段）。带了反而会让上层“看起来”
    // 热路径有 id，掩盖 cold 路径的 id 缺失。
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

/// 一段固定的 usage，供 run.summary 的统计回归使用。
///
/// 与真 pi 同形：input/output/cacheRead/cacheWrite/totalTokens/cost.total。
fn usage_fixture() -> Value {
    json!({
        "input": 1200,
        "output": 340,
        "cacheRead": 800,
        "cacheWrite": 0,
        "totalTokens": 2340,
        "cost": {"input": 0.00018, "output": 0.00051, "total": 0.00069}
    })
}

fn spawn_run(state: Arc<State>, out: Out, message: String, images: Value) {
    state.abort.store(false, Ordering::SeqCst);
    state.streaming.store(true, Ordering::SeqCst);
    tokio::spawn(async move {
        let run_id = format!("run-{}", now());
        let seq = state.message_count.fetch_add(1, Ordering::SeqCst);
        let msg_id = format!("msg-{}-{seq}", std::process::id());
        let user_entry = state.entry_id();
        state.append_entry(
            "message",
            &user_entry,
            json!({
                "message": {
                    "role": "user",
                    "content": [{"type": "text", "text": message}],
                    "images": images
                }
            }),
        );
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
        // `tools` 场景：一轮内跑多个工具（成功 / 失败 / 多次 update），
        // 用于回归 run.summary 的请求数与轨迹面板的多工具渲染。
        if state.scenario == "tools" && !state.abort.load(Ordering::SeqCst) {
            let tools = [("tool-read", "read", false), ("tool-bash", "bash", true)];
            for (idx, (id, name, with_updates)) in tools.iter().enumerate() {
                let args = if *name == "read" {
                    json!({"path": "src/main.rs"})
                } else {
                    json!({"command": "cargo test"})
                };
                let _ = out.send(json!({
                    "type": "tool_execution_start", "toolCallId": id,
                    "toolName": name, "args": args
                }));
                if *with_updates {
                    for step in ["编译中", "运行测试中"] {
                        let _ = out.send(json!({
                            "type": "tool_execution_update", "toolCallId": id,
                            "toolName": name,
                            "partialResult": {"output": step}
                        }));
                        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
                    }
                }
                // 第二个工具故意失败：轨迹面板必须能分开显示成功/失败。
                let failed = *name == "bash";
                let _ = out.send(json!({
                    "type": "tool_execution_end", "toolCallId": id,
                    "toolName": name,
                    "result": if failed { json!({"error": "boom"}) } else { json!({"output": "ok"}) },
                    "isError": failed,
                    "durationMs": 12 + idx as u64
                }));
            }
            // 工具跑完后再补一句话：真实 pi 也是这样，一条消息里带 toolCall 后继续生成。
            let _ = out.send(json!({
                "type": "message_update", "message": {"role": "assistant", "id": msg_id},
                "assistantMessageEvent": {"type": "text_delta", "contentIndex": 0, "delta": "工具已跑完。"}
            }));
        }
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
            let allowed = match tokio::time::timeout(std::time::Duration::from_secs(130), rx).await
            {
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
        let assistant_entry = state.entry_id();
        let assistant_msg = json!({
            "role": "assistant",
            "id": msg_id,
            "content": [{"type": "text", "text": content}],
            "stopReason": if aborted {"aborted"} else {"end_turn"},
            "timestamp": now(),
            "model": state.model.lock().unwrap().clone(),
            "usage": usage_fixture(),
        });
        // 真 pi 的会话文件里 message 体不带 id（AgentMessage 没有 id 字段），
        // 行级 entry 才有；fake-pi 必须同形，否则测试会掩盖客户的 id 对齐问题。
        state.append_entry(
            "message",
            &assistant_entry,
            json!({"message": assistant_msg.clone()}),
        );
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

#[cfg(test)]
mod tests {
    use super::*;

    /// NEW-6：文件里已有内容但没有 session 头时，header 必须补到**首行**。
    /// 追加到末尾会让 bridge 的会话扫描永远看不到头，等于伪造了一个坏会话。
    #[test]
    fn header_goes_to_first_line_when_missing() {
        let dir = std::env::temp_dir().join(format!("fake-pi-hdr-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("broken.jsonl");
        std::fs::write(&path, "{\"type\":\"custom\",\"note\":\"旧内容\"}\n").unwrap();

        let (id, _cwd) = ensure_session(&path).unwrap();
        let text = std::fs::read_to_string(&path).unwrap();
        let mut lines = text.lines();
        let first: Value = serde_json::from_str(lines.next().unwrap()).unwrap();
        assert_eq!(first.get("type").and_then(Value::as_str), Some("session"));
        assert_eq!(first.get("id").and_then(Value::as_str), Some(id.as_str()));
        // 旧内容不能丢：仍在第二行。
        let second: Value = serde_json::from_str(lines.next().unwrap()).unwrap();
        assert_eq!(second.get("note").and_then(Value::as_str), Some("旧内容"));
        assert_eq!(lines.count(), 0, "不应产生多余行");

        std::fs::remove_dir_all(&dir).ok();
    }

    /// 已有合法 header 时必须原样复用，不能重写文件（重写会丢历史）。
    #[test]
    fn existing_header_is_reused_without_rewrite() {
        let dir = std::env::temp_dir().join(format!("fake-pi-hdr2-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ok.jsonl");
        let original = "{\"type\":\"session\",\"version\":3,\"id\":\"known-id\",\"timestamp\":\"t\",\"cwd\":\"/w\"}\n{\"type\":\"custom\",\"note\":\"历史\"}\n";
        std::fs::write(&path, original).unwrap();

        let (id, cwd) = ensure_session(&path).unwrap();
        assert_eq!(id, "known-id");
        assert_eq!(cwd, "/w");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), original);

        std::fs::remove_dir_all(&dir).ok();
    }
}
