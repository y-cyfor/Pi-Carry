package dev.picarry.app.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
}
