//! carry.v1 协议：帧格式、JSONL 分帧、错误码与线上模型。
//!
//! 本 crate 不依赖 iroh，便于 Android fixtures 与纯单元测试复用。

pub mod constants;
pub mod error;
pub mod events;
pub mod frame;
pub mod jsonl;
pub mod model;
pub mod payloads;

pub use constants::*;
pub use error::{ErrorCode, ProtocolError};
pub use frame::{decode_frame, encode_frame, read_frame, write_frame};
pub use jsonl::{read_json_line, JsonlReader};
pub use model::*;
pub use payloads::*;
