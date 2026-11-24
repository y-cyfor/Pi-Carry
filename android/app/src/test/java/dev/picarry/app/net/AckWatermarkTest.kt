package dev.picarry.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ack 水位只允许前进。
 *
 * 旧实现每帧都从 0 起步取值，解析失败/收尾路径会把 `ack 0` 发出去，等于把已确认水位退回 0。
 * 服务端 `set_ack` 用 `MAX(seq, excluded.seq)` 且 journal 按会话记录，所以水位必须按会话独立的
 * 且单调递增。
 */
class AckWatermarkTest {
    @Test
    fun advancesForIncreasingSequences() {
        val watermark = AckWatermark()
        assertEquals(1L, watermark.advance("s1", 1))
        assertEquals(2L, watermark.advance("s1", 2))
    }

    @Test
    fun refusesToMoveBackwards() {
        val watermark = AckWatermark()
        assertEquals(5L, watermark.advance("s1", 5))
        // 乱序到达的旧帧不能把水位拉回去。
        assertNull(watermark.advance("s1", 3))
        // 重复帧也不重复 ack。
        assertNull(watermark.advance("s1", 5))
    }

    @Test
    fun zeroOrNegativeSequenceIsIgnored() {
        // 收尾/解析失败路径没有有效 seq：这里必须不发 ack，而不是发 ack 0。
        val watermark = AckWatermark()
        assertNull(watermark.advance("s1", 0))
        assertNull(watermark.advance(null, 0))
        assertNull(watermark.advance("s1", -1))
    }

    @Test
    fun sessionsTrackSeparately() {
        val watermark = AckWatermark()
        assertEquals(9L, watermark.advance("s1", 9))
        // 另一个会话的 seq 从 1 开始，不能被 s1 的水位挡住。
        assertEquals(1L, watermark.advance("s2", 1))
    }

    @Test
    fun missingSessionUsesOwnBucket() {
        val watermark = AckWatermark()
        // null 与空串归为同一个「无会话」桶，不与具名会话的水位混用。
        assertEquals(4L, watermark.advance(null, 4))
        assertNull(watermark.advance("", 4))
        assertEquals(5L, watermark.advance("s1", 5))
    }
}
