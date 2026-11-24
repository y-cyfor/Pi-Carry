package dev.picarry.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class WireTest {
    @Test
    fun frameUsesBigEndianLengthPrefix() {
        val payload = """{"type":"ping"}"""
        val bytes = Wire.frame(payload)
        assertEquals(4 + payload.toByteArray().size, bytes.size)
        assertEquals(payload.toByteArray().size, ByteBuffer.wrap(bytes, 0, 4).int)
        assertEquals(payload, String(bytes, 4, bytes.size - 4))
    }

    @Test
    fun requestContainsIdOpAndPayload() {
        val payload = buildJsonObject { put("sessionId", "s-1") }
        val parsed = Wire.parse(Wire.request("r7", "session.open", payload))
        assertEquals("request", parsed.type)
        assertEquals("r7", parsed.id)
        assertEquals("s-1", parsed.payload!!["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun responseCarriesOkAndPayload() {
        val parsed = Wire.parse(
            """{"type":"response","id":"r1","ok":true,"payload":{"node":{"name":"pc"}}}"""
        )
        assertEquals("response", parsed.type)
        assertTrue(parsed.ok)
        assertEquals("pc", parsed.payload!!["node"]!!.jsonObject()["name"]!!.jsonPrimitive.content)
        assertNull(parsed.error)
    }

    @Test
    fun failedResponseCarriesError() {
        val parsed = Wire.parse(
            """{"type":"response","id":"r2","ok":false,"error":{"code":"SESSION_BUSY","message":"busy"}}"""
        )
        assertFalse(parsed.ok)
        assertEquals("SESSION_BUSY", parsed.error!!["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun eventCarriesSessionAndSequence() {
        val parsed = Wire.parse(
            """{"type":"event","sessionId":"s1","seq":42,"event":{"type":"run.state","status":"streaming"}}"""
        )
        assertEquals("event", parsed.type)
        assertEquals("s1", parsed.sessionId)
        assertEquals(42L, parsed.seq)
        assertEquals("run.state", parsed.event!!["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun ackOmitsNullSession() {
        val parsed = Wire.parse(Wire.ack(null, 9))
        assertEquals("ack", parsed.type)
        assertEquals(9L, parsed.seq)
        assertNull(parsed.sessionId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameRejectsOversizePayload() {
        // 服务端读客户端帧统一按 1MiB 控制帧上限，超过就是必被拒的一帧。
        Wire.frame("x".repeat(Wire.MAX_CONTROL + 1))
    }

    @Test
    fun eventFrameLimitIsLargerThanControlLimit() {
        // 读侧要能收 4MiB 事件帧，写侧不能超 1MiB：两个上限不能混用。
        assertTrue(Wire.MAX_EVENT > Wire.MAX_CONTROL)
        assertTrue(Wire.PROMPT_FRAME_BUDGET < Wire.MAX_CONTROL)
    }

    @Test
    fun parseKeepsSeqOfEventWithoutPayload() {
        // 载荷损坏但 seq 还在的事件必须能识别出 seq，否则无法 ACK、服务端会永久重放。
        val parsed = Wire.parse("""{"type":"event","sessionId":"s1","seq":17,"event":"oops"}""")
        assertEquals("event", parsed.type)
        assertEquals(17L, parsed.seq)
        assertEquals("s1", parsed.sessionId)
        assertNull(parsed.event)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsNonObjectFrame() {
        Wire.parse("[1,2,3]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsBrokenJson() {
        Wire.parse("{\"type\":")
    }

    @Test
    fun parseToleratesWrongFieldTypes() {
        // 单条畸形字段不能让整条连接重连：能解析的部分要保留，不能硬 cast 崩溃。
        val parsed = Wire.parse("""{"type":"event","sessionId":5,"seq":"x","event":{"type":"message.delta"}}""")
        assertEquals("event", parsed.type)
        assertEquals("5", parsed.sessionId)
        assertEquals("无法解析的 seq 按 0 处理，不丢帧", 0L, parsed.seq)
        assertEquals("message.delta", parsed.event!!["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun pingUsesNumericNonceForRustProtocol() {
        // 服务端 ClientFrame::Ping.nonce 是 i64；字符串 nonce 会被协议层判为非法帧，永远收不到 pong。
        val text = Wire.ping(123L)
        assertTrue(text.contains("\"nonce\":123"))
        assertFalse(text.contains("\"123\""))
    }

    @Test
    fun parseReadsPongFrame() {
        val parsed = Wire.parse("""{"type":"pong","nonce":"n1"}""")
        assertEquals("pong", parsed.type)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObject(): JsonObject = this as JsonObject
}
