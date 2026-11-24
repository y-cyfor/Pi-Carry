package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快照合并必须清掉已被服务端历史确认的 `local-` 乐观消息，
 * 否则每次重连/重进会话都会多出一份用户消息；仍在途的乐观消息不能被误删。
 */
class SnapshotMergeTest {
    private fun local(id: String, text: String) =
        ChatMessage(id = id, sessionId = "s1", role = "user", text = text)

    private fun server(id: String, text: String) =
        ChatMessage(id = id, sessionId = "s1", role = "user", text = text)

    @Test
    fun confirmedLocalMessageIsDropped() {
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "你好")),
            existing = listOf(local("local-a", "你好")),
        )
        assertEquals(listOf("m1"), merged.map { it.id })
    }

    @Test
    fun inFlightLocalMessageIsKeptEvenWithSameText() {
        // 请求还没返回时快照里出现同文本消息，可能是另一条同内容的新消息，不能当作已确认。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "你好")),
            existing = listOf(local("local-a", "你好")),
            inFlight = setOf("local-a"),
        )
        assertEquals(listOf("m1", "local-a"), merged.map { it.id })
    }

    @Test
    fun unmatchedLocalMessageIsKept() {
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "你好")),
            existing = listOf(local("local-a", "还没到服务端的消息")),
        )
        assertEquals(listOf("m1", "local-a"), merged.map { it.id })
    }

    @Test
    fun duplicateTextsAreConsumedOneToOne() {
        // 同一句话发了两次，快照只回来一份：只能吃掉一条本地副本。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "继续")),
            existing = listOf(local("local-a", "继续"), local("local-b", "继续")),
        )
        assertEquals(listOf("m1", "local-b"), merged.map { it.id })
    }

    @Test
    fun imageSuffixIsIgnoredWhenMatching() {
        // 本地副本带图片后缀，服务端历史只存正文。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "看图")),
            existing = listOf(local("local-a", "看图\n\n📎 2 张图片")),
        )
        assertEquals(listOf("m1"), merged.map { it.id })
    }

    @Test
    fun streamingAndToolMessagesSurviveSnapshot() {
        val streaming = ChatMessage(id = "m2", sessionId = "s1", role = "assistant", streaming = true)
        val tool = ChatMessage(
            id = "m3",
            sessionId = "s1",
            role = "tool",
            tool = ToolCall(id = "call-1", name = "bash"),
        )
        val merged = mergeSnapshotMessages(
            snapshot = listOf(server("m1", "你好")),
            existing = listOf(streaming, tool),
        )
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
    }

    private fun assistant(id: String, text: String, streaming: Boolean = false) =
        ChatMessage(id = id, sessionId = "s1", role = "assistant", text = text, streaming = streaming)

    private fun serverAssistant(id: String, text: String) =
        ChatMessage(id = id, sessionId = "s1", role = "assistant", text = text)

    @Test
    fun completedAssistantWithSameTextIsDropped() {
        // 快照用的是会话文件 entry id，message.final 的 messageId 来自 bridge 的 ULID：
        // 两者永不相同，只能按正文一对一消耗，否则重连后 assistant 会双份。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "回答A")),
            existing = listOf(assistant("ulid-1", "回答A")),
        )
        assertEquals(listOf("e1"), merged.map { it.id })
    }

    @Test
    fun assistantWithDifferentTextIsKept() {
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "回答A")),
            existing = listOf(assistant("ulid-1", "回答B")),
        )
        assertEquals(listOf("e1", "ulid-1"), merged.map { it.id })
    }

    @Test
    fun streamingAssistantIsKeptEvenWithSameText() {
        // 还在输出的消息可能尚未落盘，快照里的同文本副本不是同一份。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "回答A")),
            existing = listOf(assistant("ulid-1", "回答A", streaming = true)),
        )
        assertEquals(listOf("e1", "ulid-1"), merged.map { it.id })
    }

    @Test
    fun duplicateAssistantTextsAreConsumedOneToOne() {
        // 快照里两条同文本、旧消息一条：只能吃掉一条，剩下的那条仍可能属于另一份历史。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "回答A"), serverAssistant("e2", "回答A")),
            existing = listOf(assistant("ulid-1", "回答A")),
        )
        assertEquals(listOf("e1", "e2"), merged.map { it.id })
    }

    @Test
    fun singleSnapshotAssistantConsumesOnlyOneOldCopy() {
        // 反过来的一对一：旧消息两条同文本、快照只一条，只能吃掉一条。
        // 若实现成「快照命中就删掉所有同文本旧消息」，这里会只剩 e1。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "回答A")),
            existing = listOf(assistant("ulid-1", "回答A"), assistant("ulid-2", "回答A")),
        )
        assertEquals(listOf("e1", "ulid-2"), merged.map { it.id })
    }

    @Test
    fun assistantWhitespaceDifferencesStillMatch() {
        // 流式拼接与落盘后正文的换行/空格可能不同，归一化后仍应视为同一份。
        val merged = mergeSnapshotMessages(
            snapshot = listOf(serverAssistant("e1", "第一段\n\n第二段")),
            existing = listOf(assistant("ulid-1", "第一段 第二段")),
        )
        assertEquals(listOf("e1"), merged.map { it.id })
    }
}
