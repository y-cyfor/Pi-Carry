package dev.picarry.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 6：地址分类与尝试顺序（纯 JVM，不依赖原生库）。 */
class AddressPlanTest {

    @Test
    fun `按 lan4 lan6 public6 public4 顺序排列`() {
        val plan = planAddresses(
            listOf(
                "8.8.8.8:41000",
                "[2408:8207:1234:5678::1]:41000",
                "[fe80::1]:41000",
                "192.168.1.10:41000",
                "::1:41000",
                "127.0.0.1:41000",
                "[fd00::2]:41000",
            ),
        )
        assertEquals(
            listOf(
                AddressCandidate.Kind.LAN4,
                AddressCandidate.Kind.LAN6,
                AddressCandidate.Kind.PUBLIC6,
                AddressCandidate.Kind.PUBLIC4,
            ),
            plan.map { it.kind },
        )
        assertEquals("192.168.1.10:41000", plan[0].addr)
        assertEquals("[fd00::2]:41000", plan[1].addr)
        assertEquals("[2408:8207:1234:5678::1]:41000", plan[2].addr)
        assertEquals("8.8.8.8:41000", plan[3].addr)
    }

    @Test
    fun `回环 IPv6 链路本地与非法地址被丢弃`() {
        assertTrue(planAddresses(listOf("::1:41000", "127.0.0.1:41000", "[fe80::1]:41000")).isEmpty())
        assertNull(classifyAddress("not-an-address"))
        assertNull(classifyAddress("1.2.3.4"))
        assertNull(classifyAddress("1.2.3.4:0"))
        assertNull(classifyAddress("1.2.3.4:70000"))
        // 未加方括号的 IPv6 无法可靠拆端口，按非法处理。
        assertNull(classifyAddress("2408:8207::1:41000"))
    }

    @Test
    fun `私网与公网 v4 分类覆盖常见段`() {
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("10.1.2.3:1"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("172.16.5.5:1"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("172.31.255.255:1"))
        assertEquals(AddressCandidate.Kind.PUBLIC4, classifyAddress("172.32.0.1:1"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("192.168.0.9:1"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("169.254.3.3:1"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("100.100.1.1:1"))
        assertEquals(AddressCandidate.Kind.PUBLIC4, classifyAddress("100.200.1.1:1"))
        assertEquals(AddressCandidate.Kind.PUBLIC4, classifyAddress("1.1.1.1:1"))
    }

    @Test
    fun `v6 ULA 与全球地址分类及 zone id 处理`() {
        assertEquals(AddressCandidate.Kind.LAN6, classifyAddress("[fd12:3456::1]:1"))
        assertEquals(AddressCandidate.Kind.LAN6, classifyAddress("[fc00::1]:1"))
        assertEquals(AddressCandidate.Kind.PUBLIC6, classifyAddress("[2408:8207::1]:1"))
        // 带 zone id 的链路本地仍应被丢弃（去掉 zone 后判定）。
        assertNull(classifyAddress("[fe80::1%wlan0]:1"))
        assertNull(classifyAddress("[febf::1]:1"))
        assertEquals(AddressCandidate.Kind.PUBLIC6, classifyAddress("[fec0::1]:1"))
    }

    @Test
    fun `IPv4-mapped IPv6 折回 v4 后再分类`() {
        // 双栈节点广告的映射地址在网络层就是 v4：必须归到 LAN4，不能当成公网 v6。
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("[::ffff:192.168.1.5]:4433"))
        assertEquals(AddressCandidate.Kind.PUBLIC4, classifyAddress("[::ffff:8.8.8.8]:4433"))
        // 映射后的回环同样要被过滤。
        assertNull(classifyAddress("[::ffff:127.0.0.1]:1234"))
    }

    @Test
    fun `mappedV4Host 识别大小写变体且不误折普通 v6`() {
        assertEquals("192.168.1.5", mappedV4Host("::ffff:192.168.1.5"))
        assertEquals("192.168.1.5", mappedV4Host("::FFFF:192.168.1.5"))
        assertEquals(AddressCandidate.Kind.LAN4, classifyAddress("[::FFFF:192.168.1.5]:4433"))
        // 非映射地址、不完整的映射、以及非法点分十进制都不折回。
        assertNull(mappedV4Host("2408:8207:1234:5678::1"))
        assertNull(mappedV4Host("fd00::2"))
        assertNull(mappedV4Host("::ffff:192.168.1.5.6"))
        assertNull(mappedV4Host("::ffff:192.168.1.999"))
        assertNull(mappedV4Host("192.168.1.5"))
    }

    @Test
    fun `映射地址在排序里与真 v4 同级`() {
        val plan = planAddresses(
            listOf("[::ffff:8.8.8.8]:1", "192.168.1.5:1", "[::ffff:192.168.1.9]:1", "[2408:8207::1]:1"),
        )
        assertEquals(
            listOf(
                AddressCandidate.Kind.LAN4,
                AddressCandidate.Kind.LAN4,
                AddressCandidate.Kind.PUBLIC6,
                AddressCandidate.Kind.PUBLIC4,
            ),
            plan.map { it.kind },
        )
    }

    @Test
    fun `重复地址按 host 与端口去重且保持类内原序`() {
        val plan = planAddresses(
            listOf("192.168.1.2:1", "192.168.1.2:1", "10.0.0.2:1", "[fd00::1]:5", "[fd00::1]:5"),
        )
        assertEquals(listOf("192.168.1.2:1", "10.0.0.2:1", "[fd00::1]:5"), plan.map { it.addr })
    }
}
