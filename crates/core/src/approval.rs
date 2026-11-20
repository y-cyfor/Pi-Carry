//! 审批分类与等待队列。默认 fail-closed：无审批者、超时、断网都拒绝。

use crate::config::ApprovalsConfig;
use crate::error::{CoreError, Result};
use crate::store::{PendingApproval, Store};
use chrono::{DateTime, Duration, Utc};
use pi_carry_protocol::{events, ApprovalView};
use serde_json::{json, Value};
use std::collections::HashMap;
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
    ids: Arc<dyn crate::IdGen>,
    waiters: Mutex<HashMap<String, Waiter>>,
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
        let view = self.build_view(session_id, tool_name, &c, "pending", timeout);
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
        let pending = PendingApproval {
            view: view.clone(),
            tool_call_id: tool_call_id.to_string(),
            resolved_by: None,
        };
        self.inner.store.insert_approval(&pending)?;
        let (tx, rx) = oneshot::channel();
        self.inner.waiters.lock().expect("waiters poisoned").insert(
            view.id.clone(),
            Waiter {
                session_id: session_id.to_string(),
                tx,
            },
        );
        let _ = self.inner.events.send(ApprovalEvent {
            session_id: session_id.to_string(),
            event: events::approval_requested(&view),
        });
        self.inner.store.audit(
            &view.requested_at,
            "pi",
            "approval.requested",
            &json!({
                "approvalId": view.id,
                "toolName": tool_name,
                "risk": c.risk.as_str(),
            }),
        )?;

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

    fn build_view(
        &self,
        session_id: &str,
        tool_name: &str,
        c: &Classification,
        state: &str,
        ttl_secs: u64,
    ) -> ApprovalView {
        let now = self.inner.clock.now();
        let seq = self.inner.seq.fetch_add(1, Ordering::SeqCst);
        let uid = self.inner.ids.next_id();
        let short = &uid[..uid.len().min(8)];
        let id = format!("ap-{seq}-{short}");
        ApprovalView {
            id,
            session_id: session_id.to_string(),
            tool_name: tool_name.to_string(),
            category: c.category.as_str().into(),
            risk: c.risk.as_str().into(),
            summary: c.summary.clone(),
            command: c.command.clone(),
            path: c.path.clone(),
            args: Value::Null,
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
        if state == "allowed"
            && remember
            && self.inner.config.allow_remember
            && pending.view.category == "exec"
        {
            if let Some(cmd) = pending.view.command.as_deref() {
                if let Err(e) = self.inner.store.add_remembered_rule(
                    &self.inner.ids.next_id(),
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
                Decision::Allow { remember }
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
        self.inner.store.audit(
            &crate::rfc3339(now),
            by,
            "approval.resolved",
            &json!({
                "approvalId": id,
                "decision": state,
                "remember": remember,
                "toolName": view.tool_name,
            }),
        )?;
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
        let next = (current + Duration::seconds(seconds.min(300) as i64)).min(max);
        pending.view.expires_at = crate::rfc3339(next);
        let conn_view = pending.view.clone();
        // 直接更新 expires_at：Store 只暴露 resolve；这里用 SQL 通过新方法完成。
        self.inner
            .store
            .update_approval_expiry(id, &conn_view.expires_at)?;
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
    inner
        .store
        .audit(&now, by, "approval.expired", &json!({"approvalId": id}))?;
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
}
