package dev.picarry.app.data

import kotlinx.serialization.Serializable

/** 一个已配对的 pi 节点。 */
@Serializable
data class Node(
    val id: String,
    val name: String,
    val endpointId: String,
    val token: String = "",
    val code: String? = null,
    val addrs: List<String> = emptyList(),
    val relay: String? = null,
    val platform: String = "windows",
    val capability: String = "full",
    val lastSeen: Long = 0L,
    /** 节点上报的 pi 版本（`hello` 携带）；空串表示未探测到。 */
    val piVersion: String = "",
    /** 节点上报的 pi-carry 版本。 */
    val appVersion: String = "",
    /** 节点当前 relay 模式（n0 / disabled / custom），来自 `node.status`。 */
    val relayMode: String = "",
    /**
     * 节点上报的能力列表（`hello` 的 `node.capabilities`）。
     *
     * 老节点没有该字段时为 null/空列表：调用方据此降级（缺 `fs-browse` 只能选默认目录）。
     */
    val capabilities: List<String>? = null,
) {
    /** 是否声明支持某项能力；未知（老版本节点）统一当成不支持。 */
    fun supports(cap: String): Boolean = capabilities?.contains(cap) == true
}

enum class LinkState { Offline, Connecting, Online, Error }

@Serializable
data class Session(
    val id: String,
    val nodeId: String,
    val nodeName: String = "",
    val cwd: String = "",
    val name: String = "",
    val preview: String = "",
    val updatedAt: String = "",
    val messageCount: Long = 0,
    val status: String = "idle",
    val active: Boolean = false,
    val pendingApprovals: Int = 0,
    val lastSource: String = "",
    // 构造默认值只用于本地乐观构造（服务端快照路径统一由 `parseSession` 覆写）：
    // 本地新建的会话先当作可写，等 `session.writable` 到达后再收敛为真实值。
    val writable: Boolean = true,
    // 仅 session.list 返回：旧会话迁移后的目标 id；普通会话为 null。
    val migratedTo: String? = null,
) {
    val title: String get() = name.ifBlank {
        cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { "新会话" }
    }
    val isRunning: Boolean get() = status == "streaming" || status == "waiting_approval" || status == "waiting_input"
}

@Serializable
data class MessagePart(
    val kind: String = "text",
    val text: String = "",
)

/**
 * 会话里的一张图片。
 *
 * 0.2.0 起图片正文默认走内容寻址 blob：消息只携带 [blobId] 与 [size]，
 * 由客户端按需 blob.get 分片下载并缓存；历史冷数据或旧节点仍可能只有 base64 [data]。
 */
@Serializable
data class ImagePart(
    val mimeType: String = "image/jpeg",
    val data: String = "",
    val blobId: String? = null,
    val size: Long = 0,
    val unavailable: Boolean = false,
) {
    val isBlob: Boolean get() = !blobId.isNullOrBlank()
}

/** `session.entries` 的一个 entry（message 或 compaction 等）。 */
@Serializable
data class SessionEntry(
    val id: String,
    val parentId: String? = null,
    val type: String = "message",
    val timestamp: String = "",
    val role: String = "",
    val preview: String = "",
    // 载入时算一次，避免每个 item 组合时沿 parentId 上溯（B-45）。
    val depth: Int = 0,
)

data class EntriesPage(val entries: List<SessionEntry> = emptyList(), val leafId: String? = null)

/** 服务端广播的 `session.migrated`。 */
data class SessionMigration(
    val nodeId: String,
    val fromSessionId: String,
    val toSessionId: String,
    val reason: String,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: String = "",
    val result: String = "",
    val status: String = "running",
    val durationMs: Long = 0,
    val isError: Boolean = false,
    val images: List<ImagePart> = emptyList(),
)

@Serializable
data class Approval(
    val id: String,
    val sessionId: String,
    val nodeId: String = "",
    val toolName: String,
    val category: String = "other",
    val risk: String = "high",
    val summary: String = "",
    val command: String? = null,
    val path: String? = null,
    val cwd: String = "",
    val requestedAt: String = "",
    val expiresAt: String = "",
    var remainingSeconds: Long = 120,
    val state: String = "pending",
)

@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    var text: String = "",
    var thinking: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var streaming: Boolean = false,
    var tool: ToolCall? = null,
    var approval: Approval? = null,
    var images: List<ImagePart> = emptyList(),
    var error: Boolean = false,
    /** 与服务端 `run.summary` 的 runId 对齐；历史冷数据可能没有，整轮归属靠 0 值区分。 */
    var runId: String = "",
    /** pi message 自身的 `timestamp`（已归一化为毫秒）；缺失时为 0。 */
    var tsMs: Long = 0L,
    /** 该条消息的 usage 快照；非 assistant 消息或老节点未上报时为 null。 */
    var usage: MessageUsage? = null,
)

/** 单条模型调用的用量。字段与服务端 `usage` 保持同名字段，便于直接比对各文档。 */
@Serializable
data class MessageUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
    val cost: Double = 0.0,
)

/**
 * 模型选项。
 *
 * `context`/`price` 是既有的展示字符串（保留兼容），`inputPrice`/`outputPrice` 是
 * 从节点 `cost{input,output}` 解析出的「每百万 token 美元」数值，UI 据此统一格式化，
 * 不再依赖各自硬编码的价格文案。
 */
data class ModelOption(
    val provider: String,
    val id: String,
    val name: String,
    val context: String = "",
    val price: String = "",
    val providerLabel: String = providerLabelFor(provider),
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val reasoning: Boolean = false,
    /**
     * 该条目只是「当前正在用但已不在 `models.list` 里」的伪条目（见 [withCurrentModel]）。
     *
     * 伪条目的名字回退成裸 id，看起来和正常模型一模一样；UI 必须据此标注，否则用户
     * 不知道自己正在用的模型已经下线。仍然保持可点：用户可能就想切回来。
     */
    val unavailable: Boolean = false,
) {
    /** 复合键：跨 provider 存在同名 id，只比 id 会误勾选。 */
    val key: String get() = "$provider|$id"

    /** 价格展示；两项都缺失时返回空串（老节点没有 cost 字段）。 */
    val priceText: String get() = when {
        inputPrice != null && outputPrice != null -> "$${fmtPrice(inputPrice)} / $${fmtPrice(outputPrice)}"
        inputPrice != null -> "$${fmtPrice(inputPrice)} in"
        outputPrice != null -> "$${fmtPrice(outputPrice)} out"
        else -> price
    }
}

data class ThinkingLevel(val id: String, val label: String)

/** 已知供应商的品牌名；未知 id 直接回显，不伪造品牌。 */
fun providerLabelFor(provider: String): String = when (provider.lowercase()) {
    "anthropic" -> "Anthropic"
    "openai" -> "OpenAI"
    "google" -> "Google"
    "x-ai", "xai" -> "xAI"
    "deepseek" -> "DeepSeek"
    "openrouter" -> "OpenRouter"
    "ollama" -> "Ollama"
    "mistral" -> "Mistral"
    "groq" -> "Groq"
    "custom" -> "自定义"
    else -> provider
}

/** 单价格式化：整数不带小数，其余最多两位并去掉末尾 0。 */
private fun fmtPrice(v: Double): String {
    if (v == v.toLong().toDouble()) return v.toLong().toString()
    return String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
}

data class CommandItem(val name: String, val description: String, val source: String = "extension")
