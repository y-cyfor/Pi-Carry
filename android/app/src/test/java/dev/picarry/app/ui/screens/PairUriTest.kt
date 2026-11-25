package dev.picarry.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 深链解析只认 `pi-carry://pair`：host 不校验时任意 `pi-carry://whatever?...`
 * 都会被当作合法配对链接放进配对页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PairUriTest {
    private val endpoint = "0123456789abcdef".repeat(4)

    @Test
    fun acceptsPairHost() {
        val info = parsePairUri("pi-carry://pair?v=1&endpoint=$endpoint&token=abc")
        assertNotNull(info)
        assertEquals(endpoint, info!!.endpointId)
        assertEquals("abc", info.token)
    }

    @Test
    fun rejectsWrongHost() {
        assertNull(parsePairUri("pi-carry://evil?v=1&endpoint=$endpoint&token=abc"))
    }

    @Test
    fun rejectsWrongSchemeAndBrokenEndpoint() {
        assertNull(parsePairUri("https://pair?endpoint=$endpoint&token=abc"))
        assertNull(parsePairUri("pi-carry://pair?v=1&endpoint=not-a-key&token=abc"))
        assertNull(parsePairUri(""))
    }

    /** 二维码里的 name 要能取出来并做 percent-decode，否则节点名会显示成 `%E5%B7%A5` 一类的编码串。 */
    @Test
    fun decodesNodeName() {
        val info = parsePairUri(
            "pi-carry://pair?v=1&endpoint=$endpoint&token=abc&name=%E5%B7%A5%E4%BD%9C%E7%AB%99%20A",
        )
        assertNotNull(info)
        assertEquals("工作站 A", info!!.name)
    }

    /** BUG-026：版本字段必须校验，否则未来协议的链接会被旧客户端按旧语义静默接受。 */
    @Test
    fun rejectsUnsupportedVersion() {
        assertNull(parsePairUri("pi-carry://pair?v=2&endpoint=$endpoint&token=abc"))
        assertNull(parsePairUri("pi-carry://pair?endpoint=$endpoint&token=abc"))
    }

    /** 生成侧会写 `relay=n0` 和多条 `addr`：解析必须照单全收（BUG-001 的客户端一侧）。 */
    @Test
    fun acceptsRelayModeAndMultipleAddrs() {
        val info = parsePairUri(
            "pi-carry://pair?v=1&endpoint=$endpoint&token=abc&relay=n0" +
                "&addr=127.0.0.1%3A55277&addr=192.168.1.9%3A55277&addr=%5B%3A%3A1%5D%3A55279",
        )
        assertNotNull(info)
        assertEquals("n0", info!!.relay)
        assertEquals(listOf("127.0.0.1:55277", "192.168.1.9:55277", "[::1]:55279"), info.addrs)
    }
}
