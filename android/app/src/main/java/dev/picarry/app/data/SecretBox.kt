package dev.picarry.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据加解密：抽成接口，让落盘编码逻辑能在 JVM 单测里跑（Keystore 只有真机有）。 */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** 解密失败返回 null：密钥被清除、数据被篡改或换了设备，都只能让用户重新配对。 */
    fun decrypt(stored: String): String?
}

/**
 * 落盘凭据的编码。
 *
 * 存的是 `enc:v1:` + base64，读的时候兼容历史明文（没有前缀）并在下次保存时迁移。
 * 前缀带版本号是为了将来换算法时不至于把旧数据解成乱码。
 */
internal object CredentialCodec {
    const val PREFIX = "enc:v1:"

    fun encode(cipher: SecretCipher, plain: String): String =
        PREFIX + cipher.encrypt(plain)

    fun decode(cipher: SecretCipher, stored: String?): String? {
        if (stored == null) return null
        if (!stored.startsWith(PREFIX)) return stored // 历史明文：直接返回，保存时会重新加密
        return cipher.decrypt(stored.removePrefix(PREFIX))
    }

    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true
}

/**
 * Android Keystore 里的 AES-256-GCM 密钥。
 *
 * 为什么不用 EncryptedSharedPreferences：那套库已进入维护模式且有已知的
 * 并发初始化崩溃；这里只需要两个字段，直接用 Keystore + GCM 更可控。
 * 密钥不可导出（`setUserAuthenticationRequired(false)`，因为重连发生在后台），
 * 即使 prefs 被备份/读取，脱离本机 Keystore 也无法解密。
 */
class KeystoreCipher : SecretCipher {
    // 不能用 `by lazy`：lazy 让构造永远不会失败，于是 NodeStore 里
    // `runCatching { KeystoreCipher() }.getOrElse { FallbackCipher }` 是死代码，
    // Keystore 坏掉时错误被推迟到首次加解密，降级路径永远走不到（N-01）。
    // 改成构造期立即加载：失败直接抛，让上层真的落到 FallbackCipher。
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        // 构造期自检：密钥不可导出，所以用「取到密钥并初始化一次 GCM cipher」来探测。
        // 密钥被系统清掉/损坏时这里的 init 会抛（InvalidKeyException 等），
        // 从而让 NodeStore 的降级分支生效，而不是等到用户认为已加密时才发现。
        Cipher.getInstance(TRANSFORMATION).init(Cipher.ENCRYPT_MODE, key())
    }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 后台重连必须能解密：不要求用户认证，否则锁屏后连接静默失败。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // 明文存放 IV：GCM 的 IV 不是秘密，但必须逐条唯一（每次 encrypt 由系统生成随机 IV）。
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    override fun decrypt(stored: String): String? = runCatching {
        val (ivPart, bodyPart) = stored.split(":", limit = 2).takeIf { it.size == 2 } ?: return null
        val iv = Base64.decode(ivPart, Base64.NO_WRAP)
        val body = Base64.decode(bodyPart, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(body).toString(Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pi-carry-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
