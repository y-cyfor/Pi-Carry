//! 常用请求/响应载荷。字段名 camelCase，新增字段必须可选。

use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientInfo {
    pub name: String,
    /// android / windows / macos / linux
    pub platform: String,
    #[serde(default)]
    pub app_version: String,
    /// phone / desktop / tablet
    #[serde(default = "default_class")]
    pub device_class: String,
    #[serde(default)]
    pub locale: String,
}

fn default_class() -> String {
    "phone".into()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloPayload {
    pub protocol: String,
    pub client: ClientInfo,
    /// 密码兜底认证；与 token/code 三选一。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub password: Option<String>,
    /// 二维码配对 token。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    /// 6 位配对码。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    /// 重连时携带上次会话，便于客户端立即定位。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resume_session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    #[serde(default)]
    pub app_version: String,
    #[serde(default)]
    pub pi_version: String,
    #[serde(default)]
    pub pi_installed: bool,
    #[serde(default)]
    pub cwd: String,
    #[serde(default)]
    pub relay_mode: String,
    #[serde(default)]
    pub direct_addresses: Vec<String>,
    #[serde(default)]
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceInfo {
    pub id: String,
    pub name: String,
    pub platform: String,
    #[serde(default)]
    pub device_class: String,
    /// read / control / full / owner
    pub capability: String,
    #[serde(default)]
    pub paired_at: String,
    #[serde(default)]
    pub last_seen: String,
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub current: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloResponse {
    pub protocol: String,
    pub node: NodeInfo,
    pub device: DeviceInfo,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionSummary {
    pub id: String,
    pub cwd: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub preview: String,
    #[serde(default)]
    pub updated_at: String,
    #[serde(default)]
    pub message_count: u64,
    /// idle / streaming / waiting_approval / waiting_input / error
    #[serde(default = "idle")]
    pub status: String,
    #[serde(default)]
    pub active: bool,
    #[serde(default)]
    pub pending_approvals: u32,
    /// local / mobile / external
    #[serde(default)]
    pub last_source: String,
    #[serde(default)]
    pub writable: bool,
    #[serde(default)]
    pub parent_id: Option<String>,
}

fn idle() -> String {
    "idle".into()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PromptPayload {
    pub session_id: String,
    pub message: String,
    /// auto / steer / queue / followUp
    #[serde(default = "auto")]
    pub mode: String,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub images: Vec<ImagePayload>,
}

fn auto() -> String {
    "auto".into()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImagePayload {
    #[serde(default = "image_type")]
    pub r#type: String,
    /// base64（不带 data: 前缀）
    pub data: String,
    pub mime_type: String,
    #[serde(default)]
    pub width: u32,
    #[serde(default)]
    pub height: u32,
}

fn image_type() -> String {
    "image".into()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalView {
    pub id: String,
    pub session_id: String,
    pub tool_name: String,
    /// read / write / exec / network / other
    pub category: String,
    /// low / medium / high / critical
    pub risk: String,
    pub summary: String,
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub args: Value,
    pub requested_at: String,
    pub expires_at: String,
    pub remaining_seconds: u64,
    /// pending / allowed / denied / expired
    pub state: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalDecision {
    pub approval_id: String,
    /// allow / allow_always / deny
    pub decision: String,
    #[serde(default)]
    pub remember: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UiResponsePayload {
    pub request_id: String,
    #[serde(default)]
    pub value: Option<Value>,
    #[serde(default)]
    pub confirmed: Option<bool>,
    #[serde(default)]
    pub cancelled: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelInfo {
    pub provider: String,
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub context_window: u64,
    #[serde(default)]
    pub reasoning: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionSnapshot {
    pub session: SessionSummary,
    pub last_seq: u64,
    #[serde(default)]
    pub messages: Vec<Value>,
    #[serde(default)]
    pub pending_approvals: Vec<ApprovalView>,
    #[serde(default)]
    pub run: Option<Value>,
    #[serde(default)]
    pub queue: Value,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_keep_old_clients_valid() {
        let v: PromptPayload = serde_json::from_str(r#"{"sessionId":"s","message":"hi"}"#).unwrap();
        assert_eq!(v.mode, "auto");
        assert!(v.images.is_empty());
    }

    #[test]
    fn hello_omits_absent_credentials() {
        let hello = HelloPayload {
            protocol: "carry.v1".into(),
            client: ClientInfo {
                name: "phone".into(),
                platform: "android".into(),
                app_version: "0.1.0".into(),
                device_class: "phone".into(),
                locale: "zh-CN".into(),
            },
            password: None,
            token: None,
            code: None,
            resume_session_id: None,
        };
        let s = serde_json::to_string(&hello).unwrap();
        assert!(!s.contains("password"));
        assert!(!s.contains("token"));
    }
}
