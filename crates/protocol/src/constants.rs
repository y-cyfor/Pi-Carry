//! 协议常量。所有上限必须与服务端校验、客户端预分配一致。

/// 协议版本字符串。
pub const PROTOCOL_VERSION: &str = "carry.v1";
/// iroh ALPN。
pub const ALPN: &[u8] = b"pi-carry/1";

pub const MAX_CONTROL_FRAME: usize = 1024 * 1024;
pub const MAX_EVENT_FRAME: usize = 4 * 1024 * 1024;
pub const MAX_BLOB_BYTES: usize = 32 * 1024 * 1024;
pub const MAX_JSONL_LINE: usize = 8 * 1024 * 1024;

/// 单条消息最多图片数。
pub const MAX_IMAGES_PER_PROMPT: usize = 5;
/// 单张图片上限。
pub const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;
/// 单条消息图片总上限。
pub const MAX_PROMPT_IMAGE_BYTES: usize = 20 * 1024 * 1024;

pub const HEARTBEAT_SEC: u64 = 15;
pub const HEARTBEAT_BACKGROUND_SEC: u64 = 30;
pub const CONNECTION_IDLE_SEC: u64 = 90;
pub const APPROVAL_TIMEOUT_SEC: u64 = 120;
pub const APPROVAL_MAX_EXTENSION_SEC: u64 = 300;
pub const PAIRING_TTL_SEC: u64 = 600;
pub const PAIRING_MAX_ATTEMPTS: u32 = 5;
pub const PAIRING_LOCK_SEC: u64 = 600;
pub const MAX_DEVICES: usize = 8;
pub const MAX_CONNECTIONS_PER_DEVICE: usize = 2;
pub const MAX_PENDING_REQUESTS: usize = 128;
pub const EVENT_QUEUE_CAPACITY: usize = 256;
