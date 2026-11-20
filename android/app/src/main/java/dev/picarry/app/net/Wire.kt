package dev.picarry.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer

/** carry.v1 帧：4 字节大端长度 + UTF-8 JSON。 */
object Wire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    const val MAX_CONTROL = 1024 * 1024
    const val MAX_EVENT = 4 * 1024 * 1024

    fun frame(payload: String): ByteArray {
        val body = payload.encodeToByteArray()
        require(body.size <= MAX_EVENT) { "帧过大: ${body.size}" }
        return ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
    }

    fun request(id: String, op: String, payload: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("type", JsonPrimitive("request"))
            put("id", JsonPrimitive(id))
            put("op", JsonPrimitive(op))
            put("payload", payload)
        }.toString()

    fun ack(sessionId: String?, seq: Long): String = buildJsonObject {
        put("type", JsonPrimitive("ack"))
        if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        put("seq", JsonPrimitive(seq))
    }.toString()

    fun ping(nonce: String): String = buildJsonObject {
        put("type", JsonPrimitive("ping"))
        put("nonce", JsonPrimitive(nonce))
    }.toString()

    fun length(header: ByteArray): Int = ByteBuffer.wrap(header).int

    data class Frame(val type: String, val id: String?, val ok: Boolean, val payload: JsonObject?, val error: JsonObject?, val sessionId: String?, val seq: Long, val event: JsonObject?)

    fun parse(text: String): Frame {
        val obj = json.parseToJsonElement(text).jsonObject
        return when (obj["type"]?.jsonPrimitive?.content) {
            "response" -> Frame("response", obj["id"]?.jsonPrimitive?.contentOrNull(), obj["ok"]?.jsonPrimitive?.content == "true", obj["payload"] as? JsonObject, obj["error"] as? JsonObject, null, 0, null)
            "event" -> Frame("event", null, true, null, null, obj["sessionId"]?.jsonPrimitive?.contentOrNull(), obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0, obj["event"] as? JsonObject)
            "ack" -> Frame("ack", null, true, null, null, obj["sessionId"]?.jsonPrimitive?.contentOrNull(), obj["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0, null)
            "request" -> Frame("request", obj["id"]?.jsonPrimitive?.contentOrNull(), true, obj["payload"] as? JsonObject, null, null, 0, null)
            "ping", "pong" -> Frame(obj["type"]!!.jsonPrimitive.content, null, true, null, null, null, 0, null)
            else -> Frame(obj["type"]?.jsonPrimitive?.content ?: "unknown", null, true, null, null, null, 0, null)
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = if (content == "null") null else content
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as JsonObject

private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as JsonPrimitive
