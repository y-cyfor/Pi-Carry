package dev.picarry.app.net

import java.net.Inet4Address
import java.net.InetAddress

/**
 * 一条直连候选地址及其类别。
 *
 * 类别决定尝试顺序：同一局域网内优先 v4（兼容性最好），跨网优先 v6（手机一般是
 * 双栈，但家庭宽带常常没有公网 v4），全球 v4 放最后（多半是 NAT 后不可达）。
 */
data class AddressCandidate(val addr: String, val kind: Kind) {
    enum class Kind { LAN4, LAN6, PUBLIC6, PUBLIC4 }
}

/** 拆分后的 host/port；`host` 已去掉 IPv6 的 `[...]` 与 `%zone` 后缀。 */
internal data class ParsedAddress(val host: String, val port: Int) {
    val isV6: Boolean get() = host.contains(':')
}

/**
 * 解析 `1.2.3.4:52131` 与 `[2408:...:1%wlan0]:52131` 两种形态。
 *
 * 只做字符串级校验，不依赖 iroh 的 [tech.annexflow.iroh4k.SocketAddr]：这样
 * 排序逻辑能在普通 JVM 单测里跑（原生库在单测环境里不可用）。
 * 返回 null 表示格式非法，调用方直接丢弃。
 */
internal fun parseAddress(addr: String): ParsedAddress? {
    val text = addr.trim()
    if (text.isEmpty()) return null
    val host: String
    val portText: String
    if (text.startsWith("[")) {
        val end = text.indexOf(']')
        if (end <= 1) return null
        host = text.substring(1, end)
        val rest = text.substring(end + 1)
        if (!rest.startsWith(":")) return null
        portText = rest.substring(1)
        if (!host.contains(':')) return null
    } else {
        val idx = text.lastIndexOf(':')
        if (idx <= 0) return null
        host = text.substring(0, idx)
        portText = text.substring(idx + 1)
        // 未加方括号的 IPv6 无法可靠拆分端口，按非法处理。
        if (host.contains(':')) return null
    }
    val port = portText.toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    // zone id（`fe80::1%wlan0`）对拨号没有意义，去掉后再判类别。
    val bare = host.substringBefore('%')
    if (bare.isEmpty()) return null
    return ParsedAddress(bare, port)
}

/** `::ffff:` 前缀（IPv4-mapped IPv6）；大小写不敏感。 */
private const val V4_MAPPED_PREFIX = "::ffff:"

/** 严格的点分十进制（四段、每段 0..255）。 */
private fun isDottedQuad(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
    }
}

/**
 * 把 IPv4-mapped IPv6（`::ffff:192.168.1.5`）折回点分十进制 v4 字符串；不是这种形态或解析失败返回 null。
 *
 * 双栈节点会把同一个 v4 地址同时广告成 `192.168.1.5:4433` 和 `[::ffff:192.168.1.5]:4433`。
 * 后者在网络层就是 v4，如果只看「含冒号」就判成 v6，它会被归到 [AddressCandidate.Kind.PUBLIC6]，
 * 排到真正的局域网地址之后，还可能被当成「公网直连」。所以分类前必须先折回来。
 */
internal fun mappedV4Host(host: String): String? {
    if (!host.regionMatches(0, V4_MAPPED_PREFIX, 0, V4_MAPPED_PREFIX.length, ignoreCase = true)) return null
    val tail = host.substring(V4_MAPPED_PREFIX.length)
    // 常见形态就是点分十进制，直接取最后一段，不碰 DNS/解析器。
    if (isDottedQuad(tail)) return tail
    // 只有「冒号分隔的十六进制」形态才交给 InetAddress（`::ffff:c0a8:105` 这类变体）：
    // 先挡住其它垃圾字符串，否则 InetAddress 会把它们当主机名去查 DNS。
    if (tail.isEmpty() || tail.any { it != ':' && Character.digit(it, 16) < 0 }) return null
    // 只有它返回 Inet4Address 才算映射地址，解析失败一律返回 null。
    val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
    if (addr !is Inet4Address) return null
    val v4 = addr.hostAddress ?: return null
    return if (isDottedQuad(v4)) v4 else null
}

private fun firstHextet(host: String): Int? {
    val group = host.substringBefore(':')
    if (group.isEmpty()) return 0
    return group.toIntOrNull(16)
}

private fun isLoopback(host: String): Boolean =
    host == "::1" || host.startsWith("127.") || host == "0.0.0.0" || host == "::"

/** fe80::/10：链路本地 v6。桌面端已不再广告，但旧节点仍可能带出来。 */
internal fun isLinkLocalV6(host: String): Boolean {
    val v = firstHextet(host) ?: return false
    return (v and 0xffc0) == 0xfe80
}

/** fc00::/7：唯一本地地址，等价于 v4 私网。 */
private fun isUniqueLocalV6(host: String): Boolean {
    val v = firstHextet(host) ?: return false
    return (v and 0xfe00) == 0xfc00
}

private fun isPrivateV4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return when {
        a == 10 -> true
        a == 127 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        a == 169 && b == 254 -> true
        a == 100 && b in 64..127 -> true
        else -> false
    }
}

/**
 * 判定一条地址的类别；返回 null 表示这条地址不该尝试。
 *
 * 丢弃规则与需求一致：回环、IPv6 链路本地、解析失败。任何格式错误都不能让
 * 整个列表失效——一条坏地址只丢自己。
 */
fun classifyAddress(addr: String): AddressCandidate.Kind? {
    val parsed = parseAddress(addr) ?: return null
    // IPv4-mapped IPv6 先折回 v4 再按 v4 规则分类（含回环过滤）：它的语义就是 v4，
    // 不折回会被误判成公网 v6，既打乱尝试顺序，也会把局域网地址当成公网直连。
    val host = if (parsed.isV6) mappedV4Host(parsed.host) ?: parsed.host else parsed.host
    if (isLoopback(host)) return null
    return if (host.contains(':')) {
        when {
            isLinkLocalV6(host) -> null
            isUniqueLocalV6(host) -> AddressCandidate.Kind.LAN6
            else -> AddressCandidate.Kind.PUBLIC6
        }
    } else {
        if (isPrivateV4(host)) AddressCandidate.Kind.LAN4 else AddressCandidate.Kind.PUBLIC4
    }
}

/**
 * 排出直连尝试顺序：lan4 → lan6 → public6 → public4。
 *
 * 同类别内保持节点给出的原始顺序（去重后），不做额外排序：节点侧已经按
 * 「最可能可达」排过序，重排只会打乱它的判断。
 */
fun planAddresses(addrs: List<String>): List<AddressCandidate> {
    val seen = HashSet<String>()
    val buckets = linkedMapOf(
        AddressCandidate.Kind.LAN4 to mutableListOf<AddressCandidate>(),
        AddressCandidate.Kind.LAN6 to mutableListOf<AddressCandidate>(),
        AddressCandidate.Kind.PUBLIC6 to mutableListOf<AddressCandidate>(),
        AddressCandidate.Kind.PUBLIC4 to mutableListOf<AddressCandidate>(),
    )
    for (raw in addrs) {
        val kind = classifyAddress(raw) ?: continue
        val parsed = parseAddress(raw) ?: continue
        // 同一地址可能因 zone id 不同重复出现，按 host:port 去重。
        if (!seen.add("${parsed.host}:${parsed.port}")) continue
        buckets.getValue(kind).add(AddressCandidate(raw, kind))
    }
    return buckets.values.flatten()
}
