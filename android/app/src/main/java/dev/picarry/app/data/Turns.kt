package dev.picarry.app.data

/**
 * 一轮（一次 agent run）的聚合结果，用于会话流里的总结行与轨迹面板。
 *
 * `summary` 非 null 表示拿到了服务端 `run.summary`（字段权威，缺失显示 `—`）；
 * 为 null 表示只能从历史消息本地推导（首字时长永远未知）。
 */
data class Turn(
    /** 归轮键：runId 或本地推导的时间窗键。 */
    val key: String,
    /** 本轮最后一条消息的 id，总结行插在它之后。 */
    val anchorMessageId: String,
    val durationMs: Long?,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val totalTokens: Long,
    val cost: Double?,
    val firstTokenMs: Long?,
    val model: String,
    val aborted: Boolean,
    /** 该轮是否至少有一条 assistant 消息；没有就不显示总结行。 */
    val hasAssistant: Boolean,
) {
    /** 行内数字：本轮 input + output。 */
    val displayTokens: Long get() = inputTokens + outputTokens

    /** `46分钟25秒 · 4.6k` 的右侧部分。 */
    // 没有 usage 时不能显示 `0`：会被读成「本轮没花 token」，而实际情况是数据缺失。
    // 与左侧的时长 `—` 对齐，缺失一律显示 `—`（BUG-008）。
    val tokenText: String get() = if (displayTokens == 0L) "—" else formatTokens(displayTokens)

    /** `0分钟8秒` 形式，永不为空（时长未知时显示 `—`）。 */
    val durationText: String get() = durationMs?.let(::formatDuration) ?: "—"

    /** 缓存命中率 `cacheRead/(input+cacheRead)`；分母为 0 时返回 null。 */
    val cacheHitRate: Double? get() {
        val denom = inputTokens + cacheReadTokens
        if (denom <= 0) return null
        return cacheReadTokens.toDouble() / denom
    }

    /** 平均输出速度（token/秒）；时长缺失或为 0 时返回 null。 */
    val avgSpeed: Double? get() {
        val ms = durationMs ?: return null
        if (ms <= 0) return null
        return outputTokens.toDouble() * 1000.0 / ms
    }
}

/** 用时始终「分 + 秒」：`0分钟8秒` / `46分钟25秒`。 */
fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val minutes = total / 60
    val seconds = total % 60
    return "${minutes}分钟${seconds}秒"
}

/**
 * token 数格式化：`<1000` 原样、`<1e6` 一位小数 `k`、否则一位小数 `m`。
 *
 * 1000 → `1.0k`、4600 → `4.6k`、1_500_000 → `1.5m`。
 */
fun formatTokens(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 1_000_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
    else -> String.format(java.util.Locale.US, "%.1fm", n / 1_000_000.0)
}

/** 缓存命中率展示为百分数；null 显示 `—`。 */
fun formatPercent(v: Double?): String =
    if (v == null) "—" else String.format(java.util.Locale.US, "%.0f%%", v * 100)

/** 平均速度展示为 `123.4 tok/s`；null 显示 `—`。 */
fun formatSpeed(v: Double?): String =
    if (v == null) "—" else String.format(java.util.Locale.US, "%.1f tok/s", v)

/** 成本展示：`$0.0007`；null 显示 `—`。 */
fun formatCost(v: Double?): String =
    if (v == null) "—" else "$" + String.format(java.util.Locale.US, "%.4f", v)

/** 毫秒展示：`1800` → `1.8s`；null 显示 `—`。 */
fun formatMs(v: Long?): String =
    if (v == null) "—" else String.format(java.util.Locale.US, "%.1fs", v / 1000.0)

/**
 * 从消息流推导轮次。
 *
 * 归轮依据：消息自带的 `runId` 优先；没有 runId 的老数据按「连续 assistant/tool
 * 消息 + 其前面的 user 消息」归轮，用首条消息 id 作为键（确定性，重算稳定）。
 * `summaries` 提供权威字段，取不到时用消息自身 usage/时间戳推导。
 *
 * 没有 runId 的块（journal 回放的历史消息不带 runId）还会按时间窗再匹配一次
 * `run.summary`：否则重连后每一轮都退化成「本地推导」，权威 cost/首字时长白丢（C-1）。
 */
fun computeTurns(
    messages: List<ChatMessage>,
    summaries: Map<String, RunSummary> = emptyMap(),
): List<Turn> {
    if (messages.isEmpty()) return emptyList()
    // 切块规则（按优先顺序）：
    //  1) 同一 runId 的消息归同一轮（插话/排队也带同一个 runId）；
    //  2) runId 变了就是新轮；
    //  3) 没有 runId 时，只要当前块已经产出过 assistant，新的 user 消息就开启新一轮；
    //  4) 其余（tool 结果、连续的 user 消息）归入当前块。
    class Block(var runId: String, val items: MutableList<ChatMessage> = mutableListOf())

    val blocks = mutableListOf<Block>()
    for (m in messages) {
        val rid = m.runId
        val b = blocks.lastOrNull()
        val newBlock = b == null ||
            (rid.isNotBlank() && b.runId.isNotBlank() && rid != b.runId) ||
            (m.role == "user" && b.items.any { it.role == "assistant" })
        if (newBlock) {
            blocks += Block(rid, mutableListOf(m))
        } else {
            if (rid.isNotBlank() && b.runId.isBlank()) b.runId = rid
            b.items += m
        }
    }

    // 时间窗候选：把 ISO 的 startedAt/endedAt 预解析成毫秒。解析失败（字段缺失或畸形）
    // 的 summary 不参与时间窗匹配，只留给 runId 精确命中——猜错时间窗比不匹配更糟。
    val windows = summaries.values
        .mapNotNull { s ->
            val start = isoEpochMs(s.startedAt)
            val end = isoEpochMs(s.endedAt)
            if (start == null || end == null) null else SummaryWindow(s, start, end)
        }
        .sortedWith(compareBy({ it.startMs }, { it.summary.runId }))
    // 一条 summary 只能被一轮占用：按块顺序先到先得，避免两条冷轮把同一轮的成本算两遍。
    val claimed = HashSet<String>()

    return blocks.mapNotNull { block ->
        val assistants = block.items.filter { it.role == "assistant" }
        if (assistants.isEmpty()) return@mapNotNull null
        val summary = block.runId.takeIf { it.isNotBlank() }?.let { summaries[it] }
            ?: matchSummaryByWindow(block.items, windows, claimed)
        summary?.let { claimed += it.runId }
        val input = summary?.inputTokens ?: assistants.sumOf { it.usage?.input ?: 0 }
        val output = summary?.outputTokens ?: assistants.sumOf { it.usage?.output ?: 0 }
        val cacheRead = summary?.cacheReadTokens ?: assistants.sumOf { it.usage?.cacheRead ?: 0 }
        val cacheWrite = summary?.cacheWriteTokens ?: assistants.sumOf { it.usage?.cacheWrite ?: 0 }
        val usageTotal = assistants.sumOf { it.usage?.total ?: 0 }
        // 成本只从「有 usage 的消息」汇总；一条都没有时保持 null（面板显示 —，不是 $0.0000）。
        val cost = summary?.cost
            ?: assistants.mapNotNull { it.usage?.cost }.takeIf { it.isNotEmpty() }?.sum()
        Turn(
            key = block.runId.ifBlank { "local-${block.items.first().id}" },
            anchorMessageId = block.items.last().id,
            durationMs = summary?.durationMs ?: localDuration(block.items),
            // 请求数口径与 plan/服务端一致：每条 assistant 消息对应一次模型调用。
            // 旧实现只数「带 usage 的 assistant」，老节点/半路 attach 的轮次没有 usage，
            // 明明跑了 N 次调用却显示 0（N-10）。
            requests = summary?.requests ?: assistants.size,
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            totalTokens = summary?.totalTokens ?: (if (usageTotal > 0) usageTotal else input + output),
            cost = cost,
            firstTokenMs = summary?.firstTokenMs,
            // 模型名只能来自服务端 summary；历史冷轮不自造（面板显示 —）。
            model = summary?.model.orEmpty(),
            aborted = summary?.aborted ?: false,
            hasAssistant = true,
        )
    }
}

/** 一条可在时间窗里被匹配的 summary（毫秒已解析）。 */
private class SummaryWindow(val summary: RunSummary, val startMs: Long, val endMs: Long)

/** 时间窗两侧的容差：落盘时间戳与 run.summary 的起止时间来自不同时钟点，允许 2 秒偏差。 */
private const val SUMMARY_WINDOW_SLACK_MS = 2_000L

/**
 * ISO 时间转毫秒；解析失败返回 null（该 summary 不参与时间窗匹配，不影响其它轮次）。
 *
 * 先按带偏移的 ISO 解析，再退回 [java.time.Instant]：两种形态服务端都出现过。
 * minSdk 26 起 `java.time` 原生可用，不需要 core library desugaring。
 */
private fun isoEpochMs(text: String): Long? {
    if (text.isBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(text).toEpochMilli() }
        .getOrNull()
}

/**
 * 按首条消息的时间戳给无 runId 的块找一条未占用的 summary。
 *
 * 判据是 `[startedAt - 2s, endedAt + 2s]`：历史消息的 ts 是 pi 落盘时间，
 * 与 run.summary 的起止（服务端计时）会差几十毫秒到一两秒，必须留容差。
 */
private fun matchSummaryByWindow(
    items: List<ChatMessage>,
    windows: List<SummaryWindow>,
    claimed: Set<String>,
): RunSummary? {
    val ts = items.firstOrNull()?.tsMs?.takeIf { it > 0 } ?: return null
    return windows.firstOrNull { w ->
        w.summary.runId !in claimed &&
            ts >= w.startMs - SUMMARY_WINDOW_SLACK_MS && ts <= w.endMs + SUMMARY_WINDOW_SLACK_MS
    }?.summary
}

/** 冷数据没有 run.summary：用「首条 user/assistant 时间」到「最后一条消息时间」近似本轮用时。 */
private fun localDuration(items: List<ChatMessage>): Long? {
    val stamps = items.mapNotNull { it.tsMs.takeIf { ts -> ts > 0 } }
    if (stamps.size < 2) return null
    val span = (stamps.maxOrNull() ?: return null) - (stamps.minOrNull() ?: return null)
    return if (span > 0) span else null
}

/** 按 runId 取轮次；历史冷轮没有 runId 时用 key 前缀 `local-` 标识。 */
fun turnForMessage(turns: List<Turn>, messageId: String): Turn? =
    turns.firstOrNull { it.anchorMessageId == messageId }
