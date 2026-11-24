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
/// 单张图片的解码后上限。图片以 base64 内嵌在 prompt 控制帧里，
/// 真正生效的硬上限是 [`MAX_CONTROL_FRAME`]，客户端必须先把图压到
/// 帧预算以内（Android 侧见 `Wire.PROMPT_FRAME_BUDGET`）。
pub const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;
/// 单条消息图片总上限（解码后），同样受 [`MAX_CONTROL_FRAME`] 约束。
pub const MAX_PROMPT_IMAGE_BYTES: usize = 20 * 1024 * 1024;

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

    /// 图片上限与控制帧上限的关系是刻意保留的：常量是「解码后的原始字节」，
    /// 帧里装的是 base64（膨胀约 4/3），所以真正的单帧门槛总是先被
    /// `read_frame(MAX_CONTROL_FRAME)` 挡住。带外 blob 传输接管图片后，
    /// 这条断言要随行为一起改。
    #[test]
    fn image_limits_exceed_control_frame() {
        assert!(MAX_IMAGE_BYTES > MAX_CONTROL_FRAME);
        assert!(MAX_PROMPT_IMAGE_BYTES > MAX_CONTROL_FRAME);
    }

    /// 上限必须单调：单张 < 单条总量，且帧上限远小于事件上限。
    #[test]
    fn limits_are_monotonic() {
        assert!(MAX_IMAGE_BYTES < MAX_PROMPT_IMAGE_BYTES);
        assert!(MAX_CONTROL_FRAME < MAX_EVENT_FRAME);
        assert!(MAX_EVENT_FRAME < MAX_BLOB_BYTES);
        assert!(MAX_IMAGES_PER_PROMPT > 0);
        assert!(MAX_CONNECTIONS_PER_DEVICE > 0);
    }
}
