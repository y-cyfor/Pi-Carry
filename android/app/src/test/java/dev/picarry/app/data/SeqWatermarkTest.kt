package dev.picarry.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重连会重放 journal；水位必须按会话独立推进，丢弃已应用过的事件。 */
class SeqWatermarkTest {
    @Test
    fun rejectsReplayedSequence() {
        val watermark = SeqWatermark()
        val key = "node::s1"
        assertTrue(watermark.accept(key, 1))
        assertTrue(watermark.accept(key, 2))
        assertFalse(watermark.accept(key, 2))
        assertFalse(watermark.accept(key, 1))
    }

    @Test
    fun sessionsAreIndependent() {
        val watermark = SeqWatermark()
        assertTrue(watermark.accept("n::s1", 7))
        assertTrue(watermark.accept("n::s2", 1))
        assertFalse(watermark.accept("n::s1", 6))
    }

    @Test
    fun observeRaisesWatermarkWithoutMovingBackwards() {
        val watermark = SeqWatermark()
        watermark.observe("n::s1", 42)
        assertFalse(watermark.accept("n::s1", 42))
        assertTrue(watermark.accept("n::s1", 43))
        watermark.observe("n::s1", 10)
        assertTrue(watermark.accept("n::s1", 44))
    }

    @Test
    fun sequencesWithoutNumberAlwaysPass() {
        val watermark = SeqWatermark()
        assertTrue(watermark.accept("n::s1", 0))
        assertTrue(watermark.accept("n::s1", 0))
    }

    @Test
    fun clearRemovesOnlyMatchingPrefix() {
        val watermark = SeqWatermark()
        watermark.observe("a::s1", 5)
        watermark.observe("b::s1", 5)
        watermark.clear("a::")
        assertTrue(watermark.accept("a::s1", 1))
        assertFalse(watermark.accept("b::s1", 1))
    }

    @Test
    fun forgetDropsSingleSessionWatermark() {
        // closeSession 要能清掉该会话水位，重开会话时从快照 lastSeq 重建。
        val watermark = SeqWatermark()
        watermark.observe("n::s1", 9)
        watermark.observe("n::s2", 9)
        watermark.forget("n::s1")
        assertTrue(watermark.accept("n::s1", 1))
        assertFalse(watermark.accept("n::s2", 1))
    }
}
