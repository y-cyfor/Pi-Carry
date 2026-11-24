package dev.picarry.app.net

import android.util.Log
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.annexflow.iroh4k.Connection
import tech.annexflow.iroh4k.Endpoint
import tech.annexflow.iroh4k.EndpointAddr
import tech.annexflow.iroh4k.EndpointConfig
import tech.annexflow.iroh4k.EndpointId
import tech.annexflow.iroh4k.EndpointPreset
import tech.annexflow.iroh4k.IrohError
import tech.annexflow.iroh4k.RecvStream
import tech.annexflow.iroh4k.RelayMode
import tech.annexflow.iroh4k.SendStream
import tech.annexflow.iroh4k.SecretKey
import tech.annexflow.iroh4k.SocketAddr
import tech.annexflow.iroh4k.connect
import tech.annexflow.iroh4k.openBi
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.net.SocketTimeoutException

/**
 * 单节点连接：握手、控制流、事件流、心跳与自动重连。
 * 一个实例只服务一个 [Node]。
 */
class NodeConnection(
    val node: Node,
    /** 连接代次：Repository 每次 connect 递增，旧实例的收尾回调据此被丢弃。 */
    val generation: Long = 0L,
    private val clientSecret: ByteArray,
    private val clientName: String,
    private val clientVersion: String,
    private val scope: CoroutineScope,
    private val onEvent: (sessionId: String?, seq: Long, event: JsonObject) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
    private val onHandshake: (JsonObject) -> Unit,
) {
    companion object {
        private const val TAG = "CarryConn"
        private const val HEARTBEAT_MS = 15_000L
        // 超过这个时长没收到任何 pong 就认为连接已半开（NAT 超时等），主动断开重连。
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
        private const val PONG = "pong"
        /** 单次控制帧写入上限：对端不读时不能让写流永久挂起。 */
        private const val WRITE_TIMEOUT_MS = 10_000L
        /** 单个直连地址的连接超时；用完所有地址后交给外层退避重连。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /**
         * 握手响应帧的读取超时。节点可能接受 TCP/QUIC 却永不回帧（旧版本、过载、中间盒吞包），
         * 而 [connectOnce] 的超时只覆盖建链，读握手没有超时就会整条连接卡死：心跳要 15s 后才发
         * 第一个 ping，且 ping 只写不读，救不回来。
         */
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        val ALPN = "pi-carry/1".encodeToByteArray()
        /** 确定性失败：同一套凭据重试不会成功，不进入退避重连。 */
        private val FATAL_CODES = setOf(
            "PAIRING_INVALID",
            "PAIRING_EXPIRED",
            "PAIRING_LOCKED",
            "AUTH_FAILED",
            "AUTH_REQUIRED",
            "PROTOCOL_VERSION_UNSUPPORTED",
        )
    }

    private val _state = MutableStateFlow(LinkState.Offline)
    val state: StateFlow<LinkState> = _state
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Wire.Frame>>()
    private val writeLock = Mutex()
    private val counter = AtomicLong(0)
    private var serveJob: Job? = null

    @Volatile private var endpoint: Endpoint? = null

    @Volatile private var connection: Connection? = null

    @Volatile private var send: SendStream? = null

    @Volatile private var lastPongAt: Long = 0L

    private fun now(): Long = System.nanoTime() / 1_000_000

    fun start() {
        if (serveJob?.isActive == true) return
        serveJob = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                publish(LinkState.Connecting, null)
                try {
                    serveOnce()
                    backoff = 1_000L
                } catch (t: CancellationException) {
                    // 正常停止（stop()/切换节点）不是错误：必须向上传递，
                    // 否则会被当成连接失败去重连，把旧 endpoint 重新拉起来。
                    throw t
                } catch (t: Throwable) {
                    if (!isActive) break
                    // 异常文本可能携带 endpoint / 握手片段（甚至凭据），日志只记类型与节点名；
                    // 给用户看的文案仍走 friendly(t)。
                    Log.w(TAG, "节点 ${node.name} 连接失败: ${t.javaClass.simpleName}")
                    publish(LinkState.Error, friendly(t))
                    // 身份类错误（配对码无效/已吊销/权限不足）重试多少次都不会成功，
                    // 无限重连只会白耗电并刷屏日志。停止自动重连，把原因留在 _state 上，
                    // 由用户重新配对后显式 connect。
                    if (isFatal(t)) {
                        Log.w(TAG, "节点 ${node.name} 认证失败，停止自动重连")
                        break
                    }
                }
                // 每次断线都释放上一个 endpoint，避免重连时泄漏 UDP 套接字与后台任务。
                runCatching { cleanup() }
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
            publish(LinkState.Offline, null)
        }
    }

    fun stop() {
        serveJob?.cancel()
        serveJob = null
        scope.launch(Dispatchers.IO) { cleanup() }
    }

    private suspend fun serveOnce() {
        val relay = node.relay?.trim().orEmpty()
        val relayMode = when {
            relay.isBlank() -> RelayMode.Disabled
            relay.equals("n0", true) || relay.equals("default", true) -> RelayMode.Default
            relay.startsWith("http://") || relay.startsWith("https://") -> RelayMode.customFromUrls(listOf(relay))
            else -> RelayMode.Disabled
        }
        val cfg = EndpointConfig(
            preset = if (relayMode is RelayMode.Disabled) EndpointPreset.Minimal else EndpointPreset.N0,
            secretKey = SecretKey.fromBytes(clientSecret),
            relayMode = relayMode,
            bindAddrs = listOf(SocketAddr.parse("0.0.0.0:0")),
            alpns = listOf(ALPN),
        )
        val ep = Endpoint.bind(cfg)
        endpoint = ep
        val conn = connectAny(ep, relayMode)
        connection = conn
        val bi = conn.openBi()
        send = bi.send
        write(helloFrame(nextId()))
        val parsed = Wire.parse(readHandshakeFrame(bi.recv))
        if (parsed.type != "response" || !parsed.ok) {
            // 把服务端的错误码带出去，外层才能分辨「配对失效」与「网络抖动」：
            // 后者要无限重连，前者重连一万次也没用。
            val err = parsed.error
            val code = (err?.get("code") as? JsonPrimitive)?.content ?: "AUTH_FAILED"
            val message = (err?.get("message") as? JsonPrimitive)?.content ?: "配对失败"
            throw CarryException(code, message)
        }
        // 先标记在线，onHandshake 里立即发出的 session.list 才不会撞上 Connecting 状态。
        publish(LinkState.Online, null)
        onHandshake(parsed.payload ?: JsonObject(emptyMap()))

        // 响应和事件共用同一条双向控制流，客户端只维护一个读取循环。
        lastPongAt = now()
        coroutineScope {
            val control = launch { readLoop(bi.recv) }
            val ping = launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val idle = now() - lastPongAt
                    if (idle > HEARTBEAT_TIMEOUT_MS) {
                        // 只发不校验会让 NAT 半开连接假在线；这里抛出让 start() 走重连。
                        Log.w(TAG, "节点 ${node.name} 心跳超时 ${idle}ms，主动断开重连")
                        throw IllegalStateException("心跳超时")
                    }
                    runCatching { write(Wire.ping(System.currentTimeMillis())) }
                }
            }
            control.join()
            ping.cancel()
        }
    }

    private suspend fun connectAny(ep: Endpoint, relayMode: RelayMode): Connection {
        val id = EndpointId.fromString(node.endpointId)
        // planAddresses 负责过滤回环、fe80::/10、非法项，并排出 lan4→lan6→public6→public4 顺序。
        val planned = planAddresses(node.addrs)
        // 全部被过滤掉时（例如 adb reverse 场景下节点只带 127.0.0.1）退回原始列表：
        // 旧行为允许这种地址连上，不能因为排序重构把它丢掉。
        val direct = if (planned.isEmpty()) {
            node.addrs.mapNotNull { runCatching { SocketAddr.parse(it) }.getOrNull() }
        } else {
            planned.mapNotNull { runCatching { SocketAddr.parse(it.addr) }.getOrNull() }
        }
        val targets = direct.map { EndpointAddr.of(id).withIpAddr(it) }
        var last: Throwable? = null
        val record: (EndpointAddr, Throwable) -> Unit = { addr, t ->
            last = t
            logAddrFailure(addr, t)
        }

        // 前两个候选并发竞速：跨网时 v6 直连往往要好几秒才建链，串行尝试会白白消耗时间。
        if (targets.size >= 2) {
            raceTwo(ep, targets[0], targets[1], record)?.let { return it }
        }
        for (addr in if (targets.size >= 2) targets.drop(2) else targets) {
            try {
                return connectOnce(ep, addr)
            } catch (t: CancellationException) {
                // 真正的外层取消（stop()/切换节点）必须向上传递。
                if (!currentCoroutineContext().isActive) throw t
                record(addr, t)
            } catch (t: Throwable) {
                record(addr, t)
            }
        }
        // 直连全部失败时回退到 relay/DNS 发现（只有启用了 relay 才有意义）。
        if (relayMode !is RelayMode.Disabled) {
            val addr = EndpointAddr.of(id)
            try {
                return connectOnce(ep, addr)
            } catch (t: CancellationException) {
                if (!currentCoroutineContext().isActive) throw t
                record(addr, t)
            } catch (t: Throwable) {
                record(addr, t)
            }
        }
        throw last ?: IllegalStateException("节点没有可用地址")
    }

    /** 单次尝试；超时必须转成普通异常，否则会被当成「用户主动停止」吞掉整个重连循环（NEW-1）。 */
    private suspend fun connectOnce(ep: Endpoint, addr: EndpointAddr): Connection = try {
        withTimeout(CONNECT_TIMEOUT_MS) { ep.connect(addr, ALPN) }
    } catch (t: TimeoutCancellationException) {
        throw SocketTimeoutException("连接 ${addr.addrs} 超时")
    }

    private fun logAddrFailure(addr: EndpointAddr, t: Throwable) {
        when (t) {
            // withTimeout 抛的 TimeoutCancellationException 是 CancellationException 的子类，
            // 必须先按类型分派，否则日志会把超时写成「被取消」。
            is SocketTimeoutException -> Log.w(TAG, "地址连接超时 ${addr.addrs}")
            is CancellationException -> Log.w(TAG, "地址连接被取消 ${addr.addrs}: ${t.javaClass.simpleName}")
            else -> Log.w(TAG, "地址连接失败 ${addr.addrs}: ${t.javaClass.simpleName}")
        }
    }

    /**
     * 两个候选并发竞速，先成功的胜出并立即取消另一个。
     *
     * 只负责前两个：多路复用全部地址收益很小，而且会让节点同时收到多条建链请求。
     * 失败记录只在父协程里写入，避免两个子协程同时改同一个变量。
     */
    private suspend fun raceTwo(
        ep: Endpoint,
        first: EndpointAddr,
        second: EndpointAddr,
        record: (EndpointAddr, Throwable) -> Unit,
    ): Connection? = supervisorScope {
        val channel = Channel<Pair<EndpointAddr, Result<Connection>>>(Channel.UNLIMITED)
        val workers = listOf(first, second).map { addr ->
            launch {
                val result = try {
                    Result.success(connectOnce(ep, addr))
                } catch (t: CancellationException) {
                    // 外层取消：直接向上传，兄弟协程由 supervisorScope 一并取消。
                    if (!isActive) throw t
                    Result.failure(t)
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                // 发送前再确认一次自己没被取消：如果 connect() 刚好在 cancel() 之后返回，
                // 这条连接不会有人取、也不会有人关，必须在这里就关掉再退出。
                if (!isActive) {
                    result.getOrNull()?.let { discardConnection(it) }
                    return@launch
                }
                channel.send(addr to result)
            }
        }
        var winner: Connection? = null
        for (i in 0 until 2) {
            val (addr, result) = channel.receive()
            val conn = result.getOrNull()
            if (conn != null) {
                winner = conn
                break
            }
            record(addr, result.exceptionOrNull() ?: IllegalStateException("地址连接失败"))
        }
        // 胜出后必须取消另一个：否则它建链成功会留下一条没人关闭的连接。
        workers.forEach { it.cancel() }
        // 竞速输家必须显式关闭，否则每条连接都会在节点侧留一条僵尸连接：输家可能已经把
        // 建好的连接投进了 Channel 而没人取，光 cancel 协程不会关它——节点侧 active_connections
        // 只增不减，旧连接收尾时还可能把新连接的 conn_info 擦掉。
        while (true) {
            val leftover = channel.tryReceive().getOrNull() ?: break
            leftover.second.getOrNull()?.let { discardConnection(it) }
        }
        winner
    }

    /**
     * 关掉一条没人接管的连接：先给对端发 QUIC close，让节点立刻回收 `active_connections`，
     * 再释放原生句柄（`close(errorCode)` 本身不释放句柄，只发 close 会漏原生连接）。
     */
    private fun discardConnection(conn: Connection) {
        runCatching { conn.close(0L, ByteArray(0)) }
        runCatching { conn.close() }
    }

    /** 握手响应固定是一个控制帧，长度非法只能整链失败；错误文案不带帧正文。 */
    private suspend fun readHandshakeFrame(recv: RecvStream): String = try {
        withTimeout(HANDSHAKE_TIMEOUT_MS) {
            val len = Wire.length(recv.readExact(4))
            if (len < 1 || len > Wire.MAX_CONTROL) {
                throw CarryException("PROTOCOL_ERROR", "握手响应帧长度异常，可能与节点版本不一致")
            }
            recv.readExact(len).decodeToString()
        }
    } catch (t: TimeoutCancellationException) {
        // 超时必须转成普通异常：它是 CancellationException 的子类，直接外溢会被当成
        // 「用户主动停止」而吞掉整个重连循环。
        throw CarryException("TIMEOUT", "握手超时（${HANDSHAKE_TIMEOUT_MS / 1000}s），节点无响应")
    }

    private suspend fun readLoop(recv: RecvStream) {
        // ack 水位按会话独立且只前进：收尾/异常路径都不允许把已确认水位退回 0。
        val ackWatermark = AckWatermark()
        while (true) {
            // 读帧失败（长度头非法/流提前结束）无法与后续帧对齐，只能让连接结束重连：
            // 把异常留在循环外，由 serveOnce 的统一重连退避处理，而不是在这里静默死循环。
            val len = Wire.length(recv.readExact(4))
            if (len <= 0) {
                // 帧长非法意味着流已失步：0 或负数说明 4 字节长度前缀已经和帧边界错位，
                // continue 只会把后续字节继续当长度前缀误读，连接挂着却永远收不到消息。
                // 只能断开重连。
                throw CarryException("PROTOCOL_ERROR", "帧长度非法：$len")
            }
            if (len > Wire.MAX_EVENT) {
                // 超长帧同样可对齐（字节还在流里），但继续读会白耗内存；直接按协议错误断开，
                // 并给出可操作的原因，让用户看到节点版本不一致而不是无限重连。
                throw CarryException(
                    "PROTOCOL_ERROR",
                    "节点发出的帧过大（$len 字节，上限 ${Wire.MAX_EVENT}），可能与节点版本不一致",
                )
            }
            val text = recv.readExact(len).decodeToString()
            var ackedSeq = 0L
            var ackedSession: String? = null
            val handled = runCatching {
                val frame = Wire.parse(text)
                when (frame.type) {
                    "response" -> frame.id?.let { pending.remove(it)?.complete(frame) }
                    "event" -> {
                        // 先登记 seq 再解析载荷：载荷无法解析也要 ack，否则服务端会永久重放该帧。
                        ackedSeq = frame.seq
                        ackedSession = frame.sessionId
                        frame.event?.let { onEvent(frame.sessionId, frame.seq, it) }
                    }
                    PONG -> lastPongAt = now()
                }
            }
            if (handled.isFailure) {
                // 单条畸形帧只丢弃（能识别 seq 时仍回 ack），否则会整条连接重连死循环。
                // 异常 message 可能含帧正文片段，日志只记类型与字节数。
                Log.w(TAG, "丢弃异常帧: ${handled.exceptionOrNull()?.javaClass?.simpleName} bytes=${text.length}")
            }
            // 只在帧带回有效且前进的 seq 时才 ack：解析失败的帧不能把水位退回 0。
            ackWatermark.advance(ackedSession, ackedSeq)?.let { seq ->
                try {
                    write(Wire.ack(ackedSession, seq))
                } catch (e: Exception) {
                    // ack 写失败（典型是 write() 抛的 TIMEOUT）不能吞：连接实际已经死了，
                    // 读循环和心跳若继续跑，UI 会一直显示「正在重连」却永远不重连。
                    // 抛出去让 coroutineScope 取消心跳、serveOnce 走正常断开/重连。
                    Log.w(TAG, "回 ack 失败，断开重连: ${e.javaClass.simpleName}")
                    throw e
                }
            }
        }
    }

    private suspend fun write(payload: String) {
        val stream = send ?: error("控制流未连接")
        // 写流有背压：对端不读时 writeAll 会永久挂起，把读取循环和心跳一起拖死
        // （心跳是另一个协程，但它也在等 writeLock）。超时后抛出，让 start() 重连。
        val bytes = Wire.frame(payload)
        try {
            withTimeout(WRITE_TIMEOUT_MS) { writeLock.withLock { stream.writeAll(bytes) } }
        } catch (e: TimeoutCancellationException) {
            throw CarryException("TIMEOUT", "向节点写入超时（${WRITE_TIMEOUT_MS / 1000}s），连接已重置")
        }
    }

    suspend fun request(
        op: String,
        payload: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 20_000,
    ): JsonObject {
        if (_state.value != LinkState.Online) error("节点未连接")
        val id = nextId()
        val waiter = CompletableDeferred<Wire.Frame>()
        pending[id] = waiter
        return try {
            write(Wire.request(id, op, payload))
            val response = withTimeout(timeoutMs) { waiter.await() }
            if (!response.ok) {
                val message = response.error?.get("message")?.jsonPrimitive?.content ?: "请求失败"
                val code = response.error?.get("code")?.jsonPrimitive?.content
                throw CarryException(code ?: "INTERNAL", message)
            }
            response.payload ?: JsonObject(emptyMap())
        } catch (e: TimeoutCancellationException) {
            throw CarryException("TIMEOUT", "请求 $op 超时")
        } finally {
            pending.remove(id)
        }
    }

    private fun nextId(): String = "m${counter.incrementAndGet()}"

    private fun helloFrame(id: String): String = buildJsonObject {
        put("type", JsonPrimitive("request"))
        put("id", JsonPrimitive(id))
        put("op", JsonPrimitive("hello"))
        put("payload", buildJsonObject {
            put("protocol", JsonPrimitive("carry.v1"))
            put("client", buildJsonObject {
                put("name", JsonPrimitive(clientName))
                put("platform", JsonPrimitive("android"))
                put("appVersion", JsonPrimitive(clientVersion))
                put("deviceClass", JsonPrimitive("phone"))
                put("locale", JsonPrimitive(Locale.getDefault().toLanguageTag()))
            })
            if (node.token.isNotBlank()) put("token", JsonPrimitive(node.token))
            else node.code?.let { put("code", JsonPrimitive(it)) }
        })
    }.toString()

    private fun publish(s: LinkState, error: String?) {
        _state.value = s
        onState(s, error)
    }

    private fun friendly(t: Throwable): String = when {
        // 服务端已经给了可操作的中文文案（连接数超限、帧过大、配对失效等），
        // 原样透出比按关键词猜更准确；猜错会把“配对已失效”显示成“无法连接节点”。
        t is CarryException -> t.message ?: codeLabel(t.code)
        // iroh4k 的 IrohError 带结构化 Code：按枚举分支比匹配英文报错文案稳定，
        // 库换版本或做本地化都不会让分支失配（旧实现依赖 "Connect" 这种英文子串）。
        t is IrohError -> when (t.code) {
            IrohError.Code.Connect -> "无法连接节点"
            IrohError.Code.Bind -> "本机网络端口不可用，请检查网络设置"
            IrohError.Code.Relay -> "中继服务器不可达"
            IrohError.Code.Closed -> "连接已关闭"
            IrohError.Code.Cancelled -> "连接已取消"
            IrohError.Code.InvalidArgument -> "节点地址无效，请重新扫描二维码"
            else -> "连接失败（${t.code}）"
        }
        // 没有结构化信息的异常（超时、协程取消等）才走到文案分支。
        t.message?.isNotBlank() == true -> t.message!!
        else -> "连接失败"
    }

    private fun codeLabel(code: String): String = when (code) {
        "PAIRING_INVALID" -> "配对码无效"
        "PAIRING_EXPIRED" -> "配对码已过期，请在桌面端重新生成"
        "PAIRING_LOCKED" -> "尝试次数过多，请 10 分钟后再试"
        "AUTH_FAILED", "AUTH_REQUIRED" -> "认证失败，请重新配对"
        "PERMISSION_DENIED" -> "该设备没有此操作权限"
        "PROTOCOL_ERROR" -> "协议不兼容，请升级一端"
        "TIMEOUT" -> "连接超时，正在重连"
        else -> "连接失败（$code）"
    }

    /**
     * 这些错误是确定性的：同样的凭据重试只会得到同样结果。
     * 心跳超时、网络不可达、连接数超限（旧连接释放后可能恢复）都不在此列。
     */
    private fun isFatal(t: Throwable): Boolean {
        val code = (t as? CarryException)?.code ?: return false
        return code in FATAL_CODES
    }

    private suspend fun cleanup() {
        send?.let { runCatching { it.finish() } }
        send = null
        connection?.let { runCatching { it.close() } }
        connection = null
        endpoint?.let {
            runCatching { it.shutdown() }
            runCatching { it.close() }
        }
        endpoint = null
        pending.values.forEach { it.completeExceptionally(IllegalStateException("连接已关闭")) }
        pending.clear()
    }
}

class CarryException(val code: String, message: String) : Exception(message)