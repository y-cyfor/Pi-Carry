package dev.picarry.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 历史消息兜底 id 必须稳定（U-8）。
 *
 * 随机兜底会让每次快照都产生新 key：LazyColumn 重建全部气泡、按 id 合并去重全部失效，
 * 用户消息在重连后看起来“变了两份”。这里钉住三个性质：同输入同输出、
 * 不同消息不同输出、以及带 id 的消息不被改写。
 */
class HistoryMessageIdTest {
    private fun content(text: String): JsonArray =
        buildJsonArray { add(buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(text))
        }) }

    @Test
    fun sameMessageYieldsSameId() {
        val a = historyMessageId("user", content("你好"), 3)
        val b = historyMessageId("user", content("你好"), 3)
        assertEquals(a, b)
    }

    @Test
    fun differentTextOrIndexOrRoleYieldsDifferentId() {
        val base = historyMessageId("user", content("你好"), 3)
        assertNotEquals(base, historyMessageId("user", content("再见"), 3))
        assertNotEquals(base, historyMessageId("user", content("你好"), 4))
        assertNotEquals(base, historyMessageId("assistant", content("你好"), 3))
    }

    @Test
    fun nullContentDoesNotThrow() {
        val id = historyMessageId("system", null, 0)
        assertEquals(id, historyMessageId("system", null, 0))
    }

    @Test
    fun idIsStableForDeeplyEqualJson() {
        // 同一个 JSON 由不同实例构造（反序列化 vs 内存构造）也必须得到同一个 id。
        val fromObject = historyMessageId("assistant", content("完成"), 1)
        val rebuilt = historyMessageId(
            "assistant",
            JsonArray(listOf(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("完成"))
            })),
            1,
        )
        assertEquals(fromObject, rebuilt)
    }
}
