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
pub const MAX_IMAGES_PER_PROMPT: usize = 10;
/// 单张图片的解码后上限。0.2.0 起图片走 blob 分片上传，
/// 控制帧里只保留 blobId，因此该上限不再受 [`MAX_CONTROL_FRAME`] 约束。
pub const MAX_IMAGE_BYTES: usize = 20 * 1024 * 1024;
/// 单条消息图片总上限（解码后）。图片不内联进控制帧，这里只限制一次 prompt 的合计体积。
pub const MAX_PROMPT_IMAGE_BYTES: usize = 100 * 1024 * 1024;
/// 单会话 blob 累计上限（去重后）。超过后拒绝继续上传，不静默降质。
pub const MAX_SESSION_BLOB_BYTES: u64 = 100 * 1024 * 1024;
/// blob 分片大小。base64 后约 683 KiB，连同 JSON 信封仍在 1 MiB 控制帧以内。
pub const BLOB_CHUNK_BYTES: usize = 512 * 1024;

// 心跳间隔不在这里定义：服务端用运行时配置（见 config 的 transport）驱动心跳，
// 协议并不强制某个具体值，写死常量只会让人误以为客户端必须照抄。
// `CONNECTION_IDLE_SEC` 是服务端判定连接空闲的依据（server.rs 引用），必须保留。
pub const CONNECTION_IDLE_SEC: u64 = 90;
pub const APPROVAL_TIMEOUT_SEC: u64 = 120;
pub const APPROVAL_MAX_EXTENSION_SEC: u64 = 300;
pub const PAIRING_TTL_SEC: u64 = 600;
pub const PAIRING_MAX_ATTEMPTS: u32 = 5;
pub const PAIRING_LOCK_SEC: u64 = 600;
pub const MAX_DEVICES: usize = 8;
/// 单设备并发连接上限。每条连接都持有一个 broadcast 订阅，
/// 不限则一个身份就能把服务端内存拖垮。
pub const MAX_CONNECTIONS_PER_DEVICE: usize = 2;
/// 会话事件广播通道容量。客户端落后超过这个数会收到 `SNAPSHOT_REQUIRED`
/// 并重拉快照；它必须大于「一个快照周期内可能产生的最大事件数」，
/// 否则高峰期会频繁掉帧（8182 约等于一个高吞吐 run 的 2000 条 delta 的四倍）。
pub const EVENT_QUEUE_CAPACITY: usize = 8192;

#[cfg(test)]
mod tests {
    use super::*;

    /// 图片上限与控制帧上限的关系是刻意保留的：0.2.0 起图片走 blob 上传，
    /// 不再内联进控制帧，所以解码后上限可以远大于单帧。
    #[test]
    #[allow(clippy::assertions_on_constants)]
    fn image_limits_exceed_control_frame() {
        assert!(MAX_IMAGE_BYTES > MAX_CONTROL_FRAME);
        assert!(MAX_PROMPT_IMAGE_BYTES > MAX_CONTROL_FRAME);
    }

    /// 上限必须单调：单张 < 单条总量，且帧上限远小于事件上限。
    #[test]
    #[allow(clippy::assertions_on_constants)]
    fn limits_are_monotonic() {
        assert!(MAX_IMAGE_BYTES < MAX_PROMPT_IMAGE_BYTES);
        assert!(MAX_CONTROL_FRAME < MAX_EVENT_FRAME);
        assert!(MAX_EVENT_FRAME < MAX_BLOB_BYTES);
        assert!(MAX_IMAGES_PER_PROMPT > 0);
        assert!(MAX_CONNECTIONS_PER_DEVICE > 0);
        // 分片 base64 后必须仍能装进一帧。
        assert!(BLOB_CHUNK_BYTES / 4 * 3 * 4 / 3 < MAX_CONTROL_FRAME);
        assert!(MAX_SESSION_BLOB_BYTES >= MAX_IMAGE_BYTES as u64);
    }
}
