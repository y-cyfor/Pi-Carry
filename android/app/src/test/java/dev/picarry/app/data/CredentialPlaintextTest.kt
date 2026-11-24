package dev.picarry.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 凭据未加密时上层必须能感知到（设置页据此显示警示）。
 *
 * 旧实现有两个问题：字段缺失也被算成「明文」（还没存过凭据就报警），
 * 且全仓没有调用者，Keystore 不可用时用户永远看不到这件事。
 */
class CredentialPlaintextTest {
    /** 只做 Base64 的兜底 cipher：模拟 Keystore 不可用。 */
    private object PlainFallback : SecretCipher {
        override fun encrypt(plain: String): String = plain
        override fun decrypt(stored: String): String? = stored
    }

    @Test
    fun nothingStoredIsNotReportedAsPlaintext() {
        // 还没存过凭据时没有可泄露的内容，不该打扰用户。
        assertFalse(credentialsArePlaintext(PlainFallback, null, null))
        assertFalse(credentialsArePlaintext(PlainFallback, "", null))
    }

    @Test
    fun legacyPlaintextNodesAreReported() {
        // 历史版本直接写 JSON（没有 enc:v1: 前缀）。
        assertTrue(credentialsArePlaintext(PlainFallback, "[{\"id\":\"n1\"}]", null))
    }

    @Test
    fun encryptedNodesAreNotReported() {
        assertFalse(credentialsArePlaintext(PlainFallback, CredentialCodec.PREFIX + "Zm9vOmJhcg==", null))
    }

    @Test
    fun plaintextClientSecretIsReported() {
        assertTrue(
            credentialsArePlaintext(
                PlainFallback,
                CredentialCodec.PREFIX + "Zm9vOmJhcg==",
                "c2VjcmV0",
            ),
        )
    }

    @Test
    fun fallbackCipherIsAlwaysReported() {
        // Keystore 不可用时落盘数据带着 enc:v1: 前缀，但里面只是 Base64：光看前缀会漏报。
        assertTrue(credentialsArePlaintext(FallbackCipher, CredentialCodec.PREFIX + "aXY6Ym9keQ==", null))
    }

    @Test
    fun keystoreCipherWithPrefixedDataIsNotReported() {
        // 真正的 Keystore cipher 走同一判定：前缀齐全就认为受保护。
        assertFalse(
            credentialsArePlaintext(
                object : SecretCipher {
                    override fun encrypt(plain: String): String = "x"
                    override fun decrypt(stored: String): String? = null
                },
                CredentialCodec.PREFIX + "aXY6Ym9keQ==",
                CredentialCodec.PREFIX + "aXY6Ym9keQ==",
            ),
        )
    }
}
