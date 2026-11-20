//! 错误码与错误对象。

use serde::{Deserialize, Serialize};

/// 稳定的错误码字符串。未知码在客户端保留原文，便于前向兼容。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorCode {
    AuthRequired,
    AuthFailed,
    PairingInvalid,
    PairingExpired,
    PairingLocked,
    PairingDenied,
    PermissionDenied,
    SessionNotFound,
    SessionBusy,
    SessionConflict,
    ApprovalNotFound,
    ApprovalExpired,
    ApprovalAlreadyResolved,
    ProtocolVersionUnsupported,
    PayloadTooLarge,
    RateLimited,
    Unsupported,
    InvalidArgument,
    PiSpawnFailed,
    PiNotInstalled,
    PiExited,
    NoApprover,
    SlowConsumer,
    Conflict,
    Internal,
}

impl ErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::AuthRequired => "AUTH_REQUIRED",
            Self::AuthFailed => "AUTH_FAILED",
            Self::PairingInvalid => "PAIRING_INVALID",
            Self::PairingExpired => "PAIRING_EXPIRED",
            Self::PairingLocked => "PAIRING_LOCKED",
            Self::PairingDenied => "PAIRING_DENIED",
            Self::PermissionDenied => "PERMISSION_DENIED",
            Self::SessionNotFound => "SESSION_NOT_FOUND",
            Self::SessionBusy => "SESSION_BUSY",
            Self::SessionConflict => "SESSION_CONFLICT",
            Self::ApprovalNotFound => "APPROVAL_NOT_FOUND",
            Self::ApprovalExpired => "APPROVAL_EXPIRED",
            Self::ApprovalAlreadyResolved => "APPROVAL_ALREADY_RESOLVED",
            Self::ProtocolVersionUnsupported => "PROTOCOL_VERSION_UNSUPPORTED",
            Self::PayloadTooLarge => "PAYLOAD_TOO_LARGE",
            Self::RateLimited => "RATE_LIMITED",
            Self::Unsupported => "UNSUPPORTED",
            Self::InvalidArgument => "INVALID_ARGUMENT",
            Self::PiSpawnFailed => "PI_SPAWN_FAILED",
            Self::PiNotInstalled => "PI_NOT_INSTALLED",
            Self::PiExited => "PI_EXITED",
            Self::NoApprover => "NO_APPROVER",
            Self::SlowConsumer => "SLOW_CONSUMER",
            Self::Conflict => "CONFLICT",
            Self::Internal => "INTERNAL",
        }
    }

    pub const fn retryable(self) -> bool {
        matches!(
            self,
            Self::SessionBusy
                | Self::RateLimited
                | Self::PiSpawnFailed
                | Self::PiExited
                | Self::SlowConsumer
                | Self::Internal
        )
    }
}

impl std::fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl Serialize for ErrorCode {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for ErrorCode {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let s = String::deserialize(d)?;
        let known = [
            Self::AuthRequired,
            Self::AuthFailed,
            Self::PairingInvalid,
            Self::PairingExpired,
            Self::PairingLocked,
            Self::PairingDenied,
            Self::PermissionDenied,
            Self::SessionNotFound,
            Self::SessionBusy,
            Self::SessionConflict,
            Self::ApprovalNotFound,
            Self::ApprovalExpired,
            Self::ApprovalAlreadyResolved,
            Self::ProtocolVersionUnsupported,
            Self::PayloadTooLarge,
            Self::RateLimited,
            Self::Unsupported,
            Self::InvalidArgument,
            Self::PiSpawnFailed,
            Self::PiNotInstalled,
            Self::PiExited,
            Self::NoApprover,
            Self::SlowConsumer,
            Self::Conflict,
            Self::Internal,
        ];
        for code in known {
            if code.as_str() == s {
                return Ok(code);
            }
        }
        Err(serde::de::Error::custom(format!("unknown error code: {s}")))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProtocolError {
    pub code: ErrorCode,
    pub message: String,
    #[serde(default)]
    pub retryable: bool,
    #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
    pub details: serde_json::Value,
}

impl ProtocolError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            retryable: code.retryable(),
            details: serde_json::Value::Null,
        }
    }

    pub fn with_details(mut self, details: serde_json::Value) -> Self {
        self.details = details;
        self
    }
}

impl std::fmt::Display for ProtocolError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for ProtocolError {}

#[derive(Debug, thiserror::Error)]
pub enum FrameError {
    #[error("帧长度 {0} 超出上限 {1}")]
    TooLarge(usize, usize),
    #[error("帧长度为 0")]
    Empty,
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON 解析失败: {0}")]
    Json(#[from] serde_json::Error),
    #[error("JSONL 行超长: {0} 字节")]
    LineTooLong(usize),
    #[error("非法 UTF-8: {0}")]
    Utf8(#[from] std::str::Utf8Error),
}
