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
)

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
    val lastSource: String = "mobile",
    val writable: Boolean = true,
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

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: String = "",
    val result: String = "",
    val status: String = "running",
    val durationMs: Long = 0,
    val isError: Boolean = false,
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
    var error: Boolean = false,
)

data class ModelOption(val provider: String, val id: String, val name: String, val context: String = "", val price: String = "")
data class ThinkingLevel(val id: String, val label: String)

data class CommandItem(val name: String, val description: String, val source: String = "extension")
