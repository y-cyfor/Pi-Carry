package dev.picarry.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `fs.roots` 的一个允许根目录。
 *
 * `kind`：`home`（主目录）/ `cwd`（桥接启动目录）/ `custom`（用户配置）。界面只用它
 * 决定快捷芯片上的小字，不据此限制可点性。
 */
data class FsRoot(val path: String, val label: String, val kind: String)

/** `fs.list` 里的一个子目录；`hasSessions` 命中时界面显示小圆点。 */
data class FsEntry(val name: String, val path: String, val hasSessions: Boolean)

/**
 * `fs.list` 的结果。
 *
 * `parent` 为 null 表示当前已是允许根，面包屑到此为止（服务端不允许回退到根之外）。
 */
data class FsListing(
    val path: String,
    val parent: String?,
    val entries: List<FsEntry>,
    val truncated: Boolean,
)

/** `fs.roots` 的完整结果。`recent` 为最近使用过的 cwd（已删目录服务端已过滤）。 */
data class FsRoots(val roots: List<FsRoot>, val recent: List<String>)

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

/** 解析 `fs.roots` 响应；字段缺失按空列表处理（老节点不会走到这里，缺能力已降级）。 */
internal fun parseFsRoots(resp: JsonObject): FsRoots {
    val roots = (resp["roots"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val path = o.str("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        FsRoot(path = path, label = o.str("label") ?: path, kind = o.str("kind") ?: "custom")
    }
    val recent = (resp["recent"] as? JsonArray).orEmpty().mapNotNull { el ->
        (el as? JsonObject)?.str("path")?.takeIf { it.isNotBlank() }
    }
    return FsRoots(roots, recent)
}

/** 解析 `fs.list` 响应；条目按服务端顺序保留（服务端已排序）。 */
internal fun parseFsListing(resp: JsonObject): FsListing {
    val entries = (resp["entries"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o.str("name") ?: return@mapNotNull null
        val path = o.str("path") ?: return@mapNotNull null
        FsEntry(name = name, path = path, hasSessions = o.str("hasSessions") == "true")
    }
    return FsListing(
        path = resp.str("path").orEmpty(),
        parent = resp.str("parent")?.takeIf { it.isNotBlank() },
        entries = entries,
        truncated = resp.str("truncated") == "true",
    )
}

/**
 * 面包屑：从允许根到当前目录的逐级路径。
 *
 * 服务端只给 `parent`，逐级回退时每层都要发一次 `fs.list`，那会在打开选择器时
 * 产生多次往返。这里按路径分隔符直接切出各级，点击任一级只需一次请求。
 * `roots` 用来截断——超出允许根的层级不展示，避免把绝对路径上半段暴露成可点项。
 */
fun breadcrumbs(path: String, roots: List<FsRoot>): List<String> {
    if (path.isBlank()) return emptyList()
    val isWindows = path.length >= 2 && path[1] == ':' || path.contains('\\')
    val sep = if (isWindows) '\\' else '/'
    val normalized = path.trimEnd('/', '\\')
    val parts = normalized.split('/', '\\').filter { it.isNotEmpty() }
    val out = mutableListOf<String>()
    for (i in parts.indices) {
        val joined = parts.subList(0, i + 1).joinToString(sep.toString())
        out += when {
            // 盘符必须补回分隔符：`C:` 不是绝对路径，点它会被服务端当相对路径拼到自己的 cwd 上
            // 然后越界拒绝，表现为「面包屑点第一级必报错」。
            isWindows && i == 0 && joined.endsWith(':') -> "$joined\\"
            isWindows -> joined
            else -> "/$joined"
        }
    }
    // 只在确实越过了某个根时才截断：根就是 `C:\` 这类短前缀时不能把整条路径删光。
    val root = roots.map { it.path.trimEnd('/', '\\') }
        .filter { normalized.startsWith(it, ignoreCase = isWindows) }
        .maxByOrNull { it.length }
    if (root == null) return out.ifEmpty { listOf(normalized) }
    val prefixParts = root.split('/', '\\').count { it.isNotEmpty() }
    return out.drop((prefixParts - 1).coerceAtLeast(0)).ifEmpty { out.takeLast(1) }
}

/** 展示用目录名：取最后一段；根目录回显自身。 */
fun displayName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return path
    val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    return trimmed.substring(cut + 1).ifBlank { trimmed }
}
