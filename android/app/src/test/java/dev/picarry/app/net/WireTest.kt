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
        Wire.frame("x".repeat(Wire.MAX_EVENT + 1))
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObject(): JsonObject = this as JsonObject
}
