//! carry.v1 线上信封。所有帧都是带 `type` 标签的 JSON 对象。

use crate::{ProtocolError, MAX_EVENT_FRAME};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// 客户端 → 服务端。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum ClientFrame {
    Request {
        id: String,
        op: String,
        #[serde(default)]
        payload: Value,
    },
    Ack {
        session_id: String,
        seq: u64,
    },
    Ping {
        #[serde(default)]
        nonce: i64,
    },
}

impl ClientFrame {
    pub fn request(id: impl Into<String>, op: impl Into<String>, payload: Value) -> Self {
        Self::Request {
            id: id.into(),
            op: op.into(),
            payload,
        }
    }
}

/// 服务端 → 客户端。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum ServerFrame {
    Response {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        id: Option<String>,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        payload: Option<Value>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<ProtocolError>,
    },
    Event {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        session_id: Option<String>,
        seq: u64,
        event: Value,
    },
    Pong {
        #[serde(default)]
        nonce: i64,
        server_time: String,
    },
}

impl ServerFrame {
    pub fn ok(id: impl Into<String>, payload: Value) -> Self {
        Self::Response {
            id: Some(id.into()),
            ok: true,
            payload: Some(payload),
            error: None,
        }
    }

    pub fn err(id: impl Into<String>, error: ProtocolError) -> Self {
        Self::Response {
            id: Some(id.into()),
            ok: false,
            payload: None,
            error: Some(error),
        }
    }

    pub fn event(session_id: Option<String>, seq: u64, event: Value) -> Self {
        Self::Event {
            session_id,
            seq,
            event,
        }
    }

    pub fn pong(nonce: i64, server_time: impl Into<String>) -> Self {
        Self::Pong {
            nonce,
            server_time: server_time.into(),
        }
    }

    pub fn encoded_len_ok(&self) -> bool {
        serde_json::to_vec(self)
            .map(|v| v.len() <= MAX_EVENT_FRAME)
            .unwrap_or(false)
    }
}

/// 单条请求的便捷结构（服务端 handler 使用）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Request {
    pub id: String,
    pub op: String,
    #[serde(default)]
    pub payload: Value,
}

impl From<Request> for ClientFrame {
    fn from(r: Request) -> Self {
        ClientFrame::Request {
            id: r.id,
            op: r.op,
            payload: r.payload,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ErrorCode;
    use serde_json::json;

    #[test]
    fn client_wire_shape() {
        let frame = ClientFrame::request("c-1", "hello", json!({"protocol": "carry.v1"}));
        let v = serde_json::to_value(&frame).unwrap();
        assert_eq!(v["type"], "request");
        assert_eq!(v["id"], "c-1");
        assert_eq!(v["op"], "hello");
        assert_eq!(v["payload"]["protocol"], "carry.v1");
    }

    #[test]
    fn server_response_and_error_shape() {
        let ok = ServerFrame::ok("c-1", json!({"pong": true}));
        let v = serde_json::to_value(&ok).unwrap();
        assert_eq!(v["type"], "response");
        assert_eq!(v["ok"], true);
        assert!(v.get("error").is_none());

        let err = ServerFrame::err(
            "c-2",
            ProtocolError::new(ErrorCode::PermissionDenied, "device is read-only"),
        );
        let v = serde_json::to_value(&err).unwrap();
        assert_eq!(v["ok"], false);
        assert_eq!(v["error"]["code"], "PERMISSION_DENIED");
        assert_eq!(v["error"]["retryable"], false);
    }

    #[test]
    fn event_roundtrip() {
        let frame = ServerFrame::event(
            Some("s-1".into()),
            42,
            json!({"type": "message.delta", "delta": "hi"}),
        );
        let bytes = serde_json::to_vec(&frame).unwrap();
        let back: ServerFrame = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(back, frame);
    }

    #[test]
    fn unknown_fields_are_ignored() {
        let raw = r#"{"type":"response","id":"x","ok":true,"payload":{},"future":1}"#;
        let frame: ServerFrame = serde_json::from_str(raw).unwrap();
        assert!(matches!(frame, ServerFrame::Response { ok: true, .. }));
    }
}
