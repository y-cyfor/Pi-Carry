use pi_carry_protocol::{ErrorCode, ProtocolError};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum CoreError {
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("JSON 错误: {0}")]
    Json(#[from] serde_json::Error),
    #[error("TOML 错误: {0}")]
    Toml(#[from] toml::de::Error),
    #[error("数据库错误: {0}")]
    Db(#[from] rusqlite::Error),
    #[error("协议错误: {0}")]
    Protocol(#[from] ProtocolError),
    #[error("配置错误: {0}")]
    Config(String),
    #[error("pi 未安装: {0}")]
    PiNotInstalled(String),
    #[error("pi 进程错误: {0}")]
    Pi(String),
    #[error("会话不存在: {0}")]
    SessionNotFound(String),
    #[error("会话忙: {0}")]
    SessionBusy(String),
    #[error("会话冲突: {0}")]
    SessionConflict(String),
    #[error("权限不足: {0}")]
    PermissionDenied(String),
    #[error("参数非法: {0}")]
    InvalidArgument(String),
    #[error("内部错误: {0}")]
    Internal(String),
}

impl CoreError {
    pub fn code(&self) -> ErrorCode {
        match self {
            Self::Protocol(p) => p.code.clone(),
            Self::Config(_) | Self::Toml(_) => ErrorCode::InvalidArgument,
            Self::PiNotInstalled(_) => ErrorCode::PiNotInstalled,
            Self::Pi(_) => ErrorCode::PiSpawnFailed,
            Self::SessionNotFound(_) => ErrorCode::SessionNotFound,
            Self::SessionBusy(_) => ErrorCode::SessionBusy,
            Self::SessionConflict(_) => ErrorCode::SessionConflict,
            Self::PermissionDenied(_) => ErrorCode::PermissionDenied,
            Self::InvalidArgument(_) => ErrorCode::InvalidArgument,
            Self::Db(_) | Self::Io(_) | Self::Json(_) | Self::Internal(_) => ErrorCode::Internal,
        }
    }

    pub fn to_protocol(&self) -> ProtocolError {
        ProtocolError::new(self.code(), self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, CoreError>;
