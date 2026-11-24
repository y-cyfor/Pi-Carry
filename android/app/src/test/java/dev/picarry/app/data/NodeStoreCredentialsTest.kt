package dev.picarry.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 凭据解不开时的降级行为（N-01）。
 *
 * 真实场景：Keystore 密钥被系统清掉、或用户跨设备恢复了 SharedPreferences 备份。
 * 此时「解不开」必须与「没有节点」区分开，否则一次空表保存就会把还能救的密文覆盖掉，
 * 节点永久消失。这几个用例走的是真实 [NodeStore] 路径，只是把 Keystore 换成可注入的
 * 测试 cipher（JVM 上没有 AndroidKeyStore 实现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NodeStoreCredentialsTest {
    /** 模拟「密钥还在」的正常 cipher：Base64 包裹，加解密自洽。 */
    private object WorkingCipher : SecretCipher {
        override fun encrypt(plain: String): String =
            Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)

        override fun decrypt(stored: String): String? =
            runCatching { String(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()
    }

    /**
     * 模拟「Keystore 密钥被系统清掉后又重新生成」：只能解开自己加密的内容，
     * 之前落盘的密文一律解不开。
     */
    private class RotatedKeyCipher : SecretCipher {
        private val handle = UUID.randomUUID().toString().take(8)

        override fun encrypt(plain: String): String =
            "$handle:${Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)}"

        override fun decrypt(stored: String): String? {
            val prefix = "$handle:"
            if (!stored.startsWith(prefix)) return null
            return runCatching { String(Base64.decode(stored.removePrefix(prefix), Base64.NO_WRAP)) }.getOrNull()
        }
    }

    private fun freshContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs(context).edit().clear().commit()
        return context
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE)

    private fun node(id: String = "n1") = Node(id = id, name = "办公电脑", endpointId = "ep-1")

    /** 先用「密钥还在」时的 cipher 落一份密文，模拟历史数据。 */
    private fun seedCiphertext(context: Context): String {
        NodeStore(context, WorkingCipher).saveNodes(listOf(node()))
        val stored = prefs(context).getString("nodes", null)
        assertNotNull(stored)
        assertTrue(CredentialCodec.isEncrypted(stored!!))
        return stored
    }

    @Test
    fun `密文解不开时返回空表并标记丢失且不动磁盘`() {
        val context = freshContext()
        val seeded = seedCiphertext(context)

        val store = NodeStore(context, RotatedKeyCipher())

        assertTrue(store.loadNodes().isEmpty())
        assertTrue(store.credentialsLost())
        // 密文仍是唯一的数据副本：读失败不能顺手清 prefs。
        assertEquals(seeded, prefs(context).getString("nodes", null))
    }

    @Test
    fun `凭据丢失的空表保存不会覆盖旧密文`() {
        val context = freshContext()
        val seeded = seedCiphertext(context)
        val store = NodeStore(context, RotatedKeyCipher())
        store.loadNodes()

        store.saveNodes(emptyList())

        assertEquals(seeded, prefs(context).getString("nodes", null))
        assertTrue(store.credentialsLost())
    }

    @Test
    fun `重新写入节点后覆盖旧密文并清除丢失标记`() {
        val context = freshContext()
        val seeded = seedCiphertext(context)
        val store = NodeStore(context, RotatedKeyCipher())
        store.loadNodes()

        store.saveNodes(listOf(node("n2")))

        assertFalse(store.credentialsLost())
        val stored = prefs(context).getString("nodes", null)
        assertNotNull(stored)
        assertTrue(stored != seeded)
        // 旧密文必须先留一份备份：重配对覆盖之后密钥恢复也还能找回其余节点（R-01）。
        assertEquals(seeded, prefs(context).getString("nodes_unreadable_backup", null))
        // 新密文用当前 cipher 解得开，节点列表回到可用状态。
        assertEquals(listOf("n2"), store.loadNodes().map { it.id })
        assertFalse(store.credentialsLost())
    }

    @Test
    fun `凭据丢失时新身份不覆盖旧密文且跨实例稳定`() {
        val context = freshContext()
        // 先落一份「密钥还在」时的身份密文，再用换了密钥的 cipher 去读。
        val seededIdentity = NodeStore(context, WorkingCipher).clientSecret()
        val storedSecret = prefs(context).getString("client_secret", null)
        assertNotNull(storedSecret)

        // 同一个 cipher 实例 = 「同一次密钥轮换后的稳定密钥」，跨实例（重启应用）也用它。
        val rotated = RotatedKeyCipher()
        val store = NodeStore(context, rotated)
        val first = store.clientSecret()
        assertEquals(32, first.size)
        // 原密文不能被打断：密钥恢复后同一台手机还能用回旧身份。
        assertEquals(storedSecret, prefs(context).getString("client_secret", null))
        assertNotNull(prefs(context).getString("client_secret_recovered", null))

        // 新身份必须稳定：每次启动都换会让服务端把它当新设备，累计触发设备数上限。
        assertArrayEquals(first, store.clientSecret())
        assertArrayEquals(first, NodeStore(context, rotated).clientSecret())
        // 与真正丢失的旧身份不同（密钥已换，无法恢复同一个 EndpointId）。
        assertFalse(first.contentEquals(seededIdentity))
    }
}
