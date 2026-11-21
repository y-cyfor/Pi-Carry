package dev.picarry.app.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** 轻量持久化：节点凭据只留在本机。 */
class NodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadNodes(): List<Node> = runCatching {
        prefs.getString("nodes", null)?.let { json.decodeFromString<List<Node>>(it) } ?: emptyList()
    }.getOrDefault(emptyList())

    fun saveNodes(nodes: List<Node>) {
        prefs.edit().putString("nodes", json.encodeToString(nodes)).apply()
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
        val saved = prefs.getString("client_secret", null)
        if (saved != null) {
            runCatching { Base64.decode(saved, Base64.NO_WRAP) }
                .getOrNull()
                ?.takeIf { it.size == 32 }
                ?.let { return it }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("client_secret", Base64.encodeToString(fresh, Base64.NO_WRAP))
            .commit()
        return fresh
    }
}
