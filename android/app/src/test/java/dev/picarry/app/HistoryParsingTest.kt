package dev.picarry.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.mergeHistoryMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 重开 App 后历史工具调用与结果不能丢失，图片也要能还原。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HistoryParsingTest {
    private val repo: CarryRepository
        get() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE).edit().clear().commit()
            return CarryRepository(context)
        }

    @Test
    fun assistantToolCallAndToolResultSurviveHistoryReload() {
        val r = repo
        val assistant = Json.parseToJsonElement(
            """
            {"id":"a1","role":"assistant","content":[
              {"type":"text","text":"先看一下目录"},
              {"type":"toolCall","id":"call-1","name":"bash","arguments":{"command":"ls -la"}}
            ]}
            """.trimIndent(),
        ).jsonObject
        val toolResult = Json.parseToJsonElement(
            """
            {"id":"r1","role":"toolResult","toolCallId":"call-1","toolName":"bash","isError":false,
             "durationMs":42,"content":[{"type":"text","text":"total 0\ndrwxr-xr-x"}]}
            """.trimIndent(),
        ).jsonObject

        val msgs = mergeHistoryMessages(
            r.parseHistoryMessages("s1", 0, assistant) +
                r.parseHistoryMessages("s1", 1, toolResult),
        )

        assertEquals(2, msgs.size)
        assertEquals("assistant", msgs[0].role)
        assertEquals("先看一下目录", msgs[0].text)
        assertEquals("tool", msgs[1].role)
        assertEquals("call-1", msgs[1].tool?.id)
        assertEquals("bash", msgs[1].tool?.name)
        assertEquals("{\"command\":\"ls -la\"}", msgs[1].tool?.args)
        assertEquals("total 0\ndrwxr-xr-x", msgs[1].tool?.result)
        assertEquals(42L, msgs[1].tool?.durationMs)
    }

    @Test
    fun toolResultImageIsParsedFromStructuredContent() {
        val obj = Json.parseToJsonElement(
            """
            {"id":"r2","role":"toolResult","toolCallId":"call-2","toolName":"read","isError":false,
             "content":[{"type":"text","text":"截图"},{"type":"image","mimeType":"image/png","data":"QUJD"}]}
            """.trimIndent(),
        ).jsonObject

        val msg = repo.parseHistoryMessages("s1", 0, obj).single()

        assertEquals("截图", msg.tool?.result)
        assertEquals(1, msg.tool?.images?.size)
        assertEquals("image/png", msg.tool?.images?.first()?.mimeType)
        assertEquals("QUJD", msg.tool?.images?.first()?.data)
        assertTrue(msg.tool?.result?.contains("QUJD") != true)
    }

    @Test
    fun missingToolCallIdYieldsStableIdAcrossRepeatedParsing() {
        // 老节点/老 journal 的 toolResult 可能缺 toolCallId（N-11）。
        // 兜底 id 必须每次回放都相同，否则历史合并去重会把同一条结果当成新消息越堆越多。
        val r = repo
        val json = """
            {"role":"toolResult","toolName":"bash","isError":false,
             "content":[{"type":"text","text":"total 0"}]}
        """.trimIndent()
        val first = r.parseHistoryMessages("s1", 7, Json.parseToJsonElement(json).jsonObject).single()
        val second = r.parseHistoryMessages("s1", 7, Json.parseToJsonElement(json).jsonObject).single()

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("tool-tr-7-"))
        // 同一条消息在合并时应该被认成同一个 id，而不是两条。
        assertEquals(1, mergeHistoryMessages(listOf(first) + listOf(second)).size)
    }
}
