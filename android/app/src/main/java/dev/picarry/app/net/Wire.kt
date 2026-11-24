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
    /**
     * 客户端发出的帧上限。服务端读客户端帧固定按 `MAX_CONTROL_FRAME`（1 MiB）校验
     * （`crates/core/src/server.rs: read_frame(&mut recv, MAX_CONTROL_FRAME)`），
     * 所以客户端任何写入都必须落在这个值以下，否则必然被对端拒帧。
     */
    const val MAX_CONTROL = 1024 * 1024
    /**
     * 服务端写出事件帧的上限（`MAX_EVENT_FRAME` = 4 MiB）。客户端读帧时还不知道类型，
     * 只能一律按这个上限收；写侧仍受 [MAX_CONTROL] 约束——两个上限不可混用。
     */
    const val MAX_EVENT = 4 * 1024 * 1024
    /** prompt.send 除图片与正文外还有 sessionId/mode/clientId 等字段，预留信封余量。 */
    const val PROMPT_FRAME_BUDGET = MAX_CONTROL - 8 * 1024

    fun frame(payload: String): ByteArray {
        val body = payload.encodeToByteArray()
        // 异常消息会经 UI 错误气泡露出，这里给可操作的中文文案而不是“帧过大”。
        require(body.size <= MAX_CONTROL) {
            "消息超过单帧上限（${body.size / 1024} KiB > ${MAX_CONTROL / 1024} KiB），请减少图片或缩短正文"
        }
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

    fun ping(nonce: Long): String = buildJsonObject {
        put("type", JsonPrimitive("ping"))
        put("nonce", JsonPrimitive(nonce))
    }.toString()

    fun length(header: ByteArray): Int = ByteBuffer.wrap(header).int

    data class Frame(val type: String, val id: String?, val ok: Boolean, val payload: JsonObject?, val error: JsonObject?, val sessionId: String?, val seq: Long, val event: JsonObject?)

    /** 非 JSON 对象或字段类型不符时抛 [IllegalArgumentException]，由读循环丢弃单帧而不是断链。 */
    fun parse(text: String): Frame {
        val obj = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("帧不是 JSON 对象")
        return when (obj.str("type")) {
            "response" -> Frame("response", obj.str("id"), obj.str("ok") == "true", obj["payload"] as? JsonObject, obj["error"] as? JsonObject, null, 0, null)
            "event" -> Frame("event", null, true, null, null, obj.str("sessionId"), obj.long("seq"), obj["event"] as? JsonObject)
            "ack" -> Frame("ack", null, true, null, null, obj.str("sessionId"), obj.long("seq"), null)
            "request" -> Frame("request", obj.str("id"), true, obj["payload"] as? JsonObject, null, null, 0, null)
            "ping", "pong" -> Frame(obj.str("type")!!, null, true, null, null, null, 0, null)
            else -> Frame(obj.str("type") ?: "unknown", null, true, null, null, null, 0, null)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0

    private fun JsonPrimitive.contentOrNull(): String? = if (content == "null") null else content
}
