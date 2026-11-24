package dev.picarry.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/**
 * 轻量持久化：节点凭据只留在本机。
 *
 * 节点列表里带配对 token / 一次性码 / endpoint secret，属于可直接通过认证的凭据，
 * 不能以明文躺在 shared_prefs 里（root、备份、adb backup 都能读走）。这里统一用
 * [KeystoreCipher] 加密后落盘，并在读取时兼容旧版明文数据、下次保存时自动迁移。
 */
class NodeStore(
    context: Context,
    /**
     * 仅供单测注入：Keystore 在 JVM 上没有真实实现。真实路径固定为
     * `KeystoreCipher()`（可失败）或 [FallbackCipher]，不接受外部选择。
     */
    cipherOverride: SecretCipher? = null,
) {
    private val prefs = context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cipher: SecretCipher = cipherOverride ?: runCatching { KeystoreCipher() }
        .getOrElse {
            // Keystore 不可用（极老的 ROM / 被系统禁用）时不能让应用直接崩，
            // 但必须让上层知道凭据是明文：这里保留可用性，错误由 doctor 页面暴露。
            FallbackCipher
        }

    /**
     * 密文还在盘上、但当前解不开（Keystore 密钥被系统清掉 / 跨设备恢复备份）。
     *
     * 这是「节点暂时看不见」而不是「节点不存在」：密钥恢复后同一段密文还能读回来，
     * 所以既不能把它当空列表处理，也不能让后续保存把这段可救的密文覆盖掉。
     */
    @Volatile
    private var credentialsLost = false

    /** 凭据是否解不开（doctor/设置页据此提示「重新配对」而不是默默显示空列表）。 */
    fun credentialsLost(): Boolean = credentialsLost

    fun loadNodes(): List<Node> = runCatching {
        val raw = prefs.getString(KEY_NODES, null) ?: return emptyList()
        // decode 只在「带 enc:v1: 前缀但解不开」时返回 null（明文历史数据会原样返回），
        // 所以这里可以断定：有密文却解不开，不能用「空列表」冒充「没有节点」（N-01）。
        // 只置标记、不动 prefs——密文仍是唯一的数据副本。
        val plain = CredentialCodec.decode(cipher, raw)
        if (plain == null) {
            credentialsLost = true
            return emptyList()
        }
        json.decodeFromString<List<Node>>(plain)
    }.getOrDefault(emptyList())

    fun saveNodes(nodes: List<Node>) {
        // 旧密文解不开时，空列表保存会把它彻底覆盖：用户重新配对或密钥恢复后
        // 就再也找不回原节点。宁可不写盘，等真实数据回来（N-01）。
        if (credentialsLost && nodes.isEmpty()) {
            Log.w("NodeStore", "凭据解不开，跳过空表覆盖：旧密文仍在，等重新配对或密钥恢复")
            return
        }
        // 非空写入（用户重新配对）同样会覆盖解不开的旧密文。先留一份原样备份，
        // 密钥恢复后还能靠它找回其余节点，而不是「重配一台就永久丢掉别的」（R-01）。
        if (credentialsLost) backupUnreadableNodes()
        val plain = json.encodeToString(nodes)
        val stored = runCatching { CredentialCodec.encode(cipher, plain) }
            .getOrElse { plain }
        prefs.edit().putString(KEY_NODES, stored).apply()
        // 已经写入了有效数据：丢失标记必须清掉，否则下一次空表保存又会被跳过。
        credentialsLost = false
    }

    /** 把当前解不开的密文另存一份：只在第一次写入时做，后续正常数据不会顶掉这份旧副本。 */
    private fun backupUnreadableNodes() {
        if (prefs.getString(KEY_NODES_BACKUP, null) != null) return
        val raw = prefs.getString(KEY_NODES, null) ?: return
        prefs.edit().putString(KEY_NODES_BACKUP, raw).apply()
        Log.w("NodeStore", "凭据解不开：旧密文已备份到 $KEY_NODES_BACKUP")
    }

    fun loadLastNode(): String? = prefs.getString("last_node", null)
    fun saveLastNode(id: String) = prefs.edit().putString("last_node", id).apply()

    fun loadBool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun saveBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun loadString(key: String, default: String): String = prefs.getString(key, default) ?: default
    fun saveString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    /**
     * 稳定的 iroh 客户端身份。每次连接都生成新 EndpointId 会让电脑把同一台手机识别成
     * 多台新设备，最终触发设备数上限并导致再也连不上。
     */
    @Synchronized
    fun clientSecret(): ByteArray {
        val saved = prefs.getString(KEY_CLIENT_SECRET, null)
        if (saved != null) {
            secretFrom(KEY_CLIENT_SECRET)?.let { return it }
            // 原密文解不开（Keystore 密钥被系统清掉）：既不能覆盖它（密钥恢复后身份还能读回），
            // 也不能每次启动都换一个新身份（服务端会当成新设备，累计触发设备数上限）。
            // 所以把恢复出来的身份存在旁路键上，之后一直复用它（R-01）。
            return secretFrom(KEY_CLIENT_SECRET_FALLBACK) ?: rememberSecret(KEY_CLIENT_SECRET_FALLBACK)
        }
        // 从没存过身份：直接用主键。
        return secretFrom(KEY_CLIENT_SECRET) ?: rememberSecret(KEY_CLIENT_SECRET)
    }

    /** 读取并校验指定键上的身份；解不开或长度不对都算「没有」。 */
    private fun secretFrom(key: String): ByteArray? = prefs.getString(key, null)
        ?.let { CredentialCodec.decode(cipher, it) }
        ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        ?.takeIf { it.size == 32 }

    private fun rememberSecret(key: String): ByteArray {
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // 用 apply 而不是 commit：这是应用主路径（连接时同步调用），
        // commit 会在主线程阻塞 fsync。身份已在上面的内存路径返回，
        // 即使落盘失败下次重试也会重新生成一个新身份，不影响本次连接。
        prefs.edit()
            .putString(key, encodeSecret(Base64.encodeToString(fresh, Base64.NO_WRAP)))
            .apply()
        return fresh
    }

    /**
     * 凭据是否实际未受 Keystore 保护（设置页据此给出警示）。
     *
     * 两种情形都算：
     * - `cipher` 落在 [FallbackCipher]：它只做 Base64，但 `CredentialCodec.encode` 仍会加
     *   `enc:v1:` 前缀，光看前缀看不出来，所以这里必验 cipher 本身；
     * - 落盘数据没有 `enc:v1:` 前缀：历史明文，或加密抛错后直接写了明文。
     * 字段缺失不算风险：还没存过任何凭据时没有可泄露的内容。
     */
    fun credentialsArePlaintext(): Boolean = credentialsArePlaintext(
        cipher = cipher,
        storedNodes = prefs.getString(KEY_NODES, null),
        storedSecret = prefs.getString(KEY_CLIENT_SECRET, null),
    )

    private fun encodeSecret(base64: String): String =
        runCatching { CredentialCodec.encode(cipher, base64) }.getOrElse { base64 }

    private companion object {
        const val KEY_NODES = "nodes"
        const val KEY_NODES_BACKUP = "nodes_unreadable_backup"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_CLIENT_SECRET_FALLBACK = "client_secret_recovered"
    }
}

/** Keystore 完全不可用时的最后兜底：能连上比连不上重要，但凭据不再受 Keystore 保护。 */
internal object FallbackCipher : SecretCipher {
    override fun encrypt(plain: String): String = Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)

    override fun decrypt(stored: String): String? =
        runCatching { String(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull()
}

/**
 * 凭据明文判定（抽成纯函数以便 JVM 单测：Keystore 与 prefs 在单测里都不可靠）。
 *
 * [cipher] 是兜底 cipher 时，即使落盘数据带 `enc:v1:` 前缀也仍是 Base64 明文；
 * `enc:v1:` 前缀缺失则是历史明文或加密失败后直接写入。
 * 没存过凭据时没有可泄露的内容，不报风险（否则新装用户一启动就被警示打扰）。
 */
internal fun credentialsArePlaintext(
    cipher: SecretCipher,
    storedNodes: String?,
    storedSecret: String?,
): Boolean {
    val fallback = cipher === FallbackCipher
    fun unsafe(stored: String?): Boolean =
        stored?.isNotEmpty() == true && (fallback || !CredentialCodec.isEncrypted(stored))
    return unsafe(storedNodes) || unsafe(storedSecret)
}
