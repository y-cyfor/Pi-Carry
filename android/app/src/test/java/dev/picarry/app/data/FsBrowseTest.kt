package dev.picarry.app.data

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 2：目录浏览响应的解析与面包屑。 */
class FsBrowseTest {

    @Test
    fun `解析 fs_roots 与 recent`() {
        val resp = buildJsonObject {
            put("roots", buildJsonArray {
                add(buildJsonObject {
                    put("path", "C:\\Users\\me")
                    put("label", "me")
                    put("kind", "home")
                })
                add(buildJsonObject {
                    put("path", "D:\\work")
                    put("kind", "custom")
                })
                // 缺 path 的条目必须丢弃，不能生成不可点的空芯片。
                add(buildJsonObject { put("label", "broken") })
            })
            put("recent", buildJsonArray {
                add(buildJsonObject { put("path", "D:\\work\\p1") })
                add(buildJsonObject { put("path", "") })
            })
        }
        val roots = parseFsRoots(resp)
        assertEquals(2, roots.roots.size)
        assertEquals("me", roots.roots[0].label)
        assertEquals("home", roots.roots[0].kind)
        // label 缺省时回显路径。
        assertEquals("D:\\work", roots.roots[1].label)
        assertEquals(listOf("D:\\work\\p1"), roots.recent)
    }

    @Test
    fun `解析 fs_list 保留顺序并标记 truncated`() {
        val resp = buildJsonObject {
            put("path", "/home/me")
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("name", "alpha")
                    put("path", "/home/me/alpha")
                    put("hasSessions", true)
                })
                add(buildJsonObject {
                    put("name", "beta")
                    put("path", "/home/me/beta")
                    put("hasSessions", false)
                })
            })
            put("truncated", true)
        }
        val listing = parseFsListing(resp)
        assertEquals("/home/me", listing.path)
        assertNull(listing.parent)
        assertEquals(listOf("alpha", "beta"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].hasSessions)
        assertFalse(listing.entries[1].hasSessions)
        assertTrue(listing.truncated)
    }

    @Test
    fun `面包屑从允许根开始逐级给出完整路径`() {
        val roots = listOf(FsRoot("C:\\Users\\me", "me", "home"))
        // 每一级都是完整路径：点击任一级可直接发一次 fs.list，不用逐级回退。
        assertEquals(
            listOf("C:\\Users\\me", "C:\\Users\\me\\work", "C:\\Users\\me\\work\\p1"),
            breadcrumbs("C:\\Users\\me\\work\\p1", roots),
        )
        val unixRoots = listOf(FsRoot("/home/me", "me", "home"))
        assertEquals(
            listOf("/home/me", "/home/me/work"),
            breadcrumbs("/home/me/work/", unixRoots),
        )
        // 没有匹配的根（允许根为空）时保留完整逐级路径，不截断。
        assertEquals(listOf("/tmp", "/tmp/x"), breadcrumbs("/tmp/x", emptyList()))
        assertTrue(breadcrumbs("", roots).isEmpty())
    }

    @Test
    fun `盘符面包屑补回分隔符成为绝对路径`() {
        // 根就是盘符时，第一级必须给 `C:\\`：裸 `C:` 不是绝对路径，服务端会拼到自己的 cwd 上再越界拒绝。
        val driveRoot = listOf(FsRoot("C:\\", "C:", "custom"))
        assertEquals(
            listOf("C:\\", "C:\\Users", "C:\\Users\\me"),
            breadcrumbs("C:\\Users\\me", driveRoot),
        )
    }

    @Test
    fun `目录名取最后一段`() {
        assertEquals("work", displayName("C:\\Users\\me\\work"))
        assertEquals("work", displayName("/home/me/work/"))
        // 根目录去掉尾部斜杠后只剩盘符，回显 "C:" 已无更多可读信息。
        assertEquals("C:", displayName("C:\\"))
        assertEquals("/", displayName("/"))
    }

    @Test
    fun `节点能力支持判断`() {
        val modern = Node(id = "n", name = "n", endpointId = "e", capabilities = listOf("fs-browse"))
        assertTrue(modern.supports("fs-browse"))
        assertFalse(modern.supports("run-summary"))
        // 老节点没有 capabilities 字段：一律当作不支持，走降级路径。
        val legacy = Node(id = "n", name = "n", endpointId = "e")
        assertFalse(legacy.supports("fs-browse"))
    }
}
