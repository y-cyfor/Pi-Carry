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
}
