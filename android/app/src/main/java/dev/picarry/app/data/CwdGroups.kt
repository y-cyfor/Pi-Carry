package dev.picarry.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一个 cwd 分组：同一节点下工作目录相同的会话。 */
data class CwdGroup(
    val nodeId: String,
    /** 分组键：归一化并（Windows 路径）小写后的 cwd；空串表示「默认目录」。 */
    val key: String,
    /** 展示用原始 cwd；空串表示「默认目录」。 */
    val cwd: String,
    val sessions: List<Session>,
) {
    val label: String get() = if (cwd.isBlank()) "默认目录" else lastSegment(cwd)

    val count: Int get() = sessions.size

    /** 组间排序依据：组内最新更新时间（ISO 字符串可直接比较）。 */
    val latest: String get() = sessions.maxOfOrNull { it.updatedAt } ?: ""
}

private fun lastSegment(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return trimmed.substring(cut + 1).ifBlank { trimmed }
}

/**
 * cwd 归一化：去尾部路径分隔符，Windows 风格路径统一小写。
 *
 * 判 Windows 的两种依据：带盘符（`C:`/`c:\`）或含反斜杠。Linux 路径里允许出现
 * 反斜杠，但那种目录在本项目里不会出现，宁可把小写化多一点也不要同组被拆开。
 */
fun normalizeCwdKey(cwd: String): String {
    val trimmed = cwd.trim().trimEnd('/', '\\')
    if (trimmed.isEmpty()) return ""
    val isWindows = trimmed.contains('\\') ||
        (trimmed.length >= 2 && trimmed[1] == ':' && trimmed[0].isLetter())
    return if (isWindows) trimmed.lowercase() else trimmed
}

/** 折叠集元素的编码：`"<nodeId>|<normalizedCwd>"`。 */
fun collapsedToken(nodeId: String, key: String): String = "$nodeId|$key"

private val collapsedJson = Json { ignoreUnknownKeys = true }

/** 解析持久化的折叠集；格式损坏时返回空集（宁可全展开，也不能整个列表消失）。 */
fun decodeCollapsed(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return runCatching {
        collapsedJson.parseToJsonElement(raw).let { el ->
            (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        }
    }.getOrElse { emptySet() }
}

fun encodeCollapsed(collapsed: Set<String>): String =
    collapsed.sorted().joinToString(",", prefix = "[", postfix = "]") { JsonPrimitive(it).toString() }

/**
 * 按 cwd 分组。
 *
 * 组间按组内最新更新时间倒序；「默认目录」组（cwd 为空）永远排最后 —— 它通常是
 * 老会话或节点默认目录，夹在真实目录中间会打断用户对路径的扫读。
 * `forceExpand` 为 true（搜索/筛选激活）时完全忽略折叠状态，且不写回持久化。
 */
fun groupSessionsByCwd(
    sessions: List<Session>,
    collapsed: Set<String>,
    forceExpand: Boolean = false,
): List<CwdGroup> {
    val grouped = LinkedHashMap<Pair<String, String>, MutableList<Session>>()
    for (s in sessions) {
        val key = normalizeCwdKey(s.cwd)
        grouped.getOrPut(s.nodeId to key) { mutableListOf() }.add(s)
    }
    val groups = grouped.map { (nodeAndKey, list) ->
        CwdGroup(
            nodeId = nodeAndKey.first,
            key = nodeAndKey.second,
            cwd = if (nodeAndKey.second.isEmpty()) "" else list.first().cwd,
            sessions = list.sortedByDescending { it.updatedAt },
        )
    }
    return groups.sortedWith(
        compareBy<CwdGroup> { it.cwd.isBlank() }
            .thenByDescending { it.latest },
    )
}

/** 某个组当前是否折叠；搜索/筛选激活时永远展开。 */
fun isGroupCollapsed(collapsed: Set<String>, group: CwdGroup, forceExpand: Boolean): Boolean =
    !forceExpand && collapsedToken(group.nodeId, group.key) in collapsed

/** 点击组头后的新折叠集；搜索/筛选激活时不写回（返回原集合）。 */
fun toggleCollapsed(collapsed: Set<String>, group: CwdGroup, forceExpand: Boolean): Set<String> {
    if (forceExpand) return collapsed
    val token = collapsedToken(group.nodeId, group.key)
    return if (token in collapsed) collapsed - token else collapsed + token
}
