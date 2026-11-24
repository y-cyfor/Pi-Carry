package dev.picarry.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一轮运行的总结，对应服务端 `run.summary` 事件。
 *
 * 字段全部允许缺失：半路 attach 的会话拿不到 `agent_start`，服务端会明确发 `null`
 * 而不是伪造 0。这里的默认值同样用 null 表示「未知」，避免 UI 把未知显示成 0。
 */
data class RunSummary(
    val runId: String = "",
    val startedAt: String = "",
    val endedAt: String = "",
    val durationMs: Long? = null,
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val totalTokens: Long? = null,
    val cost: Double? = null,
    val firstTokenMs: Long? = null,
    val model: String = "",
    val aborted: Boolean = false,
)

/**
 * 某条连接的实测方式（服务端 `connection.updated` 的 `connection` 体）。
 *
 * `type` 只保留服务端六种取值；未知值一律回退 `p2p`，不把服务端内部枚举泄露到 UI。
 */
data class ConnInfo(
    val type: String = "p2p",
    val remoteAddr: String = "",
    val localAddr: String = "",
    val rttMs: Long? = null,
    val direct: Boolean = true,
) {
    /** 界面文案：局域网 / relay 转发 / v6 直连 … */
    val label: String get() = connectionLabel(type)

    /** 「局域网 · 21ms」形式的小字；没有 rtt 时只有类型。 */
    val detail: String get() = if (rttMs != null) "$label · ${rttMs}ms" else label
}

/** 与服务端 `crates/core/src/conninfo.rs` 的展示文案保持一致。 */
fun connectionLabel(type: String): String = when (type) {
    "relay" -> "relay 转发"
    "lan" -> "局域网"
    "v4" -> "v4 直连"
    "v6" -> "v6 直连"
    else -> "p2p 直连"
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

/**
 * 解析 `run.summary` 事件；缺 `runId` 时返回 null（无法归轮，挂上去只会污染 UI）。
 *
 * 显式 preserve null：服务端在无 usage 时把 totalTokens/cost 置 null，
 * 这里不能 `?: 0`，否则轨迹面板会把「未知」显示成 0。
 */
internal fun parseRunSummary(event: JsonObject): RunSummary? {
    val runId = event.strOrNull("runId")?.takeIf { it.isNotBlank() } ?: return null
    return RunSummary(
        runId = runId,
        startedAt = event.strOrNull("startedAt").orEmpty(),
        endedAt = event.strOrNull("endedAt").orEmpty(),
        durationMs = event.longOrNull("durationMs"),
        requests = event.longOrNull("requests")?.toInt() ?: 0,
        inputTokens = event.longOrNull("inputTokens") ?: 0,
        outputTokens = event.longOrNull("outputTokens") ?: 0,
        cacheReadTokens = event.longOrNull("cacheReadTokens") ?: 0,
        cacheWriteTokens = event.longOrNull("cacheWriteTokens") ?: 0,
        totalTokens = event.longOrNull("totalTokens"),
        cost = event.doubleOrNull("cost"),
        firstTokenMs = event.longOrNull("firstTokenMs"),
        model = event.strOrNull("model").orEmpty(),
        aborted = event.strOrNull("aborted") == "true",
    )
}

/** 解析 `connection` 体（hello 响应与 connection.updated 事件共用）。 */
internal fun parseConnInfo(obj: JsonObject?): ConnInfo? {
    obj ?: return null
    val type = obj.strOrNull("type")?.takeIf { it.isNotBlank() } ?: return null
    return ConnInfo(
        type = type,
        remoteAddr = obj.strOrNull("remoteAddr").orEmpty(),
        localAddr = obj.strOrNull("localAddr").orEmpty(),
        rttMs = obj.longOrNull("rttMs"),
        direct = obj.strOrNull("direct") != "false",
    )
}
