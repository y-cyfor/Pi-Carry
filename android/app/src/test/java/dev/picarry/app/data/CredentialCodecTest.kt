package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据落盘编码的单元测试。
 *
 * 用假 cipher 代替 Android Keystore（真机才有的系统服务），覆盖：加解密往返、
 * 旧明文数据的兼容读取、带前缀但无法解密时返回 null 而不是抛异常。
 */
class CredentialCodecTest {

    private class FakeCipher(private val broken: Boolean = false) : SecretCipher {
        override fun encrypt(plain: String): String = "x" + plain.reversed()

        override fun decrypt(stored: String): String? =
            if (broken || !stored.startsWith("x")) null else stored.removePrefix("x").reversed()
    }

    @Test
    fun roundTripRestoresOriginal() {
        val cipher = FakeCipher()
        val encoded = CredentialCodec.encode(cipher, """[{"token":"secret"}]""")
        assertTrue(encoded.startsWith(CredentialCodec.PREFIX))
        // 密文本身不含明文片段
        assertFalse(encoded.contains("secret"))
        assertEquals("""[{"token":"secret"}]""", CredentialCodec.decode(cipher, encoded))
    }

    @Test
    fun legacyPlaintextIsReadable() {
        val cipher = FakeCipher()
        // 老版本存的是明文 JSON，升级后第一次启动必须还能读出节点列表。
        assertEquals("[{\"id\":\"n1\"}]", CredentialCodec.decode(cipher, "[{\"id\":\"n1\"}]"))
    }

    @Test
    fun missingValueDecodesToNull() {
        assertNull(CredentialCodec.decode(FakeCipher(), null))
    }

    @Test
    fun undecryptableValueReturnsNullInsteadOfThrowing() {
        // Keystore 密钥被清除/数据被篡改：只能当“没有凭据”，让用户重新配对，
        // 绝不能抛异常把应用启动打断。
        val stored = CredentialCodec.encode(FakeCipher(), "secret")
        assertNull(CredentialCodec.decode(FakeCipher(broken = true), stored))
    }

    @Test
    fun detectsUnencryptedStorage() {
        assertFalse(CredentialCodec.isEncrypted("plain"))
        assertTrue(CredentialCodec.isEncrypted(CredentialCodec.PREFIX + "abc"))
    }
}
