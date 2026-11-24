package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 3：cwd 分组、归一化与折叠集持久化。 */
class CwdGroupsTest {

    private fun session(id: String, cwd: String, updatedAt: String, node: String = "n1") =
        Session(id = id, nodeId = node, cwd = cwd, updatedAt = updatedAt)

    @Test
    fun `Windows 路径大小写与尾部分隔符归为同组`() {
        assertEquals("c:\\work", normalizeCwdKey("C:\\Work\\"))
        assertEquals("c:\\work", normalizeCwdKey("c:\\work"))
        // Linux 路径保持大小写（大小写敏感）。
        assertEquals("/Home/Work", normalizeCwdKey("/Home/Work/"))
        assertEquals("", normalizeCwdKey(""))
        assertEquals("", normalizeCwdKey("  /  ".trimEnd('/', '\\').trim()))
    }

    @Test
    fun `同 cwd 不同大小写合并为一组且保留原始展示路径`() {
        val groups = groupSessionsByCwd(
            listOf(
                session("a", "C:\\Work", "2026-01-01T00:00:01Z"),
                session("b", "c:\\work\\", "2026-01-01T00:00:02Z"),
            ),
            emptySet(),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].count)
        // 显示保留会话里的原始 cwd。
        assertTrue(groups[0].cwd.equals("C:\\Work", ignoreCase = true))
    }

    @Test
    fun `组间按最新时间倒序且默认目录排最后`() {
        val groups = groupSessionsByCwd(
            listOf(
                session("empty", "", "2099-01-01T00:00:00Z"),
                session("old", "/old", "2026-01-01T00:00:00Z"),
                session("new", "/new", "2026-06-01T00:00:00Z"),
            ),
            emptySet(),
        )
        assertEquals(listOf("/new", "/old", ""), groups.map { it.cwd })
        assertEquals("默认目录", groups.last().label)
    }

    @Test
    fun `组内按 updatedAt 倒序且多节点互不合并`() {
        val groups = groupSessionsByCwd(
            listOf(
                session("a1", "/w", "2026-01-01T00:00:01Z", node = "n1"),
                session("a2", "/w", "2026-01-01T00:00:05Z", node = "n1"),
                session("b1", "/w", "2026-01-01T00:00:09Z", node = "n2"),
            ),
            emptySet(),
        )
        assertEquals(2, groups.size)
        val n1 = groups.first { it.nodeId == "n1" }
        assertEquals(listOf("a2", "a1"), n1.sessions.map { it.id })
        // n2 的组更新时间更晚，排在最前。
        assertEquals("n2", groups.first().nodeId)
    }

    @Test
    fun `折叠集编码解码往返`() {
        val set = setOf("n1|/a", "n2|c:\\work")
        assertEquals(set, decodeCollapsed(encodeCollapsed(set)))
        assertEquals(emptySet<String>(), decodeCollapsed(null))
        assertEquals(emptySet<String>(), decodeCollapsed("not-json"))
    }

    @Test
    fun `折叠状态受 forceExpand 影响且不写回`() {
        val group = groupSessionsByCwd(listOf(session("a", "/w", "2026-01-01T00:00:00Z")), emptySet()).first()
        val collapsed = setOf(collapsedToken("n1", "/w"))
        assertTrue(isGroupCollapsed(collapsed, group, forceExpand = false))
        assertFalse(isGroupCollapsed(collapsed, group, forceExpand = true))
        // 搜索/筛选激活时点击组头不改折叠集。
        assertEquals(collapsed, toggleCollapsed(collapsed, group, forceExpand = true))
        assertEquals(emptySet<String>(), toggleCollapsed(collapsed, group, forceExpand = false))
    }
}
