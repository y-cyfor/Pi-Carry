//! 会话索引、pi 子进程生命周期、事件归一化与 journal。

use crate::approval::{ApprovalManager, Decision};
use crate::blob::BlobStore;
use crate::clock::{rfc3339, Clock, IdGen};
use crate::config::Config;
use crate::error::{CoreError, Result};
use crate::paths::Paths;
use crate::pi::{PiClient, PiEvent, PiExit};
use crate::store::Store;
use base64::Engine;
use pi_carry_protocol::events as ev;
use pi_carry_protocol::{
    ErrorCode, ImagePayload, PromptPayload, ProtocolError, SessionSnapshot, SessionSummary,
    UiResponsePayload, MAX_IMAGE_BYTES, MAX_JSONL_LINE,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{broadcast, oneshot};

/// 会话文件只解析末尾这么多字节：大文件不整读，避免 list() 把整个历史读进内存。
const JSONL_TAIL_BYTES: u64 = 1024 * 1024;
/// 读取 header 时的字节上限（header 永远是单行短 JSON）。
const JSONL_HEADER_BYTES: u64 = 64 * 1024;
/// 单次快照最多回放的消息条数：超长会话保留最新的一段，
/// 免得整份历史塞进一个控制响应帧。
const MAX_SNAPSHOT_MESSAGES: usize = 4000;
/// 单次快照的序列化预算。控制响应帧上限是 1 MiB（`MAX_CONTROL_FRAME`），
/// 留约 128 KiB 给 session/审批/信封与 JSON 转义；超过就丢最旧的消息或
/// 截断最后一条，绝不能把帧撑爆后断连（B-04）。
const MAX_SNAPSHOT_BYTES: usize = 900 * 1024;
/// 快照里允许内联的 base64 图片大小。超过就降级成占位，否则一张原图
/// 就能把整帧撑爆；0.2 客户端应走 blobId，老客户端只能看到占位（B-12）。
const MAX_INLINE_IMAGE_BYTES: usize = 512 * 1024;
/// spawn_locks 条目上限；超过时清理已无人持有的锁。
const SPAWN_LOCK_LIMIT: usize = 256;

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
    /// 本轮运行的消耗统计。与 `state.run_id` 同生命周期：
    /// agent_start 新建、agent_settled 消费并清空。
    run_stats: Mutex<Option<RunStats>>,
    writer: Mutex<Option<String>>,
    task: Mutex<Option<tokio::task::JoinHandle<()>>>,
}

/// 一轮运行（`agent_start` → `agent_settled`）的累计量。
///
/// 半路 attach 时拿不到 `agent_start`，`started_at`/`first_token_at` 为 `None`，
/// 对外报告 `durationMs: null`，绝不伪造一个看起来合理的时长。
#[derive(Debug, Clone, Default)]
struct RunStats {
    run_id: String,
    started_at: Option<chrono::DateTime<chrono::Utc>>,
    first_token_at: Option<chrono::DateTime<chrono::Utc>>,
    /// 本轮 assistant 消息数（每轮每次模型调用一条）。
    requests: u64,
    input_tokens: u64,
    output_tokens: u64,
    cache_read_tokens: u64,
    cache_write_tokens: u64,
    total_tokens: u64,
    cost: f64,
    /// 是否拿到了过至少一条 usage；没有就不报 totalTokens，避免伪造 0。
    has_usage: bool,
    model: String,
}

impl RunStats {
    fn new(run_id: String, model: &Value, now: chrono::DateTime<chrono::Utc>) -> Self {
        Self {
            run_id,
            started_at: Some(now),
            model: model_label(model),
            ..Default::default()
        }
    }

    /// 半路 attach（pi 已在流式中）补的统计：本轮没有 `agent_start`，
    /// 拿不到起始时刻。保留 `run_id`/`model`，这样 `agent_settled` 仍会发出
    /// `run.summary`（客户端不能因为缺了这一条就以为本轮没有统计），
    /// 而 `durationMs`/`firstTokenMs` 报 null。
    fn attached(run_id: String, model: &Value) -> Self {
        Self {
            run_id,
            model: model_label(model),
            ..Default::default()
        }
    }

    /// 累加一条 assistant 消息的 usage。字段缺失就当 0，不报错：
    /// 不同 pi 版本/供应商的 usage 字段差异很大，缺字段不该打断统计。
    fn add_usage(&mut self, message: &Value) {
        let Some(usage) = message.get("usage") else {
            self.requests += 1;
            return;
        };
        if !usage.is_object() {
            self.requests += 1;
            return;
        }
        let num = |k: &str| usage.get(k).and_then(Value::as_u64).unwrap_or(0);
        self.requests += 1;
        self.has_usage = true;
        self.input_tokens += num("input");
        self.output_tokens += num("output");
        self.cache_read_tokens += num("cacheRead");
        self.cache_write_tokens += num("cacheWrite");
        self.total_tokens += num("totalTokens");
        // cost 可能是数字，也可能是 {total: n}；两种 pi 版本都见过。
        let cost = match usage.get("cost") {
            Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
            Some(v) => v.get("total").and_then(Value::as_f64).unwrap_or(0.0),
            None => 0.0,
        };
        self.cost += cost;
        if self.model.is_empty() {
            if let Some(m) = message.get("model") {
                self.model = model_label(m);
            }
        }
    }

    /// 生成 `run.summary` 事件体。`aborted` 由调用方传入（pi 的 agent_settled 字段）。
    fn to_event(&self, ended: chrono::DateTime<chrono::Utc>, aborted: bool) -> Value {
        let duration_ms = self
            .started_at
            .map(|s| (ended - s).num_milliseconds().max(0) as u64);
        let first_token_ms = match (self.started_at, self.first_token_at) {
            (Some(s), Some(f)) => Some((f - s).num_milliseconds().max(0) as u64),
            _ => None,
        };
        let mut fields = json!({
            "startedAt": self.started_at.map(rfc3339),
            "endedAt": rfc3339(ended),
            "durationMs": duration_ms,
            "requests": self.requests,
            "inputTokens": self.input_tokens,
            "outputTokens": self.output_tokens,
            "cacheReadTokens": self.cache_read_tokens,
            "cacheWriteTokens": self.cache_write_tokens,
            "firstTokenMs": first_token_ms,
            "aborted": aborted,
        });
        if let Some(obj) = fields.as_object_mut() {
            // 拿不到 usage 时字段留 null（而不是 0）：UI 显示「—」，
            // 不能被误读成「这一轮真的没花 token」。
            if self.has_usage {
                obj.insert("totalTokens".into(), json!(self.total_tokens));
                obj.insert("cost".into(), json!(self.cost));
            } else {
                obj.insert("totalTokens".into(), Value::Null);
                obj.insert("cost".into(), Value::Null);
            }
            if !self.model.is_empty() {
                obj.insert("model".into(), json!(self.model));
            }
        }
        ev::run_summary(&self.run_id, fields)
    }
}

/// `model` 可能是 `"anthropic/claude-sonnet-4-5"` 字符串，也可能是 `{provider,id}` 对象。
fn model_label(m: &Value) -> String {
    if let Some(s) = m.as_str() {
        return s.to_string();
    }
    let provider = m.get("provider").and_then(Value::as_str).unwrap_or("");
    let id = m.get("id").and_then(Value::as_str).unwrap_or("");
    match (provider.is_empty(), id.is_empty()) {
        (false, false) => format!("{provider}/{id}"),
        (true, false) => id.to_string(),
        (false, true) => provider.to_string(),
        (true, true) => String::new(),
    }
}

impl LiveSession {
    fn status(&self) -> String {
        self.state.lock().expect("state poisoned").status.clone()
    }

    fn set_status(&self, status: &str) {
        self.state.lock().expect("state poisoned").status = status.to_string();
        let mut s = self.summary.lock().expect("summary poisoned");
        s.status = status.to_string();
        // error 表示 pi 已退出，会话不再存活；idle/streaming 仍算活跃。
        s.active = status != "error";
        s.updated_at = rfc3339(chrono::Utc::now());
    }
}

/// 扩展 UI 请求的待回复通道。会话归属由 map 的键保证，不重复存。
struct UiWaiterSlot {
    tx: oneshot::Sender<Value>,
}

pub struct SessionManager {
    config: Config,
    store: Store,
    clock: Arc<dyn Clock>,
    ids: Arc<dyn IdGen>,
    approvals: ApprovalManager,
    pi_path: PathBuf,
    guard: PathBuf,
    /// 图片 blob 库；与会话同生命周期，fork 时复制引用、close 时清理孤儿。
    blobs: BlobStore,
    /// 正在 fork 迁移的旧会话：窗口内拒绝新 prompt，避免写进即将被替换的运行时。
    migrating: Mutex<HashSet<String>>,
    live: Mutex<HashMap<String, Arc<LiveSession>>>,
    /// 每个会话 id 一把 attach 互斥锁，防止并发 open 对同一会话双开 pi 进程。
    spawn_locks: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
    events: broadcast::Sender<SessionEvent>,
    /// 序列化「分配 seq + 落库 + 广播」，保证广播顺序与 journal seq 一致。
    emit_lock: Mutex<()>,
    /// 扩展 UI 请求：requestId -> (所属会话, 一次性回复通道)。
    ///
    /// 以 `(session_id, requestId)` 为键：pi 的 requestId 只在其自身进程内唯一，
    /// 两个会话同时弹框时会出现同 id，单键 map 会互相覆盖并报错到错误会话上。
    ui_waiters: Mutex<HashMap<(String, String), UiWaiterSlot>>,
}

impl SessionManager {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        config: Config,
        paths: Paths,
        store: Store,
        clock: Arc<dyn Clock>,
        ids: Arc<dyn IdGen>,
        approvals: ApprovalManager,
        pi_path: PathBuf,
        guard: PathBuf,
    ) -> Arc<Self> {
        // 容量与 protocol::EVENT_QUEUE_CAPACITY 对齐：客户端落后超过这个数会收到
        // SNAPSHOT_REQUIRED 并重拉快照，所以它需要覆盖一个快照周期的峰值事件量。
        let (events, _) = broadcast::channel(pi_carry_protocol::EVENT_QUEUE_CAPACITY);
        let blobs = BlobStore::new(paths.blobs(), store.clone());
        Arc::new(Self {
            config,
            store,
            clock,
            ids,
            approvals,
            pi_path,
            guard,
            blobs,
            migrating: Mutex::new(HashSet::new()),
            live: Mutex::new(HashMap::new()),
            spawn_locks: Mutex::new(HashMap::new()),
            events,
            emit_lock: Mutex::new(()),
            ui_waiters: Mutex::new(HashMap::new()),
        })
    }

    pub fn subscribe(&self) -> broadcast::Receiver<SessionEvent> {
        self.events.subscribe()
    }

    pub fn approvals(&self) -> &ApprovalManager {
        &self.approvals
    }

    /// 图片 blob 库（server 的 blob.* 操作与快照投影共用）。
    pub fn blobs(&self) -> &BlobStore {
        &self.blobs
    }

    pub fn sessions_dir(&self) -> PathBuf {
        PathBuf::from(&self.config.sessions.dir)
    }

    /// 扫描磁盘会话，合并内存中的实时状态。
    pub fn list(&self) -> Result<Vec<SessionSummary>> {
        let mut out = self.scan_disk()?;
        // 先把内存态拷出来再释放 live 锁：锁内既不能做 IO 也不能发 DB 查询，
        // 否则 open/prompt 路径会被慢查询（或下一个调用者的锁）拖住。
        let live_snapshots: HashMap<String, SessionSummary> = {
            let live = self.live.lock().expect("live poisoned");
            live.iter()
                .map(|(id, l)| {
                    let mut mem = l.summary.lock().expect("summary poisoned").clone();
                    mem.status = l.status();
                    // pi 退出（error）的会话不再算活跃；以前这里无条件置 true，
                    // 列表里会出现「已退出但仍 active」的僵尸行。
                    mem.active = mem.status != "error";
                    mem.last_source = "mobile".into();
                    (id.clone(), mem)
                })
                .collect()
        };
        // 一次查全部待审批计数，避免每会话一条 SQL（N+1）。
        let pending = self.store.pending_approval_counts()?;
        for s in &mut out {
            if let Some(mem) = live_snapshots.get(&s.id) {
                s.clone_from(mem);
            }
            s.pending_approvals = pending.get(&s.id).copied().unwrap_or(0) as u32;
        }
        out.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
        out.truncate(self.config.sessions.scan_limit);
        Ok(out)
    }

    pub fn summary(&self, id: &str) -> Result<SessionSummary> {
        if let Some(l) = self.live.lock().expect("live poisoned").get(id) {
            let mut s = l.summary.lock().expect("summary poisoned").clone();
            s.status = l.status();
            s.active = s.status != "error";
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

    fn spawn_lock(&self, id: &str) -> Arc<tokio::sync::Mutex<()>> {
        let mut locks = self.spawn_locks.lock().expect("spawn locks poisoned");
        if let Some(l) = locks.get(id) {
            return l.clone();
        }
        if locks.len() > SPAWN_LOCK_LIMIT {
            // 只保留仍有人持有的锁，避免每个历史会话 id 都留一个永久条目。
            locks.retain(|_, l| Arc::strong_count(l) > 1);
        }
        let l = Arc::new(tokio::sync::Mutex::new(()));
        locks.insert(id.to_string(), l.clone());
        l
    }

    fn release_spawn_lock(&self, id: &str) {
        let mut locks = self.spawn_locks.lock().expect("spawn locks poisoned");
        // 只在没有其他持有者时移除：若还有人拿着这把锁（正在 attach 或排队），
        // 换一把新锁会让同一 id 双开 pi 进程。
        if locks
            .get(id)
            .map(|l| Arc::strong_count(l) == 1)
            .unwrap_or(false)
        {
            locks.remove(id);
        }
    }

    pub async fn open(self: &Arc<Self>, id: &str) -> Result<SessionSummary> {
        // 已迁移的旧会话不得再拉起 runtime：两份 pi 进程写同一 jsonl 会静默
        // 分叉出与原 fork 不一致的历史（B-14）。快照走 snapshot，不受此限。
        if let Some(to) = self.store.migrated_to(id)? {
            return Err(CoreError::SessionConflict(format!(
                "会话已迁移到 fork 会话 {to}，旧会话只读"
            )));
        }
        if let Some(l) = self.live.lock().expect("live poisoned").get(id).cloned() {
            if !l.client.is_closed() {
                return Ok(l.summary.lock().expect("summary poisoned").clone());
            }
            self.live.lock().expect("live poisoned").remove(id);
        }
        // 同一 id 的 attach 串行化：等锁期间若别人已经开成功，直接复用。
        // guard 随函数返回（成功或失败）释放，不会泄漏锁；tokio Mutex 没有 poisoning。
        let lock = self.spawn_lock(id);
        let _guard = lock.lock().await;
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
    /// 校验并归一化会话工作目录：必须是已存在目录，且落在允许的根目录内。
    /// 会话工作目录是否被显式允许加载项目级可执行资源（`.pi/extensions`、`.agents/skills`）。
    ///
    /// 只认 `sessions.trustedRoots` 显式列出的目录：默认空清单 = 一律不信任。
    /// 这样即使用户把主目录加进 `allowedRoots`（很常见），也不会自动信任里面每一个 git clone。
    fn project_trusted(&self, path: &Path) -> bool {
        let roots = &self.config.sessions.trusted_roots;
        if roots.is_empty() {
            return false;
        }
        let Some(cwd) = session_cwd(path) else {
            return false;
        };
        let Ok(canon) = std::fs::canonicalize(&cwd) else {
            return false;
        };
        roots.iter().any(|root| {
            std::fs::canonicalize(root)
                .map(|r| canon.starts_with(r))
                .unwrap_or(false)
        })
    }

    fn resolve_cwd(&self, cwd: &str) -> Result<String> {
        // 根目录判定与目录选择器共用 [`crate::fsbrowse::ensure_allowed`]，
        // 两处各写一遍迟早会漂移（一边放行、一边拒绝）。它还会把路径绝对化、
        // 解析 `.`/`..` 与符号链接，所以必须采用它返回的规范路径：
        // 只校验不采用会把同一目录的不同写法存成不同 cwd，
        // 分组会出现两个同名组，`fs.list` 的 `hasSessions` 也对不上。
        let canon = crate::fsbrowse::ensure_allowed(&self.config, cwd)?;
        // canonicalize 在 Windows 上会带 `\\?\` 前缀，回给客户端前剥掉。
        Ok(crate::fsbrowse::strip_verbatim(&canon.to_string_lossy()))
    }

    pub async fn create(self: &Arc<Self>, cwd: &str) -> Result<SessionSummary> {
        let cwd = self.resolve_cwd(cwd)?;
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
        // 项目级资源信任：只有工作目录落在显式允许清单内时才传 `--approve`。
        // pi 的 `--approve` 会信任项目里的 `.pi/extensions`（=任意代码执行），
        // 一旦手机端能指定任意 cwd（主目录内），就等于把“用户会在本机手动审批”这一步抹掉了。
        let trust_project = self.project_trusted(path);
        let client = PiClient::spawn(
            &self.pi_path,
            path,
            Some(&self.guard),
            &self.config.node.pi_args,
            trust_project,
        )
        .await?;
        let state = match client.request("get_state", Value::Null).await {
            Ok(v) => unwrap_state(v),
            Err(e) => {
                client.shutdown().await;
                return Err(e);
            }
        };
        // pi 以 `--approve` 启动：若审批扩展没被真正加载，所有工具调用会无审批
        // 直通（安全闸门静默消失）。`get_commands` 里看不到 guard 注册的自检命令
        // 就等于闸门不存在，必须在建会话前就拒绝。
        if let Err(e) = verify_guard_loaded(&client).await {
            client.shutdown().await;
            return Err(e);
        }
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
        let streaming = state_streaming(&state);
        let model = state.get("model").cloned().unwrap_or(Value::Null);
        let status = if streaming { "streaming" } else { "idle" };
        let mut summary = match scan_one(path) {
            Ok(Some(s)) => s,
            Ok(None) => SessionSummary {
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
                migrated_to: None,
            },
            Err(e) => {
                // 子进程已启动，错误路径上必须先关掉，否则每次开放失败都泄漏一个 pi。
                client.shutdown().await;
                return Err(e);
            }
        };
        summary.id = session_id.clone();
        summary.active = true;
        summary.writable = true;
        // 摘要里的状态同样跟随 pi 的真实状态，不能恒定成 idle。
        summary.status = status.into();
        if summary.name.is_empty() {
            summary.name = name;
        }
        // 若 attach 时 pi 正在流式中：事件流不会回放 `agent_start`，若状态硬编码
        // 成 idle，① `message_update` 会因 `st != "streaming"` 整轮丢弃；
        // ② `agent_settled` 时没有 run_stats，本轮 `run.summary` 静默缺失；
        // ③ prompt 的 auto 模式会把本该 steer 的消息发成普通 prompt。
        // 因此按 `isStreaming` 置状态，并补一条没有起始时刻的 RunStats：
        // 本轮结束仍会发 `run.summary`，但 `durationMs`/`firstTokenMs` 为 null。
        let run_id = streaming.then(|| self.ids.next_id());
        let live = Arc::new(LiveSession {
            id: session_id.clone(),
            path: path.to_path_buf(),
            cwd: cwd.clone(),
            client: client.clone(),
            summary: Mutex::new(summary.clone()),
            state: Mutex::new(RunState {
                status: status.into(),
                run_id: run_id.clone(),
                model: model.clone(),
                thinking: state
                    .get("thinkingLevel")
                    .and_then(Value::as_str)
                    .unwrap_or("medium")
                    .into(),
                ..Default::default()
            }),
            run_stats: Mutex::new(run_id.map(|id| RunStats::attached(id, &model))),
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
        // 插入 live 之后的任何失败都必须回滚：否则会话卡在 live map 里且 pi 子进程泄漏。
        let registered = self
            .store
            .upsert_session(&summary, &path.display().to_string())
            .and_then(|_| {
                self.emit_raw(
                    &summary.id,
                    json!({
                        "type": ev::SESSION_UPDATED,
                        "session": summary,
                    }),
                )
            });
        if let Err(e) = registered {
            self.live.lock().expect("live poisoned").remove(&session_id);
            if let Some(h) = live.task.lock().expect("task poisoned").take() {
                h.abort();
            }
            client.shutdown().await;
            return Err(e);
        }
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
        self.release_spawn_lock(id);
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
        self.spawn_locks
            .lock()
            .expect("spawn locks poisoned")
            .clear();
    }

    fn resolve_path(&self, id: &str) -> Result<PathBuf> {
        // id 完全是客户端可控输入：只接受单个普通文件名标识，
        // 任何路径分隔符、`..`、盘符或 `.jsonl` 后缀都被拒绝，
        // 防止把 id 当路径打开会话目录之外的文件。
        if !is_plain_session_id(id) {
            return Err(CoreError::SessionNotFound(id.into()));
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
                            if s.id == id && is_within_dir(&dir, &p) {
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
        let mut snap = self.snapshot_inner(id, false, true)?;
        snap.messages = bound_snapshot_messages(snap.messages);
        Ok(snap)
    }

    /// [cold] 为 false 时跳过整份会话文件的冷读：调用方马上会用 pi 的
    /// get_entries 覆盖消息，先读一遍只是白白把大文件解析两次（N-02）。
    /// [project_images] 只保留参数占位，投影由调用方在热路径之后统一做。
    fn snapshot_inner(
        &self,
        id: &str,
        _project_images: bool,
        cold: bool,
    ) -> Result<SessionSnapshot> {
        let summary = self.summary(id)?;
        let last_seq = self.store.last_seq(id)?;
        let messages = if cold {
            self.resolve_path(id)
                .ok()
                .map(|p| read_session_messages(&p))
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        let snap = SessionSnapshot {
            session: summary,
            last_seq,
            messages,
            // 走审批管理器而不是直接读库：后者把 remainingSeconds 固定成 0，
            // 客户端会显示“剩 0 秒”但审批其实还在等待（N-01）。
            pending_approvals: self.approvals.pending(Some(id))?,
            run: None,
            queue: json!({"steering": [], "followUp": []}),
        };
        Ok(snap)
    }

    /// 带 get_messages 的完整快照（服务端使用）。
    pub async fn snapshot_async(&self, id: &str) -> Result<SessionSnapshot> {
        self.snapshot_async_with_blobs(id, false).await
    }

    /// 客户端声明支持 blob 图片时，把命中本机 blob 的历史图投影成 blobId。
    pub async fn snapshot_async_with_blobs(
        &self,
        id: &str,
        project_images: bool,
    ) -> Result<SessionSnapshot> {
        // 先看有没有 live：有的话不需要读整份 jsonl，get_entries 会覆盖消息；
        // 冷路径也要等热路径覆盖后再统一投影一次（B-22/N-02）。
        let live = {
            let map = self.live.lock().expect("live poisoned");
            map.get(id).cloned()
        };
        let mut snap = self.snapshot_inner(id, false, live.is_none())?;
        if let Some(live) = live {
            // 必须用 get_entries 而不是 get_messages：只有 entry 带行级 id，
            // 与冷路径 read_session_messages 的 id 同源，客户端才对齐得了。
            if let Ok(v) = live.client.request("get_entries", Value::Null).await {
                let entries = v
                    .get("entries")
                    .and_then(Value::as_array)
                    .cloned()
                    .or_else(|| v.as_array().cloned())
                    .unwrap_or_default();
                let msgs: Vec<Value> = entries
                    .iter()
                    .filter(|e| e.get("type").and_then(Value::as_str) == Some("message"))
                    .filter_map(|e| entry_to_message(e))
                    .collect();
                // 空会话合法；只有拿到有效条目才覆盖冷路径结果。
                snap.messages = msgs;
            } else {
                // get_entries 失败时仍给出可读历史，而不是空白会话。
                snap.messages = read_session_messages(&live.path);
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
        if project_images {
            // 先收编/投影再裁剪：反过来的话，>512 KiB 的历史图会先被降级成
            // unavailable，blob 客户端永远看不到本可收编的原图（B-12/I4）。
            snap.messages = self.project_images(id, snap.messages);
        }
        snap.messages = bound_snapshot_messages(snap.messages);
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
        // 图片数量/大小必须在进入 payload 前挡：超限帧会被 read_frame 拒掉，
        // 但 1MiB 内的「5 张巨图」仍会作成一个超大 prompt 推给 pi。
        if self.is_migrating(&p.session_id) {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::SessionBusy,
                "会话正在迁移，请重试",
            )));
        }
        // fork 后旧会话必须保持只读：再冷开一个 pi 进程写同一份 jsonl，
        // 会和 fork 出来的新会话静默分叉（B-14）。
        if let Some(to) = self.store.migrated_to(&p.session_id)? {
            return Err(CoreError::SessionConflict(format!(
                "会话已迁移到 fork 会话 {to}，旧会话只读"
            )));
        }
        validate_images(&p.images)?;
        // blob 引用在这里变成 base64：pi 的 RPC 只认内联图片，blob 只在
        // carry 的传输层存在，不会写进 pi 的 JSONL。
        let images = self.resolve_prompt_images(&p.session_id, &p.images)?;
        let live = self.ensure_live(&p.session_id).await?;
        // 只用来决定 auto 模式发 steer 还是 prompt；writer 的释放判据不能复用
        // 这个快照（见 `should_release_writer`）。
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
        if !images.is_empty() {
            payload["images"] = Value::Array(images.iter().map(image_json).collect());
        }
        // 先校验模式再占用 writer：非法模式不能把别的写者踢掉。
        match mode {
            "steer" => payload["streamingBehavior"] = json!("steer"),
            "queue" | "followUp" | "follow_up" => payload["streamingBehavior"] = json!("followUp"),
            "prompt" | "auto" => {}
            other => {
                return Err(CoreError::InvalidArgument(format!(
                    "未知发送模式 {other}（auto/steer/queue/followUp）"
                )));
            }
        }
        let acquired = {
            let mut w = live.writer.lock().expect("writer poisoned");
            if let Some(owner) = w.as_ref() {
                if owner != device_id {
                    return Err(CoreError::Protocol(ProtocolError::new(
                        ErrorCode::SessionBusy,
                        format!("会话正被设备 {owner} 使用"),
                    )));
                }
            }
            let acquired = w.is_none();
            *w = Some(device_id.to_string());
            acquired
        };
        // 运行状态只由 pi 事件（agent_start/agent_settled）驱动；
        // 在这里乐观置 streaming 会让排队/被拒的 prompt 永久停在 streaming。
        // 不等待 pi 的 prompt 响应：该响应在需要审批的 run 中要等审批结束，
        // 会卡住控制循环。但响应必须被消费：pi 直接拒绝（不开 run）时不会再有
        // agent_start/agent_settled，若不处理 writer 会永久锁给该设备。
        let mut request = payload;
        request["type"] = json!("prompt");
        let request_id = format!("prompt-{}", self.ids.next_id());
        request["id"] = json!(request_id);
        let response = live.client.track_response(&request_id);
        if let Err(e) = live.client.send_raw(&request).await {
            live.client.forget_pending(&request_id);
            self.release_writer(&live, acquired, device_id);
            return Err(e);
        }
        if let Some(rx) = response {
            let mgr = self.clone();
            let live_for_task = live.clone();
            let device = device_id.to_string();
            tokio::spawn(async move {
                // 响应可能与审批一样长时间不回；超时就放弃等待，不泄漏任务。
                let resp = match tokio::time::timeout(Duration::from_secs(120), rx).await {
                    Ok(resp) => resp,
                    Err(_) => {
                        live_for_task.client.forget_pending(&request_id);
                        tracing::warn!(session_id = %live_for_task.id, "prompt 响应超时");
                        // 超时确实可能意味着「pi 没有开 run」（不释放就会永久 SESSION_BUSY），
                        // 也可能意味着响应被审批卡住了，而这一轮其实已经在跑：
                        // 所以按「此刻」的状态判断，不能看发送前的快照。
                        if Self::should_release_writer(acquired, &live_for_task.status()) {
                            mgr.release_writer(&live_for_task, true, &device);
                        }
                        return;
                    }
                };
                let Ok(Err(e)) = resp else { return };
                // pi 没开 run（拒绝/排队失败）时才释放本次请求占下的 writer；
                // 同理按释放时的状态判断，run 已开始时 writer 归这一轮所有。
                if Self::should_release_writer(acquired, &live_for_task.status()) {
                    mgr.release_writer(&live_for_task, true, &device);
                }
                let _ = mgr.emit_live(
                    &live_for_task,
                    json!({
                        "type": ev::ERROR,
                        "message": e.to_string(),
                        "source": "prompt",
                    }),
                );
            });
        }
        // 这里不能再广播一个硬编码的空队列：pi 真实的 `queue_update` 可能还在路上，
        // 先发 `steering: []` 会让 UI 把已有的排队消息闪成空，还往 journal 里
        // 多写一条伪事件。队列状态只由 pi 的 `queue_update` / `clear_queue` 驱动。
        Ok(json!({"accepted": true, "mode": mode}))
    }

    fn is_migrating(&self, id: &str) -> bool {
        self.migrating
            .lock()
            .expect("migrating poisoned")
            .contains(id)
    }

    /// 返回 false 表示已有迁移在飞；调用方必须直接拒绝，不能再排队。
    fn begin_migration(&self, id: &str) -> bool {
        self.migrating
            .lock()
            .expect("migrating poisoned")
            .insert(id.to_string())
    }

    fn end_migration(&self, id: &str) {
        self.migrating
            .lock()
            .expect("migrating poisoned")
            .remove(id);
    }

    /// 把 PromptPayload 里的 blob 引用换成 pi 能接受的内联 base64。
    /// 非 blob 图片原样透传（老客户端兼容）。
    fn resolve_prompt_images(
        &self,
        session_id: &str,
        images: &[ImagePayload],
    ) -> Result<Vec<ImagePayload>> {
        let mut out = Vec::with_capacity(images.len());
        // 以真实字节重新累计：blob 的 `size` 是客户端声明值，不能作为配额依据（B-11）。
        let mut total = 0u64;
        for (i, img) in images.iter().enumerate() {
            let Some(sha) = img.blob_id.as_deref().filter(|s| !s.is_empty()) else {
                out.push(img.clone());
                continue;
            };
            let (bytes, mime) = self.blobs.read_image(session_id, sha).map_err(|e| {
                CoreError::InvalidArgument(format!("第 {} 张图片不可用：{e}", i + 1))
            })?;
            total = total.saturating_add(bytes.len() as u64);
            if total > pi_carry_protocol::MAX_PROMPT_IMAGE_BYTES as u64 {
                return Err(CoreError::InvalidArgument(format!(
                    "本条消息图片实际合计超过上限 {} MiB",
                    pi_carry_protocol::MAX_PROMPT_IMAGE_BYTES / (1024 * 1024)
                )));
            }
            out.push(ImagePayload {
                r#type: "image".into(),
                data: base64::engine::general_purpose::STANDARD.encode(&bytes),
                blob_id: Some(sha.to_string()),
                mime_type: mime,
                width: img.width,
                height: img.height,
                size: bytes.len() as u64,
            });
        }
        Ok(out)
    }

    /// 快照里的历史图如果在本会话的 blob 库里，就把 base64 换成 blobId：
    /// 一张 20 MiB 的图 base64 后约 27 MiB，直接塞快照会超过 4 MiB 事件帧。
    /// 未命中（老会话/已清理）保持原样，客户端仍能按 base64 渲染。
    fn project_images(&self, session_id: &str, messages: Vec<Value>) -> Vec<Value> {
        let live = self.is_live(session_id);
        messages
            .into_iter()
            .map(|mut m| {
                project_images_in(&mut m, self, session_id, live);
                m
            })
            .collect()
    }

    async fn ensure_live(self: &Arc<Self>, id: &str) -> Result<Arc<LiveSession>> {
        // 迁移后的旧会话即使还留着一个未关闭的 live 句柄，也绝不能再拉起/复用：
        // 所有走 ensure_live 的入口都要先看到只读错误（R-07）。
        if let Some(to) = self.store.migrated_to(id)? {
            return Err(CoreError::SessionConflict(format!(
                "会话已迁移到 fork 会话 {to}，旧会话只读"
            )));
        }
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

    /// 会话是否已打开。blob.put 用它拒绝关闭后的上传：否则 cleanup 刚跑完，
    /// 新引用又会被写入，直到下一次关闭才清理（B-29）。
    pub fn is_live(&self, id: &str) -> bool {
        self.live.lock().expect("live poisoned").contains_key(id)
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

    /// 失败路径是否该释放 writer。
    ///
    /// `status` 必须是**释放那一刻**读到的状态，不能用发送 prompt 之前的快照：
    /// prompt 的响应要等本轮结束（要审批的 run 甚至要等审批超时）才回，
    /// 这期间 pi 完全可能已经发出 `agent_start`。此时若按旧快照里的 idle 去释放，
    /// 就等于把正在运行的这一轮的 writer 放掉，另一台设备可以插进来 steer/abort
    /// 同一轮，破坏单写者租约。反过来，pi 确实没开 run 时必须释放，
    /// 否则会话会永久停在 SESSION_BUSY。
    fn should_release_writer(acquired: bool, status: &str) -> bool {
        acquired && status != "streaming"
    }

    /// 只释放仍属于本设备的 writer：失败路径不能把别人的写者一起清掉。
    fn release_writer(&self, live: &LiveSession, owned: bool, device_id: &str) {
        if !owned {
            return;
        }
        let mut w = live.writer.lock().expect("writer poisoned");
        if w.as_deref() == Some(device_id) {
            *w = None;
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
    pub async fn rename(self: &Arc<Self>, id: &str, name: &str) -> Result<SessionSummary> {
        let name = name.trim();
        if name.is_empty() {
            return Err(CoreError::InvalidArgument("会话名不能为空".into()));
        }
        if name.chars().count() > 200 {
            return Err(CoreError::InvalidArgument("会话名过长".into()));
        }
        if let Some(to) = self.store.migrated_to(id)? {
            return Err(CoreError::SessionConflict(format!(
                "会话已迁移到 fork 会话 {to}，旧会话只读"
            )));
        }
        // 冷会话也要能改名：设置只在 pi 进程内生效，ensure_live 会顺手
        // 拉起 runtime；旧实现直接报「尚未打开」，列表里的重命名会失败（B-34）。
        let live = self.ensure_live(id).await?;
        live.client
            .request("set_session_name", json!({ "name": name }))
            .await?;
        let mut summary = live.summary.lock().expect("summary poisoned").clone();
        summary.name = name.to_string();
        summary.updated_at = chrono::Utc::now().to_rfc3339();
        *live.summary.lock().expect("summary poisoned") = summary.clone();
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

    pub async fn get_entries(self: &Arc<Self>, id: &str, since: Option<String>) -> Result<Value> {
        // 分支面板与 openSession 是并发发起的：首次点击时会话可能还没 live。
        // 先 ensure_live，避免用户看到「尚未打开」这种无意义错误（B-35）。
        let live = self.ensure_live(id).await;
        let mut live_err = None;
        if let Ok(live) = &live {
            let res = live
                .client
                .request(
                    "get_entries",
                    since
                        .clone()
                        .map(|s| json!({"since": s}))
                        .unwrap_or(Value::Null),
                )
                .await;
            match res {
                Ok(v) => return Ok(v),
                // 历史图超过 pi 的单行上限时响应会被截断，别让面板干等 120 秒：
                // 先试本地文件兜底，再决定报哪个错（B-01/B-35）。
                Err(e) => live_err = Some(e),
            }
        }
        if let Ok(v) = self.entries_from_file(id, since) {
            return Ok(v);
        }
        match (live_err, live) {
            (Some(e), _) => Err(e),
            (None, Err(e)) => Err(e),
            (None, Ok(_)) => Err(CoreError::Internal("读取会话 entries 失败".into())),
        }
    }

    /// 不经过 pi 的 entries 兜底：只保留 entry id/父子关系与很短的消息预览。
    /// 目的只是让分支面板与 fork 选点可用，不用于渲染聊天历史。
    fn entries_from_file(&self, id: &str, since: Option<String>) -> Result<Value> {
        let path = self.resolve_path(id)?;
        let file = std::fs::File::open(&path)?;
        let mut reader = BufReader::new(file);
        let mut entries: Vec<Value> = Vec::new();
        loop {
            let Some((bytes, truncated)) = read_line_prefix(&mut reader, MAX_JSONL_LINE)? else {
                break;
            };
            let text = String::from_utf8_lossy(&bytes);
            if !looks_like_message_line(&text) {
                continue;
            }
            let parsed = if truncated {
                truncated_entry(&text)
            } else {
                serde_json::from_str::<Value>(&text).ok()
            };
            let Some(entry) = parsed else { continue };
            if entry.get("type").and_then(Value::as_str) != Some("message") {
                continue;
            }
            entries.push(entry_preview(&entry));
        }
        // leafId 始终是文件里最后一条 entry，供客户端知道“当前叶子”；
        // since 只影响返回的增量，不影响 leaf 的定义。
        let leaf = entries
            .last()
            .and_then(|e| e.get("id"))
            .cloned()
            .unwrap_or(Value::Null);
        if let Some(since) = since {
            match entries
                .iter()
                .position(|e| e.get("id").and_then(Value::as_str) == Some(since.as_str()))
            {
                Some(pos) => {
                    entries.drain(..=pos);
                }
                // since 不在当前文件里（文件被替换/截断）：不能退化成返回全量，
                // 否则客户端会把整棵旧树当成新条目。返回空增量 + 真实 leaf（R-05）。
                None => entries.clear(),
            }
        }
        Ok(json!({"entries": entries, "leafId": leaf}))
    }

    pub async fn fork(self: &Arc<Self>, id: &str, entry_id: &str) -> Result<SessionSummary> {
        let live = self.require_live(id)?;
        if live.status() == "streaming" {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::SessionBusy,
                "运行中的会话不能 fork，请先停止当前运行",
            )));
        }
        // 迁移窗口内拒绝并发 fork/prompt：同一会话只允许一次 fork 在飞，
        // 否则两个调用会争用同一个 pi 进程的 live 记录。
        if !self.begin_migration(id) {
            return Err(CoreError::Protocol(ProtocolError::new(
                ErrorCode::SessionBusy,
                "会话正在迁移，请重试",
            )));
        }
        let result = self.fork_migrate(id, entry_id).await;
        self.end_migration(id);
        result
    }

    async fn fork_migrate(self: &Arc<Self>, id: &str, entry_id: &str) -> Result<SessionSummary> {
        let live = self.require_live(id)?;
        let v = live
            .client
            .request("fork", json!({"entryId": entry_id}))
            .await?;
        if v.get("cancelled").and_then(Value::as_bool) == Some(true) {
            return Err(CoreError::InvalidArgument("fork 被 pi 取消".into()));
        }
        // fork RPC 成功后 pi 已经在服务新会话（旧会话 id 在 pi 那边已不存在）：
        // 从这里到 live 迁移完成之间任何一步失败都不能简单用 `?` 上抛。上抛会让
        // self.live 里仍挂着旧 id——之后对旧 id 的任何调用都会打到新会话上，
        // 而且这个 pi 子进程再没有人回收。因此每段失败都先关掉会话
        // （abort 事件任务 + client.shutdown），再原样返回错误类型。
        let (new_id, path, summary) = match read_forked_session(&live).await {
            Ok(v) => v,
            Err(e) => {
                // 迁移前失败：live 还挂在旧 id 下，close 就是关掉这个 pi 进程。
                let _ = self.close(id).await;
                return Err(e);
            }
        };

        // 同一个 pi 进程现在服务新会话：把 live 记录迁到新 id。
        // 先启动新事件任务再中止旧的：反过来会在两者之间丢掉 pi 事件；
        // 重叠窗口内旧任务只会向旧会话写重复事件，不会污染新会话。
        let old = self
            .live
            .lock()
            .expect("live poisoned")
            .remove(id)
            .unwrap_or(live);
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
            run_stats: Mutex::new(None),
            writer: Mutex::new(None),
            task: Mutex::new(None),
        });
        self.live
            .lock()
            .expect("live poisoned")
            .insert(new_id.clone(), new_live.clone());
        // 先订阅再中止旧任务：旧任务 abort 与新任务 subscribe 之间若没有接收者，
        // broadcast 会把这段窗口内的 pi 事件直接丢掉（S-9）。
        let rx = old.client.subscribe();
        let exit = old.client.subscribe_exit();
        let me = self.clone();
        let live_for_task = new_live.clone();
        let handle = tokio::spawn(async move {
            run_events_with(me, live_for_task, rx, exit).await;
        });
        *new_live.task.lock().expect("task poisoned") = Some(handle);
        if let Some(h) = old.task.lock().expect("task poisoned").take() {
            h.abort();
        }
        // fork 会复制到所选 entry，旧会话仍引用同一批图片：先给新会话加引用，
        // 之后关闭旧会话时才不会把历史图删掉。
        if let Err(e) = self.blobs.copy_session_refs(id, &new_id) {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        // 旧会话的待审批项必须取消：它们属于旧 runtime，批准后也无处执行。
        if let Err(e) = self.approvals.cancel_session(id) {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        if let Err(e) = self
            .store
            .upsert_session(&summary, &path.display().to_string())
        {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        // 等等：先把「旧会话只读」落库，再广播。否则客户端已切到新页，
        // 旧会话却仍能开新 runtime 写入，形成静默双写（B-14）。
        if let Err(e) = self.store.mark_session_migrated(id, &new_id) {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        if let Err(e) = self.emit_raw(
            &new_id,
            json!({
                "type": ev::SESSION_UPDATED,
                "session": summary,
            }),
        ) {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        // 在旧会话的流上广播迁移：客户端此刻多半还开着旧会话，
        // 它据此替换路由并保留草稿。广播失败必须回滚，否则没有任何客户端
        // 知道旧 id 已经不可用（B-13/B-18）。
        if let Err(e) = self.emit_raw(id, ev::session_migrated(id, &new_id, "fork")) {
            self.rollback_fork(id, &new_id).await;
            return Err(e);
        }
        Ok(summary)
    }

    /// fork 的任一步失败后收尾：关掉新 runtime、撤掉复制过来的图片引用、
    /// 删除新会话索引，并清掉旧会话的迁移标记（B-13）。
    async fn rollback_fork(&self, old_id: &str, new_id: &str) {
        if let Err(e) = self.store.clear_session_migrated(old_id) {
            tracing::warn!(session_id = old_id, error = %e, "回滚迁移标记失败");
        }
        // fork 出来的 jsonl 已经写到磁盘：回滚时一并删掉，避免留下孤儿会话文件（B-13）。
        let path = self
            .live
            .lock()
            .expect("live poisoned")
            .get(new_id)
            .map(|l| l.path.clone());
        let _ = self.close(new_id).await;
        if let Some(path) = path {
            let _ = std::fs::remove_file(path);
        }
        let _ = self.approvals.cancel_session(new_id);
        // 清理会删文件，不能阻塞异步控制循环（R-09）。
        let blobs = self.blobs.clone();
        let cleanup_id = new_id.to_string();
        match tokio::task::spawn_blocking(move || blobs.cleanup_session(&cleanup_id)).await {
            Ok(Ok(_)) => {}
            Ok(Err(e)) => tracing::warn!(session_id = new_id, error = %e, "回滚 fork blob 失败"),
            Err(e) => tracing::warn!(session_id = new_id, error = %e, "回滚 fork blob 任务失败"),
        }
        if let Err(e) = self.store.delete_session(new_id) {
            tracing::warn!(session_id = new_id, error = %e, "回滚 fork 会话索引失败");
        }
    }

    pub fn resolve_ui(&self, session_id: &str, p: UiResponsePayload) -> Result<()> {
        // 键里已经带上会话 id，跨会话串门在结构上就不可能发生：
        // 任意设备用别的会话 id 只会在自己的键下找不到请求。
        let waiter = {
            let mut waiters = self.ui_waiters.lock().expect("ui poisoned");
            let key = (session_id.to_string(), p.request_id.clone());
            waiters.remove(&key).ok_or_else(|| {
                CoreError::InvalidArgument(format!(
                    "未知的 ui 请求 {}（会话 {session_id}）",
                    p.request_id
                ))
            })?
        };
        let _ = waiter.tx.send(json!({
            "value": p.value,
            "confirmed": p.confirmed,
            "cancelled": p.cancelled,
        }));
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
        // 分配 seq、落库、广播必须在同一把锁内串行：否则并发写者（事件任务、
        // prompt、审批）可能乱序入库，客户端按 seq 补洞时会丢事件。
        let _guard = self.emit_lock.lock().expect("emit lock poisoned");
        let kind = event
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let seq = match self
            .store
            .append_event(session_id, &rfc3339(self.clock.now()), &event)
        {
            Ok(seq) => seq,
            Err(e) => {
                // 落库失败就不广播：广播了但 journal 没有，重连时客户端会收到
                // 跳号的旧 seq，补洞逻辑无法区分「丢事件」和「从未发生」。
                // 调用方大量使用 `let _ =`，必须在唯一入口把失败喊出来。
                tracing::error!(
                    session_id,
                    event_type = %kind,
                    error = %e,
                    "会话事件写入 journal 失败，该事件不会广播也不会被重放"
                );
                return Err(e);
            }
        };
        let _ = self.events.send(SessionEvent {
            session_id: session_id.to_string(),
            seq,
            event,
        });
        Ok(seq)
    }
}

async fn run_events(mgr: Arc<SessionManager>, live: Arc<LiveSession>) {
    let rx = live.client.subscribe();
    let exit = live.client.subscribe_exit();
    run_events_with(mgr, live, rx, exit).await;
}

/// 带上已建立的订阅运行事件任务。
///
/// `fork` 迁移会话 id 时必须先订阅再中止旧任务：不然旧任务 abort 到新任务 subscribe
/// 之间的 pi 事件没有任何接收者，broadcast 会直接丢弃（S-9）。
async fn run_events_with(
    mgr: Arc<SessionManager>,
    live: Arc<LiveSession>,
    mut rx: broadcast::Receiver<PiEvent>,
    mut exit: broadcast::Receiver<PiExit>,
) {
    // attach 与 subscribe 之间 pi 可能已经退出，广播会丢掉这条早退事件；
    // 先回放一次已记录的退出状态，避免会话卡在「active 但无进程」。
    if let Some(x) = live.client.exit_status() {
        on_pi_exit(&mgr, &live, x);
        return;
    }
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
                    on_pi_exit(&mgr, &live, x);
                }
                break;
            }
        }
    }
}

fn on_pi_exit(mgr: &Arc<SessionManager>, live: &Arc<LiveSession>, x: PiExit) {
    live.set_status("error");
    *live.writer.lock().expect("writer poisoned") = None;
    let _ = mgr.emit_live(
        live,
        json!({
            "type": ev::RUN_STATE,
            "state": "error",
            "error": x.reason,
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

/// `auto_retry_end` 应该发哪条 `run.state`。
///
/// 重试失败才是终态 `error`；重试成功只是回到正常生成（pi 会继续把本轮跑完，
/// `agent_settled` 再收尾）。成功时若不发事件，UI 会一直停在 `auto_retry_start`
/// 设的「重试中」，直到本轮结束才跳回 idle。`success` 缺失时保持沉默（协议外）。
fn auto_retry_end_event(v: &Value) -> Option<Value> {
    match v.get("success").and_then(Value::as_bool) {
        Some(false) => Some(json!({
            "type": ev::RUN_STATE,
            "state": "error",
            "error": v.get("finalError"),
        })),
        Some(true) => Some(json!({
            "type": ev::RUN_STATE,
            "state": "streaming",
        })),
        None => None,
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
            let model = st.model.clone();
            live.summary.lock().expect("summary poisoned").status = "streaming".into();
            drop(st);
            // 本轮统计从这里开始计；半路 attach 时本分支不会执行，统计由
            // `RunStats::attached` 补上（没有起始时刻）。
            // 时间戳一律取注入时钟：测试要能控制 `durationMs`，
            // 直接用系统时钟会让冻结时钟下的差值算成负数并被 `max(0)` 吞掉。
            *live.run_stats.lock().expect("run_stats poisoned") =
                Some(RunStats::new(run_id.clone(), &model, mgr.clock.now()));
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
            // 先发 run.summary 再发 idle 的 run.state：
            // 反过来的话 UI 会先收起 streaming 再补上总结行，出现一次闪动。
            let stats = live.run_stats.lock().expect("run_stats poisoned").take();
            if let Some(stats) = stats {
                // 结束时刻同样取注入时钟，与 `agent_start` 的起始时刻同源。
                let _ = mgr.emit_live(live, stats.to_event(mgr.clock.now(), aborted));
            }
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
                    // 首字时长只在第一次 delta 记：它衡量的是「发出到第一个字」的延迟。
                    {
                        let mut guard = live.run_stats.lock().expect("run_stats poisoned");
                        if let Some(stats) = guard.as_mut() {
                            if stats.first_token_at.is_none() {
                                stats.first_token_at = Some(mgr.clock.now());
                            }
                        }
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
            // usage/请求数在这里累计：一次模型调用 = 一条 assistant 消息 =
            // 一次 message_end（工具循环会再起一轮，所以消息数就是请求数）。
            {
                let mut guard = live.run_stats.lock().expect("run_stats poisoned");
                if let Some(stats) = guard.as_mut() {
                    stats.add_usage(&msg);
                }
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
            if let Some(event) = auto_retry_end_event(&v) {
                let _ = mgr.emit_live(live, event);
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
        .insert((live.id.clone(), id.clone()), UiWaiterSlot { tx });
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
    mgr.ui_waiters
        .lock()
        .expect("ui poisoned")
        .remove(&(live.id.clone(), id.clone()));
    let _ = live.client.extension_ui_response(&id, body).await;
}

/// 会话 id 必须是单个普通标识，不能携带任何路径语义。
fn is_plain_session_id(id: &str) -> bool {
    if id.is_empty() || id.len() > 200 {
        return false;
    }
    if id.contains(['/', '\\', ':', '\0']) || id.contains("..") || id.ends_with(".jsonl") {
        return false;
    }
    let path = Path::new(id);
    !path.is_absolute() && path.components().count() == 1
}

/// 两侧 canonicalize 后做前缀校验，防止符号链把路径带出会话目录。
fn is_within_dir(dir: &Path, path: &Path) -> bool {
    let (Ok(dir), Ok(path)) = (dir.canonicalize(), path.canonicalize()) else {
        return false;
    };
    path.starts_with(&dir)
}

fn image_json(img: &ImagePayload) -> Value {
    json!({"type": "image", "data": img.data, "mimeType": img.mime_type})
}

/// 校验图片数量、类型与体积。
/// blob 图片按客户端声明的 `size` 计（服务端读 blob 时还会再核对实际文件），
/// 内联图片按 base64 估算；真正的帧体积限制由 `read_frame(MAX_CONTROL_FRAME)` 负责。
fn project_images_in(value: &mut Value, manager: &SessionManager, session_id: &str, live: bool) {
    let blobs = &manager.blobs;
    match value {
        Value::Object(map) => {
            if map.get("type").and_then(Value::as_str) == Some("image") {
                // 关闭中的会话不再收编：cleanup 会清掉引用，若快照在 cleanup 之后
                // 才 finalize，就会留下永久孤儿引用（N-03）。此时保留 base64；
                // 超大图仍由 snapshot 预算统一降级。
                if !live {
                    return;
                }
                // pi 的历史图有两种形状：顶层 data，或 Anthropic 风格的 source.data。
                let top_data = map
                    .get("data")
                    .and_then(Value::as_str)
                    .filter(|d| !d.is_empty());
                let nested_data = map
                    .get("source")
                    .and_then(|s| s.get("data"))
                    .and_then(Value::as_str)
                    .filter(|d| !d.is_empty());
                let from_source = top_data.is_none() && nested_data.is_some();
                if let Some(data) = top_data.or(nested_data) {
                    let projected: std::result::Result<(String, u64), String> = (|| {
                        let bytes = base64::engine::general_purpose::STANDARD
                            .decode(data)
                            .map_err(|_| "图片 base64 无法解码".to_string())?;
                        if bytes.is_empty() {
                            return Err("图片内容为空".to_string());
                        }
                        if bytes.len() > MAX_IMAGE_BYTES {
                            return Err("图片超过 20 MiB".to_string());
                        }
                        let declared = map
                            .get("mimeType")
                            .or_else(|| map.get("mediaType"))
                            .or_else(|| map.get("source").and_then(|s| s.get("media_type")))
                            .and_then(Value::as_str)
                            .unwrap_or("");
                        let mime = if crate::blob::ALLOWED_IMAGE_MIMES.contains(&declared) {
                            declared
                        } else {
                            sniff_image_mime(&bytes).ok_or("图片格式不受支持".to_string())?
                        };
                        // 历史图（尤其是 0.1.x 内联进 jsonl 的）也要收编进 blob 库，
                        // 否则多图快照会超过 1 MiB 控制帧，客户端连历史都打不开。
                        let sha = match blobs.adopt(session_id, mime, &bytes) {
                            Ok(sha) => sha,
                            Err(e) => return Err(format!("图片收编失败：{e}")),
                        };
                        // 收编期间会话可能被关闭：此时 cleanup 已经跑过，
                        // 主动释放这一次新建的引用，避免永久占额（N-03）。
                        if !manager.is_live(session_id) {
                            let _ = blobs.release(session_id, &sha);
                            return Err("会话已关闭，停止收编内联图".to_string());
                        }
                        Ok((sha, bytes.len() as u64))
                    })(
                    );
                    match projected {
                        Ok((sha, size)) => {
                            if from_source {
                                if let Some(Value::Object(source)) = map.get_mut("source") {
                                    source.remove("data");
                                }
                            } else {
                                map.remove("data");
                            }
                            map.insert("blobId".into(), Value::String(sha));
                            map.insert("size".into(), json!(size));
                        }
                        Err(reason) => {
                            // 投影失败绝不能把原 base64 留在快照里：一张 20 MiB 图
                            // 就足以把帧撑爆，导致断连（B-12）。
                            if from_source {
                                if let Some(Value::Object(source)) = map.get_mut("source") {
                                    source.remove("data");
                                }
                            } else {
                                map.remove("data");
                            }
                            map.insert("unavailable".into(), Value::Bool(true));
                            map.insert("reason".into(), Value::String(reason));
                        }
                    }
                }
            }
            for (_, v) in map.iter_mut() {
                project_images_in(v, manager, session_id, live);
            }
        }
        Value::Array(items) => {
            for v in items {
                project_images_in(v, manager, session_id, live);
            }
        }
        _ => {}
    }
}

/// 快照帧预算：先降级放不下的内联图，再按条数与序列化字节裁剪最旧消息。
/// 这是最后一道防线；投影成功的大图此时只剩 blobId，不会触发降级。
fn bound_snapshot_messages(mut messages: Vec<Value>) -> Vec<Value> {
    for m in &mut messages {
        degrade_oversized_images(m);
    }
    if messages.len() > MAX_SNAPSHOT_MESSAGES {
        let drop = messages.len() - MAX_SNAPSHOT_MESSAGES;
        messages.drain(..drop);
    }
    let sizes: Vec<usize> = messages
        .iter()
        .map(|m| serde_json::to_vec(m).map(|b| b.len()).unwrap_or(0))
        .collect();
    let mut total: usize = sizes.iter().sum();
    let mut start = 0;
    while total > MAX_SNAPSHOT_BYTES && start + 1 < messages.len() {
        total -= sizes[start];
        start += 1;
    }
    if start > 0 {
        messages.drain(..start);
    }
    // 只剩一条超大消息时上面的循环不会删；把正文换成占位，保留 id/role，
    // 至少让客户端还能渲染“这条消息过长”。
    if total > MAX_SNAPSHOT_BYTES {
        if let Some(last) = messages.last_mut() {
            if let Some(map) = last.as_object_mut() {
                let placeholder = json!([{"type":"text","text":"（消息过长，快照已截断）"}]);
                if let Some(body) = map.get_mut("message").and_then(Value::as_object_mut) {
                    body.remove("images");
                    body.insert("content".into(), placeholder);
                } else {
                    map.remove("images");
                    map.insert("content".into(), placeholder);
                }
            }
        }
    }
    messages
}

fn degrade_oversized_images(value: &mut Value) {
    match value {
        Value::Object(map) => {
            if map.get("type").and_then(Value::as_str) == Some("image") {
                let limit = MAX_INLINE_IMAGE_BYTES / 3 * 4;
                let top_too_big = map
                    .get("data")
                    .and_then(Value::as_str)
                    .is_some_and(|d| d.len() > limit);
                let nested_too_big = map
                    .get("source")
                    .and_then(|s| s.get("data"))
                    .and_then(Value::as_str)
                    .is_some_and(|d| d.len() > limit);
                if top_too_big || nested_too_big {
                    if nested_too_big {
                        if let Some(Value::Object(source)) = map.get_mut("source") {
                            source.remove("data");
                        }
                    } else {
                        map.remove("data");
                    }
                    map.insert("unavailable".into(), Value::Bool(true));
                    map.insert(
                        "reason".into(),
                        Value::String("图片过大，快照已省略；请升级客户端或重新发送".into()),
                    );
                }
            }
            for (_, v) in map.iter_mut() {
                degrade_oversized_images(v);
            }
        }
        Value::Array(items) => {
            for v in items {
                degrade_oversized_images(v);
            }
        }
        _ => {}
    }
}

fn sniff_image_mime(bytes: &[u8]) -> Option<&'static str> {
    if bytes.starts_with(&[0x89, b'P', b'N', b'G']) {
        return Some("image/png");
    }
    if bytes.starts_with(&[0xFF, 0xD8, 0xFF]) {
        return Some("image/jpeg");
    }
    if bytes.starts_with(b"GIF8") {
        return Some("image/gif");
    }
    if bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        return Some("image/webp");
    }
    None
}

fn validate_images(images: &[ImagePayload]) -> Result<()> {
    if images.len() > pi_carry_protocol::MAX_IMAGES_PER_PROMPT {
        return Err(CoreError::InvalidArgument(format!(
            "单条消息最多 {} 张图片，实际 {}",
            pi_carry_protocol::MAX_IMAGES_PER_PROMPT,
            images.len()
        )));
    }
    let mut total = 0u64;
    for (i, img) in images.iter().enumerate() {
        // 只接受 pi 支持的图片类型：否则 pi 会在 run 中报错，而用户看不到原因。
        if !matches!(
            img.mime_type.as_str(),
            "image/png" | "image/jpeg" | "image/webp" | "image/gif"
        ) {
            return Err(CoreError::InvalidArgument(format!(
                "第 {} 张图片的类型 {} 不受支持（png/jpeg/webp/gif）",
                i + 1,
                img.mime_type
            )));
        }
        let is_blob = img.blob_id.as_deref().is_some_and(|s| !s.is_empty());
        if is_blob {
            // blob 的 size 是客户端声明值，只做「单图不超过 20 MiB」的上界提示；
            // 真实体积与合计在 resolve_prompt_images 读盘后重算（B-11）。
            if img.size == 0 {
                return Err(CoreError::InvalidArgument(format!(
                    "第 {} 张图片缺少 size，无法校验",
                    i + 1
                )));
            }
            if img.size > pi_carry_protocol::MAX_IMAGE_BYTES as u64 {
                return Err(CoreError::InvalidArgument(format!(
                    "第 {} 张图片声明体积超过单图上限 {} MiB",
                    i + 1,
                    pi_carry_protocol::MAX_IMAGE_BYTES / (1024 * 1024)
                )));
            }
            continue;
        }
        if img.data.is_empty() {
            return Err(CoreError::InvalidArgument(format!(
                "第 {} 张图片缺少图片数据",
                i + 1
            )));
        }
        // base64 每 4 字符 = 3 字节，末尾可能有 padding。
        let raw_len = (img.data.len() / 4 * 3) as u64;
        if raw_len > pi_carry_protocol::MAX_IMAGE_BYTES as u64 {
            return Err(CoreError::InvalidArgument(format!(
                "第 {} 张图片约 {} MiB，超过单图上限 {} MiB",
                i + 1,
                raw_len / (1024 * 1024),
                pi_carry_protocol::MAX_IMAGE_BYTES / (1024 * 1024)
            )));
        }
        total = total.saturating_add(raw_len);
    }
    if total > pi_carry_protocol::MAX_PROMPT_IMAGE_BYTES as u64 {
        return Err(CoreError::InvalidArgument(format!(
            "本条消息图片合计约 {} MiB，超过上限 {} MiB",
            total / (1024 * 1024),
            pi_carry_protocol::MAX_PROMPT_IMAGE_BYTES / (1024 * 1024)
        )));
    }
    Ok(())
}

fn unwrap_state(v: Value) -> Value {
    v.get("state").cloned().unwrap_or(v)
}

/// fork RPC 之后从 pi 读回新会话的（id、会话文件、摘要）。
///
/// 单独成函数是为了让 `fork` 把「pi 已切到新会话」之后的所有失败收口：
/// 这几步任何一步出错都必须关掉会话，不能让 live 继续挂在旧 id 上。
async fn read_forked_session(live: &LiveSession) -> Result<(String, PathBuf, SessionSummary)> {
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
    Ok((new_id, path, summary))
}

/// `get_state` 里的 `isStreaming`。
///
/// 字段缺失或类型不对（老版本 pi）一律按「没在跑」处理，
/// 与引入本判断之前的行为一致，不会因升级失配而误判成流式中。
fn state_streaming(state: &Value) -> bool {
    state
        .get("isStreaming")
        .and_then(Value::as_bool)
        .unwrap_or(false)
}

/// 确认审批扩展真的被 pi 加载了。
///
/// pi 以 `--approve` 启动（无头进程无法交互确认项目信任），所以审批闸门完全依赖
/// `-e guard.ts`。`-e` 拼错、文件被删、pi 扩展 API 变更都会让扩展静默不加载，
/// 此时所有工具调用直通——必须在建会话前拦住，而不是让它跑一个无闸门的 run。
async fn verify_guard_loaded(client: &PiClient) -> Result<()> {
    let resp = client.request("get_commands", Value::Null).await?;
    let commands = resp
        .get("commands")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let loaded = commands
        .iter()
        .any(|c| c.get("name").and_then(Value::as_str) == Some(crate::guard::GUARD_COMMAND));
    if !loaded {
        return Err(CoreError::Pi(format!(
            "审批扩展未加载（缺少命令 {}），拒绝启动会话；请检查 pi 版本是否支持 -e 扩展、{} 是否可读",
            crate::guard::GUARD_COMMAND,
            "~/.pi-carry/extensions/guard.ts"
        )));
    }
    Ok(())
}

/// 从会话文件读出历史消息。
///
/// 每条消息都带上**行级 entry id**（会话文件里的 `id` 字段）：pi 的 `AgentMessage`
/// 本身没有 id，客户端只能靠 entry id 做合并/去重，否则用户消息成对重复、
/// LazyColumn key 全体 churn。热路径必须走 `get_entries` 而不是 `get_messages`，
/// 才能拿到与这里一致的 id。
fn read_session_messages(path: &Path) -> Vec<Value> {
    let mut out: Vec<Value> = Vec::new();
    let Ok(file) = std::fs::File::open(path) else {
        return out;
    };
    // 快照必须整读：以前只读末尾 1 MiB，长会话在手机上重建时前半段历史被无声
    // 截断（journal 只保留最近事件，回放补不回来）。这里逐行流式读，单行上限
    // 8 MiB：0.2 的原图内联会让一行达到数十 MB，整行进 String 会直接吃爆内存（B-10）。
    let mut reader = BufReader::new(file);
    loop {
        let Some((bytes, truncated)) = read_line_prefix(&mut reader, MAX_JSONL_LINE).ok().flatten()
        else {
            break;
        };
        let line = String::from_utf8_lossy(&bytes);
        if !looks_like_message_line(&line) {
            continue;
        }
        let entry = if truncated {
            // 保留 entry id/parentId/role，让客户端的历史合并与分支树不断链；
            // 图片内容降级成占位，避免把超长 base64 塞进快照。
            truncated_entry(&line)
        } else {
            serde_json::from_str::<Value>(&line).ok()
        };
        let Some(v) = entry else { continue };
        if let Some(msg) = entry_to_message(&v) {
            out.push(msg);
            if out.len() > MAX_SNAPSHOT_MESSAGES {
                // 极端长会话：保留最新的一段，避免一个事件帧装不下整份历史。
                out.remove(0);
            }
        }
    }
    out
}

/// 有上限地读一行，超出 `limit` 的部分丢弃；返回（保留的前缀, 是否被截断）。
/// 保留前缀是为了从里面抢救 entry id，让截断的消息仍能参与历史合并。
fn read_line_prefix<R: BufRead>(
    reader: &mut R,
    limit: usize,
) -> std::io::Result<Option<(Vec<u8>, bool)>> {
    let mut out = Vec::new();
    let mut truncated = false;
    let mut any = false;
    loop {
        let buf = reader.fill_buf()?;
        if buf.is_empty() {
            if !any {
                return Ok(None);
            }
            break;
        }
        any = true;
        if let Some(end) = buf.iter().position(|b| *b == b'\n') {
            if !truncated {
                if out.len() + end <= limit {
                    out.extend_from_slice(&buf[..end]);
                } else {
                    truncated = true;
                }
            }
            reader.consume(end + 1);
            break;
        }
        let len = buf.len();
        if !truncated {
            if out.len() + len <= limit {
                out.extend_from_slice(buf);
            } else {
                truncated = true;
            }
        }
        reader.consume(len);
    }
    Ok(Some((out, truncated)))
}

/// 从（可能被截断的）JSON 前缀里抓顶层字符串字段。
fn json_string_field(text: &str, key: &str) -> Option<String> {
    let pat = format!("\"{key}\"");
    let mut from = 0;
    while let Some(pos) = text[from..].find(&pat) {
        let start = from + pos + pat.len();
        let rest = text[start..].trim_start();
        let Some(rest) = rest.strip_prefix(':') else {
            from = start;
            continue;
        };
        let rest = rest.trim_start();
        let Some(rest) = rest.strip_prefix('"') else {
            from = start;
            continue;
        };
        let end = rest.find('"')?;
        return Some(rest[..end].to_string());
    }
    None
}

fn json_u64_field(text: &str, key: &str) -> Option<u64> {
    let pat = format!("\"{key}\"");
    let pos = text.find(&pat)? + pat.len();
    let rest = text[pos..].trim_start().strip_prefix(':')?.trim_start();
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

/// 8 MiB 截断的行可能来不及出现 `size`，但内联 data 的长度足以估算原图大小。
/// 只用于客户端占位文案，不参与任何配额计算。
fn truncated_image_size(prefix: &str) -> u64 {
    if let Some(size) = json_u64_field(prefix, "size") {
        return size;
    }
    let Some(start) = prefix.find("\"data\":\"") else {
        return 0;
    };
    let encoded = prefix[start + 8..]
        .bytes()
        .take_while(|b| b.is_ascii_alphanumeric() || *b == b'+' || *b == b'/' || *b == b'=')
        .count() as u64;
    encoded.saturating_mul(3) / 4
}

/// 单行被 8 MiB 截断时 blobId 已在 data 之前，能从进行中的 JSON 前缀抢救回来，
/// 让客户端仍可从 blob 库渲染，而不是永久显示“图片未加载”（B-01）。
fn salvage_blob_images(prefix: &str) -> Vec<Value> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(rel) = prefix[from..].find("\"blobId\"") {
        let start = from + rel;
        let Some(id) = json_string_field(&prefix[start..], "blobId") else {
            from = start + 8;
            continue;
        };
        if id.len() == 64 && id.bytes().all(|b| b.is_ascii_hexdigit()) {
            let tail = &prefix[start..];
            let mime = json_string_field(tail, "mimeType").unwrap_or_else(|| "image/jpeg".into());
            let size = json_u64_field(tail, "size").unwrap_or(0);
            out.push(json!({
                "type": "image",
                "blobId": id,
                "mimeType": mime,
                "size": size
            }));
        }
        from = start + 8 + id.len();
    }
    out
}

fn truncated_entry(prefix: &str) -> Option<Value> {
    let id = json_string_field(prefix, "id")?;
    let parent = json_string_field(prefix, "parentId");
    let timestamp = json_string_field(prefix, "timestamp");
    let role = json_string_field(prefix, "role").unwrap_or_else(|| "user".into());
    let mut content = salvage_blob_images(prefix);
    if content.is_empty() {
        content.push(json!({
            "type": "image",
            "unavailable": true,
            "size": truncated_image_size(prefix),
            "reason": "消息超过 8 MiB，图片未加载"
        }));
    }
    Some(json!({
        "type": "message",
        "id": id,
        "parentId": parent,
        "timestamp": timestamp,
        "message": {
            "role": role,
            "timestamp": timestamp,
            "content": content
        }
    }))
}

/// entries 面板只需要 id/父子关系与一小段预览，不能把整条历史图搬进去。
fn entry_preview(entry: &Value) -> Value {
    let msg = entry.get("message");
    let role = msg
        .and_then(|m| m.get("role"))
        .cloned()
        .unwrap_or_else(|| json!("user"));
    let mut preview = String::new();
    if let Some(items) = msg.and_then(|m| m.get("content")).and_then(Value::as_array) {
        for item in items {
            match item.get("type").and_then(Value::as_str) {
                Some("text") => {
                    if let Some(t) = item.get("text").and_then(Value::as_str) {
                        if !preview.is_empty() {
                            preview.push(' ');
                        }
                        preview.push_str(t);
                    }
                }
                Some("image") => preview.push_str("[图片]"),
                _ => {}
            }
            if preview.chars().count() > 160 {
                break;
            }
        }
    }
    let preview: String = preview.chars().take(160).collect();
    json!({
        "type": "message",
        "id": entry.get("id").cloned().unwrap_or(Value::Null),
        "parentId": entry.get("parentId").cloned().unwrap_or(Value::Null),
        "timestamp": entry.get("timestamp").cloned().unwrap_or(Value::Null),
        "message": {
            "role": role,
            "content": [{"type": "text", "text": preview}],
        }
    })
}

/// 廉价类型预筛：整读文件时先做子串检查，避免对每个非消息行都跑一遍 JSON 解析。
fn looks_like_message_line(line: &str) -> bool {
    line.contains("\"type\":\"message\"")
}

/// 按字节流统计会话文件里的消息条数。
///
/// 只数不解析：`list()` 在桌面端每次刷新都会跑一遍全部会话，对每份历史做完整
/// JSON 解析代价过高；而只数末尾窗口又会少算（长会话的消息数显示成个位数）。
fn count_message_lines(path: &Path) -> u64 {
    const NEEDLE: &[u8] = b"{\"type\":\"message\"";
    let Ok(mut file) = std::fs::File::open(path) else {
        return 0;
    };
    let mut buf = vec![0u8; 256 * 1024];
    let mut count = 0u64;
    // 跨块保留 NEEDLE.len()-1 字节，避免恰好跨块边界的匹配被漏掉。
    let mut carry: Vec<u8> = Vec::with_capacity(NEEDLE.len() - 1);
    loop {
        match file.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let mut chunk = std::mem::take(&mut carry);
                chunk.extend_from_slice(&buf[..n]);
                count += count_needle(&chunk, NEEDLE) as u64;
                let keep = NEEDLE.len().saturating_sub(1).min(chunk.len());
                carry = chunk[chunk.len() - keep..].to_vec();
            }
            Err(_) => break,
        }
    }
    count
}

fn count_needle(hay: &[u8], needle: &[u8]) -> usize {
    if needle.is_empty() || hay.len() < needle.len() {
        return 0;
    }
    let mut hits = 0;
    let mut i = 0;
    while i + needle.len() <= hay.len() {
        if &hay[i..i + needle.len()] == needle {
            hits += 1;
            i += needle.len();
        } else {
            i += 1;
        }
    }
    hits
}

/// 把一条会话文件 entry（`{type:"message",id,message:{...}}`）
/// 或 pi `get_entries` 返回的同形 entry 转成消息，并把 entry id 注入消息体。
fn entry_to_message(entry: &Value) -> Option<Value> {
    let mut msg = entry.get("message")?.clone();
    if let (Value::Object(map), Some(id)) = (&mut msg, entry.get("id").and_then(Value::as_str)) {
        map.insert("id".into(), Value::String(id.to_string()));
    }
    Some(msg)
}

/// 读文件开头的有界片段：header 是第一行，不必为了它整读大文件。
/// 读会话文件 header 里的 cwd。仅用于信任判定；解析失败当“无 cwd”处理（=不信任）。
fn session_cwd(path: &Path) -> Option<String> {
    let head = read_head(path, JSONL_HEADER_BYTES).ok()?;
    let first = head.lines().next()?;
    let header: Value = serde_json::from_str(first).ok()?;
    if header.get("type").and_then(Value::as_str) != Some("session") {
        return None;
    }
    header
        .get("cwd")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .map(ToOwned::to_owned)
}

fn read_head(path: &Path, limit: u64) -> Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut buf = Vec::new();
    file.by_ref().take(limit).read_to_end(&mut buf)?;
    Ok(String::from_utf8_lossy(&buf).into_owned())
}

/// 读文件末尾的窗口。第二项表示起点是否落在行中间（首个半行不可解析）。
fn read_tail(path: &Path, limit: u64) -> Result<(String, bool)> {
    let mut file = std::fs::File::open(path)?;
    let len = file.metadata()?.len();
    let start = len.saturating_sub(limit);
    file.seek(SeekFrom::Start(start))?;
    let mut buf = Vec::new();
    file.read_to_end(&mut buf)?;
    Ok((String::from_utf8_lossy(&buf).into_owned(), start > 0))
}

/// 要统计/回放的行。文件不超过 `JSONL_TAIL_BYTES` 时整读（旧行为）；
/// 更大的会话只读末尾窗口，避免 `list()` / `snapshot()` 把整段历史读进内存。
/// 第二项表示返回的行里是否包含文件第一行（header）。
fn read_body_lines(path: &Path) -> Result<(Vec<String>, bool)> {
    let len = std::fs::metadata(path)?.len();
    if len <= JSONL_TAIL_BYTES {
        let content = std::fs::read_to_string(path)?;
        return Ok((content.lines().map(ToOwned::to_owned).collect(), true));
    }
    let (tail, cut) = read_tail(path, JSONL_TAIL_BYTES)?;
    let mut lines: Vec<String> = tail.lines().map(ToOwned::to_owned).collect();
    if cut && !lines.is_empty() {
        // 窗口起点在行中间：这半行不可解析，直接丢掉。
        lines.remove(0);
    }
    Ok((lines, !cut))
}

fn scan_one(path: &Path) -> Result<Option<SessionSummary>> {
    let content = read_head(path, JSONL_HEADER_BYTES)?;
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
    // session_info 几乎总在文件前部；大文件只读尾部窗口时会错过它，
    // 所以在已读的 header 窗口里再扫一遍（窗口末尾的半行解析失败，无害）。
    for line in lines {
        if let Ok(v) = serde_json::from_str::<Value>(line) {
            if v.get("type").and_then(Value::as_str) == Some("session_info") {
                if let Some(n) = v.get("name").and_then(Value::as_str) {
                    name = n.to_string();
                }
            }
        }
    }
    // 消息条数按字节流统计整份文件：只数末尾窗口会让长会话显示成个位数。
    let message_count = count_message_lines(path);
    let mut preview = String::new();
    // 预览要的是「最后一条用户消息」，末尾窗口足够且不会为大文件整读。
    let (body, includes_header) = read_body_lines(path)?;
    for line in body.iter().skip(usize::from(includes_header)) {
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
                if role == "user" {
                    let text = message_text(msg);
                    if !text.is_empty() {
                        preview = truncate(&text, 160);
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
        migrated_to: None,
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

    #[test]
    fn scan_handles_file_larger_than_tail_window() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("--big--");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("big.jsonl");
        let mut out = String::new();
        out.push_str(
            &json!({"type":"session","version":3,"id":"big-1","timestamp":"2026-11-19T08:00:00.000Z","cwd":"C:/work"}).to_string(),
        );
        out.push('\n');
        out.push_str(&json!({"type":"session_info","name":"超大会话"}).to_string());
        out.push('\n');
        // 造出超过末尾窗口（1 MiB）的历史，header 已经不在尾部窗口里。
        let filler = "x".repeat(4096);
        for i in 0..400 {
            out.push_str(
                &json!({"type":"message","id":format!("m{i}"),"message":{"role":"user","content":[{"type":"text","text":filler}]}})
                    .to_string(),
            );
            out.push('\n');
        }
        std::fs::write(&path, out).unwrap();
        assert!(std::fs::metadata(&path).unwrap().len() > JSONL_TAIL_BYTES);
        let s = scan_one(&path).unwrap().unwrap();
        assert_eq!(s.id, "big-1", "header 只能从文件开头读，不能靠尾部窗口");
        assert_eq!(s.name, "超大会话");
        assert!(s.message_count > 0, "尾部窗口仍需统计到消息");
        // NEW-2：条数必须是整份文件的真实条数，不能只数尾部窗口。
        assert_eq!(s.message_count, 400, "长会话消息数不得因尾窗而少算");
    }

    /// NEW-2：冷路径快照必须包含整份历史，不能只给末尾窗口。
    #[test]
    fn cold_snapshot_keeps_history_beyond_tail_window() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("--big--");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("big.jsonl");
        let mut out = String::new();
        out.push_str(
            &json!({"type":"session","version":3,"id":"big-2","timestamp":"2026-11-19T08:00:00.000Z","cwd":"C:/work"}).to_string(),
        );
        out.push('\n');
        // 第一条消息写在文件最开头：旧实现只读尾窗，它会丢失。
        out.push_str(
            &json!({"type":"message","id":"first-msg","message":{"role":"user","content":"最早的一句话"}})
                .to_string(),
        );
        out.push('\n');
        let filler = "x".repeat(4096);
        for i in 0..400 {
            out.push_str(
                &json!({"type":"message","id":format!("m{i}"),"message":{"role":"assistant","content":[{"type":"text","text":filler}]}})
                    .to_string(),
            );
            out.push('\n');
        }
        std::fs::write(&path, out).unwrap();
        assert!(std::fs::metadata(&path).unwrap().len() > JSONL_TAIL_BYTES);

        let msgs = read_session_messages(&path);
        assert_eq!(msgs.len(), 401, "冷快照必须包含尾部窗口之前的消息");
        assert_eq!(
            msgs.first()
                .and_then(|m| m.get("id"))
                .and_then(Value::as_str),
            Some("first-msg"),
            "最早的条目不能被截断"
        );
    }

    /// 字节级计数要能处理跨缓冲区边界的匹配。
    #[test]
    fn count_needle_handles_matches_across_chunk_boundary() {
        let needle = b"{\"type\":\"message\"";
        let mut hay = vec![b'.'; 100];
        // 把 needle 放在偏移 95：跨越常见的 64/128 字节块边界。
        hay.truncate(95);
        hay.extend_from_slice(needle);
        hay.extend_from_slice(b"zzzz");
        hay.extend_from_slice(needle);
        assert_eq!(count_needle(&hay, needle), 2);
    }

    /// 快照消息数上限：超长会话保留最新一段，不得让一个帧撑爆。
    #[test]
    fn snapshot_is_capped_but_keeps_the_newest_messages() {
        let huge = json!({
            "id": "m1",
            "message": {"role": "user", "content": "x".repeat(MAX_SNAPSHOT_BYTES + 1024)}
        });
        let out = bound_snapshot_messages(vec![huge]);
        assert_eq!(out.len(), 1, "唯一一条消息也要保留 id/role");
        assert_eq!(out[0]["id"], "m1");
        let size = serde_json::to_vec(&out[0]).unwrap().len();
        assert!(size <= MAX_SNAPSHOT_BYTES, "单条消息仍超预算: {size}");
    }

    #[test]
    fn resolve_ui_rejects_request_owned_by_another_session() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let (tx, _rx) = tokio::sync::oneshot::channel();
        mgr.ui_waiters.lock().unwrap().insert(
            ("sess-a".to_string(), "ui-1".to_string()),
            UiWaiterSlot { tx },
        );
        let payload = |id: &str| UiResponsePayload {
            request_id: id.into(),
            value: Some(json!("ok")),
            confirmed: Some(true),
            cancelled: false,
        };
        // 别的会话不能替它解除请求。
        assert!(matches!(
            mgr.resolve_ui("sess-b", payload("ui-1")),
            Err(CoreError::InvalidArgument(_))
        ));
        assert!(
            mgr.ui_waiters
                .lock()
                .unwrap()
                .contains_key(&("sess-a".to_string(), "ui-1".to_string())),
            "越权调用不得移除别会话的 ui 请求"
        );
        // 未知请求也不能静默成功。
        assert!(matches!(
            mgr.resolve_ui("sess-a", payload("ui-missing")),
            Err(CoreError::InvalidArgument(_))
        ));
        // 归属会话自己可以解除。
        assert!(mgr.resolve_ui("sess-a", payload("ui-1")).is_ok());
        assert!(!mgr
            .ui_waiters
            .lock()
            .unwrap()
            .contains_key(&("sess-a".to_string(), "ui-1".to_string())));
    }

    /// 两个会话的 pi 进程可能产生相同 requestId，单键 map 会互相覆盖。
    #[test]
    fn ui_waiters_are_isolated_per_session() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let (tx_a, _rx_a) = tokio::sync::oneshot::channel();
        let (tx_b, mut rx_b) = tokio::sync::oneshot::channel();
        {
            let mut waiters = mgr.ui_waiters.lock().unwrap();
            waiters.insert(
                ("sess-a".to_string(), "ui-1".to_string()),
                UiWaiterSlot { tx: tx_a },
            );
            waiters.insert(
                ("sess-b".to_string(), "ui-1".to_string()),
                UiWaiterSlot { tx: tx_b },
            );
        }
        // sess-a 的同名请求不得影响 sess-b 的通道。
        let resp = UiResponsePayload {
            request_id: "ui-1".into(),
            value: Some(json!("from-b")),
            confirmed: Some(true),
            cancelled: false,
        };
        assert!(mgr.resolve_ui("sess-b", resp).is_ok());
        let got = rx_b.try_recv().expect("sess-b 的 waiter 必须被唤醒");
        assert_eq!(got["value"], json!("from-b"));
        assert!(mgr
            .ui_waiters
            .lock()
            .unwrap()
            .contains_key(&("sess-a".to_string(), "ui-1".to_string())));
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

    fn manager_in(dir: &Path) -> Arc<SessionManager> {
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
        let mut config = Config::default();
        config.sessions.dir = dir.display().to_string();
        SessionManager::new(
            config,
            Paths::at(dir.to_path_buf()),
            store,
            Arc::new(clock),
            Arc::new(SeqIdGen::new()),
            approvals,
            PathBuf::from("pi"),
            PathBuf::from("guard.ts"),
        )
    }

    #[test]
    fn resolve_path_finds_session_inside_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let path = write_session(tmp.path(), "sess-1", "C:/work");
        let mgr = manager_in(tmp.path());
        let found = mgr.resolve_path("sess-1").unwrap();
        assert_eq!(found.canonicalize().unwrap(), path.canonicalize().unwrap());
    }

    #[test]
    fn resolve_path_rejects_path_like_ids() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let outside = tempfile::tempdir().unwrap();
        let outside_file = write_session(outside.path(), "sess-evil", "C:/evil");
        let sep = std::path::MAIN_SEPARATOR;
        let bad_ids = [
            // 会话目录外的绝对 .jsonl 路径。
            outside_file.display().to_string(),
            // 会话目录内的绝对 .jsonl 路径（依然是路径，不是 id）。
            tmp.path()
                .join("--proj--")
                .join("2026-11-19T08-00-00_sess-1.jsonl")
                .display()
                .to_string(),
            "../sess-1".to_string(),
            format!("..{sep}--proj--{sep}x.jsonl"),
            "sess-1.jsonl".to_string(),
            "sub/sess-1".to_string(),
            String::new(),
        ];
        for bad in bad_ids {
            assert!(
                matches!(mgr.resolve_path(&bad), Err(CoreError::SessionNotFound(_))),
                "应拒绝非法 id: {bad}"
            );
        }
    }

    /// P-3 回归：未显式信任的工作区不得加载项目级可执行资源。
    ///
    /// 这条测试守住的是「手机能选 cwd」与「`--approve` 会执行项目里的扩展」两个事实的交叉点：
    /// 一旦 `project_trusted` 退化成“总是 true”，下面的断言会直接失败。
    #[test]
    fn project_trust_requires_explicit_trusted_root() {
        let tmp = tempfile::tempdir().unwrap();
        let workspace = tmp.path().join("repo");
        std::fs::create_dir_all(&workspace).unwrap();
        let session = write_session(tmp.path(), "sess-trust", &workspace.display().to_string());

        // 默认配置：允许创建会话，但不信任项目资源。
        let mgr = manager_in(tmp.path());
        assert!(
            !mgr.project_trusted(&session),
            "默认配置下任何工作区都不能加载项目级扩展"
        );

        let mut config = Config::default();
        config.sessions.dir = tmp.path().display().to_string();
        config.sessions.allowed_roots = vec![tmp.path().display().to_string()];
        let store = Store::open_memory().unwrap();
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());
        let (tx, _) = broadcast::channel(8);
        let approvals = ApprovalManager::new(
            store.clone(),
            config.approvals.clone(),
            Arc::new(clock.clone()),
            Arc::new(SeqIdGen::new()),
            tx,
        );
        let mgr = SessionManager::new(
            config.clone(),
            Paths::at(tmp.path().to_path_buf()),
            store.clone(),
            Arc::new(clock.clone()),
            Arc::new(SeqIdGen::new()),
            approvals.clone(),
            PathBuf::from("pi"),
            PathBuf::from("guard.ts"),
        );
        // 只要 allowedRoots 不足以信任：这正是 P-3 的核心（同一个根目录下有很多仓库）。
        assert!(
            !mgr.project_trusted(&session),
            "只配 allowedRoots 不能自动获得项目执行权限"
        );

        // 显式加入 trustedRoots 后才放行。
        let mut trusted = config.clone();
        trusted.sessions.trusted_roots = vec![workspace.display().to_string()];
        let mgr = SessionManager::new(
            trusted,
            Paths::at(tmp.path().to_path_buf()),
            store,
            Arc::new(clock.clone()),
            Arc::new(SeqIdGen::new()),
            approvals,
            PathBuf::from("pi"),
            PathBuf::from("guard.ts"),
        );
        assert!(mgr.project_trusted(&session), "显式信任的根目录应放行");
        // 信任匹配必须按路径前缀而不是字符串包含：`repo2` 不能被 `repo` 放行。
        let sibling = tmp.path().join("repo2");
        std::fs::create_dir_all(&sibling).unwrap();
        let other = write_session(tmp.path(), "sess-trust2", &sibling.display().to_string());
        assert!(!mgr.project_trusted(&other), "同前缀的兄弟目录不能被放行");
    }

    #[test]
    fn resolve_cwd_rejects_outside_default_roots() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let outside = if cfg!(windows) {
            PathBuf::from(r"C:\Windows")
        } else {
            PathBuf::from("/usr")
        };
        if outside.is_dir() {
            assert!(
                matches!(
                    mgr.resolve_cwd(&outside.display().to_string()),
                    Err(CoreError::PermissionDenied(_))
                ),
                "默认根目录之外的工作区必须被拒绝"
            );
        }
    }

    /// 同一目录的不同写法必须得到同一个 cwd 字符串：
    /// 否则会话分组按 cwd 分组时会出现两个同名组，`hasSessions` 也认不出来。
    #[test]
    fn resolve_cwd_normalizes_equivalent_spellings() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let sub = tmp.path().join("workspace");
        std::fs::create_dir_all(&sub).unwrap();
        let plain = mgr.resolve_cwd(&sub.display().to_string()).unwrap();
        let dotted = mgr
            .resolve_cwd(&sub.join("..").join("workspace").display().to_string())
            .unwrap();
        let trailing = mgr
            .resolve_cwd(&format!("{}{}", sub.display(), std::path::MAIN_SEPARATOR))
            .unwrap();
        assert_eq!(plain, dotted, "`子目录/../子目录` 应与 `子目录` 归一化一致");
        assert_eq!(plain, trailing, "尾部分隔符不应影响结果");
        // 返回值必须是剥掉 verbatim 前缀的展示形式，而不是 `\\\\?\\C:\\…`。
        assert!(
            !plain.starts_with(r"\\?\"),
            "cwd 不应带 verbatim 前缀: {plain}"
        );
    }

    #[test]
    fn resolve_path_ignores_sessions_outside_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let outside = tempfile::tempdir().unwrap();
        write_session(outside.path(), "sess-out", "C:/evil");
        assert!(matches!(
            mgr.resolve_path("sess-out"),
            Err(CoreError::SessionNotFound(_))
        ));
    }

    #[tokio::test]
    async fn spawn_lock_serializes_same_id() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let lock = mgr.spawn_lock("same");
        assert!(Arc::ptr_eq(&lock, &mgr.spawn_lock("same")));
        assert!(!Arc::ptr_eq(&lock, &mgr.spawn_lock("other")));

        let guard = lock.clone().lock_owned().await;
        let waiter = mgr.spawn_lock("same");
        let task = tokio::spawn(async move {
            let _g = waiter.lock().await;
        });
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(!task.is_finished(), "同一 id 的第二次 spawn 必须等待");
        drop(guard);
        tokio::time::timeout(Duration::from_secs(2), task)
            .await
            .expect("释放后应立即获得锁")
            .unwrap();
    }

    #[test]
    fn spawn_lock_entry_is_released_only_when_unheld() {
        let tmp = tempfile::tempdir().unwrap();
        let mgr = manager_in(tmp.path());
        let held = mgr.spawn_lock("held");
        mgr.spawn_lock("idle");
        assert_eq!(mgr.spawn_locks.lock().unwrap().len(), 2);

        // 还有人持有时不能移除：换一把新锁会让同一 id 双开 pi 进程。
        mgr.release_spawn_lock("held");
        assert!(Arc::ptr_eq(&held, &mgr.spawn_lock("held")));

        mgr.release_spawn_lock("idle");
        assert!(
            !mgr.spawn_locks.lock().unwrap().contains_key("idle"),
            "无人持有的锁必须在关闭时移除"
        );
    }

    fn stats_for(run_id: &str) -> RunStats {
        RunStats::new(
            run_id.into(),
            &json!({"provider": "anthropic", "id": "claude-sonnet-4-5"}),
            chrono::Utc::now(),
        )
    }

    #[test]
    fn run_stats_sums_usage_across_model_calls() {
        let mut stats = stats_for("run-1");
        stats.add_usage(&json!({
            "role": "assistant",
            "usage": {"input": 1000, "output": 200, "cacheRead": 700, "cacheWrite": 10,
                      "totalTokens": 1910, "cost": {"total": 0.30}}
        }));
        stats.add_usage(&json!({
            "role": "assistant",
            "usage": {"input": 500, "output": 900, "cacheRead": 0, "cacheWrite": 0,
                      "totalTokens": 1400, "cost": {"total": 0.12}}
        }));
        assert_eq!(stats.requests, 2, "每次模型调用计一次请求");
        assert_eq!(stats.input_tokens, 1500);
        assert_eq!(stats.output_tokens, 1100);
        assert_eq!(stats.cache_read_tokens, 700);
        assert_eq!(stats.cache_write_tokens, 10);
        assert_eq!(stats.total_tokens, 3310);
        assert!((stats.cost - 0.42).abs() < 1e-9);
        let ev = stats.to_event(chrono::Utc::now(), false);
        assert_eq!(ev["type"], "run.summary");
        assert_eq!(ev["runId"], "run-1");
        assert_eq!(ev["requests"], 2);
        assert_eq!(ev["model"], "anthropic/claude-sonnet-4-5");
    }

    #[test]
    fn run_stats_without_usage_reports_null_tokens() {
        let mut stats = stats_for("run-2");
        stats.add_usage(&json!({"role": "assistant"}));
        let ev = stats.to_event(chrono::Utc::now(), true);
        assert_eq!(ev["requests"], 1);
        // 没有 usage 时必须是 null：0 会被 UI 当成「这一轮没花 token」。
        assert!(ev["totalTokens"].is_null());
        assert!(ev["cost"].is_null());
        assert_eq!(ev["aborted"], true);
    }

    #[test]
    fn run_stats_without_agent_start_reports_null_duration() {
        // 半路 attach：拿不到 agent_start，不能编造时长。
        let mut stats = RunStats::default();
        stats.run_id = "run-3".into();
        stats.add_usage(&json!({"role": "assistant", "usage": {"input": 5, "output": 7}}));
        let ev = stats.to_event(chrono::Utc::now(), false);
        assert!(ev["durationMs"].is_null());
        assert!(ev["firstTokenMs"].is_null());
        assert!(ev["startedAt"].is_null());
        assert_eq!(ev["inputTokens"], 5);
    }

    /// A-2：半路 attach 补的统计：没有起始时刻 → 时长字段全 null，
    /// 但 runId/model 必须齐全，否则客户端分不清「哪一轮」的总结。
    #[test]
    fn attached_run_stats_reports_nulls_but_keeps_identity() {
        let stats = RunStats::attached(
            "run-attach".into(),
            &json!({"provider": "anthropic", "id": "claude-sonnet-4-5"}),
        );
        assert!(stats.started_at.is_none());
        assert!(stats.first_token_at.is_none());
        let ev = stats.to_event(chrono::Utc::now(), false);
        assert_eq!(ev["type"], "run.summary");
        assert_eq!(ev["runId"], "run-attach");
        assert_eq!(ev["model"], "anthropic/claude-sonnet-4-5");
        assert!(ev["startedAt"].is_null());
        assert!(ev["durationMs"].is_null());
        assert!(ev["firstTokenMs"].is_null());
    }

    /// A-2：`get_state.isStreaming` 缺失或类型不对时按「没在跑」处理，
    /// 这样老版本 pi 不返回该字段时行为与引入本判断之前一致。
    #[test]
    fn state_streaming_reads_is_streaming_flag() {
        assert!(state_streaming(&json!({"isStreaming": true})));
        assert!(!state_streaming(&json!({"isStreaming": false})));
        assert!(!state_streaming(&json!({})));
        assert!(!state_streaming(&json!({"isStreaming": "true"})));
    }

    /// A-1：释放 writer 必须看「释放这一刻」的状态。
    /// 只有「本设备拿到了 writer」且「pi 没在跑」才能释放：
    /// 否则会把正在运行的会话 writer 放掉，另一台设备可以插进同一轮。
    #[test]
    fn writer_is_released_only_when_idle_and_owned() {
        assert!(SessionManager::should_release_writer(true, "idle"));
        assert!(
            !SessionManager::should_release_writer(true, "streaming"),
            "pi 已经开跑时必须保留 writer"
        );
        assert!(!SessionManager::should_release_writer(false, "idle"));
        assert!(!SessionManager::should_release_writer(false, "streaming"));
    }

    /// A-3：重试成功必须把 UI 从 `retrying` 拉回 `streaming`；
    /// 重试失败才是终态 error；`success` 缺失时保持沉默（协议外）。
    #[test]
    fn auto_retry_end_emits_streaming_on_success() {
        let ok = auto_retry_end_event(&json!({"type": "auto_retry_end", "success": true}))
            .expect("重试成功必须发事件");
        assert_eq!(ok["type"], "run.state");
        assert_eq!(ok["state"], "streaming");

        let bad = auto_retry_end_event(&json!({"success": false, "finalError": "boom"}))
            .expect("重试失败必须发事件");
        assert_eq!(bad["state"], "error");
        assert_eq!(bad["error"], "boom");

        assert!(auto_retry_end_event(&json!({})).is_none());
    }

    #[test]
    fn run_stats_cost_accepts_plain_number() {
        let mut stats = stats_for("run-4");
        stats.add_usage(&json!({
            "role": "assistant",
            "usage": {"input": 1, "output": 1, "cost": 0.25}
        }));
        assert!((stats.cost - 0.25).abs() < 1e-9);
    }

    fn image(blob: &str, size: u64) -> ImagePayload {
        ImagePayload {
            r#type: "image".into(),
            data: String::new(),
            blob_id: Some(blob.into()),
            mime_type: "image/png".into(),
            width: 0,
            height: 0,
            size,
        }
    }

    #[test]
    fn validate_images_accepts_ten_blob_images() {
        let images: Vec<_> = (0..10).map(|i| image(&format!("{i:064x}"), 1024)).collect();
        assert!(validate_images(&images).is_ok());
        let too_many: Vec<_> = (0..11).map(|i| image(&format!("{i:064x}"), 1024)).collect();
        assert!(validate_images(&too_many)
            .unwrap_err()
            .to_string()
            .contains("最多 10 张"));
    }

    #[test]
    fn validate_images_enforces_blob_size_and_missing_data() {
        let huge = pi_carry_protocol::MAX_IMAGE_BYTES as u64 + 1;
        let err = validate_images(&[image(&"a".repeat(64), huge)]).unwrap_err();
        assert!(err.to_string().contains("超过单图上限"), "{err}");

        let mut inline = image("", 0);
        inline.blob_id = None;
        let err = validate_images(&[inline]).unwrap_err();
        assert!(err.to_string().contains("缺少图片数据"), "{err}");
    }

    #[test]
    fn truncated_entry_salvages_blob_id_from_prefix() {
        let sha = "a".repeat(64);
        // 模拟被 8 MiB 截断在 data 中间的行：blobId/size/mimeType 已在 data 之前。
        let prefix = format!(
            "{{\"type\":\"message\",\"id\":\"m9\",\"parentId\":\"m8\",\"message\":{{\"role\":\"user\",\"content\":[{{\"type\":\"image\",\"blobId\":\"{sha}\",\"size\":12345,\"mimeType\":\"image/png\",\"data\":\"AAAA"
        );
        let entry = truncated_entry(&prefix).unwrap();
        let content = &entry["message"]["content"][0];
        assert_eq!(content["blobId"], json!(sha));
        assert_eq!(content["mimeType"], json!("image/png"));
        assert_eq!(content["size"], json!(12345));
        assert!(content.get("unavailable").is_none());
    }

    #[test]
    fn model_label_handles_string_and_object() {
        assert_eq!(model_label(&json!("openai/gpt-5")), "openai/gpt-5");
        assert_eq!(
            model_label(&json!({"provider": "openai", "id": "gpt-5"})),
            "openai/gpt-5"
        );
        assert_eq!(model_label(&json!({"id": "gpt-5"})), "gpt-5");
        assert_eq!(model_label(&json!({})), "");
    }
}
