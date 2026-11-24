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
import dev.picarry.app.net.classifyAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    companion object {
        /** 服务端在事件流滞后时发出的错误码：客户端必须重拉快照。 */
        const val SNAPSHOT_REQUIRED = "SNAPSHOT_REQUIRED"
    }
    private val appContext = context.applicationContext
    private val store = NodeStore(appContext)
    private val clientVersion: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "0.1.4"
    }

    /** 电脑端看到的是“用户设备名 + 型号”，不再统一显示 pi carry。 */
    private fun clientName(): String {
        val deviceName = runCatching {
            android.provider.Settings.Global.getString(
                appContext.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME,
            )
        }.getOrNull()?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOfNotNull(
            deviceName.takeIf { it.isNotEmpty() },
            model.takeIf { it.isNotEmpty() && !it.equals(deviceName, ignoreCase = true) },
        ).joinToString(" ").ifBlank { "Android" }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 快照在途时先缓冲事件，避免快照整体覆盖期间到达的增量/最终消息丢失。
    // 值用普通 ArrayList：所有读写都在 snapshotGuard 内（见 onEvent 与 drainSession），
    // 早先的 CopyOnWriteArrayList 会对高频 delta 每次入队都全量拷贝（O(n²)）。
    private val pendingSnapshotEvents =
        java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<Long, JsonObject>>>()
    /** 保护 syncing 与缓冲队列的入队/出队切换，避免旧事件在快照后乱序落地。 */
    private val snapshotGuard = Any()

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes: StateFlow<List<Node>> = _nodes.asStateFlow()

    /** 节点列表是否已从磁盘恢复。UI 可据此区分「真的没有节点」与「还在加载」。 */
    private val _nodesLoaded = MutableStateFlow(false)
    val nodesLoaded: StateFlow<Boolean> = _nodesLoaded.asStateFlow()

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

    /**
     * 每轮运行总结，按 runId 索引。
     *
     * 按 runId 去重是必须的：`run.summary` 会进 journal，重连回放时同一轮会再来一次。
     */
    private val _runSummaries = MutableStateFlow<Map<String, RunSummary>>(emptyMap())
    val runSummaries: StateFlow<Map<String, RunSummary>> = _runSummaries.asStateFlow()

    /** 每个节点当前实测连接方式（来自 `connection.updated` 与 `hello`）；离线即清空。 */
    private val _connInfo = MutableStateFlow<Map<String, ConnInfo>>(emptyMap())
    val connInfo: StateFlow<Map<String, ConnInfo>> = _connInfo.asStateFlow()

    /** auto / dark / light；默认 auto，跟随系统。 */
    private val _themeMode = MutableStateFlow(
        store.loadString("theme_mode", "auto").takeIf { it in setOf("auto", "dark", "light") } ?: "auto"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val connections = java.util.concurrent.ConcurrentHashMap<String, NodeConnection>()
    /** 同一节点每次 connect 递增；旧实例的收尾回调据此丢弃，不能覆盖新连接的状态。 */
    private val connectionGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val connectionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    /** 尚未拿到 prompt.send 响应的乐观消息 id：快照与之文本撞车时不能当已确认删掉。 */
    private val inFlightLocalMessages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * 会话 -> 所属节点，键是 [sessionKey] 的复合键（`nodeId::sessionId`），值是 nodeId。
     *
     * 单键（只用 sessionId）是不够的：两台节点各自 `session.create` 出同名 id 时，
     * 后写的一条会把先写的那台的路由改掉，delta/final 于是落进另一台的会话（N-05）。
     * 值保留 nodeId 只为删节点时按前缀/值整体清理。
     */
    private val sessionNode = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastSeq = SeqWatermark()
    /** 会话 "打开代次"：closeSession/removeNode 递增，在途快照与 drain 据此放弃回写。 */
    private val sessionEpochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val notifiedApprovals = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** 已被服务端确认解决的审批：失败回填卡片时不能把它们复活成仍待处理的审批。
     *  值是所属 nodeId——删节点时才能只清掉该节点的已结算审批（N-09）。 */
    private val settledApprovals = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** runId -> nodeId：`_runSummaries` 的键是 runId，本身不带节点信息，
     *  删节点时必须靠这张旁表才能按节点清掉旧总结（否则重配对后旧总结会吞新事件，N-09）。 */
    private val runSummaryNodes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 凭据是否以明文落盘（Keystore 不可用时的兜底路径）：设置页据此给出可见警示。 */
    private val _credentialsPlaintext = MutableStateFlow(false)
    val credentialsPlaintext: StateFlow<Boolean> = _credentialsPlaintext.asStateFlow()

    @Volatile private var appForeground = false

    @Volatile private var visibleSessionId: String? = null

    init {
        // 不在构造函数里同步读 SharedPreferences：那是在主线程做磁盘 IO 与 JSON 反序列化
        // （StrictMode diskReads 违规，低端机冷启动有 ANR 风险）。
        scope.launch {
            val restored = store.loadNodes()
            refreshCredentialsPlaintext()
            // 异步加载完成前用户可能已经配对过新节点（addNode 直接写 _nodes）。
            // 这时不能把内存里的新节点覆盖回去，否则刚配对的节点会凭空消失。
            _nodes.mutate { current -> if (current.isEmpty()) restored else current }
            _nodesLoaded.value = true
            restored
                .filter { it.token.isNotBlank() || !it.code.isNullOrBlank() }
                .forEach { connect(it.id) }
        }
    }

    fun node(id: String): Node? = _nodes.value.firstOrNull { it.id == id }

    /** 落盘节点列表，并在 IO 线程重算「凭据是否明文」状态（prefs 读盘不能在主线程做）。 */
    private fun persistNodes(nodes: List<Node>) {
        store.saveNodes(nodes)
        scope.launch { refreshCredentialsPlaintext() }
    }

    private fun refreshCredentialsPlaintext() {
        _credentialsPlaintext.value = runCatching { store.credentialsArePlaintext() }.getOrDefault(false)
    }

    /**
     * 已配对的节点是否因为凭据解不开而“看不见”（N-01）。
     *
     * 与「没有节点」语义不同：设置/doctor 页应提示重新配对，而不是把用户当成新用户。
     */
    fun credentialsLost(): Boolean = store.credentialsLost()

    fun addNode(node: Node) {
        // 用 CAS 更新：配对本身在主线程跑，但 hello/节点状态可能同时在写 _nodes，
        // 「读整个列表 → 改 → 写回」会丢掉那一侧刚写入的更新（C-2）。
        var stored = node
        _nodes.mutate { current ->
            val existing = current.firstOrNull { it.endpointId == node.endpointId }
            // 重配对沿用旧 id：凭据已更新，但连接、事件键与 UI 都按旧 id 索引。
            stored = if (existing == null) node else node.copy(id = existing.id)
            if (existing == null) current + stored else current.map { if (it.id == stored.id) stored else it }
        }
        // 落盘用 CAS 之后的当前值：中途若有别的节点更新，这里正好一并保存。
        persistNodes(_nodes.value)
        connect(stored.id)
    }

    fun removeNode(id: String) {
        // 与 connectNow/disconnect 共用同一把锁：不持锁时，正在建立的新连接会在删除之后
        // 重新登记到 connections 里，成为没有任何界面引用的孤儿连接（读循环/心跳/重连都不释放）。
        // `_nodes` 的移除也必须在这把锁内（N-08）：否则 connectNow 里的存在性检查
        // 会在节点还没从列表里消失时通过，建好的连接随后变成孤儿。
        var next: List<Node> = emptyList()
        synchronized(connectionLocks.computeIfAbsent(id) { Any() }) {
            connections.remove(id)?.stop()
            next = _nodes.value.filterNot { it.id == id }
            _nodes.value = next
        }
        persistNodes(next)
        // 节点删除后所有派生状态都要清掉，否则重配对时事件会写进孤儿键。
        // 按节点整体清理（而不是只清 UI 见过的）：重新配对同一节点会复用旧 id，
        // 残留的 run 总结/已结算审批会把新节点的同名 runId、审批 id 当成旧事件吞掉（N-09）。
        val prefix = "$id::"
        val removedApprovals = _approvals.value.filter { it.nodeId == id }.map { it.id }.toSet()
        _link.mutate { it - id }
        _errors.mutate { it - id }
        _connInfo.mutate { it - id }
        _sessions.mutate { it.filterNot { s -> s.nodeId == id } }
        _approvals.mutate { it.filterNot { a -> a.nodeId == id } }
        _messages.mutate { it.filterKeys { k -> !k.startsWith(prefix) } }
        _models.mutate { it.filterKeys { k -> !k.startsWith(prefix) } }
        _thinkingLevels.mutate { it.filterKeys { k -> !k.startsWith(prefix) } }
        _runInfo.mutate { it.filterKeys { k -> !k.startsWith(prefix) } }
        // run 总结与节点没有键前缀关系，靠旁表按节点清：不然旧总结（含 cost/首字）
        // 会跟着复用同一 runId 的新一轮跑下去。
        _runSummaries.mutate { it.filterKeys { k -> runSummaryNodes[k] != id } }
        runSummaryNodes.entries.removeIf { it.value == id }
        settledApprovals.entries.removeIf { it.value == id }
        lastSeq.clear(prefix)
        // 删除节点同样让所有在途快照失效（代次整体推进）。
        sessionEpochs.keys.removeIf { it.startsWith(prefix) }
        syncing.removeIf { it.startsWith(prefix) }
        pendingSnapshotEvents.keys.removeIf { it.startsWith(prefix) }
        // 本地乐观消息的 in-flight 标记同样有节点前缀：不清掉会让该键永久泄漏，
        // 且同名会话重建时会误判“仍在发送中”。
        inFlightLocalMessages.removeIf { it.startsWith(prefix) || it == id }
        notifiedApprovals.removeAll(removedApprovals)
        sessionNode.keys.removeIf { it.startsWith(prefix) }
        // 连接锁与代次也要清：它们按节点 id 长期驻留，不清理就是每次配对/删节点泄漏一个对象；
        // 代次留着还会让重配对后的连接号从旧值继续，难以判断新旧回调。
        // 锁对象删除与 connectNow 的 getOrElse 存在竞态，但那个路径拿到新锁后仍会在锁内
        // 重新确认节点已不存在并拒绝登记（N-08 的二次检查），所以不会产生孤儿连接。
        connectionLocks.remove(id)
        connectionGenerations.remove(id)
    }

    fun connect(id: String) {
        // clientSecret() 首次会生成密钥并落盘，clientName() 要查 Settings.Global：
        // 两者都是磁盘/ContentResolver IO，不能留在调用者线程（配对是主线程触发的）。
        scope.launch(Dispatchers.IO) { connectNow(id) }
    }

    private fun connectNow(id: String) {
        // 同节点串行：先停旧实例再登记新实例，避免并发 connect 遗留双活连接。
        synchronized(connectionLocks.computeIfAbsent(id) { Any() }) {
            val node = node(id) ?: return
            val secret = store.clientSecret()
            val name = clientName()
            connections.remove(id)?.stop()
            val generation = (connectionGenerations[id] ?: 0L) + 1
            connectionGenerations[id] = generation
            val conn = NodeConnection(
                node = node,
                generation = generation,
                clientSecret = secret,
                clientName = name,
                clientVersion = clientVersion,
                scope = scope,
                onEvent = { sessionId, seq, event ->
                    // 旧连接代次的事件不能写进新连接的状态。
                    if (connections[id]?.generation == generation) onEvent(id, sessionId, seq, event)
                },
                onState = { state, error ->
                    // 已删除节点与旧连接代次的收尾回调都不能写回链接状态。
                    if (node(id) != null && connections[id]?.generation == generation) {
                        _link.mutate { it + (id to state) }
                        // 裸读改写会在多节点同时回调时丢掉别的节点的错误（C-2），改成 CAS 循环。
                        _errors.mutate { if (error == null) it - id else it + (id to error) }
                    }
                },
                onHandshake = { payload ->
                    if (connections[id]?.generation == generation) onHandshake(id, payload)
                },
            )
            // 建连期间节点可能已被删除（锁内 removeNode 已经把它从 _nodes 移除）：
            // 这里必须再确认一次，否则刚建好的连接会成为没人 stop 的孤儿（N-08）。
            if (node(id) == null) {
                conn.stop()
                connections.remove(id, conn)
                return
            }
            connections[id] = conn
            conn.start()
        }
    }

    fun disconnect(id: String) {
        // 与 connect 共用同一把锁：否则并发时旧实例刚被移除又会把新实例启起来。
        synchronized(connectionLocks.computeIfAbsent(id) { Any() }) {
            connections.remove(id)?.stop()
            _link.mutate { it + (id to LinkState.Offline) }
            // 实测连接方式属于「当前这条连接」，断开后必须清空：
            // 否则节点卡片会在离线状态下继续显示上一次的「局域网 · 12ms」。
            _connInfo.mutate { it - id }
        }
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
        // 版本与 relay 模式在 `hello` 里就有（NodeInfo）：存下来才能让设置页展示
        // 而不是写死“carry.v1”，也才能在 pi 版本过低时给出提示。
        val piVersion = info?.get("piVersion")?.jsonPrimitive?.contentOrNull.orEmpty()
        val appVersion = info?.get("appVersion")?.jsonPrimitive?.contentOrNull.orEmpty()
        val relayMode = info?.get("relayMode")?.jsonPrimitive?.contentOrNull.orEmpty()
        // 能力列表缺失（老节点）时保留 null：null 与空列表语义不同，
        // 前者是「不知道」，后者是「明确什么都不支持」。
        val capabilities = (info?.get("capabilities") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        // hello 里的节点直连地址是权威值（服务端已过滤 fe80::/10）；
        // 只有真的有可用地址时才覆盖，否则一次异常的空列表会把配对二维码里
        // 存下的地址清空，下次启动就只能靠 relay 了。
        val directAddrs = (info?.get("directAddresses") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { classifyAddress(it) != null }
            .orEmpty()
        // 只改本节点这一条，但必须走 CAS 而不是「整表读改写」：
        // 多节点同时握手时，整表写回会把别的节点刚写入的字段冲掉（C-2）。
        _nodes.mutate { current -> current.map { n ->
            if (n.id == nodeId) n.copy(
                capability = capability,
                name = name?.takeIf { it.isNotBlank() && n.name == n.endpointId.take(8) } ?: n.name,
                platform = platform ?: n.platform,
                lastSeen = System.currentTimeMillis(),
                piVersion = piVersion,
                appVersion = appVersion,
                relayMode = relayMode,
                addrs = if (directAddrs.isEmpty() || directAddrs == n.addrs) n.addrs else directAddrs,
                capabilities = capabilities ?: n.capabilities,
            ) else n
        } }
        persistNodes(_nodes.value)
        // hello 里的 connection 是握手那一刻的实时值，必须优先于可能残留的旧快照。
        updateConnInfo(nodeId, payload["connection"] as? JsonObject)
        refreshSessions(nodeId)
        // 重连后补拉已打开会话的完整快照，避免断网期间的事件空洞。
        _messages.value.keys.filter { it.startsWith("$nodeId::") }.forEach { key ->
            openSession(nodeId, key.substringAfter("::"))
        }
        // hello 里的 directAddresses 只是握手那一刻的；节点后来换网卡/IP（换 Wi-Fi、拿到 v6）
        // 只有 node.status 是新值。不主动拉一次，手机就会一直按旧地址拨号、只能退回 relay（N-03）。
        // 请求失败或节点不支持该 op 都只记日志：地址刷新属于优化，不能影响已建立的连接。
        scope.launch {
            val conn = connections[nodeId] ?: return@launch
            runCatching { conn.request("node.status") }
                .onFailure { Log.w("CarryRepo", "拉取 node.status 失败: ${it.message}") }
                .onSuccess { refreshDirectAddresses(nodeId, it) }
        }
    }

    /**
     * 用 node.status 的 directAddresses 覆盖本地地址列表（plan/0.1.4 需求 6）。
     *
     * 过滤回环与 link-local 与 hello 同一口径（[classifyAddress]）：这些地址只在节点本机
     * 有意义，写回去只会让手机白跑一轮超时。空列表不覆盖——一次异常响应不能把配对时
     * 存下的可用地址清空，那样下次启动就只能靠 relay 了。
     */
    private fun refreshDirectAddresses(nodeId: String, resp: JsonObject) {
        val addrs = ((resp["node"] as? JsonObject)?.get("directAddresses") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { classifyAddress(it) != null }
            ?: return
        val local = node(nodeId) ?: return
        if (addrs.isEmpty() || addrs.sorted() == local.addrs.sorted()) return
        _nodes.mutate { current ->
            current.map { n -> if (n.id == nodeId) n.copy(addrs = addrs) else n }
        }
        persistNodes(_nodes.value)
    }

    fun refreshSessions(nodeId: String) {
        scope.launch {
            runCatching {
                val resp = connections[nodeId]?.request("session.list") ?: return@runCatching
                val arr = resp["sessions"] as? JsonArray ?: return@runCatching
                val list = arr.mapNotNull { parseSession(nodeId, it as? JsonObject ?: return@mapNotNull null) }
                // 复合键：同名 sessionId 在两台节点上必须各自路由（N-05）。
                list.forEach { sessionNode[sessionKey(nodeId, it.id)] = nodeId }
                // 同上：只替换本节点的会话，用 CAS 避免抹掉另一节点同时刷进来的列表（C-2）。
                _sessions.mutate { current ->
                    (current.filterNot { it.nodeId == nodeId } + list).sortedByDescending { it.updatedAt }
                }
            }.onFailure { Log.w("CarryRepo", "刷新会话失败: ${it.message}") }
        }
    }

    fun sessionKey(nodeId: String, sessionId: String) = "$nodeId::$sessionId"

    fun openSession(nodeId: String, sessionId: String) {
        val key = sessionKey(nodeId, sessionId)
        // 同一会话只允许一个在途快照：ChatScreen 与重连回放并发时，旧快照不能覆盖新消息。
        if (!syncing.add(key)) return
        // 记下本次快照所属的代次：请求期间会话可能被关掉（closeSession 会递增代次），
        // 此时把快照与缓冲事件写回去会把已关闭会话“复活”成幽灵消息。
        val epoch = sessionEpochs[key] ?: 0L
        val stillCurrent = { (sessionEpochs[key] ?: 0L) == epoch }
        scope.launch {
            try {
                runCatching {
                    val conn = connections[nodeId] ?: error("节点未连接")
                    conn.request("session.open", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
                    val snap = conn.request("session.snapshot", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
                    // 请求期间节点可能已被删除、会话可能已被关闭：不要让在途快照把派生状态写回孤儿键。
                    if (node(nodeId) == null || !stillCurrent()) return@runCatching
                    val msgs = (snap["messages"] as? JsonArray)?.flatMapIndexed { i, el ->
                        parseHistoryMessages(sessionId, i, el as? JsonObject ?: return@flatMapIndexed emptyList())
                    }.orEmpty().let(::mergeHistoryMessages)
                    // 快照是权威历史，但只含消息正文；保留本地已有、快照未覆盖的工具卡/流式消息/在途乐观消息。
                    // in-flight 集合存的是 `nodeId::messageId` 复合键：同一会话 id 可能在不同节点重现，
                    // 只有按节点裁剪才能既保留本节点在途消息、又不误用其他节点的同名 id。
                    val nodeInFlight = inFlightLocalMessages
                        .filter { it.startsWith("$nodeId::") }
                        .mapTo(HashSet()) { it.substringAfter("::") }
                    _messages.mutate {
                        it + (key to mergeSnapshotMessages(
                            snapshot = msgs,
                            existing = it[key].orEmpty(),
                            inFlight = nodeInFlight,
                        ))
                    }
                    val s = snap["session"] as? JsonObject
                    if (s != null) upsertSession(nodeId, parseSession(nodeId, s))
                    (snap["run"] as? JsonObject)?.let { run -> _runInfo.mutate { it + (key to run) } }
                    // 快照自带水位：已经包含在快照里的事件不应再按重放重复应用。
                    (snap["lastSeq"]?.jsonPrimitive?.longOrNull ?: 0L).takeIf { it > 0 }?.let { lastSeq.observe(key, it) }
                    val pending = (snap["pendingApprovals"] as? JsonArray)?.mapNotNull { parseApproval(nodeId, it as? JsonObject ?: return@mapNotNull null) }.orEmpty()
                    // 快照是服务端当前真值：空列表也要清掉离线期间已处理的旧审批。
                    // 必须按 (nodeId, sessionId) 限定：不同节点可能持有同名 sessionId，
                    // 只按 sessionId 过滤会误删其他节点的审批卡。
                    _approvals.mutate { list ->
                        list.filterNot { a -> a.nodeId == nodeId && a.sessionId == sessionId } + pending
                    }
                    loadModelCatalog(conn, nodeId, sessionId)
                }.onFailure { Log.w("CarryRepo", "打开会话失败: ${it.message}") }
            } finally {
                // 在锁内取走一批缓冲并决定是否解除 syncing；重放期间新到事件会继续入下一批，
                // 直到缓冲为空才切到直通，保证顺序且不遗留事件。
                try {
                    while (true) {
                        val batch = synchronized(snapshotGuard) {
                            val pending = pendingSnapshotEvents.remove(key).orEmpty()
                            if (pending.isEmpty()) {
                                syncing.remove(key)
                                null
                            } else {
                                pending.sortedBy { it.first }
                            }
                        } ?: break
                        // 会话已关闭：这批缓冲属于上一世代，丢弃而不是回写。
                        if (!stillCurrent()) break
                        batch.forEach { (seq, event) ->
                            // 单条毒化事件不允许打断 drain：逐条隔离，循环才能走到空批分支解除 syncing。
                            runCatching {
                                onEvent(nodeId, sessionId, seq, event, replayAfterSnapshot = true, allowWhileSyncing = true)
                            }.onFailure {
                                Log.w("CarryRepo", "快照回放事件失败: ${it.javaClass.simpleName} session=$sessionId seq=$seq")
                            }
                        }
                    }
                } finally {
                    // 兜底：任何异常都不能让会话永久停在 syncing。
                    syncing.remove(key)
                }
            }
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
                // cost 由 pi 直接给出（每百万 token 美元）；老节点没有该字段时保持 null，
                // 不拿内置表猜价格——猜错比不显示更糟。
                val cost = o["cost"] as? JsonObject
                ModelOption(
                    provider = provider,
                    id = id,
                    name = name,
                    context = if (ctx != null) "${ctx / 1000}k ctx" else "",
                    price = "",
                    inputPrice = cost?.get("input")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    outputPrice = cost?.get("output")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }.orEmpty()
            if (items.isNotEmpty()) _models.mutate { it + (key to items) }
        }.onFailure { Log.w("CarryRepo", "读取模型列表失败: ${it.message}") }
        runCatching {
            val resp = conn.request("thinking.levels", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            val levels = (resp["levels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            if (levels.isNotEmpty()) _thinkingLevels.mutate { it + (key to levels) }
        }.onFailure { Log.w("CarryRepo", "读取思考等级失败: ${it.message}") }
    }

    /**
     * 单独拉一次思考等级。
     *
     * 模型切换后可用等级会变（不同模型支持的档位不同），单页选择器必须在
     * `model.set` 成功后重新拉取，不能复用打开会话时的那份。
     */
    suspend fun fetchThinkingLevels(nodeId: String, sessionId: String): List<String>? {
        val conn = connections[nodeId] ?: return null
        val resp = conn.request("thinking.levels", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
        val levels = (resp["levels"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (levels.isNotEmpty()) _thinkingLevels.mutate { it + (sessionKey(nodeId, sessionId) to levels) }
        return levels.ifEmpty { null }
    }

    /** 读取允许的根目录与最近使用目录；节点未连接直接抛错，由调用方内联提示。 */
    suspend fun fsRoots(nodeId: String): FsRoots {
        val conn = connections[nodeId] ?: error("节点未连接")
        return parseFsRoots(conn.request("fs.roots"))
    }

    /** 列出某个目录下的子目录；`path` 留空时由服务端回退到默认目录。 */
    suspend fun fsList(nodeId: String, path: String): FsListing {
        val conn = connections[nodeId] ?: error("节点未连接")
        return parseFsListing(
            conn.request("fs.list", buildJsonObject { put("path", JsonPrimitive(path)) }),
        )
    }

    fun createSession(
        nodeId: String,
        cwd: String = "",
        onCreated: (Session) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                val resp = conn.request("session.create", buildJsonObject { put("cwd", JsonPrimitive(cwd)) })
                val obj = resp["session"] as? JsonObject ?: error("会话创建失败")
                val s = parseSession(nodeId, obj)
                // upsertSession 内部会写 sessionNode 的复合键，这里不再单写一遍（避免两处写法漂移）。
                upsertSession(nodeId, s)
                onCreated(s)
            }.onFailure {
                // 失败必须回到调用方：旧实现只打日志，界面会一直停在 loading，
                // 用户只能等 8 秒超时解锁，且看不到为什么没建成。
                val reason = it as? CarryException
                val message = reason?.message ?: (it.message ?: "创建会话失败")
                Log.w("CarryRepo", "创建会话失败: $message")
                onFailed(message)
            }
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
        // 图片以 base64 内联在 prompt.send 单帧里，超限时服务端直接断连接；先按帧预算拦下并给可读提示。
        // 估算必须包含信封开销（sessionId/mode/clientId + 每张图的 mimeType 与 JSON 引号），
        // 否则“刚好差几 KB”的帧会通过预检然后在 Wire.frame / 服务端 read_frame 处失败。
        val envelope = 256L + text.encodeToByteArray().size
        val imagesBytes = images.sumOf { 64L + it.second.length.toLong() }
        val estimate = envelope + imagesBytes
        if (estimate > Wire.PROMPT_FRAME_BUDGET) {
            val budgetKib = Wire.PROMPT_FRAME_BUDGET / 1024
            appendError(
                key,
                sessionId,
                if (images.isEmpty()) {
                    "消息太长（约 ${estimate / 1024} KiB，上限 $budgetKib KiB），请拆分发送"
                } else {
                    "图片合计太大（约 ${estimate / 1024} KiB，上限 $budgetKib KiB），请减少图片数量或分批发送"
                },
            )
            return
        }
        val local = ChatMessage(
            id = "local-${UUID.randomUUID()}",
            sessionId = sessionId,
            role = "user",
            text = if (images.isEmpty()) text else "$text\n\n📎 ${images.size} 张图片".trim(),
        )
        inFlightLocalMessages.add("$nodeId::${local.id}")
        _messages.mutate { it + (key to ((it[key] ?: emptyList()) + local)) }
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
            }.onSuccess {
                // 服务端已收到：快照会带回同文本副本，本地乐观副本由快照合并清除。
                inFlightLocalMessages.remove("$nodeId::${local.id}")
            }.onFailure {
                inFlightLocalMessages.remove("$nodeId::${local.id}")
                // 失败不能在聊天流里留下“已发出”的假用户消息：撤掉乐观插入，只留错误气泡。
                val reason = it as? CarryException
                val message = reason?.message ?: (it.message ?: "发送失败")
                _messages.mutate { current ->
                    current + (key to (current[key].orEmpty().filterNot { m -> m.id == local.id } + ChatMessage(
                        id = "err-${UUID.randomUUID()}",
                        sessionId = sessionId,
                        role = "system",
                        text = message,
                        error = true,
                    )))
                }
            }
        }
    }

    private fun appendError(key: String, sessionId: String, text: String) {
        _messages.mutate { current ->
            current + (key to (current[key].orEmpty() + ChatMessage(
                id = "err-${UUID.randomUUID()}",
                sessionId = sessionId,
                role = "system",
                text = text,
                error = true,
            )))
        }
    }

    fun abort(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                connections[nodeId]?.request("prompt.abort", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            }
        }
    }

    fun resolveApproval(nodeId: String, approvalId: String, decision: String, remember: Boolean = false) {
        // 乐观移除前先留下卡片与所属会话：失败时要把卡片放回界面，并往正确的聊天流里写错误气泡。
        val known = _approvals.value.firstOrNull { it.id == approvalId }
        val sessionId = known?.sessionId
        scope.launch {
            _approvals.mutate { list -> list.filterNot { a -> a.id == approvalId } }
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request(
                    "approval.resolve",
                    buildJsonObject {
                        put("approvalId", JsonPrimitive(approvalId))
                        put("decision", JsonPrimitive(decision))
                        put("remember", JsonPrimitive(remember))
                    },
                )
            }.onSuccess {
                // 值记所属节点，删节点时才能按节点清理（N-09）。
                settledApprovals[approvalId] = nodeId
            }.onFailure {
                // 静默失败最危险：审批卡已从界面消失、工具调用却还挂在服务端，
                // 用户会以为已经处理而实际在等超时。失败时把卡片放回界面，用户可以直接重试。
                val message = (it as? CarryException)?.message ?: (it.message ?: "审批提交失败")
                Log.w("CarryRepo", "审批 ${approvalId} 提交失败: ${it.javaClass.simpleName}")
                // 服务端可能已经解决了这条审批（超时/另一设备）：此时不能把卡片复活。
                if (known != null && !settledApprovals.containsKey(approvalId)) {
                    _approvals.mutate { list -> if (list.any { a -> a.id == known.id }) list else list + known }
                }
                if (sessionId != null) {
                    appendError(sessionKey(nodeId, sessionId), sessionId, "审批提交失败：$message")
                }
            }
        }
    }

    /**
     * 切换模型。
     *
     * 保留原有的「即发即忘」入口，同时新增 suspend 版本供单页选择器串行调用：
     * 选择器必须等 `model.set` 成功后才能改思考等级，否则会在旧模型的档位上提交新模型。
     */
    fun setModel(nodeId: String, sessionId: String, provider: String, modelId: String) {
        scope.launch { runCatching { applyModel(nodeId, sessionId, provider, modelId) } }
    }

    /** 真正执行 `model.set`；失败直接抛出，由调用方决定怎么展示。 */
    suspend fun applyModel(nodeId: String, sessionId: String, provider: String, modelId: String) {
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
        _runInfo.mutate { current ->
            val run = current[key] ?: buildJsonObject {}
            current + (key to buildJsonObject {
                run.forEach { (k, v) -> put(k, v) }
                put("model", buildJsonObject {
                    put("provider", JsonPrimitive(provider))
                    put("id", JsonPrimitive(modelId))
                })
            })
        }
    }

    fun setThinking(nodeId: String, sessionId: String, level: String) {
        scope.launch { runCatching { applyThinking(nodeId, sessionId, level) } }
    }

    /** 真正执行 `thinking.set`；失败直接抛出。 */
    suspend fun applyThinking(nodeId: String, sessionId: String, level: String) {
        val conn = connections[nodeId] ?: error("节点未连接")
        conn.request(
            "thinking.set",
            buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("level", JsonPrimitive(level))
            },
        )
        val key = sessionKey(nodeId, sessionId)
        _runInfo.mutate { current ->
            val run = current[key] ?: buildJsonObject {}
            current + (key to buildJsonObject {
                run.forEach { (k, v) -> put(k, v) }
                put("thinkingLevel", JsonPrimitive(level))
            })
        }
    }

    /**
     * 关闭会话。
     *
     * 请求成功后才清本地派生状态并回调 [onClosed]（界面再返回列表）；失败保留本地状态，
     * 并用聊天流里的错误气泡给出中文原因——用户仍留在会话内，可以直接重试。
     * 旧实现在请求未完成时就清空消息并返回，失败后用户会以为会话已关闭，而服务端其实还在跑。
     */
    fun closeSession(nodeId: String, sessionId: String, onClosed: () -> Unit = {}) {
        // 代次必须同步递增（不能放进 coroutine）：在途快照与 drain 靠它判断“我还属于当前世代吗”，
        // 晚一步就会在关闭之后把旧消息写回去。
        val key = sessionKey(nodeId, sessionId)
        sessionEpochs.merge(key, 1L, Long::plus)
        scope.launch {
            val failure = runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request("session.close", buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) })
            }.exceptionOrNull()
            if (failure != null) {
                // 失败不能静默：界面必须留在会话里并说明原因，否则用户会重复点、或以为已关闭。
                val message = (failure as? CarryException)?.message ?: failure.message ?: "请求失败"
                Log.w("CarryRepo", "关闭会话失败: ${failure.javaClass.simpleName}")
                appendError(key, sessionId, "关闭会话失败：$message")
                return@launch
            }
            // 关闭会话要把派生状态一并清掉：只清消息会让在途快照 drain 重新把消息写回来。
            synchronized(snapshotGuard) {
                pendingSnapshotEvents.remove(key)
                syncing.remove(key)
            }
            _messages.mutate { it - key }
            _models.mutate { it - key }
            _thinkingLevels.mutate { it - key }
            _runInfo.mutate { it - key }
            // sessionNode 也要一并撤掉（C-3）：留着它，关闭后迟到的 delta/final 会写回
            // 刚清空的 _messages，且同 id 会话在另一节点上复用时会被路由到已关闭的旧节点。
            sessionNode.remove(key)
            // 审批也属于该会话的派生状态，会话关掉后不能留在审批列表里。
            val closedApprovals = _approvals.value
                .filter { it.nodeId == nodeId && it.sessionId == sessionId }
                .map { it.id }
                .toSet()
            _approvals.mutate { list -> list.filterNot { it.nodeId == nodeId && it.sessionId == sessionId } }
            notifiedApprovals.removeAll(closedApprovals)
            lastSeq.forget(key)
            onClosed()
        }
    }

    fun compact(nodeId: String, sessionId: String) {
        scope.launch {
            runCatching {
                val conn = connections[nodeId] ?: error("节点未连接")
                conn.request(
                    "session.compact",
                    buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) },
                    120_000,
                )
            }.onFailure {
                // 压缩可能要跑几分钟，失败必须让用户看见，否则会以为已经生效而继续在满上下文里对话。
                val message = (it as? CarryException)?.message ?: it.message ?: "请求失败"
                Log.w("CarryRepo", "压缩上下文失败: ${it.javaClass.simpleName}")
                appendError(sessionKey(nodeId, sessionId), sessionId, "压缩上下文失败：$message")
            }
        }
    }

    fun renameNode(id: String, name: String) {
        // 同上：只改一条字段，不应该用整表写回冲掉并发写入（C-2）。
        _nodes.mutate { current -> current.map { if (it.id == id) it.copy(name = name) else it } }
        persistNodes(_nodes.value)
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

    /** 应用是否在前台；由 Activity 生命周期维护。 */
    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    /** 当前正在看的会话；聊天页在职时设置，以免用户盯着会话还被通知轰炸。 */
    fun setVisibleSession(sessionId: String?) {
        visibleSessionId = sessionId
    }

    private fun notifyApproval(a: Approval) {
        if (!settingBool("notify_approval", true)) return
        if (appForeground && visibleSessionId == a.sessionId) return
        // approval.updated 会重复触发，同一条审批只提醒一次。
        if (!notifiedApprovals.add(a.id)) return
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

    /**
     * 原子地更新一个 StateFlow：CAS 失败时重新计算，避免多节点并发事件相互覆盖。
     * 直接写 `x.value = x.value + ...` 在同时收到两个节点的事件时会丢更新。
     */
    private inline fun <T> MutableStateFlow<T>.mutate(transform: (T) -> T) {
        while (true) {
            val cur = value
            val next = transform(cur)
            if (next == cur) return
            if (compareAndSet(cur, next)) return
        }
    }

    private fun onEvent(
        nodeId: String,
        sessionId: String?,
        seq: Long,
        event: JsonObject,
        replayAfterSnapshot: Boolean = false,
        allowWhileSyncing: Boolean = false,
    ) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        // 已删除节点的在途事件不能再写回孤儿审批/会话（与 onState 的节点守卫对齐）。
        if (node(nodeId) == null) return
        val sid = sessionId ?: event["sessionId"]?.jsonPrimitive?.contentOrNull
        // 节点级事件（无 sessionId）不参与去重水位：`connection.updated` 的 seq 固定为 0，
        // 而 `run.summary` 事件本身按 runId 去重，不需要 seq 保护。
        if (type == "connection.updated") {
            updateConnInfo(nodeId, event["connection"] as? JsonObject)
            return
        }
        if (sid != null) {
            val key = sessionKey(nodeId, sid)
            if (!allowWhileSyncing && syncing.contains(key)) {
                synchronized(snapshotGuard) {
                    if (syncing.contains(key)) {
                        pendingSnapshotEvents
                            .computeIfAbsent(key) { mutableListOf() }
                            .add(seq to event)
                        return
                    }
                }
            }
            // 快照只包含消息正文，不含工具卡；在途的 tool.* / message.final 必须在快照落地后补放，
            // 否则这些事件的分片 seq 会被快照水位误判为“已包含”而丢失。
            val keepAfterSnapshot = replayAfterSnapshot && (type.startsWith("tool.") || type == "message.final")
            // 重连会重放 journal；已应用过的 seq 直接丢弃，否则流式文本会翻倍。
            if (!keepAfterSnapshot && !lastSeq.accept(key, seq)) {
                Log.v("CarryRepo", "丢弃重复事件 $type seq=$seq")
                return
            }
        }
        // 下面的会话级处理都必须带上 nodeId：sessionId 会跨节点重名，
        // 只用 sessionId 路由会落到另一台节点的同名会话上（N-05）。
        when (type) {
            "session.updated" -> (event["session"] as? JsonObject)?.let { upsertSession(nodeId, parseSession(nodeId, it)) }
            "message.delta" -> sid?.let { applyDelta(nodeId, it, event) }
            "message.final" -> sid?.let { applyFinal(nodeId, it, event) }
            "tool.started" -> sid?.let { applyToolStarted(nodeId, it, event) }
            "tool.updated" -> sid?.let { applyToolUpdated(nodeId, it, event) }
            "tool.ended" -> sid?.let { applyToolEnded(nodeId, it, event) }
            "approval.requested", "approval.updated" -> {
                val a = (event["approval"] as? JsonObject)?.let { parseApproval(nodeId, it) }
                if (a != null) {
                    _approvals.mutate { cur -> cur.filterNot { it.id == a.id } + a }
                    notifyApproval(a)
                }
            }
            "approval.resolved" -> {
                val id = event["approvalId"]?.jsonPrimitive?.contentOrNull
                // 记录所属节点：删节点时要能按节点清掉已结算集合（N-09）。
                id?.let { settledApprovals[it] = nodeId }
                _approvals.mutate { cur -> cur.filterNot { it.id == id } }
                id?.let {
                    notifiedApprovals.remove(it)
                    NotificationManagerCompat.from(appContext).cancel(it.hashCode())
                }
            }
            "run.state" -> sid?.let { onRunState(nodeId, it, event) }
            "run.summary" -> parseRunSummary(event)?.let { summary ->
                _runSummaries.mutate { it + (summary.runId to summary) }
                // runId 不带节点信息：用旁表记下归属，删节点时才能按节点清理（N-09）。
                runSummaryNodes[summary.runId] = nodeId
                sid?.let { applyRunSummaryToRunInfo(nodeId, it, summary) }
            }
            "device.revoked" -> {
                // 服务端在发现设备被吊销后会发这条事件并立即断开；本地必须把节点停掉，
                // 否则会在重连循环里反复被拒，且界面一直显示“连接中”。
                val revokedId = event["deviceId"]?.jsonPrimitive?.contentOrNull
                Log.w("CarryRepo", "本设备已被节点吊销 node=$nodeId device=$revokedId")
                disconnect(nodeId)
                _errors.mutate { it + (nodeId to "此设备已被节点吊销，请重新配对") }
            }
            "session.conflict" -> sid?.let {
                val writable = event["writable"]?.jsonPrimitive?.contentOrNull != "false"
                // 同名 sessionId 在两台节点上互不相干：必须同时比 nodeId（N-05）。
                _sessions.mutate { cur ->
                    cur.map { s -> if (s.id == it && s.nodeId == nodeId) s.copy(writable = writable) else s }
                }
            }
            "error" -> {
                // 服务端事件流滞后时只发这个错误码（携带的会话无法确定），必须重拉快照，
                // 否则漏掉的 delta/final 永远不会补回来，界面会停在半句话上。
                val code = event["code"]?.jsonPrimitive?.contentOrNull
                if (code == SNAPSHOT_REQUIRED) {
                    Log.w("CarryRepo", "事件流滞后，重拉节点快照 node=$nodeId")
                    resyncNode(nodeId)
                } else {
                    Log.w(
                        "CarryRepo",
                        "节点事件错误 code=$code session=${event["sessionId"]?.jsonPrimitive?.contentOrNull ?: sid} " +
                            "len=${event["message"]?.toString()?.length ?: 0}",
                    )
                }
            }
        }
        if (seq > 0) Log.v("CarryRepo", "event $type seq=$seq")
    }

    /**
     * 丢弃该节点所有会话的事件水位并重新拉快照。
     *
     * 服务端在广播滞后时无法告知具体丢了哪些 seq，客户端只能以快照重建：
     * `openSession` 内部会先清掉旧消息再合并，且带 `session.snapshot` 的 `lastSeq` 重置水位。
     */
    private fun resyncNode(nodeId: String) {
        val prefix = "$nodeId::"
        lastSeq.clear(prefix)
        refreshSessions(nodeId)
        _messages.value.keys.filter { it.startsWith(prefix) }.forEach { key ->
            openSession(nodeId, key.substringAfter("::"))
        }
    }

    private fun upsertSession(nodeId: String, s: Session) {
        sessionNode[sessionKey(nodeId, s.id)] = nodeId
        // 只挤掉「同节点同 id」的旧条目：按 id 单独比较会让一台节点的会话把另一台
        // 同名会话从列表里删掉（N-05）。
        _sessions.mutate { cur ->
            (cur.filterNot { it.id == s.id && it.nodeId == nodeId } + s).sortedByDescending { it.updatedAt }
        }
    }

    /**
     * 追加流式分片。
     *
     * 每次分片只复制消息引用数组（O(消息条数)，典型 <200），消息对象本身共享，
     * 文本拼接只发生在被命中的那一条上；真正的渲染成本由 ChatScreen 的
     * Markdown 缓存与「只在接近底部时跟随滚动」控制。
     */
    private fun applyDelta(nodeId: String, sessionId: String, event: JsonObject) {
        // 复合键命中才算「本节点已打开的会话」；不命中说明事件属于已关闭/其它节点的同名会话。
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        val messageId = event["messageId"]?.jsonPrimitive?.contentOrNull ?: return
        val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
        val kind = event["kind"]?.jsonPrimitive?.contentOrNull ?: "text"
        // 流式分片同样带 runId；先写上行内总结才能在本轮运行中就归好轮。
        val runId = event.str("runId").orEmpty()
        _messages.mutate { current ->
            val list = current[key].orEmpty().toMutableList()
            val at = list.indexOfFirst { it.id == messageId }
            if (at < 0) {
                list += ChatMessage(
                    id = messageId,
                    sessionId = sessionId,
                    role = "assistant",
                    streaming = true,
                    runId = runId,
                )
                val msg = list.last()
                list[list.lastIndex] =
                    if (kind == "thinking") msg.copy(thinking = msg.thinking + delta)
                    else msg.copy(text = msg.text + delta)
            } else {
                val msg = list[at]
                list[at] =
                    if (kind == "thinking") {
                        msg.copy(thinking = msg.thinking + delta, streaming = true, runId = runId.ifBlank { msg.runId })
                    } else {
                        msg.copy(text = msg.text + delta, streaming = true, runId = runId.ifBlank { msg.runId })
                    }
            }
            current + (key to list)
        }
    }

    private fun applyFinal(nodeId: String, sessionId: String, event: JsonObject) {
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        val messageId = event["messageId"]?.jsonPrimitive?.contentOrNull ?: return
        val raw = event["message"] as? JsonObject
        // 整个查找+替换必须在 CAS 循环内完成：在循环外先读一份 list 再整段写回，
        // 会把同一瞬间收到的另一节点/另一条 delta 的更新整批回滚（N-4）。
        _messages.mutate { current ->
            // 每次重算都从当前值重新读起：CAS 失败后不能复用上一轮的局部变量。
            val list = current[key].orEmpty().toMutableList()
            val idx = list.indexOfFirst { it.id == messageId }
            var text = raw?.get("content")?.let(::contentText).orEmpty()
            var thinking = raw?.get("content")?.let(::contentThinking).orEmpty()
            if (text.isBlank()) {
                val old = list.getOrNull(idx)
                text = old?.text.orEmpty()
                thinking = old?.thinking.orEmpty()
            }
            val base = list.getOrNull(idx)
                ?: ChatMessage(id = messageId, sessionId = sessionId, role = "assistant")
            val images = raw?.get("content")?.let(::contentImages).orEmpty()
            val done = base.copy(
                text = text.ifBlank { base.text },
                thinking = thinking.ifBlank { base.thinking },
                streaming = false,
                images = if (images.isEmpty()) base.images else images,
                // 输出事件里带 runId / timestamp / usage，之前全部丢掉，导致「本轮总结」
                // 在历史回放时既归不了轮也算不出 token。这三项只在缺失时保留旧值。
                runId = event.str("runId") ?: base.runId,
                tsMs = messageTsMs(raw) ?: base.tsMs,
                usage = parseUsage(raw?.get("usage")) ?: base.usage,
            )
            if (idx >= 0) list[idx] = done else list += done
            current + (key to list)
        }
        _sessions.mutate { list ->
            // 只用事件自身的文本更新预览：不能引用 CAS 外的局部变量，否则重试后会写到旧值。
            val preview = raw?.get("content")?.let(::contentText).orEmpty().ifBlank {
                _messages.value[key].orEmpty().firstOrNull { it.id == messageId }?.text.orEmpty()
            }
            list.map {
                if (it.id == sessionId && it.nodeId == nodeId) {
                    it.copy(status = "idle", preview = preview.take(80))
                } else {
                    it
                }
            }
        }
    }

    private fun applyToolStarted(nodeId: String, sessionId: String, event: JsonObject) {
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val name = event["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool"
        val args = event["args"]?.toString() ?: ""
        val list = _messages.value[key] ?: emptyList()
        // 快照后补放与 journal 重放都可能重复投递 tool.started，按 toolCallId 去重。
        if (list.any { it.tool?.id == id }) return
        _messages.mutate { current ->
            current + (key to (current[key].orEmpty() + ChatMessage(
                id = "tool-$id",
                sessionId = sessionId,
                role = "tool",
                tool = ToolCall(id = id, name = name, args = args, status = "running"),
            )))
        }
    }

    private fun applyToolUpdated(nodeId: String, sessionId: String, event: JsonObject) {
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val partial = event["partialResult"]?.toString() ?: ""
        updateTool(key, id) { it.copy(result = partial) }
    }

    private fun applyToolEnded(nodeId: String, sessionId: String, event: JsonObject) {
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        val id = event["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return
        val result = event["result"]?.let(::contentText)?.ifBlank { event["result"]?.toString().orEmpty() }.orEmpty()
        val images = event["result"]?.let(::contentImages).orEmpty()
        val isError = event["isError"]?.jsonPrimitive?.contentOrNull == "true"
        val duration = event["durationMs"]?.jsonPrimitive?.longOrNull ?: 0
        updateTool(key, id) {
            it.copy(result = result, status = if (isError) "error" else "done", isError = isError, durationMs = duration, images = images)
        }
    }

    private fun updateTool(key: String, id: String, f: (ToolCall) -> ToolCall) {
        // 同样必须在 CAS 循环内重算：外部建 list 再整写回会丢掉并发的 delta（N-4）。
        _messages.mutate { current ->
            val list = current[key] ?: return@mutate current
            val next = list.map { m ->
                m.tool?.let { t -> if (t.id == id) m.copy(tool = f(t)) else m } ?: m
            }
            if (next == list) current else current + (key to next)
        }
    }

    /** 合并一次连接方式快照；`connection` 缺失时视为无信息并移除，而不是保留旧值。 */
    private fun updateConnInfo(nodeId: String, conn: JsonObject?) {
        val info = parseConnInfo(conn)
        _connInfo.mutate { map -> if (info == null) map - nodeId else map + (nodeId to info) }
    }

    /**
     * 一轮结束后把 summary 里的模型标签写回 runInfo。
     *
     * 容器顶部胶囊显示 `provider/name`，需要 `run.summary.model` 这个规范化全名；
     * 没有 summary 时什么也不做，不伪造模型名。
     */
    private fun applyRunSummaryToRunInfo(nodeId: String, sessionId: String, summary: RunSummary) {
        if (summary.model.isBlank()) return
        if (sessionNode[sessionKey(nodeId, sessionId)] != nodeId) return
        val key = sessionKey(nodeId, sessionId)
        _runInfo.mutate { current ->
            val run = current[key] ?: return@mutate current
            current + (key to buildJsonObject {
                run.forEach { (k, v) -> put(k, v) }
                put("modelLabel", JsonPrimitive(summary.model))
            })
        }
    }

    private fun onRunState(nodeId: String, sessionId: String, event: JsonObject) {
        val state = event["state"]?.jsonPrimitive?.contentOrNull ?: return
        val status = when (state) {
            "streaming" -> "streaming"
            "waiting_approval" -> "waiting_approval"
            // 等用户输入不是空闲：塌缩成 idle 会让会话在列表里显示已结束。
            "waiting_input" -> "waiting_input"
            "retrying" -> "streaming"
            "error" -> "error"
            else -> "idle"
        }
        // 会话级状态同样按 (nodeId, sessionId) 定位，否则会把另一台同名会话改成流式中（N-05）。
        _sessions.mutate { list ->
            list.map {
                if (it.id == sessionId && it.nodeId == nodeId) {
                    it.copy(status = status, active = status != "idle")
                } else {
                    it
                }
            }
        }
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
        // 默认值与 Rust 侧 `SessionSummary` 的 `#[serde(default)]` 对齐（P-10）：
        // 缺字段时不能假设“可写”，否则版本不匹配的节点会让手机在只读会话上继续发消息。
        lastSource = obj.str("lastSource").orEmpty(),
        writable = obj.bool("writable", false),
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

    internal fun parseHistoryMessages(sessionId: String, index: Int, obj: JsonObject): List<ChatMessage> {
        val role = obj.str("role") ?: return emptyList()
        val content = obj["content"]
        val images = content?.let(::contentImages).orEmpty()
        val messageId = obj.str("id") ?: historyMessageId(role, content, index)
        // 历史条目里的 timestamp/usage 是「本轮总结」的唯一冷数据来源（没有 run.summary 可听）。
        val ts = messageTsMs(obj) ?: 0L
        val usage = parseUsage(obj["usage"])
        return when (role) {
            "user" -> {
                val text = content?.let(::contentText).orEmpty()
                if (text.isBlank() && images.isEmpty()) emptyList()
                else listOf(ChatMessage(id = messageId, sessionId = sessionId, role = "user", text = text, images = images, tsMs = ts))
            }
            "assistant" -> {
                val text = content?.let(::contentText).orEmpty()
                val thinking = content?.let(::contentThinking).orEmpty()
                val out = mutableListOf<ChatMessage>()
                if (text.isNotBlank() || thinking.isNotBlank() || images.isNotEmpty()) {
                    out += ChatMessage(
                        id = messageId,
                        sessionId = sessionId,
                        role = "assistant",
                        text = text,
                        thinking = thinking,
                        images = images,
                        tsMs = ts,
                        usage = usage,
                    )
                }
                (content as? JsonArray)?.forEachIndexed { i, el ->
                    val o = el as? JsonObject ?: return@forEachIndexed
                    if (o.str("type") != "toolCall") return@forEachIndexed
                    val toolId = o.str("id") ?: "tc-$index-$i"
                    out += ChatMessage(
                        id = "tool-$toolId",
                        sessionId = sessionId,
                        role = "tool",
                        tool = ToolCall(
                            id = toolId,
                            name = o.str("name") ?: "tool",
                            args = o["arguments"]?.toString().orEmpty(),
                            status = "done",
                        ),
                    )
                }
                out
            }
            "toolResult" -> {
                // 缺 toolCallId 的老节点用确定性兜底 id（N-11）：随机 id 会让每次 resync 的历史合并
                // 都把它当成一条新消息，同一条工具结果于是在列表里越滚越多。
                val toolId = obj.str("toolCallId")
                    ?: "tr-$index-${stableContentSuffix(obj.str("toolName").orEmpty(), contentText(content))}"
                val isError = obj["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                listOf(
                    ChatMessage(
                        id = "tool-$toolId",
                        sessionId = sessionId,
                        role = "tool",
                        tool = ToolCall(
                            id = toolId,
                            name = obj.str("toolName") ?: "tool",
                            result = contentText(content),
                            status = if (isError) "error" else "done",
                            isError = isError,
                            durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0,
                            images = images,
                        ),
                    ),
                )
            }
            else -> emptyList()
        }
    }

    private fun contentBody(el: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonElement? = when (el) {
        is JsonObject -> when {
            el["content"] != null -> el["content"]
            el["result"] != null -> el["result"]
            else -> el
        }
        else -> el
    }

    private fun contentText(el: kotlinx.serialization.json.JsonElement?): String = when (val body = contentBody(el)) {
        is JsonPrimitive -> if (body.isString) body.content else ""
        is JsonArray -> body.joinToString("\n") { item ->
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

    private fun contentThinking(el: kotlinx.serialization.json.JsonElement?): String = when (val body = contentBody(el)) {
        is JsonArray -> body.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "thinking") o.str("thinking") ?: o.str("text") else null
        }.joinToString("\n")
        else -> ""
    }

    private fun contentImages(el: kotlinx.serialization.json.JsonElement?): List<ImagePart> {
        val body = contentBody(el) as? JsonArray ?: return emptyList()
        return body.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "image") return@mapNotNull null
            val source = o["source"] as? JsonObject
            val data = o.str("data") ?: source?.str("data") ?: return@mapNotNull null
            val mime = o.str("mimeType") ?: source?.str("media_type") ?: "image/jpeg"
            ImagePart(mime, data)
        }
    }
}

/**
 * pi 的 message.timestamp 既可能是毫秒数字，也可能是 ISO 字符串；统一归一化成毫秒。
 *
 * 无法解析或缺失返回 null，由调用方决定保留旧值——返回 0 会让调用方把旧时间抹成 1970。
 */
internal fun messageTsMs(message: JsonObject?): Long? {
    val el = message?.get("timestamp") ?: return null
    val prim = el as? JsonPrimitive ?: return null
    prim.contentOrNull?.toLongOrNull()?.let { return if (it > 0) it else null }
    val text = prim.contentOrNull ?: return null
    return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(text).toEpochMilli() }
        .getOrNull()
}

/**
 * 解析单条消息的 usage；缺 input/output 时返回 null。
 *
 * 只用「有 input 或有 output」判断有效性：某些 pi 版本会给工具消息带空对象，
 * 当成 0 会让该轮 token 统计凭空少一节。
 */
internal fun parseUsage(el: JsonElement?): MessageUsage? {
    val o = el as? JsonObject ?: return null
    fun num(k: String): Long = o[k]?.jsonPrimitive?.longOrNull ?: 0L
    val input = num("input")
    val output = num("output")
    if (input == 0L && output == 0L) return null
    // cost 可能直接是数字，也可能是 {total: n}；两种形态在 pi 历史上都出现过。
    val cost = when (val c = o["cost"]) {
        is JsonObject -> c["total"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        else -> c?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }
    return MessageUsage(
        input = input,
        output = output,
        cacheRead = num("cacheRead"),
        cacheWrite = num("cacheWrite"),
        total = if (o["totalTokens"] != null) num("totalTokens") else input + output,
        cost = cost,
    )
}

/**
 * 历史消息缺失服务端 id 时的兜底 id。
 *
 * 必须是**确定性**的：旧实现用随机 UUID，同一消息在每次快照里都会得到新 key，
 * 导致 LazyColumn 重建全部气泡、合并去重全失效（U-8）。服务端已保证快照消息
 * 带 entry id（`crates/core/tests/snapshot_ids.rs`），这里是防御老版本节点。
 */
/**
 * 内容指纹：对若干字符串做 FNV-1a 32 位，输出 8 位十六进制。
 *
 * 只用于「同一个 id 必须在多次回放里完全一致」的兜底键（N-11），不用于安全判别。
 */
internal fun stableContentSuffix(vararg parts: String): String {
    var hash = -0x340d631b // 2166136261（FNV-1a 32 位偏移基，Int 表示）
    for (part in parts) {
        for (ch in part) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
    }
    return hash.toUInt().toString(16).padStart(8, '0')
}

internal fun historyMessageId(role: String, content: JsonElement?, index: Int): String {
    val fingerprint = "$role|" + content?.toString().orEmpty() + "|$index"
    return "h-$index-${fingerprint.hashCode().toUInt().toString(16)}"
}

/** 合并同一工具的历史 toolCall 与 toolResult，保留命令参数与最终结果。 */
internal fun mergeHistoryMessages(history: List<ChatMessage>): List<ChatMessage> {
    val byId = LinkedHashMap<String, ChatMessage>()
    history.forEach { message ->
        val previous = byId[message.id]
        val oldTool = previous?.tool
        val newTool = message.tool
        byId[message.id] = if (oldTool != null && newTool != null) {
            message.copy(
                tool = newTool.copy(
                    name = newTool.name.ifBlank { oldTool.name },
                    args = newTool.args.ifBlank { oldTool.args },
                ),
            )
        } else {
            message
        }
    }
    return byId.values.toList()
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: default

/**
 * 每会话事件水位：seq 在会话内单调递增，因此 `seq <= 已知水位` 就是重放或重复投递。
 * 重连补洞（snapshot 自带 lastSeq）时用 [observe] 抬高水位。
 */
internal class SeqWatermark {
    private val watermarks = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 接受并推进水位；已应用过的 seq 返回 false。 */
    fun accept(key: String, seq: Long): Boolean {
        if (seq <= 0) return true
        while (true) {
            val previous = watermarks[key] ?: 0L
            if (seq <= previous) return false
            if (watermarks.putIfAbsent(key, seq) == null || watermarks.replace(key, previous, seq)) return true
        }
    }

    /** 记录服务端已确认的水位（快照 lastSeq），不向前回退。 */
    fun observe(key: String, seq: Long) {
        if (seq <= 0) return
        watermarks.merge(key, seq) { a, b -> maxOf(a, b) }
    }

    /** 会话关闭后丢弃该会话水位，重开时从快照 lastSeq 重建。 */
    fun forget(key: String) {
        watermarks.remove(key)
    }

    fun clear(keyPrefix: String) {
        watermarks.keys.removeIf { it.startsWith(keyPrefix) }
    }
}

/**
 * 快照与本地消息合并。
 *
 * 快照是权威历史，但只含消息正文，所以按正文（而不是 id）一对一消重：
 * 服务端 `message.final` 的 messageId 来自 pi 的 message.id（缺失时 bridge 用 ULID 兑底），
 * 而会话文件快照用的是 entry id，两个 id 永远不同源。只按 id 去重会在重连/重进后
 * 把已完成的 assistant 消息留两份。这里对已完成的 assistant 做同 role + 同归一化正文的一对一消耗。
 *
 * - `streaming == true` 的消息一律保留：它可能还在跑，快照里没有它就说明还没落盘；
 * - 工具卡不受影响（toolCallId 稳定，快照本身不含工具卡）；
 * - `local-` 乐观消息同样按文本一对一消耗已确认的那份（服务端 user 消息不发 message_end）。
 */
internal fun mergeSnapshotMessages(
    snapshot: List<ChatMessage>,
    existing: List<ChatMessage>,
    inFlight: Set<String> = emptySet(),
): List<ChatMessage> {
    val known = snapshot.mapTo(HashSet()) { it.id }
    val extras = existing.filter { m ->
        m.id !in known && (m.role == "tool" || m.streaming || m.id.startsWith("local-") || m.role == "assistant")
    }
    // 按文本一对一消耗：重复发同一句话时不能被整批误删；没对上的乐观消息仍可能在途，保留。
    val confirmed = ArrayList<String>()
    val confirmedAssistant = ArrayList<String>()
    for (m in snapshot) {
        when (m.role) {
            "user" -> confirmed.add(confirmKey(m))
            // 只有正文相同的 assistant 才算同一份：两边 id 不同源，正文是唯一可对齐的键。
            "assistant" -> confirmedAssistant.add(assistantKey(m))
        }
    }
    val kept = extras.filterNot { m ->
        when {
            m.role == "assistant" -> {
                // 流式中的消息不能当作快照那份，否则会把还在输出的回复吞掉。
                if (m.streaming) return@filterNot false
                val idx = confirmedAssistant.indexOf(assistantKey(m))
                if (idx < 0) return@filterNot false
                confirmedAssistant.removeAt(idx)
                true
            }
            m.role != "user" || !m.id.startsWith("local-") -> false
            else -> {
                // 请求还没返回的乐观消息可能是另一条同文本新消息，不能当作已确认。
                if (m.id in inFlight) return@filterNot false
                val idx = confirmed.indexOf(confirmKey(m))
                if (idx < 0) return@filterNot false
                confirmed.removeAt(idx)
                true
            }
        }
    }
    return snapshot + kept
}

/** 确认匹配粒度：同一会话 + 归一化后的正文。 */
private fun confirmKey(m: ChatMessage): String = m.sessionId + '\u0000' + normalizedUserText(m.text)

/**
 * assistant 的匹配键：同会话 + 归一化正文。
 * 思考链不算正文：不同模型是否回传 thinking 不一致，带上会让同一份回答对不上。
 */
private fun assistantKey(m: ChatMessage): String = m.sessionId + '\u0000' + normalizedAssistantText(m.text)

/** 发送时为图片附了本地后缀，比对正文时去掉。 */
private fun normalizedUserText(text: String): String = text.replace(imageSuffix, "").trim()

/** 空白折叠到单空格：流式拼接与落盘后的正文可能存在换行/空格差异，逐字比对会漏配。 */
private fun normalizedAssistantText(text: String): String =
    text.replace(imageSuffix, "").replace(whitespaceRun, " ").trim()

private val whitespaceRun = Regex("\\s+")

private val imageSuffix = Regex("""\n*📎 \d+ 张图片\s*$""")
