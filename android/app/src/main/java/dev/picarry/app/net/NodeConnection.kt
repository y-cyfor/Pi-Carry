package dev.picarry.app.net

import android.util.Log
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import tech.annexflow.iroh4k.RecvStream
import tech.annexflow.iroh4k.RelayMode
import tech.annexflow.iroh4k.SendStream
import tech.annexflow.iroh4k.SocketAddr
import tech.annexflow.iroh4k.connect
import tech.annexflow.iroh4k.openBi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 单节点连接：握手、控制流、事件流、心跳与自动重连。
 * 一个实例只服务一个 [Node]。
 */
class NodeConnection(
    val node: Node,
    private val scope: CoroutineScope,
    private val onEvent: (sessionId: String?, seq: Long, event: JsonObject) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
    private val onHandshake: (JsonObject) -> Unit,
) {
    companion object {
        private const val TAG = "CarryConn"
        val ALPN = "pi-carry/1".encodeToByteArray()
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

    fun start() {
        if (serveJob?.isActive == true) return
        serveJob = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                publish(LinkState.Connecting, null)
                try {
                    serveOnce()
                    backoff = 1_000L
                } catch (t: Throwable) {
                    if (!isActive) break
                    Log.w(TAG, "节点 ${node.name} 连接失败: ${t.message}", t)
                    publish(LinkState.Error, friendly(t))
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
        val parsed = Wire.parse(readJson(bi.recv))
        if (parsed.type != "response" || !parsed.ok) {
            throw IllegalStateException(parsed.error?.get("message")?.jsonPrimitive?.content ?: "配对失败")
        }
        // 先标记在线，onHandshake 里立即发出的 session.list 才不会撞上 Connecting 状态。
        publish(LinkState.Online, null)
        onHandshake(parsed.payload ?: JsonObject(emptyMap()))

        // 响应和事件共用同一条双向控制流，客户端只维护一个读取循环。
        coroutineScope {
            val control = launch { readLoop(bi.recv) }
            val ping = launch {
                while (isActive) {
                    delay(15_000)
                    runCatching { write(Wire.ping(UUID.randomUUID().toString())) }
                }
            }
            control.join()
            ping.cancel()
        }
    }

    private suspend fun connectAny(ep: Endpoint, relayMode: RelayMode): Connection {
        val id = EndpointId.fromString(node.endpointId)
        val addrs = node.addrs.mapNotNull { runCatching { SocketAddr.parse(it) }.getOrNull() }
        // 桌面端二维码会同时带 127.0.0.1/[::1] 和局域网地址；手机上回环地址一定是无效目标。
        val reachable = addrs.filterNot { it.ip == "::1" || it.ip.startsWith("127.") }.ifEmpty { addrs }
        val targets = reachable.map { EndpointAddr.of(id).withIpAddr(it) }.toMutableList()
        // 直连全部失败时回退到 relay/DNS 发现（只有启用了 relay 才有意义）。
        if (relayMode !is RelayMode.Disabled) targets += EndpointAddr.of(id)
        var last: Throwable? = null
        for (addr in targets) {
            try {
                return withTimeout(15_000) { ep.connect(addr, ALPN) }
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "地址连接失败 ${addr.addrs}: ${t.message}")
            }
        }
        throw last ?: IllegalStateException("节点没有可用地址")
    }

    private suspend fun readLoop(recv: RecvStream) {
        while (true) {
            val frame = Wire.parse(readJson(recv))
            when (frame.type) {
                "response" -> frame.id?.let { pending.remove(it)?.complete(frame) }
                "event" -> {
                    val event = frame.event ?: continue
                    onEvent(frame.sessionId, frame.seq, event)
                    runCatching { write(Wire.ack(frame.sessionId, frame.seq)) }
                }
            }
        }
    }

    private suspend fun readJson(recv: RecvStream): String {
        val header = recv.readExact(4)
        val len = Wire.length(header)
        require(len in 1..Wire.MAX_EVENT) { "非法帧长度 $len" }
        return recv.readExact(len).decodeToString()
    }

    private suspend fun write(payload: String) {
        val stream = send ?: error("控制流未连接")
        writeLock.withLock { stream.writeAll(Wire.frame(payload)) }
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
                put("name", JsonPrimitive("pi carry"))
                put("platform", JsonPrimitive("android"))
                put("appVersion", JsonPrimitive("0.1.0"))
                put("deviceClass", JsonPrimitive("phone"))
                put("locale", JsonPrimitive("zh-CN"))
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
        t.message?.contains("Connect", true) == true -> "无法连接节点"
        t.message?.contains("pair", true) == true -> "配对已失效"
        else -> t.message ?: "连接失败"
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
