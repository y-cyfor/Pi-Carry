package dev.picarry.app.data

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.picarry.app.MainActivity
import dev.picarry.app.PiCarryApp
import android.util.Log
import dev.picarry.app.net.CarryException
import dev.picarry.app.net.NodeConnection
import dev.picarry.app.net.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/** 应用级状态与网络编排。 */
class CarryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = NodeStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _nodes = MutableStateFlow(store.loadNodes())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()

    private val _link = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    val link: StateFlow<Map<String, LinkState>> = _link.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _approvals = MutableStateFlow<List<Approval>>(emptyList())
    val approvals: StateFlow<List<Approval>> = _approvals.asStateFlow()

    private val _models = MutableStateFlow<Map<String, List<ModelOption>>>(emptyMap())
    val models: StateFlow<Map<String, List<ModelOption>>> = _models.asStateFlow()

    private val _thinkingLevels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val thinkingLevels: StateFlow<Map<String, List<String>>> = _thinkingLevels.asStateFlow()

    private val _runInfo = MutableStateFlow<Map<String, JsonObject>>(emptyMap())
    val runInfo: StateFlow<Map<String, JsonObject>> = _runInfo.asStateFlow()

    /** auto / dark / light；默认 auto，跟随系统。 */
    private val _themeMode = MutableStateFlow(
        store.loadString("theme_mode", "auto").takeIf { it in setOf("auto", "dark", "light") } ?: "auto"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val connections = java.util.concurrent.ConcurrentHashMap<String, NodeConnection>()
    private val sessionNode = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        scope.launch { _nodes.value.filter { it.token.isNotBlank() || !it.code.isNullOrBlank() }.forEach { connect(it.id) } }
    }

    fun node(id: String): Node? = _nodes.value.firstOrNull { it.id == id }

    fun addNode(node: Node) {
        val existing = _nodes.value.firstOrNull { it.endpointId == node.endpointId }
        val merged = if (existing == null) {
            _nodes.value + node
        } else {
            _nodes.value.map { if (it.id == existing.id) node.copy(id = existing.id) else it }
        }
        _nodes.value = merged
        store.saveNodes(merged)
        connect(node.id)
    }

    fun removeNode(id: String) {
        connections.remove(id)?.stop()
        val next = _nodes.value.filterNot { it.id == id }
        _nodes.value = next
        store.saveNodes(next)
        _sessions.value = _sessions.value.filterNot { it.nodeId == id }
    }

    fun connect(id: String) {
        val node = node(id) ?: return
        connections[id]?.stop()
        val conn = NodeConnection(
            node = node,
            scope = scope,
            onEvent = { sessionId, seq, event -> onEvent(id, sessionId, seq, event) },
            onState = { state, error ->
                _link.value = _link.value + (id to state)
                _errors.value = if (error == null) _errors.value - id else _errors.value + (id to error)
            },
            onHandshake = { payload -> onHandshake(id, payload) },
        )
        connections[id] = conn
        conn.start()
    }

    fun disconnect(id: String) {
        connections.remove(id)?.stop()
        _link.value = _link.value + (id to LinkState.Offline)
    }

    fun reconnect(id: String) {
        connect(id)
    }

    private fun onHandshake(nodeId: String, payload: JsonObject) {
        val device = payload["device"] as? JsonObject
        val info = payload["node"] as? JsonObject
        val capability = device?.get("capability")?.jsonPrimitive?.contentOrNull ?: "control"
        val name = info?.get("name")?.jsonPrimitive?.contentOrNull
        val platform = info?.get("platform")?.jsonPrimitive?.contentOrNull
        val updated = _nodes.value.map { n ->
            if (n.id == nodeId) n.copy(
                capability = capability,
                name = name?.takeIf { it.isNotBlank() && n.name == n.endpointId.take(8) } ?: n.name,
                platform = platform ?: n.platform,
                lastSeen = System.currentTimeMillis(),
            ) else n
        }
        _nodes.value = updated
        store.saveNodes(updated)
        refreshSessions(nodeId)
        // 重连后补拉已打开会话的完整快照，避免断网期间的事件空洞。
        _messages.value.keys.filter { it.startsWith("$nodeId::") }.forEach { key ->
            val sid = key.substringAfter("::")
            if (syncing.add(key)) {
                scope.launch {
                    try {
                        openSession(nodeId, sid)
                    } finally {
                        syncing.remove(key)
                    }
                }
            }
        }
    }

    fun refreshSessions(nodeId: String) {
        scope.launch {
            runCatching {
                val resp = connections[nodeId]?.request("session.list") ?: return@runCatching
                val arr = resp["sessions"] as? JsonArray ?: return@runCatching
                val list = arr.mapNotNull { parseSession(nodeId, it as? JsonObject ?: return@mapNotNull null) }
                list.forEach { sessionNode[it.id] = nodeId }
                val others = _sessions.value.filterNot { it.nodeId == nodeId }
                _sessions.value = (others + list).sortedByDescending { it.updatedAt }
            }.onFailure { Log.w("CarryRepo", "刷新会话失败: ${it.message}") }
        }
    }

    fun sessionKey(nodeId: String, sessionId: String) = "$nodeId::$sessionId"

    fun openSession(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request("session.open", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
                val snap = conn.request("session.snapshot", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
                val msgs = (snap["messages"] as? JsonArray)?.mapIndexedNotNull { i, el ->
                    parseHistoryMessage(sessionId, i, el as? JsonObject ?: return@mapIndexedNotNull null)
                }.orEmpty()
                val key = sessionKey(nodeId, sessionId)
                _messages.value = _messages.value + (key to msgs)
                val s = snap["session"] as? JsonObject
                if (s != null) upsertSession(nodeId, parseSession(nodeId, s))
                (snap["run"] as? JsonObject)?.let { _runInfo.value = _runInfo.value + (key to it) }
                val pending = (snap["pendingApprovals"] as? JsonArray)?.mapNotNull { parseApproval(nodeId, it as? JsonObject ?: return@mapNotNull null) }.orEmpty()
                if (pending.isNotEmpty()) {
                    _approvals.value = (_approvals.value.filterNot { it.sessionId == sessionId } + pending)
                }
                loadModelCatalog(conn, nodeId, sessionId)
            }.onFailure { Log.w("CarryRepo", "打开会话失败: ${it.message}") }
        }
    }

    /** 从节点读取真实可用模型与思考等级；失败时保留内置兜底列表。 */
    private suspend fun loadModelCatalog(conn: NodeConnection, nodeId: String, sessionId: String) {
        val key = sessionKey(nodeId, sessionId)
        runCatching {
            val resp = conn.request("models.list", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            val items = (resp["models"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
                val ctx = o["contextWindow"]?.jsonPrimitive?.intOrNull
                ModelOption(
                    provider = provider,
                    id = id,
                    name = name,
                    context = if (ctx != null) "${ctx / 1000}k ctx" else "",
                    price = "",
                )
            }.orEmpty()
            if (items.isNotEmpty()) _models.value = _models.value + (key to items)
        }.onFailure { Log.w("CarryRepo", "读取模型列表失败: ${it.message}") }
        runCatching {
            val resp = conn.request("thinking.levels", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            val levels = (resp["levels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if (levels.isNotEmpty()) _thinkingLevels.value = _thinkingLevels.value + (key to levels)
        }.onFailure { Log.w("CarryRepo", "读取思考等级失败: ${it.message}") }
    }

    fun createSession(nodeId: String, cwd: String = "", onCreated: (Session) -> Unit = {}) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                val resp = conn.request("session.create", buildJsonObject { put("cwd", JsonPrimitive(cwd)) })
                val obj = resp["session"] as? JsonObject ?: error("会话创建失败")
                val s = parseSession(nodeId, obj)
                sessionNode[s.id] = nodeId
                upsertSession(nodeId, s)
                onCreated(s)
            }.onFailure { Log.w("CarryRepo", "创建会话失败: ${it.message}") }
        }
    }

    fun sendPrompt(
        nodeId: String,
        sessionId: String,
        text: String,
        mode: String = "auto",
        images: List<Triple<String, String, String>> = emptyList(),
    ) {
        if (text.isBlank() && images.isEmpty()) return
        val key = sessionKey(nodeId, sessionId)
        val local = ChatMessage(
            id = "local-${UUID.randomUUID()}",
            sessionId = sessionId,
            role = "user",
            text = if (images.isEmpty()) text else "$text\n\n📎 ${images.size} 张图片".trim(),
        )
        _messages.value = _messages.value + (key to ((_messages.value[key] ?: emptyList()) + local))
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request(
                    "prompt.send",
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
                        put("message", JsonPrimitive(text))
                        put("mode", JsonPrimitive(mode))
                        put("clientId", JsonPrimitive(UUID.randomUUID().toString()))
                        put("images", buildJsonArray {
                            images.forEach { (mime, data, _) ->
                                add(buildJsonObject {
                                    put("mimeType", JsonPrimitive(mime))
                                    put("data", JsonPrimitive(data))
                                })
                            }
                        })
                    },
                    60_000,
                )
            }.onFailure {
                _messages.value = _messages.value + (key to ((_messages.value[key] ?: emptyList()) + ChatMessage(
                    id = "err-${UUID.randomUUID()}",
                    sessionId = sessionId,
                    role = "system",
                    text = (it as? CarryException)?.message ?: (it.message ?: "发送失败"),
                    error = true,
                )))
            }
        }
    }

    fun abort(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request("prompt.abort", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            }
        }
    }

    fun resolveApproval(nodeId: String, approvalId: String, allow: Boolean, remember: Boolean = false) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request(
                    "approval.resolve",
                    buildJsonObject {
                        put("approvalId", JsonPrimitive(approvalId))
                        put("decision", JsonPrimitive(if (allow) "allow" else "deny"))
                        put("remember", JsonPrimitive(remember))
                    },
                )
                _approvals.value = _approvals.value.filterNot { it.id == approvalId }
            }.onFailure { Log.w("CarryRepo", "审批失败: ${it.message}") }
        }
    }

    fun setModel(nodeId: String, sessionId: String, provider: String, modelId: String) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request(
                    "model.set",
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
                        put("provider", JsonPrimitive(provider))
                        put("modelId", JsonPrimitive(modelId))
                    },
                )
                val key = sessionKey(nodeId, sessionId)
                val run = _runInfo.value[key] ?: buildJsonObject {}
                _runInfo.value = _runInfo.value + (key to buildJsonObject {
                    run.forEach { (k, v) -> put(k, v) }
                    put("model", buildJsonObject {
                        put("provider", JsonPrimitive(provider))
                        put("id", JsonPrimitive(modelId))
                    })
                })
            }.onFailure { Log.w("CarryRepo", "切换模型失败: ${it.message}") }
        }
    }

    fun setThinking(nodeId: String, sessionId: String, level: String) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request(
                    "thinking.set",
                    buildJsonObject {
                        put("sessionId", JsonPrimitive(sessionId))
                        put("level", JsonPrimitive(level))
                    },
                )
                val key = sessionKey(nodeId, sessionId)
                val run = _runInfo.value[key] ?: buildJsonObject {}
                _runInfo.value = _runInfo.value + (key to buildJsonObject {
                    run.forEach { (k, v) -> put(k, v) }
                    put("thinkingLevel", JsonPrimitive(level))
                })
            }.onFailure { Log.w("CarryRepo", "切换思考等级失败: ${it.message}") }
        }
    }

    fun closeSession(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request("session.close", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
                _messages.value = _messages.value - sessionKey(nodeId, sessionId)
            }
        }
    }

    fun compact(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request(
                    "session.compact",
                    buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) },
                    120_000,
                )
            }
        }
    }

    fun revokeDevice(nodeId: String, deviceId: String) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request("device.revoke", buildJsonObject { put("deviceId", JsonPrimitive(deviceId)) })
            }
        }
    }

    fun renameNode(id: String, name: String) {
        val next = _nodes.value.map { if (it.id == id) it.copy(name = name) else it }
        _nodes.value = next
        store.saveNodes(next)
    }

    fun settingBool(key: String, default: Boolean): Boolean = store.loadBool(key, default)

    fun setSettingBool(key: String, value: Boolean) = store.saveBool(key, value)

    fun settingString(key: String, default: String): String = store.loadString(key, default)

    fun setSettingString(key: String, value: String) = store.saveString(key, value)

    fun setThemeMode(mode: String) {
        if (mode !in setOf("auto", "dark", "light")) return
        store.saveString("theme_mode", mode)
        _themeMode.value = mode
    }

    private fun notifyApproval(a: Approval) {
        val open = PendingIntent.getActivity(
            appContext,
            a.id.hashCode(),
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = a.command.orEmpty().ifBlank { a.toolName }
        val notification = NotificationCompat.Builder(appContext, PiCarryApp.CHANNEL_APPROVAL)
            .setSmallIcon(dev.picarry.app.R.drawable.ic_notification)
            .setContentTitle("pi 请求审批 · ${a.toolName}")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        // Android 13+ 未授予通知权限时静默跳过，审批仍可在应用内处理。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { NotificationManagerCompat.from(appContext).notify(a.id.hashCode(), notification) }
        }
    }

    private fun onEvent(nodeId: String, sessionId: String?, seq: Long, event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        val sid = sessionId ?: event["sessionId"]?.jsonPrimitive?.contentOrNull
        when (type) {
            "session.updated" -> (event["session"] as? JsonObject)?.let { upsertSession(nodeId, parseSession(nodeId, it)) }
            "message.delta" -> sid?.let { applyDelta(it, event) }
            "message.final" -> sid?.let { applyFinal(it, event) }
            "tool.started" -> sid?.let { applyToolStarted(it, event) }
            "tool.updated" -> sid?.let { applyToolUpdated(it, event) }
            "tool.ended" -> sid?.let { applyToolEnded(it, event) }
            "approval.requested", "approval.updated" -> {
                val a = (event["approval"] as? JsonObject)?.let { parseApproval(nodeId, it) }
                if (a != null) {
                    _approvals.value = _approvals.value.filterNot { it.id == a.id } + a
                    notifyApproval(a)
                }
            }
            "approval.resolved" -> {
                val id = event["approvalId"]?.jsonPrimitive?.contentOrNull
                _approvals.value = _approvals.value.filterNot { it.id == id }
                id?.let { NotificationManagerCompat.from(appContext).cancel(it.hashCode()) }
            }
            "run.state" -> sid?.let { onRunState(it, event) }
            "session.conflict" -> sid?.let {
                val writable = event["writable"]?.jsonPrimitive?.contentOrNull != "false"
                _sessions.value = _sessions.value.map { s -> if (s.id == it) s.copy(writable = writable) else s }
            }
            "error" -> Log.w("CarryRepo", "节点 $nodeId 事件错误: ${event["message"]}")
        }
        if (seq > 0) Log.v("CarryRepo", "event $type seq=$seq")
    }

    private fun upsertSession(nodeId: String, s: Session) {
        sessionNode[s.id] = nodeId
        val list = _sessions.value.filterNot { it.id == s.id } + s
        _sessions.value = list.sortedByDescending { it.updatedAt }
    }

    private fun applyDelta(sessionId: String, event: JsonObject) {
        val nodeId = sessionNode[sessionId] ?: return
        val key = sessionKey(nodeId, sessionId)
        val messageId = event["messageId"]?.jsonPrimitive?.contentOrNull ?: return
        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
        val kind = event["kind"]?.jsonPrimitive?.contentOrNull ?: "text"
        val list = (_messages.value[key] ?: emptyList()).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx < 0) {
            list += ChatMessage(id = messageId, sessionId = sessionId, role = "assistant", streaming = true)
        }
        val at = list.indexOfFirst { it.id == messageId }
        val msg = list[at]
        list[at] = if (kind == "thinking") msg.copy(thinking = msg.thinking + delta, streaming = true)
        else msg.copy(text = msg.text + delta, streaming = true)
        _messages.value = _messages.value + (key to list)
    }

    private fun applyFinal(sessionId: String, event: JsonObject) {
        val nodeId = sessionNode[sessionId] ?: return
        val key = sessionKey(nodeId, sessionId)
        val messageId = event["messageId"]?.jsonPrimitive?.contentOrNull ?: return
        val raw = event["message"] as? JsonObject
        val list = (_messages.value[key] ?: emptyList()).toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        var text = raw?.get("content")?.let(::contentText).orEmpty()
        var thinking = raw?.get("content")?.let(::contentThinking).orEmpty()
        if (text.isBlank()) {
            val old = list.getOrNull(idx)
            text = old?.text.orEmpty()
            thinking = old?.thinking.orEmpty()
        }
        val base = list.getOrNull(idx) ?: ChatMessage(id = messageId, sessionId = sessionId, role = "assistant")
        val done = base.copy(text = text.ifBlank { base.text }, thinking = thinking.ifBlank { base.thinking }, streaming = false)
        if (idx >= 0) list[idx] = done else list += done
        _messages.value = _messages.value + (key to list)
        _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(status = "idle", preview = text.take(80)) else it }
    }

    private fun applyToolStarted(sessionId: String, event: JsonObject) {
        val nodeId = sessionNode[sessionId] ?: return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val name = event["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val args = event["args"]?.toString() ?: ""
        val list = (_messages.value[key] ?: emptyList()) + ChatMessage(
            id = "tool-$id",
            sessionId = sessionId,
            role = "tool",
            tool = ToolCall(id = id, name = name, args = args, status = "running"),
        )
        _messages.value = _messages.value + (key to list)
    }

    private fun applyToolUpdated(sessionId: String, event: JsonObject) {
        val nodeId = sessionNode[sessionId] ?: return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val partial = event["partialResult"]?.toString() ?: ""
        updateTool(key, id) { it.copy(result = partial) }
    }

    private fun applyToolEnded(sessionId: String, event: JsonObject) {
        val nodeId = sessionNode[sessionId] ?: return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val result = event["result"]?.let(::contentText)?.ifBlank { event["result"]?.toString().orEmpty() }.orEmpty()
        val isError = event["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val duration = event["durationMs"]?.jsonPrimitive?.longOrNull ?: 0
        updateTool(key, id) { it.copy(result = result, status = if (isError) "error" else "done", isError = isError, durationMs = duration) }
    }

    private fun updateTool(key: String, id: String, f: (ToolCall) -> ToolCall) {
        val list = (_messages.value[key] ?: return).map { m ->
            m.tool?.let { t -> if (t.id == id) m.copy(tool = f(t)) else m } ?: m
        }
        _messages.value = _messages.value + (key to list)
    }

    private fun onRunState(sessionId: String, event: JsonObject) {
        val state = event["state"]?.jsonPrimitive?.contentOrNull ?: return
        val status = when (state) {
            "streaming" -> "streaming"
            "waiting_approval" -> "waiting_approval"
            "retrying" -> "streaming"
            "error" -> "error"
            else -> "idle"
        }
        _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(status = status, active = status != "idle") else it }
    }

    private fun parseSession(nodeId: String, obj: JsonObject): Session = Session(
        id = obj.str("id") ?: "",
        nodeId = nodeId,
        nodeName = node(nodeId)?.name.orEmpty(),
        cwd = obj.str("cwd").orEmpty(),
        name = obj.str("name").orEmpty(),
        preview = obj.str("preview").orEmpty(),
        updatedAt = obj.str("updatedAt").orEmpty(),
        messageCount = obj["messageCount"]?.jsonPrimitive?.longOrNull ?: 0,
        status = obj.str("status") ?: "idle",
        active = obj.bool("active"),
        pendingApprovals = obj["pendingApprovals"]?.jsonPrimitive?.intOrNull ?: 0,
        lastSource = obj.str("lastSource") ?: "mobile",
        writable = obj.bool("writable", true),
    )

    private fun parseApproval(nodeId: String, obj: JsonObject): Approval = Approval(
        id = obj.str("id") ?: "",
        sessionId = obj.str("sessionId") ?: "",
        nodeId = nodeId,
        toolName = obj.str("toolName") ?: "tool",
        category = obj.str("category") ?: "other",
        risk = obj.str("risk") ?: "high",
        summary = obj.str("summary").orEmpty(),
        command = obj.str("command"),
        path = obj.str("path"),
        requestedAt = obj.str("requestedAt").orEmpty(),
        expiresAt = obj.str("expiresAt").orEmpty(),
        remainingSeconds = obj["remainingSeconds"]?.jsonPrimitive?.longOrNull ?: 120,
        state = obj.str("state") ?: "pending",
    )

    private fun parseHistoryMessage(sessionId: String, index: Int, obj: JsonObject): ChatMessage? {
        val role = obj.str("role") ?: return null
        if (role !in listOf("user", "assistant")) return null
        val content = obj["content"]
        val text = content?.let(::contentText).orEmpty()
        val thinking = content?.let(::contentThinking).orEmpty()
        if (text.isBlank() && thinking.isBlank()) return null
        return ChatMessage(
            id = obj.str("id") ?: "h-$index-${UUID.randomUUID()}",
            sessionId = sessionId,
            role = role,
            text = text,
            thinking = thinking,
        )
    }

    private fun contentText(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        is JsonPrimitive -> if (el.isString) el.content else ""
        is JsonArray -> el.joinToString("\n") { item ->
            val o = item as? JsonObject
            when {
                o == null -> item.toString()
                o.str("type") == "text" -> o.str("text").orEmpty()
                o.str("type") == "toolCall" -> ""
                else -> ""
            }
        }.trim()
        else -> ""
    }

    private fun contentThinking(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        is JsonArray -> el.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "thinking") o.str("thinking") ?: o.str("text") else null
        }.joinToString("\n")
        else -> ""
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default
