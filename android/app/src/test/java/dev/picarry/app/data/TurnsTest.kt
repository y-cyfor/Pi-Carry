package dev.picarry.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 4：轮次推导与格式化。 */
class TurnsTest {

    private fun msg(
        id: String,
        role: String,
        runId: String = "",
        usage: MessageUsage? = null,
        ts: Long = 0L,
    ) = ChatMessage(id = id, sessionId = "s1", role = role, runId = runId, usage = usage, tsMs = ts)

    @Test
    fun `按 runId 归轮并以最后一条消息为锚点`() {
        val messages = listOf(
            msg("u1", "user", runId = "r1"),
            msg("a1", "assistant", runId = "r1", usage = MessageUsage(input = 1200, output = 100, total = 1300)),
            msg("t1", "tool", runId = "r1"),
            msg("u2", "user", runId = "r2"),
            msg("a2", "assistant", runId = "r2", usage = MessageUsage(input = 50, output = 10, total = 60)),
        )
        val turns = computeTurns(messages)
        assertEquals(2, turns.size)
        // 锚点是该轮最后一条消息，哪怕它是 tool。
        assertEquals("t1", turns[0].anchorMessageId)
        assertEquals(1300, turns[0].displayTokens)
        assertEquals("a2", turns[1].anchorMessageId)
        assertEquals(60, turns[1].displayTokens)
    }

    @Test
    fun `run_summary 覆盖本地推导且缺字段保持 null`() {
        val messages = listOf(
            msg("u1", "user", runId = "r1"),
            msg("a1", "assistant", runId = "r1", usage = MessageUsage(input = 10, output = 1)),
        )
        val summary = RunSummary(
            runId = "r1",
            durationMs = 46000,
            requests = 3,
            inputTokens = 1200,
            outputTokens = 3400,
            cacheReadTokens = 800,
            cacheWriteTokens = 0,
            totalTokens = 4600,
            cost = 0.00069,
            firstTokenMs = 850,
            model = "fake/fake-1",
        )
        val turn = computeTurns(messages, mapOf("r1" to summary)).single()
        assertEquals(4600, turn.displayTokens)
        assertEquals(46000L, turn.durationMs)
        assertEquals(3, turn.requests)
        assertEquals("fake/fake-1", turn.model)
        assertEquals(0.00069, turn.cost!!, 1e-9)
        assertEquals("0分钟46秒", turn.durationText)
    }

    @Test
    fun `中断轮的 aborted 标记与总结行文案`() {
        val messages = listOf(
            msg("u1", "user", runId = "r1"),
            msg("a1", "assistant", runId = "r1", usage = MessageUsage(input = 10, output = 1)),
        )
        val summary = RunSummary(
            runId = "r1",
            durationMs = 46000,
            inputTokens = 1200,
            outputTokens = 3400,
            totalTokens = 4600,
            aborted = true,
        )
        val turn = computeTurns(messages, mapOf("r1" to summary)).single()
        // 服务端把这一轮标成中断：总结行要能追加「已中断」，且行内数字不能是空串。
        assertTrue(turn.aborted)
        assertEquals("4.6k", turn.tokenText)
        assertEquals("0分钟46秒", turn.durationText)
        // 没有 run.summary 的冷轮推导不出中断状态，默认按正常运行展示。
        assertFalse(computeTurns(messages).single().aborted)
    }

    @Test
    fun `没有 assistant 的块不产生轮次`() {
        val turns = computeTurns(listOf(msg("u1", "user", runId = "r1")))
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `没有 runId 的老数据按消息顺序切轮并用时间戳近似时长`() {
        val messages = listOf(
            msg("u1", "user", ts = 1_000),
            msg("a1", "assistant", ts = 9_000, usage = MessageUsage(input = 100, output = 20)),
            msg("u2", "user", ts = 10_000),
            msg("a2", "assistant", ts = 11_000),
        )
        val turns = computeTurns(messages)
        assertEquals(2, turns.size)
        assertEquals(8_000L, turns[0].durationMs)
        assertEquals("local-u1", turns[0].key)
        // 冷轮拿不到 firstTokenMs / 模型名，面板应显示 —。
        assertNull(turns[0].firstTokenMs)
        assertEquals("", turns[0].model)
    }

    @Test
    fun `工具循环里的多条 assistant 合并计入同一轮`() {
        val messages = listOf(
            msg("u1", "user", runId = "r1"),
            msg("a1", "assistant", runId = "r1", usage = MessageUsage(input = 100, output = 10)),
            msg("t1", "tool", runId = "r1"),
            msg("a2", "assistant", runId = "r1", usage = MessageUsage(input = 200, output = 20)),
        )
        val turn = computeTurns(messages).single()
        assertEquals(2, turn.requests)
        assertEquals(300, turn.inputTokens)
        assertEquals(30, turn.outputTokens)
        assertEquals("a2", turn.anchorMessageId)
    }

    @Test
    fun `时长与 token 格式化`() {
        assertEquals("0分钟8秒", formatDuration(8_000))
        assertEquals("46分钟25秒", formatDuration(2_785_000))
        assertEquals("0分钟0秒", formatDuration(-5))
        assertEquals("999", formatTokens(999))
        assertEquals("1.0k", formatTokens(1_000))
        assertEquals("4.6k", formatTokens(4_600))
        assertEquals("1.5m", formatTokens(1_500_000))
        assertEquals("—", formatPercent(null))
        assertEquals("50%", formatPercent(0.5))
        assertEquals("—", formatSpeed(null))
        assertEquals("10.0 tok/s", formatSpeed(10.0))
        assertEquals("—", formatCost(null))
        assertEquals("$0.0007", formatCost(0.00069))
        assertEquals("—", formatMs(null))
        assertEquals("1.8s", formatMs(1800))
    }

    @Test
    fun `缓存命中率与平均速度按口径计算`() {
        val turn = Turn(
            key = "r1",
            anchorMessageId = "a1",
            durationMs = 2_000,
            requests = 1,
            inputTokens = 200,
            outputTokens = 100,
            cacheReadTokens = 800,
            cacheWriteTokens = 0,
            totalTokens = 1_100,
            cost = null,
            firstTokenMs = null,
            model = "",
            aborted = false,
            hasAssistant = true,
        )
        assertEquals(0.8, turn.cacheHitRate!!, 1e-9)
        assertEquals(50.0, turn.avgSpeed!!, 1e-9)
        val empty = turn.copy(inputTokens = 0, cacheReadTokens = 0, durationMs = 0)
        assertNull(empty.cacheHitRate)
        assertNull(empty.avgSpeed)
    }

    @Test
    fun `总结事件解析保留 null 而不是零值`() {
        val event = buildJsonObject {
            put("type", "run.summary")
            put("runId", "r1")
            put("durationMs", 8_000)
            put("requests", 2)
            put("inputTokens", 1200)
            put("outputTokens", 340)
            put("cacheReadTokens", 800)
            put("totalTokens", 1540)
            put("cost", 0.00069)
            put("model", "fake/fake-1")
            put("aborted", false)
        }
        val summary = parseRunSummary(event)!!
        assertEquals(8_000L, summary.durationMs)
        // 事件里没有 firstTokenMs，必须保持 null。
        assertNull(summary.firstTokenMs)
        assertEquals(1540L, summary.totalTokens)
        assertFalse(summary.aborted)
        // 缺 runId 无法归轮，返回 null。
        assertNull(parseRunSummary(JsonObject(mapOf("type" to JsonPrimitive("run.summary")))))
    }

    @Test
    fun `连接方式解析与文案`() {
        val info = parseConnInfo(
            buildJsonObject {
                put("type", "lan")
                put("remoteAddr", "192.168.1.5:41234")
                put("rttMs", 21)
                put("direct", true)
            },
        )!!
        assertEquals("lan", info.type)
        assertEquals("局域网 · 21ms", info.detail)
        assertEquals("relay 转发", connectionLabel("relay"))
        assertEquals("v6 直连", connectionLabel("v6"))
        assertEquals("p2p 直连", connectionLabel("unknown"))
        // 没有 rtt 时只显示类型。
        assertEquals("v4 直连", ConnInfo(type = "v4").detail)
        assertNull(parseConnInfo(JsonObject(emptyMap())))
    }

    @Test
    fun `usage 与时间戳解析兼容多种形态`() {
        val usage = parseUsage(
            buildJsonObject {
                put("input", 1200)
                put("output", 340)
                put("cacheRead", 800)
                put("cost", buildJsonObject { put("total", 0.00069) })
            },
        )!!
        assertEquals(1200, usage.input)
        assertEquals(1540, usage.total)
        assertEquals(0.00069, usage.cost, 1e-9)
        assertNull(parseUsage(buildJsonObject { }))
        assertNull(parseUsage(null))

        assertEquals(1_700_000_000_000L, messageTsMs(buildJsonObject { put("timestamp", 1_700_000_000_000L) }))
        // ISO 字符串（带 Z）也要能解析。
        assertEquals(0L, messageTsMs(buildJsonObject { put("timestamp", "1970-01-01T00:00:00Z") }))
        assertNull(messageTsMs(buildJsonObject { put("timestamp", "nonsense") }))
        assertNull(messageTsMs(null))
    }

    // ---- C-1：没有 runId 的冷轮按时间窗接回 run.summary ----

    /** ISO 基准时间：历史消息的 tsMs 与 summary 的 startedAt/endedAt 必须落在同一时间轴上。 */
    private val baseMs = 1_700_000_000_000L

    private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    private fun summary(
        runId: String,
        startMs: Long,
        endMs: Long,
        cost: Double? = 0.00069,
        firstTokenMs: Long? = 1_800,
    ) = RunSummary(
        runId = runId,
        startedAt = iso(startMs),
        endedAt = iso(endMs),
        durationMs = 9_000,
        requests = 2,
        cost = cost,
        firstTokenMs = firstTokenMs,
    )

    @Test
    fun `没有 runId 的冷轮按时间窗接回 run_summary 的权威字段`() {
        val messages = listOf(
            msg("u1", "user", ts = baseMs + 500),
            msg("a1", "assistant", ts = baseMs + 5_000, usage = MessageUsage(input = 100, output = 20)),
        )
        val turn = computeTurns(messages, mapOf("r1" to summary("r1", baseMs, baseMs + 10_000))).single()

        assertEquals(0.00069, turn.cost!!, 1e-9)
        assertEquals(1_800L, turn.firstTokenMs)
        assertEquals(9_000L, turn.durationMs)
        assertEquals(2, turn.requests)
    }

    @Test
    fun `时间窗外的 summary 不会被冷轮占用`() {
        val messages = listOf(
            msg("u1", "user", ts = baseMs + 600_000),
            msg("a1", "assistant", ts = baseMs + 605_000),
        )
        val turn = computeTurns(messages, mapOf("r1" to summary("r1", baseMs, baseMs + 10_000))).single()

        assertNull(turn.cost)
        assertNull(turn.firstTokenMs)
    }

    @Test
    fun `同一条 summary 不会被两轮重复使用`() {
        val messages = listOf(
            msg("u1", "user", ts = baseMs + 1_000),
            msg("a1", "assistant", ts = baseMs + 2_000),
            msg("u2", "user", ts = baseMs + 3_000),
            msg("a2", "assistant", ts = baseMs + 4_000),
        )
        // 时间窗盖住两个块的首条消息：不占用就会把同一轮的成本算两遍。
        val summaries = mapOf("r1" to summary("r1", baseMs, baseMs + 10_000))
        val turns = computeTurns(messages, summaries)

        assertEquals(2, turns.size)
        assertEquals(0.00069, turns[0].cost!!, 1e-9)
        assertNull(turns[1].cost)
        assertNull(turns[1].firstTokenMs)
    }

    @Test
    fun `ISO 时间解析失败时退化为本地推导而不是报错`() {
        val messages = listOf(
            msg("u1", "user", ts = baseMs + 1_000),
            msg("a1", "assistant", ts = baseMs + 2_000),
        )
        val broken = RunSummary(runId = "r1", startedAt = "nonsense", endedAt = "", cost = 1.0)
        val turn = computeTurns(messages, mapOf("r1" to broken)).single()

        assertNull(turn.cost)
        assertNull(turn.firstTokenMs)
    }

    @Test
    fun `没有 usage 的老轮次请求数按 assistant 消息条数统计`() {
        // 老节点/半路 attach 的轮次没有 usage：请求数不能因为拿不到 usage 就显示 0（N-10）。
        val messages = listOf(
            msg("u1", "user", runId = "r1"),
            msg("a1", "assistant", runId = "r1"),
            msg("t1", "tool", runId = "r1"),
            msg("a2", "assistant", runId = "r1"),
            msg("a3", "assistant", runId = "r1"),
        )
        val turn = computeTurns(messages).single()

        assertEquals(3, turn.requests)
        assertEquals(0, turn.inputTokens)
    }
}
