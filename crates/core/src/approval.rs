//! 审批分类与等待队列。默认 fail-closed：无审批者、超时、断网都拒绝。

use crate::clock::UlidGen;
use crate::config::ApprovalsConfig;
use crate::error::{CoreError, Result};
use crate::store::{PendingApproval, Store};
use crate::IdGen;
use chrono::{DateTime, Duration, Utc};
use pi_carry_protocol::{events, ApprovalView};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::{broadcast, oneshot};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Risk {
    Low,
    Medium,
    High,
    Critical,
}

impl Risk {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Low => "low",
            Self::Medium => "medium",
            Self::High => "high",
            Self::Critical => "critical",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    Read,
    Write,
    Exec,
    Network,
    Other,
}

impl Category {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Read => "read",
            Self::Write => "write",
            Self::Exec => "exec",
            Self::Network => "network",
            Self::Other => "other",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Classification {
    pub category: Category,
    pub risk: Risk,
    pub summary: String,
    pub command: Option<String>,
    pub path: Option<String>,
}

const READ_TOOLS: &[&str] = &[
    "read",
    "ls",
    "list",
    "grep",
    "find",
    "glob",
    "search",
    "get_state",
    "get_messages",
    "get_session_stats",
    "get_entries",
    "get_tree",
    "get_commands",
];

const WRITE_TOOLS: &[&str] = &[
    "write",
    "edit",
    "patch",
    "apply_patch",
    "todo_write",
    "memory_write",
];

const NETWORK_TOOLS: &[&str] = &[
    "web_search",
    "fetch",
    "fetch_content",
    "source_check",
    "browser",
];

/// 只读 bash 命令前缀（仍按 medium 处理，不自动放行）。
const READONLY_COMMANDS: &[&str] = &[
    "ls",
    "pwd",
    "cat",
    "head",
    "tail",
    "rg",
    "grep",
    "find",
    "wc",
    "stat",
    "file",
    "tree",
    "git status",
    "git diff",
    "git log",
    "git show",
    "git branch",
    "node --version",
    "npm --version",
    "cargo --version",
    "rustc --version",
    "java -version",
    "echo",
    "which",
    "where",
    "type",
];

/// 高危命令片段。命中即 critical。
const DANGEROUS: &[&str] = &[
    "rm -rf",
    "rm -r",
    "del /",
    "format ",
    "mkfs",
    "shutdown",
    "reboot",
    "sudo ",
    "runas ",
    "git push --force",
    "git push -f",
    "git reset --hard",
    "git clean -f",
    "chmod 777",
    "curl | sh",
    "curl | bash",
    "wget | sh",
    "wget | bash",
    "invoke-expression",
    "iex ",
    "> /dev/sd",
    "dd if=",
    ":(){",
    "reg delete",
    "sc delete",
    "taskkill /f",
];

const WRITE_COMMANDS: &[&str] = &[
    "rm ",
    "mv ",
    "cp ",
    "mkdir ",
    "touch ",
    "chmod ",
    "chown ",
    "sed -i",
    "tee ",
    "truncate ",
    "git add",
    "git commit",
    "git merge",
    "git rebase",
    "git checkout",
    "git switch",
    "npm install",
    "npm i ",
    "cargo add",
    "pip install",
    "pip3 install",
];

/// pi 工具名 + 入参 → 分类。未知工具默认 high/other，宁可多问。
pub fn classify(tool_name: &str, input: &Value) -> Classification {
    let name = tool_name.to_ascii_lowercase();
    let command = input
        .get("command")
        .and_then(Value::as_str)
        .map(ToOwned::to_owned);
    let path = input
        .get("path")
        .or_else(|| input.get("file_path"))
        .or_else(|| input.get("filePath"))
        .and_then(Value::as_str)
        .map(ToOwned::to_owned);

    if name == "bash" || name == "shell" || name == "exec" {
        let cmd = command.clone().unwrap_or_default();
        let lower = cmd.to_ascii_lowercase();
        let dangerous = DANGEROUS.iter().any(|d| lower.contains(d));
        let readonly = READONLY_COMMANDS
            .iter()
            .any(|p| lower.trim_start().starts_with(p));
        let writes = WRITE_COMMANDS.iter().any(|p| lower.contains(p));
        let (risk, summary) = if dangerous {
            (
                Risk::Critical,
                format!("执行高危命令: {}", truncate(&cmd, 160)),
            )
        } else if writes {
            (
                Risk::High,
                format!("执行可能修改系统的命令: {}", truncate(&cmd, 160)),
            )
        } else if readonly {
            (
                Risk::Medium,
                format!("执行只读命令: {}", truncate(&cmd, 160)),
            )
        } else {
            (Risk::High, format!("执行命令: {}", truncate(&cmd, 160)))
        };
        return Classification {
            category: Category::Exec,
            risk,
            summary,
            command,
            path,
        };
    }

    if READ_TOOLS.contains(&name.as_str()) {
        return Classification {
            category: Category::Read,
            risk: Risk::Low,
            summary: format!("读取 {name}"),
            command,
            path,
        };
    }

    if WRITE_TOOLS.contains(&name.as_str()) {
        return Classification {
            category: Category::Write,
            risk: Risk::High,
            summary: match &path {
                Some(p) => format!("写入 {p}"),
                None => format!("写入文件 ({name})"),
            },
            command,
            path,
        };
    }

    if NETWORK_TOOLS.contains(&name.as_str()) {
        return Classification {
            category: Category::Network,
            risk: Risk::Medium,
            summary: format!("网络请求 ({name})"),
            command,
            path,
        };
    }

    Classification {
        category: Category::Other,
        risk: Risk::High,
        summary: format!("未识别工具 {name}，默认需要审批"),
        command,
        path,
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        let mut out: String = s.chars().take(n).collect();
        out.push('…');
        out
    }
}

/// 审批状态已落库并广播**之后**的审计写入。
///
/// 到这一步状态 CAS、waiter 唤醒、事件广播都已完成，结果对 pi 和客户端都已生效。
/// 若此时因审计表不可用而返回 Err，手机端会显示“审批失败”，而重试只会拿到
/// `ALREADY_RESOLVED`——比丢掉一条审计记录更容易误导人，所以只记 warn。
fn audit_or_warn(store: &Store, ts: &str, actor: &str, action: &str, detail: &Value) {
    if let Err(e) = store.audit(ts, actor, action, detail) {
        tracing::warn!(action, actor, error = %e, "审计写入失败，但审批变更已生效");
    }
}

/// 工具入参转成审批视图里的 `args` 字段。
///
/// 参数是给手机端审批卡展示的：太大会把控制帧预算吃满（1MiB），所以按序列化后的
/// 字符数截断。空对象/Null 保持原样，UI 据此决定是否展示“参数”块。
fn truncate_json(input: &Value, max_chars: usize) -> Value {
    if input.is_null() {
        return Value::Null;
    }
    let text = input.to_string();
    if text.chars().count() <= max_chars {
        return input.clone();
    }
    Value::String(truncate(&text, max_chars))
}

#[derive(Debug, Clone)]
pub struct ApprovalEvent {
    pub session_id: String,
    pub event: Value,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Decision {
    Allow { remember: bool },
    Deny,
}

struct Waiter {
    session_id: String,
    tx: oneshot::Sender<Decision>,
}

struct Inner {
    store: Store,
    config: ApprovalsConfig,
    clock: Arc<dyn crate::Clock>,
    ids: Arc<dyn IdGen>,
    waiters: Mutex<HashMap<String, Waiter>>,
    /// 用户在审批卡选择“本对话都允许”后，本进程内该会话的普通工具直接放行。
    session_allow: Mutex<HashSet<String>>,
    events: broadcast::Sender<ApprovalEvent>,
    seq: AtomicU64,
}

#[derive(Clone)]
pub struct ApprovalManager {
    inner: Arc<Inner>,
}

impl ApprovalManager {
    pub fn new(
        store: Store,
        config: ApprovalsConfig,
        clock: Arc<dyn crate::Clock>,
        ids: Arc<dyn crate::IdGen>,
        events: broadcast::Sender<ApprovalEvent>,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                store,
                config,
                clock,
                ids,
                waiters: Mutex::new(HashMap::new()),
                session_allow: Mutex::new(HashSet::new()),
                events,
                seq: AtomicU64::new(1),
            }),
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<ApprovalEvent> {
        self.inner.events.subscribe()
    }

    /// 创建审批并等待决定。自动放行 low risk（若配置开启）会立即返回 Allow。
    pub async fn request(
        &self,
        session_id: &str,
        tool_call_id: &str,
        tool_name: &str,
        input: &Value,
    ) -> Result<Decision> {
        let c = classify(tool_name, input);
        // “本对话都允许”只放行普通工具；critical 风险仍然逐次询问，避免一次授权放大成任意破坏。
        if c.risk != Risk::Critical
            && self
                .inner
                .session_allow
                .lock()
                .expect("session_allow poisoned")
                .contains(session_id)
        {
            let now = crate::rfc3339(self.inner.clock.now());
            self.inner.store.audit(
                &now,
                "system",
                "approval.auto_allow_session",
                &json!({"toolName": tool_name, "risk": c.risk.as_str()}),
            )?;
            return Ok(Decision::Allow { remember: true });
        }
        if self.inner.config.auto_allow_low_risk && c.risk == Risk::Low {
            let view = self.build_view(session_id, tool_name, &c, "allowed", 0);
            self.inner.store.audit(
                &view.requested_at,
                "system",
                "approval.auto_allow",
                &json!({
                    "approvalId": view.id,
                    "toolName": tool_name,
                    "risk": c.risk.as_str(),
                }),
            )?;
            return Ok(Decision::Allow { remember: false });
        }

        // 用户之前选择“记住此命令”时，同一会话的完全相同命令直接放行。
        if self.inner.config.allow_remember && c.category == Category::Exec {
            if let Some(cmd) = c.command.as_deref() {
                if self
                    .inner
                    .store
                    .has_remembered_rule(session_id, tool_name, cmd)?
                {
                    self.inner.store.audit(
                        &crate::rfc3339(self.inner.clock.now()),
                        "system",
                        "approval.auto_allow_remembered",
                        &json!({"toolName": tool_name, "pattern": cmd}),
                    )?;
                    return Ok(Decision::Allow { remember: true });
                }
            }
        }

        let timeout = self.inner.config.timeout_secs;
        let view = self.build_view_with_args(session_id, tool_name, &c, "pending", timeout, input);
        if self
            .inner
            .store
            .list_pending_approvals(Some(session_id))?
            .len()
            >= self.inner.config.max_pending
        {
            return Err(CoreError::InvalidArgument(format!(
                "会话 {session_id} 待审批数量已达上限 {}",
                self.inner.config.max_pending
            )));
        }
        // waiter 必须先于审批落库注册：其他设备可能一看到 approval.list 就 resolve，
        // 那时若 waiter 还不存在，resolve 找不到接收方，本请求会永久挂起。
        let (tx, rx) = oneshot::channel();
        self.inner.waiters.lock().expect("waiters poisoned").insert(
            view.id.clone(),
            Waiter {
                session_id: session_id.to_string(),
                tx,
            },
        );
        let pending = PendingApproval {
            view: view.clone(),
            tool_call_id: tool_call_id.to_string(),
            resolved_by: None,
        };
        if let Err(e) = self.inner.store.insert_approval(&pending) {
            self.drop_waiter(&view.id);
            return Err(e);
        }
        if let Err(e) = self.inner.store.audit(
            &view.requested_at,
            "pi",
            "approval.requested",
            &json!({
                "approvalId": view.id,
                "toolName": tool_name,
                "risk": c.risk.as_str(),
            }),
        ) {
            // 请求方拿不到结果，已落库的审批必须收尾：置为 denied 并撤掉 waiter，
            // 否则手机会挂一张无人等待的幽灵审批卡。
            let now = crate::rfc3339(self.inner.clock.now());
            let _ = self
                .inner
                .store
                .resolve_approval(&view.id, "denied", "system", &now, false);
            self.drop_waiter(&view.id);
            return Err(e);
        }
        let _ = self.inner.events.send(ApprovalEvent {
            session_id: session_id.to_string(),
            event: events::approval_requested(&view),
        });

        // 真实时间超时；测试用 expire_due 驱动，不依赖 sleep 精度。
        let inner = self.inner.clone();
        let id = view.id.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_secs(timeout)).await;
            if let Err(e) = expire_one(&inner, &id, "timeout") {
                tracing::warn!(approval_id = %id, error = %e, "审批超时处理失败");
            }
        });

        match rx.await {
            Ok(d) => Ok(d),
            Err(_) => Ok(Decision::Deny),
        }
    }

    fn drop_waiter(&self, id: &str) {
        self.inner
            .waiters
            .lock()
            .expect("waiters poisoned")
            .remove(id);
    }

    fn build_view(
        &self,
        session_id: &str,
        tool_name: &str,
        c: &Classification,
        state: &str,
        ttl_secs: u64,
    ) -> ApprovalView {
        self.build_view_with_args(session_id, tool_name, c, state, ttl_secs, &Value::Null)
    }

    fn build_view_with_args(
        &self,
        session_id: &str,
        tool_name: &str,
        c: &Classification,
        state: &str,
        ttl_secs: u64,
        input: &Value,
    ) -> ApprovalView {
        let now = self.inner.clock.now();
        // 进程内序号只用来保证同进程唯一；ULID 提供跨重启/跨进程熵。
        // 旧实现只用注入的 SeqIdGen（进程内计数），重启后第 N 条审批必然
        // 与永不清理的历史行撞 approvals 主键，插入失败导致工具调用被静默拒绝。
        let seq = self.inner.seq.fetch_add(1, Ordering::SeqCst);
        let id = format!("ap-{seq}-{}", UlidGen.next_id());
        ApprovalView {
            id,
            session_id: session_id.to_string(),
            tool_name: tool_name.to_string(),
            category: c.category.as_str().into(),
            risk: c.risk.as_str().into(),
            summary: c.summary.clone(),
            command: c.command.clone(),
            path: c.path.clone(),
            // 完整工具入参：卡片要能展示「到底要写哪个文件/跑什么参数」，
            // 只给 command 时写文件类审批会看不见目标内容（P3 群）。
            args: truncate_json(input, 4000),
            requested_at: crate::rfc3339(now),
            expires_at: crate::rfc3339(now + Duration::seconds(ttl_secs as i64)),
            remaining_seconds: ttl_secs,
            state: state.into(),
        }
    }

    pub fn resolve(
        &self,
        id: &str,
        decision: &str,
        by: &str,
        remember: bool,
    ) -> Result<ApprovalView> {
        let now = self.inner.clock.now();
        let state = match decision {
            "allow" | "allow_always" | "allowAlways" => "allowed",
            "deny" => "denied",
            other => {
                return Err(CoreError::InvalidArgument(format!(
                    "decision 必须是 allow/allow_always/deny，实际 {other}"
                )))
            }
        };
        let pending = self.inner.store.get_approval(id)?.ok_or_else(|| {
            CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalNotFound,
                format!("审批 {id} 不存在"),
            ))
        })?;
        if pending.view.state != "pending" {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalAlreadyResolved,
                format!("审批 {id} 已处于 {}", pending.view.state),
            )));
        }
        let expires = pending
            .view
            .expires_at
            .parse::<DateTime<Utc>>()
            .map_err(|e| CoreError::Internal(format!("审批过期时间非法: {e}")))?;
        if expires <= now {
            // 并发下可能已被过期扫描处理：无论如何都不放行。
            let _ = expire_one(&self.inner, id, "expired")?;
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalExpired,
                format!("审批 {id} 已过期"),
            )));
        }
        if !self
            .inner
            .store
            .resolve_approval(id, state, by, &crate::rfc3339(now), remember)?
        {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalAlreadyResolved,
                format!("审批 {id} 已被处理"),
            )));
        }
        // 只有 CAS 成功后才落“记住”规则：并发 deny 抢先时不能留下放行规则。
        // 规则是附加优化，写入失败不应回滚已生效的审批。
        if state == "allowed" && matches!(decision, "allow_always" | "allowAlways") {
            self.inner
                .session_allow
                .lock()
                .expect("session_allow poisoned")
                .insert(pending.view.session_id.clone());
        }
        if state == "allowed"
            && remember
            && self.inner.config.allow_remember
            && pending.view.category == "exec"
        {
            if let Some(cmd) = pending.view.command.as_deref() {
                if let Err(e) = self.inner.store.add_remembered_rule(
                    // 规则 id 也要有跨重启熵：规则表用 INSERT OR IGNORE，
                    // 撞主键会被默默丢成一条不生效的“记住”规则。
                    &format!("rule-{}-{}", self.inner.ids.next_id(), UlidGen.next_id()),
                    &pending.view.session_id,
                    &pending.view.tool_name,
                    cmd,
                    &crate::rfc3339(now),
                ) {
                    tracing::warn!(error = %e, approval = id, "记住审批规则失败");
                }
            }
        }
        if let Some(w) = self
            .inner
            .waiters
            .lock()
            .expect("waiters poisoned")
            .remove(id)
        {
            let d = if state == "allowed" {
                Decision::Allow {
                    remember: remember || matches!(decision, "allow_always" | "allowAlways"),
                }
            } else {
                Decision::Deny
            };
            let _ = w.tx.send(d);
        }
        let mut view = pending.view;
        view.state = state.into();
        view.remaining_seconds = 0;
        let _ = self.inner.events.send(ApprovalEvent {
            session_id: view.session_id.clone(),
            event: events::approval_resolved(id, state, by, false),
        });
        // 状态已提交并广播，审计失败不能再让调用方以为审批没生效（V-05）。
        audit_or_warn(
            &self.inner.store,
            &crate::rfc3339(now),
            by,
            "approval.resolved",
            &json!({
                "approvalId": id,
                "decision": state,
                "remember": remember,
                "toolName": view.tool_name,
            }),
        );
        Ok(view)
    }

    /// 延长审批有效期，最多到原到期时间 + max_extension。
    pub fn extend(&self, id: &str, seconds: u64) -> Result<ApprovalView> {
        let mut pending = self
            .inner
            .store
            .get_approval(id)?
            .ok_or_else(|| CoreError::InvalidArgument(format!("审批 {id} 不存在")))?;
        if pending.view.state != "pending" {
            return Err(CoreError::InvalidArgument(format!(
                "审批 {id} 不是 pending"
            )));
        }
        let now = self.inner.clock.now();
        // 已过期的记录不可续命：否则吊销的审批会被“延长”重新变成有效。
        if pending.view.expires_at.as_str() <= crate::rfc3339(now).as_str() {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalExpired,
                format!("审批 {id} 已过期，不可延长"),
            )));
        }
        let current = pending
            .view
            .expires_at
            .parse::<DateTime<Utc>>()
            .map_err(|e| CoreError::Internal(format!("审批过期时间非法: {e}")))?;
        let max = now + Duration::seconds(pi_carry_protocol::APPROVAL_MAX_EXTENSION_SEC as i64);
        // 上限统一取协议常量：写死 300 会在常量被调整时与实际可延长量不一致。
        let next = (current
            + Duration::seconds(seconds.min(pi_carry_protocol::APPROVAL_MAX_EXTENSION_SEC) as i64))
        .min(max);
        pending.view.expires_at = crate::rfc3339(next);
        let conn_view = pending.view.clone();
        // CAS 式的更新：只有仍然 pending 的记录才能延长。返回 false 说明审批
        // 在读→算→写 的窗口里已被解决（超时/其它设备/abort），此时绝不能
        // 广播一个“已延期”的旧视图，否则手机端会看到一个点击后永远失败的按钮。
        let updated = self
            .inner
            .store
            .update_approval_expiry(id, &conn_view.expires_at)?;
        if !updated {
            return Err(CoreError::Protocol(pi_carry_protocol::ProtocolError::new(
                pi_carry_protocol::ErrorCode::ApprovalExpired,
                format!("审批 {id} 已被解决，无法延长"),
            )));
        }
        let _ = self.inner.events.send(ApprovalEvent {
            session_id: conn_view.session_id.clone(),
            event: events::approval_updated(&conn_view),
        });
        Ok(conn_view)
    }

    pub fn pending(&self, session_id: Option<&str>) -> Result<Vec<ApprovalView>> {
        let now = self.inner.clock.now();
        let mut views = self.inner.store.list_pending_approvals(session_id)?;
        for v in &mut views {
            if let Ok(exp) = v.expires_at.parse::<DateTime<Utc>>() {
                v.remaining_seconds = (exp - now).num_seconds().max(0) as u64;
            }
        }
        Ok(views)
    }

    /// 供测试/定时任务驱动过期。
    /// 逐个走与 `resolve` 相同的顺序（先原子改状态，再通知 waiter），
    /// 并且每次都回 store 核对真实 expires_at，避免把已延长的审批误判为过期。
    pub fn expire_due(&self) -> Result<usize> {
        let now = crate::rfc3339(self.inner.clock.now());
        let ids: Vec<String> = self
            .inner
            .store
            .list_pending_approvals(None)?
            .into_iter()
            .filter(|v| v.expires_at.as_str() <= now.as_str())
            .map(|v| v.id)
            .collect();
        let mut expired = 0;
        for id in ids {
            if expire_one(&self.inner, &id, "timeout")? {
                expired += 1;
            }
        }
        Ok(expired)
    }

    pub fn config(&self) -> &ApprovalsConfig {
        &self.inner.config
    }

    /// 中止/关闭会话时调用：该会话所有 pending 审批置为 denied，
    /// 移除 waiter 并让等待的工具调用立即拿 Deny（fail-closed）。
    pub fn cancel_session(&self, session_id: &str) -> Result<usize> {
        let now = crate::rfc3339(self.inner.clock.now());
        let ids: Vec<String> = self
            .inner
            .store
            .list_pending_approvals(Some(session_id))?
            .into_iter()
            .map(|v| v.id)
            .collect();
        let mut cancelled = 0;
        for id in ids {
            if !self
                .inner
                .store
                .resolve_approval(&id, "denied", "system", &now, false)?
            {
                // 并发方已先一步处理并负责通知 waiter。
                continue;
            }
            cancelled += 1;
            if let Some(w) = self
                .inner
                .waiters
                .lock()
                .expect("waiters poisoned")
                .remove(&id)
            {
                let _ = w.tx.send(Decision::Deny);
            }
            let _ = self.inner.events.send(ApprovalEvent {
                session_id: session_id.to_string(),
                event: events::approval_resolved(&id, "denied", "system", true),
            });
            // 同上：会话已取消完毕，审计失败不能把整个取消操作变成报错。
            audit_or_warn(
                &self.inner.store,
                &now,
                "system",
                "approval.cancelled",
                &json!({"approvalId": id, "reason": "session_cancelled"}),
            );
        }
        Ok(cancelled)
    }
}

/// 把一条确实到期的审批置为 expired；返回是否由本次调用完成了状态迁移。
/// 顺序与 `resolve` 一致：先做 store 的原子 CAS，成功者再移除 waiter 并发送决定，
/// 避免手机收到 allowed 而工具收到 denied 的 fail-closed 窗口。
fn expire_one(inner: &Arc<Inner>, id: &str, by: &str) -> Result<bool> {
    let now = crate::rfc3339(inner.clock.now());
    // 可能已被批准、拒绝或延长：只有仍 pending 且确实到期才处理。
    let Some(pending) = inner.store.get_approval(id)? else {
        return Ok(false);
    };
    if pending.view.state != "pending" || pending.view.expires_at.as_str() > now.as_str() {
        return Ok(false);
    }
    if !inner
        .store
        .resolve_approval(id, "expired", by, &now, false)?
    {
        // 并发方（手机审批或另一次过期扫描）已完成迁移并负责通知 waiter。
        return Ok(false);
    }
    let waiter = inner.waiters.lock().expect("waiters poisoned").remove(id);
    let session_id = waiter
        .as_ref()
        .map(|w| w.session_id.clone())
        .unwrap_or_else(|| pending.view.session_id.clone());
    if let Some(w) = waiter {
        let _ = w.tx.send(Decision::Deny);
    }
    let _ = inner.events.send(ApprovalEvent {
        session_id,
        event: events::approval_resolved(id, "expired", by, true),
    });
    // 到期已落库、waiter 已收到 Deny，审计失败只能记 warn（V-05）。
    audit_or_warn(
        &inner.store,
        &now,
        by,
        "approval.expired",
        &json!({"approvalId": id}),
    );
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::clock::Clock;
    use crate::{Config, FixedClock, SeqIdGen, SystemClock};
    use chrono::TimeZone;

    fn setup() -> (ApprovalManager, Store, FixedClock) {
        let store = Store::open_memory().unwrap();
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());
        let (tx, _) = broadcast::channel(64);
        let mgr = ApprovalManager::new(
            store.clone(),
            Config::default().approvals,
            Arc::new(clock.clone()),
            Arc::new(SeqIdGen::new()),
            tx,
        );
        (mgr, store, clock)
    }

    #[test]
    fn classify_known_and_unknown() {
        assert_eq!(
            classify("bash", &json!({"command": "cargo test"})).risk,
            Risk::High
        );
        assert_eq!(
            classify("bash", &json!({"command": "rm -rf /"})).risk,
            Risk::Critical
        );
        assert_eq!(
            classify("bash", &json!({"command": "git status"})).risk,
            Risk::Medium
        );
        assert_eq!(classify("read", &json!({"path": "a"})).risk, Risk::Low);
        assert_eq!(classify("write", &json!({"path": "a"})).risk, Risk::High);
        assert_eq!(classify("mystery", &json!({})).risk, Risk::High);
    }

    #[tokio::test]
    async fn resolve_allow_unblocks_waiter() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let pending = mgr.pending(Some("s1")).unwrap();
        assert_eq!(pending.len(), 1);
        mgr.resolve(&pending[0].id, "allow", "phone", false)
            .unwrap();
        assert_eq!(h.await.unwrap(), Decision::Allow { remember: false });
    }

    /// V-05：提交之后的审计失败不能把已经生效的审批变成报错。
    ///
    /// 故障注入：另开一条连接 DROP 掉 audit 表，让 `store.audit` 必然失败。
    /// 此时状态 CAS、waiter 唤醒、事件广播都已完成，若仍返回 Err，
    /// 手机端会显示失败而重试只会得到 ALREADY_RESOLVED。
    #[tokio::test]
    async fn resolve_succeeds_when_audit_write_fails() {
        let tmp = tempfile::tempdir().unwrap();
        let db = tmp.path().join("state.db");
        let store = Store::open(&db).unwrap();
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());
        let (tx, _) = broadcast::channel(64);
        let mgr = ApprovalManager::new(
            store.clone(),
            Config::default().approvals,
            Arc::new(clock),
            Arc::new(SeqIdGen::new()),
            tx,
        );
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        let id = loop {
            if let Some(v) = store.list_pending_approvals(Some("s1")).unwrap().first() {
                break v.id.clone();
            }
            tokio::task::yield_now().await;
        };
        rusqlite::Connection::open(&db)
            .unwrap()
            .execute_batch("DROP TABLE audit;")
            .unwrap();
        let view = mgr
            .resolve(&id, "allow", "phone", false)
            .expect("审计失败不应把已生效的审批变成报错");
        assert_eq!(view.state, "allowed");
        assert_eq!(
            h.await.unwrap(),
            Decision::Allow { remember: false },
            "审计失败也必须唤醒 pi 侧的 waiter"
        );
    }

    #[tokio::test]
    async fn resolve_immediately_after_visible_never_hangs() {
        // 审批一旦在库里可见，其他设备就可能立刻 resolve。
        let (mgr, store, _clock) = setup();
        for i in 0..5 {
            let mgr2 = mgr.clone();
            let h = tokio::spawn(async move {
                mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                    .await
                    .unwrap()
            });
            let id = loop {
                if let Some(v) = store.list_pending_approvals(Some("s1")).unwrap().first() {
                    break v.id.clone();
                }
                tokio::task::yield_now().await;
            };
            mgr.resolve(&id, "allow", "phone", false).unwrap();
            let d = tokio::time::timeout(std::time::Duration::from_secs(2), h)
                .await
                .expect("审批可见后立即 resolve 必须唤醒请求方")
                .unwrap();
            assert_eq!(d, Decision::Allow { remember: false }, "round {i}");
        }
    }

    #[tokio::test]
    async fn remembered_exact_command_is_auto_allowed() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
        mgr.resolve(&id, "allow", "phone", true).unwrap();
        assert_eq!(h.await.unwrap(), Decision::Allow { remember: true });

        let d = mgr
            .request("s1", "tc2", "bash", &json!({"command": "cargo test"}))
            .await
            .unwrap();
        assert_eq!(d, Decision::Allow { remember: true });
        assert_eq!(mgr.pending(Some("s1")).unwrap().len(), 0);
    }

    #[tokio::test]
    async fn allow_always_unblocks_and_allows_future_tools() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
        mgr.resolve(&id, "allow_always", "phone", false).unwrap();
        assert_eq!(h.await.unwrap(), Decision::Allow { remember: true });
        // 本对话普通命令放行，critical 仍需逐次确认。
        let d = mgr
            .request("s1", "tc2", "bash", &json!({"command": "cargo test"}))
            .await
            .unwrap();
        assert_eq!(d, Decision::Allow { remember: true });
    }

    #[tokio::test]
    async fn allow_always_does_not_auto_allow_critical() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
        mgr.resolve(&id, "allow_always", "phone", false).unwrap();
        assert_eq!(h.await.unwrap(), Decision::Allow { remember: true });

        let mgr2 = mgr.clone();
        let dangerous = tokio::spawn(async move {
            mgr2.request("s1", "tc2", "bash", &json!({"command": "rm -rf build"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let pending = mgr.pending(Some("s1")).unwrap();
        assert_eq!(pending.len(), 1, "critical 命令必须继续询问");
        assert_eq!(pending[0].risk, "critical");
        mgr.resolve(&pending[0].id, "deny", "phone", false).unwrap();
        assert_eq!(dangerous.await.unwrap(), Decision::Deny);
    }

    #[tokio::test]
    async fn deny_and_second_resolve_fails() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "write", &json!({"path": "a.txt"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
        mgr.resolve(&id, "deny", "phone", false).unwrap();
        assert_eq!(h.await.unwrap(), Decision::Deny);
        assert!(mgr.resolve(&id, "allow", "phone", false).is_err());
    }

    #[tokio::test]
    async fn expired_approval_is_denied() {
        let (mgr, _store, clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        clock.advance_secs(121);
        assert_eq!(mgr.expire_due().unwrap(), 1);
        assert_eq!(h.await.unwrap(), Decision::Deny);
    }

    #[tokio::test]
    async fn low_risk_auto_allow_when_enabled() {
        let store = Store::open_memory().unwrap();
        let clock = FixedClock::new(Utc::now());
        let (tx, _) = broadcast::channel(8);
        let mut cfg = Config::default().approvals;
        cfg.auto_allow_low_risk = true;
        let mgr = ApprovalManager::new(store, cfg, Arc::new(clock), Arc::new(SeqIdGen::new()), tx);
        let d = mgr
            .request("s1", "tc1", "read", &json!({"path": "a"}))
            .await
            .unwrap();
        assert_eq!(d, Decision::Allow { remember: false });
        assert_eq!(mgr.pending(None).unwrap().len(), 0);
    }

    #[test]
    fn extend_capped() {
        let (mgr, _store, _clock) = setup();
        // 同步创建 pending：直接构造一个请求会等待，改用内部 store 插入。
        let view = ApprovalView {
            id: "a1".into(),
            session_id: "s1".into(),
            tool_name: "bash".into(),
            category: "exec".into(),
            risk: "high".into(),
            summary: "x".into(),
            command: Some("cargo test".into()),
            path: None,
            args: json!({}),
            requested_at: "2026-11-19T08:00:00.000Z".into(),
            expires_at: "2026-11-19T08:02:00.000Z".into(),
            remaining_seconds: 120,
            state: "pending".into(),
        };
        _store
            .insert_approval(&PendingApproval {
                view,
                tool_call_id: "tc1".into(),
                resolved_by: None,
            })
            .unwrap();
        let v = mgr.extend("a1", 300).unwrap();
        assert!(v.expires_at.as_str() <= "2026-11-19T08:07:00.000Z");
    }

    fn insert_pending(store: &Store, id: &str, expires_at: &str) {
        let view = ApprovalView {
            id: id.into(),
            session_id: "s1".into(),
            tool_name: "bash".into(),
            category: "exec".into(),
            risk: "high".into(),
            summary: "x".into(),
            command: Some("cargo test".into()),
            path: None,
            args: json!({}),
            requested_at: "2026-11-19T08:00:00.000Z".into(),
            expires_at: expires_at.into(),
            remaining_seconds: 120,
            state: "pending".into(),
        };
        store
            .insert_approval(&PendingApproval {
                view,
                tool_call_id: "tc1".into(),
                resolved_by: None,
            })
            .unwrap();
    }

    #[test]
    fn extend_rejects_expired_approval() {
        let (mgr, store, _clock) = setup();
        insert_pending(&store, "a1", "2026-11-19T08:00:00.000Z");
        let err = mgr.extend("a1", 60).unwrap_err();
        assert_eq!(err.code(), pi_carry_protocol::ErrorCode::ApprovalExpired);
        // 已过期记录不可续命。
        let pending = store.get_approval("a1").unwrap().unwrap();
        assert_eq!(pending.view.expires_at, "2026-11-19T08:00:00.000Z");
    }

    #[test]
    fn expire_due_skips_extended_approval() {
        let (mgr, store, clock) = setup();
        insert_pending(&store, "a1", "2026-11-19T08:00:10.000Z");
        mgr.extend("a1", 300).unwrap();
        clock.advance_secs(20);
        assert_eq!(mgr.expire_due().unwrap(), 0);
        let pending = store.get_approval("a1").unwrap().unwrap();
        assert_eq!(pending.view.state, "pending");
    }

    #[test]
    fn expire_due_denies_genuinely_expired_without_waiter() {
        let (mgr, store, clock) = setup();
        insert_pending(&store, "a1", "2026-11-19T08:00:05.000Z");
        clock.advance_secs(10);
        assert_eq!(mgr.expire_due().unwrap(), 1);
        assert_eq!(
            store.get_approval("a1").unwrap().unwrap().view.state,
            "expired"
        );
        assert!(mgr.extend("a1", 60).is_err(), "已过期后不可再延长");
    }

    #[test]
    fn system_clock_is_available() {
        let _ = SystemClock.now();
    }

    #[tokio::test]
    async fn approval_ids_survive_restart_without_pk_collision() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("carry.db");
        let clock = FixedClock::new(Utc.with_ymd_and_hms(2026, 11, 19, 8, 0, 0).unwrap());

        for round in 0..2 {
            // 每次重启 SeqIdGen 从 1 重新计数；approvals 表从不删行，
            // 所以 id 必须自带跨重启熵，否则第二轮必撞主键。
            let store = Store::open(&path).unwrap();
            let (tx, _) = broadcast::channel(8);
            let mgr = ApprovalManager::new(
                store.clone(),
                Config::default().approvals,
                Arc::new(clock.clone()),
                Arc::new(SeqIdGen::new()),
                tx,
            );
            let mgr2 = mgr.clone();
            let h = tokio::spawn(async move {
                mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                    .await
                    .unwrap()
            });
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
            assert_eq!(mgr.pending(Some("s1")).unwrap().len(), 1, "round {round}");
            mgr.resolve(&id, "allow", "phone", false).unwrap();
            assert_eq!(h.await.unwrap(), Decision::Allow { remember: false });
        }
    }

    #[tokio::test]
    async fn cancel_session_denies_pending_and_wakes_waiter() {
        let (mgr, store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        assert_eq!(mgr.cancel_session("s1").unwrap(), 1);
        // fail-closed：会话没了，等待中的工具调用必须立刻拿到 Deny 而不是挂死。
        assert_eq!(h.await.unwrap(), Decision::Deny);
        assert_eq!(mgr.pending(Some("s1")).unwrap().len(), 0);
        let id = store.list_pending_approvals(None).unwrap();
        assert!(id.is_empty());
    }

    #[tokio::test]
    async fn cancel_session_ignores_already_resolved() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request("s1", "tc1", "bash", &json!({"command": "cargo test"}))
                .await
                .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(30)).await;
        let id = mgr.pending(Some("s1")).unwrap()[0].id.clone();
        mgr.resolve(&id, "allow", "phone", false).unwrap();
        assert_eq!(h.await.unwrap(), Decision::Allow { remember: false });
        // 已解决的审批不能再被 cancel 覆写（CAS 失败直接跳过）。
        assert_eq!(mgr.cancel_session("s1").unwrap(), 0);
    }

    /// 审批视图必须带上去敏后的工具入参：写文件类审批要能看到目标路径与内容，
    /// 否则手机端的「待审批」卡片只有一句「写入文件」无从判断（P3 群）。
    ///
    /// 必须用 `#[tokio::test]`：`request()` 在内部 spawn 并依赖调度器推进到
    /// 事件发送点，`std::thread::sleep` 会阻塞当前线程导致 pending 一直为空。
    #[tokio::test]
    async fn approval_view_carries_tool_args() {
        let (mgr, _store, _clock) = setup();
        let mgr2 = mgr.clone();
        let h = tokio::spawn(async move {
            mgr2.request(
                "s1",
                "tc-args",
                "write",
                &json!({"path": "src/main.rs", "content": "fn main() {}"}),
            )
            .await
            .unwrap()
        });
        tokio::time::sleep(std::time::Duration::from_millis(60)).await;
        let view = mgr.pending(Some("s1")).unwrap().remove(0);
        assert_eq!(view.args["path"], json!("src/main.rs"));
        mgr.resolve(&view.id, "deny", "test", false).unwrap();
        let _ = h.await;
    }

    /// 参数过大时必须截断成字符串：审批帧有 1MiB 上限，整份文件内容塞不进去。
    #[test]
    fn oversized_args_are_truncated_to_text() {
        let big = "x".repeat(9000);
        let v = truncate_json(&json!({ "content": big }), 100);
        assert!(v.is_string(), "超长入参应降级为字符串，实际 {v}");
        assert!(v.as_str().unwrap().chars().count() <= 101);
        // 小入参保持结构化：UI 可以按字段渲染。
        let small = truncate_json(&json!({ "path": "a.txt" }), 100);
        assert_eq!(small["path"], json!("a.txt"));
        // Null 保持 Null：UI 据此隐藏参数块。
        assert!(truncate_json(&Value::Null, 100).is_null());
    }
}
