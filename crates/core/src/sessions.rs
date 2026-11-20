//! 会话索引、pi 子进程生命周期、事件归一化与 journal。

use crate::approval::{ApprovalManager, Decision};
use crate::clock::{rfc3339, Clock, IdGen};
use crate::config::Config;
use crate::error::{CoreError, Result};
use crate::paths::Paths;
use crate::pi::{PiClient, PiEvent};
use crate::store::Store;
use pi_carry_protocol::events as ev;
use pi_carry_protocol::{
    ErrorCode, ImagePayload, PromptPayload, ProtocolError, SessionSnapshot, SessionSummary,
    UiResponsePayload,
};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{broadcast, oneshot};

#[derive(Debug, Clone)]
pub struct SessionEvent {
    pub session_id: String,
    pub seq: u64,
    pub event: Value,
}

#[derive(Debug, Clone)]
struct RunState {
    status: String,
    run_id: Option<String>,
    message_id: Option<String>,
    model: Value,
    thinking: String,
    queue: Value,
    error: Option<String>,
}

impl Default for RunState {
    fn default() -> Self {
        Self {
            status: "idle".into(),
            run_id: None,
            message_id: None,
            model: Value::Null,
            thinking: "medium".into(),
            queue: json!({"steering": [], "followUp": []}),
            error: None,
        }
    }
}

pub struct LiveSession {
    pub id: String,
    pub path: PathBuf,
    pub cwd: String,
    pub client: PiClient,
    pub summary: Mutex<SessionSummary>,
    state: Mutex<RunState>,
    writer: Mutex<Option<String>>,
    task: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

impl LiveSession {
    fn status(&self) -> String {
        self.state.lock().expect("state poisoned").status.clone()
    }

    fn set_status(&self, status: &str) {
        self.state.lock().expect("state poisoned").status = status.to_string();
        let mut s = self.summary.lock().expect("summary poisoned");
        s.status = status.to_string();
        s.active = true;
        s.updated_at = rfc3339(chrono::Utc::now());
    }
}

pub struct SessionManager {
    config: Config,
    store: Store,
    clock: Arc<dyn Clock>,
    ids: Arc<dyn IdGen>,
    approvals: ApprovalManager,
    pi_path: PathBuf,
    guard: PathBuf,
    live: Mutex<HashMap<String, Arc<LiveSession>>>,
    events: broadcast::Sender<SessionEvent>,
    ui_waiters: Mutex<HashMap<String, oneshot::Sender<Value>>>,
}

impl SessionManager {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        config: Config,
        _paths: Paths,
        store: Store,
        clock: Arc<dyn Clock>,
        ids: Arc<dyn IdGen>,
        approvals: ApprovalManager,
        pi_path: PathBuf,
        guard: PathBuf,
    ) -> Arc<Self> {
        let (events, _) = broadcast::channel(8192);
        Arc::new(Self {
            config,
            store,
            clock,
            ids,
            approvals,
            pi_path,
            guard,
            live: Mutex::new(HashMap::new()),
            events,
            ui_waiters: Mutex::new(HashMap::new()),
        })
    }

    pub fn subscribe(&self) -> broadcast::Receiver<SessionEvent> {
        self.events.subscribe()
    }

    pub fn approvals(&self) -> &ApprovalManager {
        &self.approvals
    }

    pub fn sessions_dir(&self) -> PathBuf {
        PathBuf::from(&self.config.sessions.dir)
    }

    /// 扫描磁盘会话，合并内存中的实时状态。
    pub fn list(&self) -> Result<Vec<SessionSummary>> {
        let mut out = self.scan_disk()?;
        let live = self.live.lock().expect("live poisoned");
        for s in &mut out {
            if let Some(l) = live.get(&s.id) {
                let mut mem = l.summary.lock().expect("summary poisoned").clone();
                mem.status = l.status();
                mem.active = true;
                mem.last_source = "mobile".into();
                s.clone_from(&mem);
            }
            s.pending_approvals = self.store.list_pending_approvals(Some(&s.id))?.len() as u32;
        }
        out.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
        out.truncate(self.config.sessions.scan_limit);
        Ok(out)
    }

    pub fn summary(&self, id: &str) -> Result<SessionSummary> {
        if let Some(l) = self.live.lock().expect("live poisoned").get(id) {
            let mut s = l.summary.lock().expect("summary poisoned").clone();
            s.status = l.status();
            s.active = true;
            return Ok(s);
        }
        self.scan_disk()?
            .into_iter()
            .find(|s| s.id == id)
            .ok_or_else(|| CoreError::SessionNotFound(id.into()))
    }

    fn scan_disk(&self) -> Result<Vec<SessionSummary>> {
        let dir = self.sessions_dir();
        let mut out = Vec::new();
        if !dir.is_dir() {
            return Ok(out);
        }
        let Ok(groups) = std::fs::read_dir(&dir) else {
            return Ok(out);
        };
        for group in groups.flatten() {
            if !group.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                continue;
            }
            let Ok(files) = std::fs::read_dir(group.path()) else {
                continue;
            };
            for file in files.flatten() {
                let p = file.path();
                if p.extension().and_then(|e| e.to_str()) != Some("jsonl") {
                    continue;
                }
                if let Ok(Some(s)) = scan_one(&p) {
                    out.push(s);
                }
            }
        }
        Ok(out)
    }

    pub async fn open(self: &Arc<Self>, id: &str) -> Result<SessionSummary> {
        if let Some(l) = self.live.lock().expect("live poisoned").get(id).cloned() {
            if !l.client.is_closed() {
                return Ok(l.summary.lock().expect("summary poisoned").clone());
            }
            self.live.lock().expect("live poisoned").remove(id);
        }
        let path = self.resolve_path(id)?;
        self.attach(&path, id).await
    }

    /// 新建会话：先写合法 session header，再让 pi attach。
    pub async fn create(self: &Arc<Self>, cwd: &str) -> Result<SessionSummary> {
        let cwd = if cwd.trim().is_empty() {
            std::env::current_dir()
                .map(|p| p.display().to_string())
                .unwrap_or_else(|_| ".".into())
        } else {
            cwd.to_string()
        };
        let id = uuid::Uuid::new_v4().to_string();
        let group = format!(
            "--{}--",
            cwd.replace(
                |c: char| [':', '/', std::path::MAIN_SEPARATOR].contains(&c),
                "-"
            )
        );
        let dir = self.sessions_dir().join(group);
        std::fs::create_dir_all(&dir)?;
        let name = format!(
            "{}_{}.jsonl",
            chrono::Utc::now().format("%Y-%m-%dT%H-%M-%S-%3fZ"),
            id
        );
        let path = dir.join(name);
        let header = json!({
            "type": "session",
            "version": 3,
            "id": id,
            "timestamp": rfc3339(self.clock.now()),
            "cwd": cwd,
        });
        std::fs::write(
            &path,
            format!(
                "{}
",
                serde_json::to_string(&header)?
            ),
        )?;
        self.attach(&path, &id).await
    }

    async fn attach(self: &Arc<Self>, path: &Path, expected_id: &str) -> Result<SessionSummary> {
        let client = PiClient::spawn(
            &self.pi_path,
            path,
            Some(&self.guard),
            &self.config.node.pi_args,
        )
        .await?;
        let state = match client.request("get_state", Value::Null).await {
            Ok(v) => unwrap_state(v),
            Err(e) => {
                client.shutdown().await;
                return Err(e);
            }
        };
        let session_id = state
            .get("sessionId")
            .and_then(Value::as_str)
            .unwrap_or(expected_id)
            .to_string();
        let cwd = state
            .get("sessionFile")
            .and_then(Value::as_str)
            .and_then(|f| scan_one(Path::new(f)).ok().flatten())
            .map(|s| s.cwd)
            .unwrap_or_else(|| {
                path.parent()
                    .map(|p| p.display().to_string())
                    .unwrap_or_default()
            });
        let name = state
            .get("sessionName")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let mut summary = scan_one(path)?.unwrap_or(SessionSummary {
            id: session_id.clone(),
            cwd: cwd.clone(),
            name: name.clone(),
            preview: String::new(),
            updated_at: rfc3339(self.clock.now()),
            message_count: 0,
            status: "idle".into(),
            active: true,
            pending_approvals: 0,
            last_source: "mobile".into(),
            writable: true,
            parent_id: None,
        });
        summary.id = session_id.clone();
        summary.active = true;
        summary.writable = true;
        if summary.name.is_empty() {
            summary.name = name;
        }
        let live = Arc::new(LiveSession {
            id: session_id.clone(),
            path: path.to_path_buf(),
            cwd: cwd.clone(),
            client: client.clone(),
            summary: Mutex::new(summary.clone()),
            state: Mutex::new(RunState {
                status: "idle".into(),
                model: state.get("model").cloned().unwrap_or(Value::Null),
                thinking: state
                    .get("thinkingLevel")
                    .and_then(Value::as_str)
                    .unwrap_or("medium")
                    .into(),
                ..Default::default()
            }),
            writer: Mutex::new(None),
            task: Mutex::new(None),
        });
        self.live
            .lock()
            .expect("live poisoned")
            .insert(session_id.clone(), live.clone());
        let me = self.clone();
        let live_for_task = live.clone();
        let handle = tokio::spawn(async move {
            run_events(me, live_for_task).await;
        });
        *live.task.lock().expect("task poisoned") = Some(handle);
        self.store
            .upsert_session(&summary, &path.display().to_string())?;
        self.emit_raw(
            &summary.id,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": summary,
            }),
        )?;
        Ok(summary)
    }

    pub async fn close(&self, id: &str) -> Result<()> {
        let live = self.live.lock().expect("live poisoned").remove(id);
        if let Some(l) = live {
            if let Some(h) = l.task.lock().expect("task poisoned").take() {
                h.abort();
            }
            l.client.shutdown().await;
        }
        Ok(())
    }

    pub async fn shutdown(&self) {
        let all: Vec<_> = self
            .live
            .lock()
            .expect("live poisoned")
            .drain()
            .map(|(_, v)| v)
            .collect();
        for l in all {
            if let Some(h) = l.task.lock().expect("task poisoned").take() {
                h.abort();
            }
            l.client.shutdown().await;
        }
    }

    fn resolve_path(&self, id: &str) -> Result<PathBuf> {
        if id.ends_with(".jsonl") && Path::new(id).is_file() {
            return Ok(PathBuf::from(id));
        }
        let dir = self.sessions_dir();
        if let Ok(groups) = std::fs::read_dir(&dir) {
            for group in groups.flatten() {
                if let Ok(files) = std::fs::read_dir(group.path()) {
                    for file in files.flatten() {
                        let p = file.path();
                        if p.extension().and_then(|e| e.to_str()) != Some("jsonl") {
                            continue;
                        }
                        if let Ok(Some(s)) = scan_one(&p) {
                            if s.id == id {
                                return Ok(p);
                            }
                        }
                    }
                }
            }
        }
        Err(CoreError::SessionNotFound(id.into()))
    }

    pub fn snapshot(&self, id: &str) -> Result<SessionSnapshot> {
        let summary = self.summary(id)?;
        let last_seq = self.store.last_seq(id)?;
        Ok(SessionSnapshot {
            session: summary,
            last_seq,
            messages: self
                .resolve_path(id)
                .ok()
                .map(|p| read_session_messages(&p))
                .unwrap_or_default(),
            pending_approvals: self.store.list_pending_approvals(Some(id))?,
            run: None,
            queue: json!({"steering": [], "followUp": []}),
        })
    }

    /// 带 get_messages 的完整快照（服务端使用）。
    pub async fn snapshot_async(&self, id: &str) -> Result<SessionSnapshot> {
        let mut snap = self.snapshot(id)?;
        let live = {
            let map = self.live.lock().expect("live poisoned");
            map.get(id).cloned()
        };
        if let Some(live) = live {
            if let Ok(v) = live.client.request("get_messages", Value::Null).await {
                snap.messages = extract_messages(v);
            }
            let st = live.state.lock().expect("state poisoned");
            snap.run = Some(json!({
                "runId": st.run_id,
                "state": st.status,
                "model": st.model,
                "thinkingLevel": st.thinking,
            }));
            snap.queue = st.queue.clone();
        }
        snap.last_seq = self.store.last_seq(id)?;
        Ok(snap)
    }

    /// 重连补洞：返回 seq > after 的事件；若客户端游标早于 journal 最旧记录，
    /// 返回 snapshot_required=true，客户端应改用 snapshot。
    pub fn replay(&self, id: &str, after: u64, limit: usize) -> Result<(Vec<Value>, bool)> {
        let (oldest, latest) = self.store.seq_bounds(id)?;
        if after > latest {
            return Ok((Vec::new(), true));
        }
        let snapshot_required = oldest > 1 && after + 1 < oldest;
        if snapshot_required {
            return Ok((Vec::new(), true));
        }
        let rows = self.store.events_after(id, after, limit.min(2000))?;
        Ok((rows.into_iter().map(|(_, _, v)| v).collect(), false))
    }

    pub async fn prompt(self: &Arc<Self>, p: PromptPayload, device_id: &str) -> Result<Value> {
        if p.message.trim().is_empty() && p.images.is_empty() {
            return Err(CoreError::InvalidArgument("消息不能为空".into()));
        }
        let live = self.ensure_live(&p.session_id).await?;
        {
            let mut w = live.writer.lock().expect("writer poisoned");
            if let Some(owner) = w.as_ref() {
                if owner != device_id {
                    return Err(CoreError::Protocol(ProtocolError::new(
                        ErrorCode::SessionBusy,
                        format!("会话正被设备 {owner} 使用"),
                    )));
                }
            }
            *w = Some(device_id.to_string());
        }
        let streaming = live.status() == "streaming";
        let mode = if p.mode == "auto" {
            if streaming {
                "steer"
            } else {
                "prompt"
            }
        } else {
            p.mode.as_str()
        };
        let mut payload = json!({"message": p.message});
        if !p.images.is_empty() {
            payload["images"] = Value::Array(p.images.iter().map(image_json).collect());
        }
        match mode {
            "steer" => payload["streamingBehavior"] = json!("steer"),
            "queue" | "followUp" | "follow_up" => payload["streamingBehavior"] = json!("followUp"),
            "prompt" | "auto" => {}
            other => {
                *live.writer.lock().expect("writer poisoned") = None;
                return Err(CoreError::InvalidArgument(format!(
                    "未知发送模式 {other}（auto/steer/queue/followUp）"
                )));
            }
        }
        live.set_status("streaming");
        self.emit_logged(
            &live,
            json!({
                "type": ev::RUN_STATE,
                "runId": live.state.lock().expect("state poisoned").run_id,
                "state": "streaming",
            }),
        );
        // 不等待 pi 的 prompt 响应：该响应在需要审批的 run 中要等审批结束，
        // 会卡住控制循环，导致审批请求无法被处理。这里只保证写入成功。
        let mut request = payload;
        request["type"] = json!("prompt");
        request["id"] = json!(format!("prompt-{}", self.ids.next_id()));
        if let Err(e) = live.client.send_raw(&request).await {
            *live.writer.lock().expect("writer poisoned") = None;
            live.set_status("idle");
            self.emit_logged(
                &live,
                json!({
                    "type": ev::RUN_STATE,
                    "state": "idle",
                    "error": e.to_string(),
                }),
            );
            return Err(e);
        }
        self.emit_logged(
            &live,
            json!({
                "type": ev::QUEUE_UPDATED,
                "steering": [],
                "followUp": [],
            }),
        );
        Ok(json!({"accepted": true, "mode": mode}))
    }

    async fn ensure_live(self: &Arc<Self>, id: &str) -> Result<Arc<LiveSession>> {
        if let Some(l) = self.live.lock().expect("live poisoned").get(id).cloned() {
            if !l.client.is_closed() {
                return Ok(l);
            }
            self.live.lock().expect("live poisoned").remove(id);
        }
        self.open(id).await?;
        self.live
            .lock()
            .expect("live poisoned")
            .get(id)
            .cloned()
            .ok_or_else(|| CoreError::SessionNotFound(id.into()))
    }

    pub async fn abort(&self, id: &str, device_id: &str) -> Result<()> {
        let live = self.require_live(id)?;
        self.assert_writer(&live, device_id)?;
        live.client.request("abort", Value::Null).await?;
        Ok(())
    }

    pub async fn clear_queue(&self, id: &str, device_id: &str) -> Result<Value> {
        let live = self.require_live(id)?;
        self.assert_writer(&live, device_id)?;
        let v = live.client.request("clear_queue", Value::Null).await?;
        self.emit_logged(
            &live,
            json!({
                "type": ev::QUEUE_UPDATED,
                "steering": v.get("steering").cloned().unwrap_or(json!([])),
                "followUp": v.get("followUp").cloned().unwrap_or(json!([])),
            }),
        );
        Ok(v)
    }

    fn require_live(&self, id: &str) -> Result<Arc<LiveSession>> {
        self.live
            .lock()
            .expect("live poisoned")
            .get(id)
            .cloned()
            .ok_or_else(|| CoreError::SessionNotFound(format!("{id} 尚未打开")))
    }

    fn assert_writer(&self, live: &LiveSession, device_id: &str) -> Result<()> {
        let w = live.writer.lock().expect("writer poisoned");
        match w.as_ref() {
            Some(owner) if owner != device_id => Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::SessionBusy,
                format!("会话正被设备 {owner} 使用"),
            ))),
            _ => Ok(()),
        }
    }

    pub async fn set_model(&self, id: &str, provider: &str, model_id: &str) -> Result<Value> {
        let live = self.require_live(id)?;
        let v = live
            .client
            .request(
                "set_model",
                json!({"provider": provider, "modelId": model_id}),
            )
            .await?;
        live.state.lock().expect("state poisoned").model =
            json!({"provider": provider, "id": model_id});
        self.emit_logged(
            &live,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": live.summary.lock().expect("summary poisoned").clone(),
            }),
        );
        Ok(v)
    }

    pub async fn set_thinking(&self, id: &str, level: &str) -> Result<Value> {
        let live = self.require_live(id)?;
        let v = live
            .client
            .request("set_thinking_level", json!({"level": level}))
            .await?;
        live.state.lock().expect("state poisoned").thinking = level.to_string();
        self.emit_logged(
            &live,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": live.summary.lock().expect("summary poisoned").clone(),
            }),
        );
        Ok(v)
    }

    /// 修改会话显示名；pi 会把新名字写回会话文件。
    pub async fn rename(&self, id: &str, name: &str) -> Result<SessionSummary> {
        let name = name.trim();
        if name.is_empty() {
            return Err(CoreError::InvalidArgument("会话名不能为空".into()));
        }
        if name.chars().count() > 200 {
            return Err(CoreError::InvalidArgument("会话名过长".into()));
        }
        let live = self.require_live(id)?;
        live.client
            .request("set_session_name", json!({ "name": name }))
            .await?;
        let mut summary = live.summary.lock().expect("summary poisoned").clone();
        summary.name = name.to_string();
        self.store
            .upsert_session(&summary, &live.path.display().to_string())?;
        self.emit_logged(
            &live,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": summary.clone(),
            }),
        );
        Ok(summary)
    }

    pub async fn compact(&self, id: &str, instructions: Option<String>) -> Result<Value> {
        let live = self.require_live(id)?;
        let v = live
            .client
            .request(
                "compact",
                instructions
                    .map(|i| json!({"customInstructions": i}))
                    .unwrap_or(Value::Null),
            )
            .await?;
        Ok(v)
    }

    /// 从 pi 读取当前可用模型（不同 provider 配置下动态变化）。
    pub async fn available_models(self: &Arc<Self>, id: &str) -> Result<Value> {
        let live = self.ensure_live(id).await?;
        live.client
            .request("get_available_models", Value::Null)
            .await
    }

    /// 从 pi 读取当前模型支持的思考等级。
    pub async fn available_thinking_levels(self: &Arc<Self>, id: &str) -> Result<Value> {
        let live = self.ensure_live(id).await?;
        live.client
            .request("get_available_thinking_levels", Value::Null)
            .await
    }

    pub async fn commands(&self, id: &str) -> Result<Value> {
        let live = self.require_live(id)?;
        live.client.request("get_commands", Value::Null).await
    }

    pub async fn get_entries(&self, id: &str, since: Option<String>) -> Result<Value> {
        let live = self.require_live(id)?;
        live.client
            .request(
                "get_entries",
                since.map(|s| json!({"since": s})).unwrap_or(Value::Null),
            )
            .await
    }

    pub async fn fork(self: &Arc<Self>, id: &str, entry_id: &str) -> Result<SessionSummary> {
        let live = self.require_live(id)?;
        let v = live
            .client
            .request("fork", json!({"entryId": entry_id}))
            .await?;
        if v.get("cancelled").and_then(Value::as_bool) == Some(true) {
            return Err(CoreError::InvalidArgument("fork 被 pi 取消".into()));
        }
        let state = unwrap_state(live.client.request("get_state", Value::Null).await?);
        let new_id = state
            .get("sessionId")
            .and_then(Value::as_str)
            .ok_or_else(|| CoreError::Pi("fork 后 get_state 没有 sessionId".into()))?
            .to_string();
        let path = state
            .get("sessionFile")
            .and_then(Value::as_str)
            .map(PathBuf::from)
            .ok_or_else(|| CoreError::Pi("fork 后 get_state 没有 sessionFile".into()))?;
        let mut summary = scan_one(&path)?
            .ok_or_else(|| CoreError::Pi(format!("fork 后找不到会话文件 {}", path.display())))?;
        summary.active = true;
        summary.writable = true;
        summary.status = "idle".into();

        // 同一个 pi 进程现在服务新会话：停掉旧的事件任务，把 live 记录迁到新 id。
        let old = self
            .live
            .lock()
            .expect("live poisoned")
            .remove(id)
            .unwrap_or(live);
        if let Some(h) = old.task.lock().expect("task poisoned").take() {
            h.abort();
        }
        let (model, thinking) = {
            let st = old.state.lock().expect("state poisoned");
            (st.model.clone(), st.thinking.clone())
        };
        let new_live = Arc::new(LiveSession {
            id: new_id.clone(),
            path: path.clone(),
            cwd: summary.cwd.clone(),
            client: old.client.clone(),
            summary: Mutex::new(summary.clone()),
            state: Mutex::new(RunState {
                status: "idle".into(),
                model,
                thinking,
                ..Default::default()
            }),
            writer: Mutex::new(None),
            task: Mutex::new(None),
        });
        self.live
            .lock()
            .expect("live poisoned")
            .insert(new_id.clone(), new_live.clone());
        let me = self.clone();
        let live_for_task = new_live.clone();
        let handle = tokio::spawn(async move {
            run_events(me, live_for_task).await;
        });
        *new_live.task.lock().expect("task poisoned") = Some(handle);
        self.store
            .upsert_session(&summary, &path.display().to_string())?;
        self.emit_raw(
            &new_id,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": summary,
            }),
        )?;
        Ok(summary)
    }

    pub fn resolve_ui(&self, session_id: &str, p: UiResponsePayload) -> Result<()> {
        let tx = self
            .ui_waiters
            .lock()
            .expect("ui poisoned")
            .remove(&p.request_id);
        if let Some(tx) = tx {
            let _ = tx.send(json!({
                "value": p.value,
                "confirmed": p.confirmed,
                "cancelled": p.cancelled,
            }));
        }
        let _ = self.emit_raw(
            session_id,
            json!({
                "type": ev::UI_RESOLVED,
                "requestId": p.request_id,
                "cancelled": p.cancelled,
            }),
        );
        Ok(())
    }

    fn emit_logged(&self, live: &LiveSession, event: Value) {
        if let Err(e) = self.emit_live(live, event) {
            tracing::warn!(error = %e, "写入会话事件失败");
        }
    }

    fn emit_live(&self, live: &LiveSession, event: Value) -> Result<u64> {
        self.emit_raw(&live.id, event)
    }

    pub fn emit_raw(&self, session_id: &str, event: Value) -> Result<u64> {
        let seq = self
            .store
            .append_event(session_id, &rfc3339(self.clock.now()), &event)?;
        let _ = self.events.send(SessionEvent {
            session_id: session_id.to_string(),
            seq,
            event,
        });
        Ok(seq)
    }
}

async fn run_events(mgr: Arc<SessionManager>, live: Arc<LiveSession>) {
    let mut rx = live.client.subscribe();
    let mut exit = live.client.subscribe_exit();
    loop {
        tokio::select! {
            ev = rx.recv() => {
                match ev {
                    Ok(e) => handle_pi_event(&mgr, &live, e).await,
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!(session_id = %live.id, skipped = n, "pi 事件订阅落后");
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
            x = exit.recv() => {
                if let Ok(x) = x {
                    live.set_status("error");
                    *live.writer.lock().expect("writer poisoned") = None;
                    let _ = mgr.emit_live(&live, json!({
                        "type": ev::RUN_STATE,
                        "state": "error",
                        "error": x.reason,
                    }));
                    let _ = mgr.emit_live(&live, json!({
                        "type": ev::SESSION_UPDATED,
                        "session": live.summary.lock().expect("summary poisoned").clone(),
                    }));
                }
                break;
            }
        }
    }
}

async fn handle_pi_event(mgr: &Arc<SessionManager>, live: &Arc<LiveSession>, e: PiEvent) {
    let v = e.value;
    match e.kind.as_str() {
        "agent_start" => {
            let run_id = mgr.ids.next_id();
            let mut st = live.state.lock().expect("state poisoned");
            st.status = "streaming".into();
            st.run_id = Some(run_id.clone());
            st.error = None;
            live.summary.lock().expect("summary poisoned").status = "streaming".into();
            drop(st);
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::RUN_STATE,
                    "runId": run_id,
                    "state": "streaming",
                }),
            );
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::SESSION_UPDATED,
                    "session": live.summary.lock().expect("summary poisoned").clone(),
                }),
            );
        }
        "agent_settled" => {
            let aborted = v.get("aborted").and_then(Value::as_bool).unwrap_or(false);
            let state = "idle";
            {
                let mut st = live.state.lock().expect("state poisoned");
                st.status = state.into();
                st.run_id = None;
                st.message_id = None;
            }
            *live.writer.lock().expect("writer poisoned") = None;
            live.set_status(state);
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::RUN_STATE,
                    "state": state,
                    "aborted": aborted,
                }),
            );
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::SESSION_UPDATED,
                    "session": live.summary.lock().expect("summary poisoned").clone(),
                }),
            );
        }
        "message_start" => {
            let id = v
                .get("message")
                .and_then(|m| m.get("id"))
                .and_then(Value::as_str)
                .map(ToOwned::to_owned)
                .unwrap_or_else(|| mgr.ids.next_id());
            live.state.lock().expect("state poisoned").message_id = Some(id);
        }
        "message_update" => {
            let inner = v
                .get("assistantMessageEvent")
                .cloned()
                .unwrap_or(Value::Null);
            let kind = inner.get("type").and_then(Value::as_str).unwrap_or("");
            let (st, run_id, message_id) = {
                let st = live.state.lock().expect("state poisoned");
                (st.status.clone(), st.run_id.clone(), st.message_id.clone())
            };
            if st != "streaming" {
                return;
            }
            let run_id = run_id.unwrap_or_default();
            let message_id = message_id.unwrap_or_default();
            let idx = inner
                .get("contentIndex")
                .and_then(Value::as_u64)
                .unwrap_or(0) as u32;
            match kind {
                "text_delta" | "thinking_delta" => {
                    let text = inner.get("delta").and_then(Value::as_str).unwrap_or("");
                    if text.is_empty() {
                        return;
                    }
                    let kind = if kind == "text_delta" {
                        "text"
                    } else {
                        "thinking"
                    };
                    let _ = mgr.emit_live(
                        live,
                        ev::message_delta(&run_id, &message_id, kind, idx, text),
                    );
                }
                _ => {}
            }
        }
        "message_end" => {
            let msg = v.get("message").cloned().unwrap_or(Value::Null);
            if msg.get("role").and_then(Value::as_str) == Some("user") {
                return;
            }
            let (run_id, message_id) = {
                let st = live.state.lock().expect("state poisoned");
                (st.run_id.clone(), st.message_id.clone())
            };
            let _ = mgr.emit_live(
                live,
                ev::message_final(
                    &run_id.unwrap_or_default(),
                    &message_id.unwrap_or_default(),
                    msg,
                ),
            );
        }
        "tool_execution_start" => {
            let (run_id, _) = {
                let st = live.state.lock().expect("state poisoned");
                (st.run_id.clone(), st.message_id.clone())
            };
            let tool = v.get("toolCallId").and_then(Value::as_str).unwrap_or("");
            let name = v.get("toolName").and_then(Value::as_str).unwrap_or("");
            let args = v.get("args").cloned().unwrap_or(Value::Null);
            let _ = mgr.emit_live(
                live,
                ev::tool_started(&run_id.unwrap_or_default(), tool, name, args),
            );
        }
        "tool_execution_update" => {
            let (run_id, _) = {
                let st = live.state.lock().expect("state poisoned");
                (st.run_id.clone(), st.message_id.clone())
            };
            let tool = v.get("toolCallId").and_then(Value::as_str).unwrap_or("");
            let partial = v.get("partialResult").cloned().unwrap_or(Value::Null);
            let _ = mgr.emit_live(
                live,
                ev::tool_updated(&run_id.unwrap_or_default(), tool, partial),
            );
        }
        "tool_execution_end" => {
            let (run_id, _) = {
                let st = live.state.lock().expect("state poisoned");
                (st.run_id.clone(), st.message_id.clone())
            };
            let tool = v.get("toolCallId").and_then(Value::as_str).unwrap_or("");
            let result = v.get("result").cloned().unwrap_or(Value::Null);
            let is_error = v.get("isError").and_then(Value::as_bool).unwrap_or(false);
            let dur = v.get("durationMs").and_then(Value::as_u64).unwrap_or(0);
            let _ = mgr.emit_live(
                live,
                ev::tool_ended(&run_id.unwrap_or_default(), tool, result, is_error, dur),
            );
        }
        "queue_update" => {
            let steering = v.get("steering").cloned().unwrap_or(json!([]));
            let follow = v.get("followUp").cloned().unwrap_or(json!([]));
            live.state.lock().expect("state poisoned").queue =
                json!({"steering": steering, "followUp": follow});
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::QUEUE_UPDATED,
                    "steering": steering,
                    "followUp": follow,
                }),
            );
        }
        "compaction_start" => {
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::SESSION_COMPACTED,
                    "state": "started",
                    "reason": v.get("reason"),
                }),
            );
        }
        "compaction_end" => {
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::SESSION_COMPACTED,
                    "state": "ended",
                    "result": v.get("result"),
                    "aborted": v.get("aborted"),
                }),
            );
        }
        "auto_retry_start" => {
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::RUN_STATE,
                    "state": "retrying",
                    "attempt": v.get("attempt"),
                    "error": v.get("errorMessage"),
                }),
            );
        }
        "auto_retry_end" => {
            if v.get("success").and_then(Value::as_bool) == Some(false) {
                let _ = mgr.emit_live(
                    live,
                    json!({
                        "type": ev::RUN_STATE,
                        "state": "error",
                        "error": v.get("finalError"),
                    }),
                );
            }
        }
        "extension_ui_request" => {
            let mgr = mgr.clone();
            let live = live.clone();
            tokio::spawn(async move {
                handle_ui_request(mgr, live, v).await;
            });
        }
        "extension_error" => {
            let _ = mgr.emit_live(
                live,
                json!({
                    "type": ev::ERROR,
                    "message": v.get("error"),
                    "source": "extension",
                }),
            );
        }
        _ => {
            tracing::trace!(session_id = %live.id, kind = %e.kind, "忽略 pi 事件");
        }
    }
}

async fn handle_ui_request(mgr: Arc<SessionManager>, live: Arc<LiveSession>, v: Value) {
    let id = v
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let method = v
        .get("method")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let title = v
        .get("title")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string();
    let message = v.get("message").and_then(Value::as_str).unwrap_or("");
    if method == "confirm" && title.contains("pi-carry") {
        let Some(rest) = message.strip_prefix("PI_CARRY_APPROVAL:") else {
            let _ = live
                .client
                .extension_ui_response(&id, json!({"confirmed": false, "cancelled": true}))
                .await;
            return;
        };
        let parsed: Value = match serde_json::from_str(rest) {
            Ok(v) => v,
            Err(e) => {
                tracing::warn!(error = %e, "审批载荷不是 JSON");
                let _ = live
                    .client
                    .extension_ui_response(&id, json!({"confirmed": false, "cancelled": true}))
                    .await;
                return;
            }
        };
        let tool_name = parsed.get("toolName").and_then(Value::as_str).unwrap_or("");
        let tool_call_id = parsed
            .get("toolCallId")
            .and_then(Value::as_str)
            .unwrap_or("");
        let input = parsed.get("input").cloned().unwrap_or(Value::Null);
        let decision = mgr
            .approvals
            .request(&live.id, tool_call_id, tool_name, &input)
            .await;
        let ok = matches!(decision, Ok(Decision::Allow { .. }));
        let _ = live
            .client
            .extension_ui_response(&id, json!({"confirmed": ok}))
            .await;
        return;
    }

    // 普通扩展 UI：转给手机，超时取消。
    let (tx, rx) = oneshot::channel();
    mgr.ui_waiters
        .lock()
        .expect("ui poisoned")
        .insert(id.clone(), tx);
    let options = v.get("options").cloned().unwrap_or(json!([]));
    let _ = mgr.emit_live(
        &live,
        json!({
            "type": ev::UI_REQUESTED,
            "requestId": id,
            "method": method,
            "title": title,
            "message": message,
            "options": options,
            "placeholder": v.get("placeholder"),
            "prefill": v.get("prefill"),
        }),
    );
    let resp = tokio::time::timeout(Duration::from_secs(120), rx).await;
    let body = match resp {
        Ok(Ok(v)) => {
            let mut b = v;
            b.as_object_mut().map(|o| o.remove("cancelled"));
            b
        }
        _ => json!({"cancelled": true}),
    };
    mgr.ui_waiters.lock().expect("ui poisoned").remove(&id);
    let _ = live.client.extension_ui_response(&id, body).await;
}

fn image_json(img: &ImagePayload) -> Value {
    json!({"type": "image", "data": img.data, "mimeType": img.mime_type})
}

fn unwrap_state(v: Value) -> Value {
    v.get("state").cloned().unwrap_or(v)
}

fn extract_messages(v: Value) -> Vec<Value> {
    match v {
        Value::Array(a) => a,
        Value::Object(mut o) => o
            .remove("messages")
            .and_then(|x| x.as_array().cloned())
            .unwrap_or_default(),
        _ => Vec::new(),
    }
}

fn read_session_messages(path: &Path) -> Vec<Value> {
    let Ok(content) = std::fs::read_to_string(path) else {
        return Vec::new();
    };
    content
        .lines()
        .filter_map(|l| serde_json::from_str::<Value>(l).ok())
        .filter(|v| v.get("type").and_then(Value::as_str) == Some("message"))
        .filter_map(|v| v.get("message").cloned())
        .collect()
}

fn scan_one(path: &Path) -> Result<Option<SessionSummary>> {
    let content = std::fs::read_to_string(path)?;
    let mut lines = content.lines();
    let Some(first) = lines.next() else {
        return Ok(None);
    };
    let header: Value = match serde_json::from_str(first) {
        Ok(v) => v,
        Err(_) => return Ok(None),
    };
    if header.get("type").and_then(Value::as_str) != Some("session") {
        return Ok(None);
    }
    let id = header
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    if id.is_empty() {
        return Ok(None);
    }
    let cwd = header
        .get("cwd")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let parent_id = header
        .get("parentSession")
        .and_then(Value::as_str)
        .map(ToOwned::to_owned);
    let mut name = String::new();
    let mut message_count = 0u64;
    let mut preview = String::new();
    for line in lines {
        let Ok(v) = serde_json::from_str::<Value>(line) else {
            continue;
        };
        match v.get("type").and_then(Value::as_str) {
            Some("session_info") => {
                if let Some(n) = v.get("name").and_then(Value::as_str) {
                    name = n.to_string();
                }
            }
            Some("message") => {
                let msg = v.get("message").unwrap_or(&v);
                let role = msg.get("role").and_then(Value::as_str).unwrap_or("");
                if role == "user" || role == "assistant" {
                    message_count += 1;
                    if role == "user" {
                        let text = message_text(msg);
                        if !text.is_empty() {
                            preview = truncate(&text, 160);
                        }
                    }
                }
            }
            _ => {}
        }
    }
    let updated_at = std::fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .map(|t| {
            let dt: chrono::DateTime<chrono::Utc> = t.into();
            rfc3339(dt)
        })
        .unwrap_or_default();
    Ok(Some(SessionSummary {
        id,
        cwd,
        name,
        preview,
        updated_at,
        message_count,
        status: "idle".into(),
        active: false,
        pending_approvals: 0,
        last_source: String::new(),
        writable: true,
        parent_id,
    }))
}

fn message_text(msg: &Value) -> String {
    if let Some(s) = msg.get("content").and_then(Value::as_str) {
        return s.to_string();
    }
    let Some(arr) = msg.get("content").and_then(Value::as_array) else {
        return String::new();
    };
    let mut out = String::new();
    for c in arr {
        if c.get("type").and_then(Value::as_str) == Some("text") {
            if let Some(t) = c.get("text").and_then(Value::as_str) {
                if !out.is_empty() {
                    out.push('\n');
                }
                out.push_str(t);
            }
        }
    }
    out
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect::<String>() + "…"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Config, FixedClock, SeqIdGen};
    use chrono::TimeZone;

    fn write_session(dir: &Path, id: &str, cwd: &str) -> PathBuf {
        let sub = dir.join("--proj--");
        std::fs::create_dir_all(&sub).unwrap();
        let p = sub.join(format!("2026-11-19T08-00-00_{id}.jsonl"));
        let lines = [
            json!({"type":"session","version":3,"id":id,"timestamp":"2026-11-19T08:00:00.000Z","cwd":cwd}),
            json!({"type":"session_info","name":"桥接测试"}),
            json!({"type":"message","id":"m1","parentId":null,"timestamp":"2026-11-19T08:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"你好，carry"}]}}),
            json!({"type":"message","id":"m2","parentId":"m1","timestamp":"2026-11-19T08:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":"收到"}]}}),
        ];
        std::fs::write(
            &p,
            lines
                .iter()
                .map(|v| v.to_string())
                .collect::<Vec<_>>()
                .join("\n"),
        )
        .unwrap();
        p
    }

    #[test]
    fn scan_extracts_header_name_and_preview() {
        let tmp = tempfile::tempdir().unwrap();
        write_session(tmp.path(), "sess-1", "C:/work");
        let s = scan_one(
            &tmp.path().join("--proj--").join(
                std::fs::read_dir(tmp.path().join("--proj--"))
                    .unwrap()
                    .next()
                    .unwrap()
                    .unwrap()
                    .file_name(),
            ),
        )
        .unwrap()
        .unwrap();
        assert_eq!(s.id, "sess-1");
        assert_eq!(s.cwd, "C:/work");
        assert_eq!(s.name, "桥接测试");
        assert_eq!(s.message_count, 2);
        assert!(s.preview.contains("carry"));
    }

    #[allow(dead_code)]
    fn dummy_manager() -> Arc<SessionManager> {
        let store = Store::open_memory().unwrap();
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());
        let (tx, _) = broadcast::channel(8);
        let approvals = ApprovalManager::new(
            store.clone(),
            Config::default().approvals,
            Arc::new(clock.clone()),
            Arc::new(SeqIdGen::new()),
            tx,
        );
        SessionManager::new(
            Config::default(),
            Paths::at(std::env::temp_dir()),
            store,
            Arc::new(clock),
            Arc::new(SeqIdGen::new()),
            approvals,
            PathBuf::from("pi"),
            PathBuf::from("guard.ts"),
        )
    }

    use chrono::Utc;
}
