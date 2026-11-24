//! 事件体构造器。事件体自身带 `type`，外层由 ServerFrame::Event 包裹 seq/sessionId。

use crate::{ApprovalView, SessionSummary};
use serde_json::{json, Value};

pub const MESSAGE_DELTA: &str = "message.delta";
pub const MESSAGE_FINAL: &str = "message.final";
pub const TOOL_STARTED: &str = "tool.started";
pub const TOOL_UPDATED: &str = "tool.updated";
pub const TOOL_ENDED: &str = "tool.ended";
pub const APPROVAL_REQUESTED: &str = "approval.requested";
pub const APPROVAL_UPDATED: &str = "approval.updated";
pub const APPROVAL_RESOLVED: &str = "approval.resolved";
pub const RUN_STATE: &str = "run.state";
/// 一轮运行结束后的消耗总结（`agent_settled` 时发，先于 idle 的 run.state）。
pub const RUN_SUMMARY: &str = "run.summary";
/// 本条连接实测的连接方式（不经全局广播，只发给该连接）。
pub const CONNECTION_UPDATED: &str = "connection.updated";
pub const QUEUE_UPDATED: &str = "queue.updated";
pub const SESSION_UPDATED: &str = "session.updated";
pub const SESSION_CONFLICT: &str = "session.conflict";
pub const SESSION_COMPACTED: &str = "session.compacted";
/// 会话被 fork 迁移：旧 id 停止写入，客户端应把路由切到新 id。
pub const SESSION_MIGRATED: &str = "session.migrated";
pub const UI_REQUESTED: &str = "ui.requested";
pub const UI_RESOLVED: &str = "ui.resolved";
pub const NOTIFICATION: &str = "notification";
pub const NODE_STATUS: &str = "node.status";
pub const DEVICE_REVOKED: &str = "device.revoked";
pub const ERROR: &str = "error";

pub fn message_delta(
    run_id: &str,
    message_id: &str,
    kind: &str,
    content_index: u32,
    delta: &str,
) -> Value {
    json!({
        "type": MESSAGE_DELTA,
        "runId": run_id,
        "messageId": message_id,
        "kind": kind,
        "contentIndex": content_index,
        "delta": delta,
    })
}

pub fn message_final(run_id: &str, message_id: &str, message: Value) -> Value {
    json!({
        "type": MESSAGE_FINAL,
        "runId": run_id,
        "messageId": message_id,
        "message": message,
    })
}

pub fn tool_started(run_id: &str, tool_call_id: &str, tool_name: &str, args: Value) -> Value {
    json!({
        "type": TOOL_STARTED,
        "runId": run_id,
        "toolCallId": tool_call_id,
        "toolName": tool_name,
        "args": args,
    })
}

pub fn tool_updated(run_id: &str, tool_call_id: &str, partial_result: Value) -> Value {
    json!({
        "type": TOOL_UPDATED,
        "runId": run_id,
        "toolCallId": tool_call_id,
        "partialResult": partial_result,
    })
}

pub fn tool_ended(
    run_id: &str,
    tool_call_id: &str,
    result: Value,
    is_error: bool,
    duration_ms: u64,
) -> Value {
    json!({
        "type": TOOL_ENDED,
        "runId": run_id,
        "toolCallId": tool_call_id,
        "result": result,
        "isError": is_error,
        "durationMs": duration_ms,
    })
}

pub fn approval_requested(approval: &ApprovalView) -> Value {
    json!({"type": APPROVAL_REQUESTED, "approval": approval})
}

pub fn approval_updated(approval: &ApprovalView) -> Value {
    json!({"type": APPROVAL_UPDATED, "approval": approval})
}

pub fn approval_resolved(
    approval_id: &str,
    decision: &str,
    resolved_by: &str,
    auto: bool,
) -> Value {
    json!({
        "type": APPROVAL_RESOLVED,
        "approvalId": approval_id,
        "decision": decision,
        "resolvedBy": resolved_by,
        "auto": auto,
    })
}

pub fn run_state(run_id: &str, state: &str, extra: Value) -> Value {
    let mut out = json!({"type": RUN_STATE, "runId": run_id, "state": state});
    if let (Some(obj), Value::Object(more)) = (out.as_object_mut(), extra) {
        extend_event(obj, more, &["runId", "state"]);
    }
    out
}

/// 一轮运行的消耗总结。`fields` 由服务端按实际统计拼装，这里只负责补 `type`，
/// 并保证 `runId`/`type` 不会被 payload 覆盖（信封字段完整性）。
pub fn run_summary(run_id: &str, fields: Value) -> Value {
    let mut out = json!({"type": RUN_SUMMARY, "runId": run_id});
    if let (Some(obj), Value::Object(more)) = (out.as_object_mut(), fields) {
        extend_event(obj, more, &["type", "runId"]);
    }
    out
}

pub fn queue_updated(steering: Value, follow_up: Value) -> Value {
    json!({"type": QUEUE_UPDATED, "steering": steering, "followUp": follow_up})
}

pub fn session_updated(session: &SessionSummary) -> Value {
    json!({"type": SESSION_UPDATED, "session": session})
}

pub fn session_conflict(session_id: &str, reason: &str, writable: bool) -> Value {
    json!({
        "type": SESSION_CONFLICT,
        "sessionId": session_id,
        "reason": reason,
        "writable": writable,
    })
}

pub fn session_compacted(session_id: &str, summary: &str) -> Value {
    json!({"type": SESSION_COMPACTED, "sessionId": session_id, "summary": summary})
}

pub fn ui_requested(request_id: &str, method: &str, title: &str, extra: Value) -> Value {
    let mut out = json!({
        "type": UI_REQUESTED,
        "requestId": request_id,
        "method": method,
        "title": title,
    });
    if let (Some(obj), Value::Object(more)) = (out.as_object_mut(), extra) {
        extend_event(obj, more, &["requestId", "method", "title"]);
    }
    out
}

pub fn ui_resolved(request_id: &str, cancelled: bool, value: Value) -> Value {
    json!({"type": UI_RESOLVED, "requestId": request_id, "cancelled": cancelled, "value": value})
}

/// notification 同时带 `title`/`body` 与遗留的 `message`（旧发射端只用 message），
/// 保持向后兼容：新客户端读 title/body，老客户端读 message。
pub fn notification(level: &str, title: &str, body: &str) -> Value {
    json!({
        "type": NOTIFICATION,
        "level": level,
        "title": title,
        "body": body,
        "message": body,
    })
}

pub fn node_status(node: Value) -> Value {
    json!({"type": NODE_STATUS, "node": node})
}

pub fn device_revoked(device_id: &str, reason: &str) -> Value {
    json!({"type": DEVICE_REVOKED, "deviceId": device_id, "reason": reason})
}

pub fn error_event(code: &str, message: &str) -> Value {
    json!({"type": ERROR, "code": code, "message": message})
}

/// 信封保留键：`extra` 里的同名键会覆盖外层事件类型/会话/序号，必须丢弃。
const RESERVED_EVENT_KEYS: [&str; 4] = ["type", "sessionId", "seq", "event"];

/// 把 `extra` 合并进事件主体，但剔除信封保留键与本构造器自身的固定字段。
fn extend_event(
    obj: &mut serde_json::Map<String, Value>,
    more: serde_json::Map<String, Value>,
    own_keys: &[&str],
) {
    for (k, v) in more {
        let reserved = RESERVED_EVENT_KEYS.contains(&k.as_str()) || own_keys.contains(&k.as_str());
        if !reserved {
            obj.insert(k, v);
        }
    }
}

/// 会话迁移事件。`fromSessionId` 是旧会话，`toSessionId` 是 fork 出来的新会话；
/// `reason` 固定为 `"fork"`，后续新增迁移原因时沿用该字段。
pub fn session_migrated(from_session_id: &str, to_session_id: &str, reason: &str) -> Value {
    json!({
        "type": SESSION_MIGRATED,
        // 事件自描述会话归属：客户端不能只依赖帧外层字段，
        // 否则离线补放/日志回放时无法判断它属于哪个会话。
        "sessionId": from_session_id,
        "fromSessionId": from_session_id,
        "toSessionId": to_session_id,
        "reason": reason,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_types_are_stable() {
        assert_eq!(
            message_delta("r", "m", "text", 0, "x")["type"],
            "message.delta"
        );
        assert_eq!(
            approval_resolved("a", "allow", "d", false)["type"],
            "approval.resolved"
        );
        assert_eq!(queue_updated(json!([]), json!([]))["type"], "queue.updated");
    }

    #[test]
    fn run_state_merges_extra_fields() {
        let v = run_state("r1", "streaming", json!({"startedAt": "t"}));
        assert_eq!(v["state"], "streaming");
        assert_eq!(v["startedAt"], "t");
    }

    #[test]
    fn extra_cannot_overwrite_envelope() {
        let v = run_state(
            "r1",
            "streaming",
            json!({"type": "evil", "sessionId": "s", "seq": 9, "event": {}, "runId": "x"}),
        );
        assert_eq!(v["type"], "run.state");
        assert_eq!(v["runId"], "r1");
        assert!(v.get("sessionId").is_none());
        assert!(v.get("seq").is_none());
        assert!(v.get("event").is_none());

        let u = ui_requested(
            "q1",
            "select",
            "pick",
            json!({"type": "evil", "requestId": "z"}),
        );
        assert_eq!(u["type"], "ui.requested");
        assert_eq!(u["requestId"], "q1");
    }

    #[test]
    fn notification_keeps_legacy_message_field() {
        let v = notification("info", "t", "b");
        assert_eq!(v["body"], "b");
        assert_eq!(v["message"], "b");
    }
}
