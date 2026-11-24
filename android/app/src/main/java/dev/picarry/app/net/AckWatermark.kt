package dev.picarry.app.net

/**
 * 事件确认水位（每条连接一份，按会话独立）。
 *
 * 服务端 journal 按会话记录 seq，且 ack 只前进（crates/core/src/store.rs 的 `set_ack` 用
 * `MAX(seq, excluded.seq)`）。客户端旧实现在读循环里每帧从 0 起步取值：解析不出 seq 的帧
 * 会被当成 `ack 0` 发出去，等于把确认水位退回 0——轻则协议上无意义，重则在服务端实现变化后
 * 重放整段 journal。
 *
 * 这里保证「只前进」：seq 无意义（<= 0）或没有超过已发水位时返回 `null`，调用方据此不发 ack。
 * 水位只活在一次读循环里，重连后由服务端按它自己存的水位重放，不会漏 ack。
 */
internal class AckWatermark {
    private val highest = HashMap<String, Long>()

    /** 返回可以发送的 seq；`null` 表示这次不该发 ack。 */
    fun advance(sessionId: String?, seq: Long): Long? {
        if (seq <= 0L) return null
        val key = sessionId ?: ""
        val previous = highest[key] ?: 0L
        if (seq <= previous) return null
        highest[key] = seq
        return seq
    }
}
