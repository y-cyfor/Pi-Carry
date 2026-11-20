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
pub const QUEUE_UPDATED: &str = "queue.updated";
pub const SESSION_UPDATED: &str = "session.updated";
pub const SESSION_CONFLICT: &str = "session.conflict";
pub const SESSION_COMPACTED: &str = "session.compacted";
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
        obj.extend(more);
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
        obj.extend(more);
    }
    out
}

pub fn ui_resolved(request_id: &str, cancelled: bool, value: Value) -> Value {
    json!({"type": UI_RESOLVED, "requestId": request_id, "cancelled": cancelled, "value": value})
}

pub fn notification(level: &str, title: &str, body: &str) -> Value {
    json!({"type": NOTIFICATION, "level": level, "title": title, "body": body})
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
}
