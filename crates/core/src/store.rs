//! SQLite 持久化：设备、会话索引、journal、审批、审计、幂等表。
//!
//! 所有写入串行化在一个连接上；journal 的 seq 分配在事务内完成。

use crate::error::Result;
use chrono::{DateTime, Duration, Utc};
use pi_carry_protocol::{ApprovalView, SessionSummary};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use serde_json::Value;
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex};

const SCHEMA_VERSION: i64 = 1;

/// device_acks 设备被吊销后的保留天数（表内无时间列，只能按设备吊销时间推算）。
const DEVICE_ACK_RETENTION_DAYS: i64 = 30;

#[derive(Debug, Clone)]
pub struct Store {
    conn: Arc<Mutex<Connection>>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct DeviceRecord {
    pub id: String,
    pub name: String,
    pub platform: String,
    pub device_class: String,
    pub capability: String,
    pub paired_at: String,
    pub last_seen: String,
    pub revoked_at: Option<String>,
    pub online: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PendingApproval {
    pub view: ApprovalView,
    pub tool_call_id: String,
    pub resolved_by: Option<String>,
}

/// [`Store::prune_old_records`] 各表的删除行数。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PruneReport {
    pub approvals: usize,
    pub outbox_seen: usize,
    pub device_acks: usize,
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        // 桌面 UI 轮询等第二进程会同一时间写同一个库文件，没有 busy_timeout
        // 时并发写直接返回 SQLITE_BUSY 而不是等待重试。
        conn.pragma_update(None, "busy_timeout", 5_000)?;
        let store = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        store.migrate()?;
        Ok(store)
    }

    pub fn open_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        let store = Self {
            conn: Arc::new(Mutex::new(conn)),
        };
        store.migrate()?;
        Ok(store)
    }

    fn migrate(&self) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if version > SCHEMA_VERSION {
            return Err(crate::error::CoreError::Internal(format!(
                "数据库版本 {version} 高于支持版本 {SCHEMA_VERSION}"
            )));
        }
        if version < 1 {
            conn.execute_batch(
                r#"
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    platform TEXT NOT NULL,
                    device_class TEXT NOT NULL DEFAULT 'phone',
                    capability TEXT NOT NULL DEFAULT 'control',
                    paired_at TEXT NOT NULL,
                    last_seen TEXT NOT NULL,
                    revoked_at TEXT,
                    online INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    file_path TEXT NOT NULL,
                    cwd TEXT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    updated_at TEXT NOT NULL,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    preview TEXT NOT NULL DEFAULT '',
                    parent_id TEXT,
                    source TEXT NOT NULL DEFAULT 'local'
                );
                CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at DESC);
                CREATE TABLE IF NOT EXISTS journal (
                    session_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    ts TEXT NOT NULL,
                    event_json TEXT NOT NULL,
                    PRIMARY KEY (session_id, seq)
                );
                CREATE TABLE IF NOT EXISTS approvals (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    tool_call_id TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    category TEXT NOT NULL,
                    risk TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    command TEXT,
                    path TEXT,
                    args_json TEXT NOT NULL,
                    requested_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    state TEXT NOT NULL,
                    resolved_by TEXT,
                    resolved_at TEXT,
                    remember INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS idx_approvals_pending ON approvals(state, expires_at);
                CREATE TABLE IF NOT EXISTS audit (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    action TEXT NOT NULL,
                    detail_json TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS outbox_seen (
                    device_id TEXT NOT NULL,
                    client_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (device_id, client_id)
                );
                CREATE TABLE IF NOT EXISTS device_acks (
                    device_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    PRIMARY KEY (device_id, session_id)
                );
                CREATE TABLE IF NOT EXISTS remembered_rules (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    tool_name TEXT NOT NULL,
                    pattern TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                PRAGMA user_version = 1;
                "#,
            )?;
        }
        Ok(())
    }

    pub fn schema_version(&self) -> Result<i64> {
        let conn = self.conn.lock().expect("db poisoned");
        Ok(conn.query_row("PRAGMA user_version", [], |r| r.get(0))?)
    }

    // ---------- 设备 ----------

    pub fn upsert_device(&self, d: &DeviceRecord) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            r#"INSERT INTO devices(id,name,platform,device_class,capability,paired_at,last_seen,revoked_at,online)
               VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)
               ON CONFLICT(id) DO UPDATE SET name=excluded.name, platform=excluded.platform,
                 device_class=excluded.device_class, capability=excluded.capability,
                 last_seen=excluded.last_seen, revoked_at=excluded.revoked_at, online=excluded.online"#,
            params![
                d.id,
                d.name,
                d.platform,
                d.device_class,
                d.capability,
                d.paired_at,
                d.last_seen,
                d.revoked_at,
                d.online as i64
            ],
        )?;
        Ok(())
    }

    pub fn get_device(&self, id: &str) -> Result<Option<DeviceRecord>> {
        let conn = self.conn.lock().expect("db poisoned");
        let v = conn
            .query_row(
                "SELECT id,name,platform,device_class,capability,paired_at,last_seen,revoked_at,online FROM devices WHERE id=?1",
                params![id],
                row_to_device,
            )
            .optional()?;
        Ok(v)
    }

    pub fn list_devices(&self) -> Result<Vec<DeviceRecord>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut stmt = conn.prepare(
            "SELECT id,name,platform,device_class,capability,paired_at,last_seen,revoked_at,online FROM devices ORDER BY paired_at DESC",
        )?;
        let rows = stmt.query_map([], row_to_device)?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub fn revoke_device(&self, id: &str, at: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "UPDATE devices SET revoked_at=?2, online=0 WHERE id=?1 AND revoked_at IS NULL",
            params![id, at],
        )?;
        Ok(n > 0)
    }

    pub fn set_capability(&self, id: &str, capability: &str) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "UPDATE devices SET capability=?2 WHERE id=?1",
            params![id, capability],
        )?;
        Ok(())
    }

    pub fn touch_device(&self, id: &str, at: &str, online: bool) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "UPDATE devices SET last_seen=?2, online=?3 WHERE id=?1",
            params![id, at, online as i64],
        )?;
        Ok(())
    }

    /// 进程重启后不可能还有已建立的连接，先把持久化的在线标记清零。
    pub fn mark_all_devices_offline(&self) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute("UPDATE devices SET online=0", [])?;
        Ok(())
    }

    // ---------- 会话索引 ----------

    pub fn upsert_session(&self, s: &SessionSummary, file_path: &str) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            r#"INSERT INTO sessions(id,file_path,cwd,name,updated_at,message_count,preview,parent_id,source)
               VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9)
               ON CONFLICT(id) DO UPDATE SET file_path=excluded.file_path, cwd=excluded.cwd,
                 name=excluded.name, updated_at=excluded.updated_at,
                 message_count=excluded.message_count, preview=excluded.preview"#,
            params![
                s.id,
                file_path,
                s.cwd,
                s.name,
                s.updated_at,
                s.message_count as i64,
                s.preview,
                s.parent_id,
                s.last_source
            ],
        )?;
        Ok(())
    }

    pub fn list_sessions(&self, limit: usize) -> Result<Vec<(SessionSummary, String)>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut stmt = conn.prepare(
            "SELECT id,cwd,name,preview,updated_at,message_count,parent_id,source,file_path FROM sessions ORDER BY updated_at DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map(params![limit as i64], |r| {
            let id: String = r.get(0)?;
            let source: String = r.get(7)?;
            let summary = SessionSummary {
                id,
                cwd: r.get(1)?,
                name: r.get(2)?,
                preview: r.get(3)?,
                updated_at: r.get(4)?,
                message_count: r.get::<_, i64>(5)? as u64,
                status: "idle".into(),
                active: false,
                pending_approvals: 0,
                last_source: source,
                writable: true,
                parent_id: r.get(6)?,
            };
            Ok((summary, r.get::<_, String>(8)?))
        })?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    // ---------- journal ----------

    pub fn append_event(&self, session_id: &str, ts: &str, event: &Value) -> Result<u64> {
        let mut conn = self.conn.lock().expect("db poisoned");
        // 用 IMMEDIATE 抢先拿写锁：deferred 事务里先 SELECT MAX(seq) 再 INSERT，
        // 两个进程（例如桌面壳重启时新旧 Bridge 短暂共存）会读到同一个 seq，
        // 后提交者直接撞主键把事件丢掉。写锁一拿到就把「读+写」变成一个原子步。
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let next: i64 = tx.query_row(
            "SELECT COALESCE(MAX(seq),0)+1 FROM journal WHERE session_id=?1",
            params![session_id],
            |r| r.get(0),
        )?;
        tx.execute(
            "INSERT INTO journal(session_id,seq,ts,event_json) VALUES(?1,?2,?3,?4)",
            params![session_id, next, ts, serde_json::to_string(event)?],
        )?;
        tx.commit()?;
        Ok(next as u64)
    }

    pub fn last_seq(&self, session_id: &str) -> Result<u64> {
        let conn = self.conn.lock().expect("db poisoned");
        let seq: i64 = conn.query_row(
            "SELECT COALESCE(MAX(seq),0) FROM journal WHERE session_id=?1",
            params![session_id],
            |r| r.get(0),
        )?;
        Ok(seq as u64)
    }

    /// 返回某个会话 journal 的 (最小 seq, 最大 seq)；空 journal 返回 (0,0)。
    pub fn seq_bounds(&self, session_id: &str) -> Result<(u64, u64)> {
        let conn = self.conn.lock().expect("db poisoned");
        let (min, max): (i64, i64) = conn.query_row(
            "SELECT COALESCE(MIN(seq),0), COALESCE(MAX(seq),0) FROM journal WHERE session_id=?1",
            params![session_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        Ok((min as u64, max as u64))
    }

    pub fn events_after(
        &self,
        session_id: &str,
        after_seq: u64,
        limit: usize,
    ) -> Result<Vec<(u64, String, Value)>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut stmt = conn.prepare(
            "SELECT seq,ts,event_json FROM journal WHERE session_id=?1 AND seq>?2 ORDER BY seq ASC LIMIT ?3",
        )?;
        let rows = stmt.query_map(params![session_id, after_seq as i64, limit as i64], |r| {
            let seq: i64 = r.get(0)?;
            let ts: String = r.get(1)?;
            let raw: String = r.get(2)?;
            let event: Value = serde_json::from_str(&raw).map_err(|e| {
                rusqlite::Error::FromSqlConversionFailure(
                    2,
                    rusqlite::types::Type::Text,
                    Box::new(e),
                )
            })?;
            Ok((seq as u64, ts, event))
        })?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub fn journal_count(&self, session_id: &str) -> Result<u64> {
        let conn = self.conn.lock().expect("db poisoned");
        let n: i64 = conn.query_row(
            "SELECT COUNT(*) FROM journal WHERE session_id=?1",
            params![session_id],
            |r| r.get(0),
        )?;
        Ok(n as u64)
    }

    /// 保留最近 max_events 条；返回删除数。
    /// max_events 为 0 时仍然保留最后一条：清空会让 MAX(seq) 归零，
    /// 之后客户端已确认的水位会和新事件 seq 冲突。
    pub fn prune_journal(&self, session_id: &str, max_events: u32) -> Result<usize> {
        let keep = max_events.max(1) as i64;
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "DELETE FROM journal WHERE session_id=?1 AND seq <= (
                SELECT COALESCE(MAX(seq),0)-?2 FROM journal WHERE session_id=?1
             )",
            params![session_id, keep],
        )?;
        Ok(n)
    }

    /// journal 中出现过事件的会话 id（只查索引，不读会话文件）。
    pub fn journal_session_ids(&self) -> Result<Vec<String>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut stmt = conn.prepare("SELECT DISTINCT session_id FROM journal")?;
        let rows = stmt.query_map([], |r| r.get::<_, String>(0))?;
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    /// 删除 ts 早于 cutoff 的 journal 事件；每个会话至少保留最后一条，
    /// 避免 MAX(seq) 归零导致新事件序号与客户端水位冲突。
    pub fn prune_journal_before(&self, cutoff: &str) -> Result<usize> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "DELETE FROM journal WHERE ts < ?1 AND seq < (
                SELECT MAX(seq) FROM journal j2 WHERE j2.session_id = journal.session_id
             )",
            params![cutoff],
        )?;
        Ok(n)
    }

    // ---------- 审批 ----------

    pub fn insert_approval(&self, p: &PendingApproval) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        let v = &p.view;
        conn.execute(
            r#"INSERT INTO approvals(id,session_id,tool_call_id,tool_name,category,risk,summary,command,path,args_json,requested_at,expires_at,state,resolved_by,resolved_at,remember)
               VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,NULL,0)"#,
            params![
                v.id,
                v.session_id,
                p.tool_call_id,
                v.tool_name,
                v.category,
                v.risk,
                v.summary,
                v.command,
                v.path,
                serde_json::to_string(&v.args)?,
                v.requested_at,
                v.expires_at,
                v.state,
                p.resolved_by
            ],
        )
        .map_err(|e| {
            crate::error::CoreError::Internal(format!("写入审批 {} 失败: {e}", v.id))
        })?;
        Ok(())
    }

    pub fn resolve_approval(
        &self,
        id: &str,
        state: &str,
        by: &str,
        at: &str,
        remember: bool,
    ) -> Result<bool> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "UPDATE approvals SET state=?2, resolved_by=?3, resolved_at=?4, remember=?5 WHERE id=?1 AND state='pending'",
            params![id, state, by, at, remember as i64],
        )?;
        Ok(n > 0)
    }

    /// 仅更新 pending 审批的到期时间；返回是否更新。
    pub fn update_approval_expiry(&self, id: &str, expires_at: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "UPDATE approvals SET expires_at=?2 WHERE id=?1 AND state='pending'",
            params![id, expires_at],
        )?;
        Ok(n > 0)
    }

    pub fn get_approval(&self, id: &str) -> Result<Option<PendingApproval>> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.query_row(
            "SELECT id,session_id,tool_call_id,tool_name,category,risk,summary,command,path,args_json,requested_at,expires_at,state,resolved_by FROM approvals WHERE id=?1",
            params![id],
            |r| {
                let args: String = r.get(9)?;
                Ok(PendingApproval {
                    view: ApprovalView {
                        id: r.get(0)?,
                        session_id: r.get(1)?,
                        tool_name: r.get(3)?,
                        category: r.get(4)?,
                        risk: r.get(5)?,
                        summary: r.get(6)?,
                        command: r.get(7)?,
                        path: r.get(8)?,
                        args: serde_json::from_str(&args).map_err(|e| {
                            rusqlite::Error::FromSqlConversionFailure(
                                9,
                                rusqlite::types::Type::Text,
                                Box::new(e),
                            )
                        })?,
                        requested_at: r.get(10)?,
                        expires_at: r.get(11)?,
                        remaining_seconds: 0,
                        state: r.get(12)?,
                    },
                    tool_call_id: r.get(2)?,
                    resolved_by: r.get(13)?,
                })
            },
        )
        .optional()
        .map_err(Into::into)
    }

    pub fn list_pending_approvals(&self, session_id: Option<&str>) -> Result<Vec<ApprovalView>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut sql = String::from(
            "SELECT id,session_id,tool_name,category,risk,summary,command,path,args_json,requested_at,expires_at,state FROM approvals WHERE state='pending'",
        );
        if session_id.is_some() {
            sql.push_str(" AND session_id=?1");
        }
        sql.push_str(" ORDER BY requested_at ASC");
        let mut stmt = conn.prepare(&sql)?;
        let map_row = |r: &rusqlite::Row<'_>| -> rusqlite::Result<ApprovalView> {
            let args: String = r.get(8)?;
            Ok(ApprovalView {
                id: r.get(0)?,
                session_id: r.get(1)?,
                tool_name: r.get(2)?,
                category: r.get(3)?,
                risk: r.get(4)?,
                summary: r.get(5)?,
                command: r.get(6)?,
                path: r.get(7)?,
                args: serde_json::from_str(&args).unwrap_or(Value::Null),
                requested_at: r.get(9)?,
                expires_at: r.get(10)?,
                remaining_seconds: 0,
                state: r.get(11)?,
            })
        };
        let rows = match session_id {
            Some(id) => stmt.query_map(params![id], map_row)?,
            None => stmt.query_map([], map_row)?,
        };
        Ok(rows.collect::<std::result::Result<Vec<_>, _>>()?)
    }

    /// 每个会话当前的待审批数量（一次 SQL 取全部，避免逐会话查询的 N+1）。
    pub fn pending_approval_counts(&self) -> Result<HashMap<String, u32>> {
        let conn = self.conn.lock().expect("db poisoned");
        let mut stmt = conn.prepare(
            "SELECT session_id, COUNT(*) FROM approvals WHERE state='pending' GROUP BY session_id",
        )?;
        let rows = stmt.query_map([], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)? as u32))
        })?;
        let mut out = HashMap::new();
        for row in rows {
            let (id, n) = row?;
            out.insert(id, n);
        }
        Ok(out)
    }

    pub fn expire_approvals(&self, now: &str) -> Result<usize> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "UPDATE approvals SET state='expired', resolved_at=?1 WHERE state='pending' AND expires_at<=?1",
            params![now],
        )?;
        Ok(n)
    }

    // ---------- 幂等 / ack ----------

    /// 返回 true 表示首次见到该 clientId。
    /// 幂等检查：只读，不占用 clientId。
    pub fn has_client_id(&self, device_id: &str, client_id: &str) -> Result<bool> {
        let conn = self.conn.lock().expect("store poisoned");
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM outbox_seen WHERE device_id=?1 AND client_id=?2",
            params![device_id, client_id],
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    /// 原子抢占幂等键：首次见到 (device, clientId) 返回 true，重复返回 false。
    /// INSERT OR IGNORE 保证并发重试只有一方能抢占成功（TOCTOU 安全）。
    pub fn claim_client_id(
        &self,
        device_id: &str,
        client_id: &str,
        session_id: &str,
        at: &str,
    ) -> Result<bool> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute(
            "INSERT OR IGNORE INTO outbox_seen(device_id,client_id,session_id,created_at) VALUES(?1,?2,?3,?4)",
            params![device_id, client_id, session_id, at],
        )?;
        Ok(n > 0)
    }

    /// 释放已抢占的幂等键（执行失败时调用，让客户端可以重试）。
    pub fn release_client_id(&self, device_id: &str, client_id: &str) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "DELETE FROM outbox_seen WHERE device_id=?1 AND client_id=?2",
            params![device_id, client_id],
        )?;
        Ok(())
    }

    pub fn set_ack(&self, device_id: &str, session_id: &str, seq: u64) -> Result<()> {
        // u64 超过 i64::MAX 时 `as i64` 会翻成负数，写进库后水位倒退。
        let seq = i64::try_from(seq).map_err(|_| {
            crate::error::CoreError::InvalidArgument(format!("ack seq {seq} 超出可存储范围"))
        })?;
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "INSERT INTO device_acks(device_id,session_id,seq) VALUES(?1,?2,?3)
             ON CONFLICT(device_id,session_id) DO UPDATE SET seq=MAX(seq,excluded.seq)",
            params![device_id, session_id, seq],
        )?;
        Ok(())
    }

    pub fn get_ack(&self, device_id: &str, session_id: &str) -> Result<u64> {
        let conn = self.conn.lock().expect("db poisoned");
        let seq: Option<i64> = conn
            .query_row(
                "SELECT seq FROM device_acks WHERE device_id=?1 AND session_id=?2",
                params![device_id, session_id],
                |r| r.get(0),
            )
            .optional()?;
        Ok(seq.unwrap_or(0) as u64)
    }

    // ---------- 记住的审批规则 ----------
    // 表里的 device_id 列在当前版本用作 session_id，避免破坏 v1 schema。

    pub fn has_remembered_rule(
        &self,
        session_id: &str,
        tool_name: &str,
        pattern: &str,
    ) -> Result<bool> {
        let conn = self.conn.lock().expect("db poisoned");
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM remembered_rules WHERE device_id=?1 AND tool_name=?2 AND pattern=?3",
            params![session_id, tool_name, pattern],
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    pub fn add_remembered_rule(
        &self,
        id: &str,
        session_id: &str,
        tool_name: &str,
        pattern: &str,
        at: &str,
    ) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "INSERT OR IGNORE INTO remembered_rules(id,device_id,tool_name,pattern,created_at) VALUES(?1,?2,?3,?4,?5)",
            params![id, session_id, tool_name, pattern, at],
        )?;
        Ok(())
    }

    // ---------- 审计 ----------

    pub fn audit(&self, ts: &str, actor: &str, action: &str, detail: &Value) -> Result<()> {
        let conn = self.conn.lock().expect("db poisoned");
        conn.execute(
            "INSERT INTO audit(ts,actor,action,detail_json) VALUES(?1,?2,?3,?4)",
            params![ts, actor, action, serde_json::to_string(detail)?],
        )?;
        Ok(())
    }

    pub fn audit_count(&self) -> Result<u64> {
        let conn = self.conn.lock().expect("db poisoned");
        let n: i64 = conn.query_row("SELECT COUNT(*) FROM audit", [], |r| r.get(0))?;
        Ok(n as u64)
    }

    /// 删除 ts 早于 cutoff 的审计记录；返回删除数。
    pub fn prune_audit_before(&self, cutoff: &str) -> Result<usize> {
        let conn = self.conn.lock().expect("db poisoned");
        let n = conn.execute("DELETE FROM audit WHERE ts < ?1", params![cutoff])?;
        Ok(n)
    }

    /// 清理审批/幂等/水位表中的终态旧记录（只删已失效数据，不影响 pending 审批）。
    ///
    /// - `approval_days`：已解决（非 pending）审批保留天数。
    /// - `seen_days`：outbox_seen 幂等键保留天数。
    /// - device_acks 无时间列，以其设备被吊销的时间为准，保留
    ///   [`DEVICE_ACK_RETENTION_DAYS`] 天（吊销设备不会再发 ack）。
    pub fn prune_old_records(
        &self,
        now: DateTime<Utc>,
        approval_days: i64,
        seen_days: i64,
    ) -> Result<PruneReport> {
        let approval_cutoff = crate::rfc3339(now - Duration::days(approval_days));
        let seen_cutoff = crate::rfc3339(now - Duration::days(seen_days));
        let ack_cutoff = crate::rfc3339(now - Duration::days(DEVICE_ACK_RETENTION_DAYS));
        let conn = self.conn.lock().expect("db poisoned");
        let approvals = conn.execute(
            "DELETE FROM approvals WHERE state <> 'pending' AND COALESCE(resolved_at, requested_at) < ?1",
            params![approval_cutoff],
        )?;
        let outbox_seen = conn.execute(
            "DELETE FROM outbox_seen WHERE created_at < ?1",
            params![seen_cutoff],
        )?;
        let device_acks = conn.execute(
            "DELETE FROM device_acks WHERE device_id IN (
                SELECT id FROM devices WHERE revoked_at IS NOT NULL AND revoked_at < ?1
             )",
            params![ack_cutoff],
        )?;
        Ok(PruneReport {
            approvals,
            outbox_seen,
            device_acks,
        })
    }
}

fn row_to_device(r: &rusqlite::Row<'_>) -> rusqlite::Result<DeviceRecord> {
    Ok(DeviceRecord {
        id: r.get(0)?,
        name: r.get(1)?,
        platform: r.get(2)?,
        device_class: r.get(3)?,
        capability: r.get(4)?,
        paired_at: r.get(5)?,
        last_seen: r.get(6)?,
        revoked_at: r.get(7)?,
        online: r.get::<_, i64>(8)? != 0,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn store() -> Store {
        Store::open_memory().unwrap()
    }

    fn summary(id: &str) -> SessionSummary {
        SessionSummary {
            id: id.into(),
            cwd: "C:/work".into(),
            name: "demo".into(),
            preview: "hello".into(),
            updated_at: "2026-11-19T08:00:00.000Z".into(),
            message_count: 2,
            status: "idle".into(),
            active: false,
            pending_approvals: 0,
            last_source: "local".into(),
            writable: true,
            parent_id: None,
        }
    }

    #[test]
    fn journal_seq_is_monotonic_and_replayable() {
        let s = store();
        let a = s
            .append_event("s1", "t1", &json!({"type": "x", "n": 1}))
            .unwrap();
        let b = s
            .append_event("s1", "t2", &json!({"type": "x", "n": 2}))
            .unwrap();
        let c = s
            .append_event("s2", "t3", &json!({"type": "x", "n": 3}))
            .unwrap();
        assert_eq!((a, b, c), (1, 2, 1));
        let rows = s.events_after("s1", 0, 10).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1].2["n"], 2);
        assert_eq!(s.last_seq("s1").unwrap(), 2);
    }

    #[test]
    fn journal_prune_keeps_tail() {
        let s = store();
        for i in 0..5 {
            s.append_event("s1", "t", &json!({"n": i})).unwrap();
        }
        assert_eq!(s.prune_journal("s1", 2).unwrap(), 3);
        let rows = s.events_after("s1", 0, 10).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].0, 4);
    }

    #[test]
    fn journal_prune_never_empties_session() {
        let s = store();
        for i in 0..3 {
            s.append_event("s1", "t", &json!({"n": i})).unwrap();
        }
        // journal_max_events 配成 0 时也不能删光：否则 seq 归零，
        // 客户端记录的已确认水位会让后续事件被当成重复。
        assert_eq!(s.prune_journal("s1", 0).unwrap(), 2);
        let rows = s.events_after("s1", 0, 10).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].0, 3);
        assert_eq!(s.append_event("s1", "t", &json!({"n": 3})).unwrap(), 4);
    }

    #[test]
    fn set_ack_rejects_out_of_range_seq() {
        let s = store();
        assert!(s
            .set_ack("d", "s", u64::MAX)
            .unwrap_err()
            .to_string()
            .contains("seq"));
        assert_eq!(s.get_ack("d", "s").unwrap(), 0);
        s.set_ack("d", "s", 7).unwrap();
        assert_eq!(s.get_ack("d", "s").unwrap(), 7);
    }

    #[test]
    fn prune_old_records_only_drops_settled_rows() {
        let s = store();
        let now = chrono::Utc::now();
        let old = crate::rfc3339(now - Duration::days(60));
        let fresh = crate::rfc3339(now);
        let approved = |id: &str, state: &str, at: &str| PendingApproval {
            view: ApprovalView {
                id: id.into(),
                session_id: "s1".into(),
                tool_name: "bash".into(),
                category: "exec".into(),
                risk: "high".into(),
                summary: "x".into(),
                command: None,
                path: None,
                args: json!({}),
                requested_at: at.into(),
                expires_at: at.into(),
                remaining_seconds: 0,
                state: state.into(),
            },
            tool_call_id: "tc".into(),
            resolved_by: None,
        };
        s.insert_approval(&approved("old-done", "allowed", &old))
            .unwrap();
        s.insert_approval(&approved("old-pending", "pending", &old))
            .unwrap();
        s.insert_approval(&approved("new-done", "allowed", &fresh))
            .unwrap();
        s.claim_client_id("d1", "old", "s1", &old).unwrap();
        s.claim_client_id("d1", "new", "s1", &fresh).unwrap();

        let report = s.prune_old_records(now, 7, 7).unwrap();
        assert_eq!(report.approvals, 1);
        assert_eq!(report.outbox_seen, 1);
        assert!(s.get_approval("old-pending").unwrap().is_some());
        assert!(s.get_approval("new-done").unwrap().is_some());
        // 未吊销设备的 ack 水位不能丢，否则客户端会重拉已看过的历史。
        s.set_ack("d1", "s1", 9).unwrap();
        assert_eq!(s.prune_old_records(now, 7, 7).unwrap().device_acks, 0);
        assert_eq!(s.get_ack("d1", "s1").unwrap(), 9);
    }

    #[test]
    fn journal_prune_before_keeps_last_seq() {
        let s = store();
        for i in 0..3 {
            s.append_event("s1", "2000-01-01T00:00:00.000Z", &json!({"n": i}))
                .unwrap();
        }
        assert_eq!(
            s.prune_journal_before("2100-01-01T00:00:00.000Z").unwrap(),
            2
        );
        assert_eq!(s.last_seq("s1").unwrap(), 3);
        assert_eq!(
            s.append_event("s1", "t", &json!({"n": 4})).unwrap(),
            4,
            "序号必须继续递增"
        );
    }

    #[test]
    fn device_lifecycle_and_revoke() {
        let s = store();
        let d = DeviceRecord {
            id: "dev1".into(),
            name: "Pixel".into(),
            platform: "android".into(),
            device_class: "phone".into(),
            capability: "control".into(),
            paired_at: "t0".into(),
            last_seen: "t0".into(),
            revoked_at: None,
            online: false,
        };
        s.upsert_device(&d).unwrap();
        assert_eq!(s.list_devices().unwrap().len(), 1);
        s.touch_device("dev1", "t1", true).unwrap();
        assert!(s.get_device("dev1").unwrap().unwrap().online);
        s.mark_all_devices_offline().unwrap();
        assert!(!s.get_device("dev1").unwrap().unwrap().online);
        assert!(s.revoke_device("dev1", "t1").unwrap());
        assert!(!s.revoke_device("dev1", "t2").unwrap());
        assert!(s.get_device("dev1").unwrap().unwrap().revoked_at.is_some());
    }

    #[test]
    fn approvals_transition_once() {
        let s = store();
        let view = ApprovalView {
            id: "a1".into(),
            session_id: "s1".into(),
            tool_name: "bash".into(),
            category: "exec".into(),
            risk: "high".into(),
            summary: "run tests".into(),
            command: Some("cargo test".into()),
            path: None,
            args: json!({"command": "cargo test"}),
            requested_at: "t0".into(),
            expires_at: "t60".into(),
            remaining_seconds: 60,
            state: "pending".into(),
        };
        s.insert_approval(&PendingApproval {
            view,
            tool_call_id: "tc1".into(),
            resolved_by: None,
        })
        .unwrap();
        assert!(s
            .resolve_approval("a1", "allowed", "phone", "t1", false)
            .unwrap());
        assert!(!s
            .resolve_approval("a1", "denied", "phone", "t2", false)
            .unwrap());
        assert_eq!(s.list_pending_approvals(None).unwrap().len(), 0);
    }

    #[test]
    fn idempotency_and_ack() {
        let s = store();
        assert!(s.claim_client_id("d", "c1", "s", "t").unwrap());
        assert!(!s.claim_client_id("d", "c1", "s", "t").unwrap());
        s.set_ack("d", "s", 5).unwrap();
        s.set_ack("d", "s", 3).unwrap();
        assert_eq!(s.get_ack("d", "s").unwrap(), 5);
    }

    #[test]
    fn claim_client_id_is_atomic_and_releasable() {
        let s = store();
        let at = "2026-11-19T08:00:00.000Z";
        // 抢占成功后才执行，重复请求直接失败（无需先读）。
        assert!(s.claim_client_id("d1", "c1", "s1", at).unwrap());
        assert!(!s.claim_client_id("d1", "c1", "s2", at).unwrap());
        // 设备与客户端共同构成幂等键，互不干扰。
        assert!(s.claim_client_id("d2", "c1", "s1", at).unwrap());
        assert!(s.claim_client_id("d1", "c2", "s1", at).unwrap());
        // 执行失败释放键后可重新提交。
        s.release_client_id("d1", "c1").unwrap();
        assert!(s.claim_client_id("d1", "c1", "s3", at).unwrap());
    }

    #[test]
    fn session_index_roundtrip() {
        let s = store();
        s.upsert_session(&summary("s1"), "C:/f.jsonl").unwrap();
        let rows = s.list_sessions(10).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].0.id, "s1");
        assert_eq!(rows[0].1, "C:/f.jsonl");
    }
}
