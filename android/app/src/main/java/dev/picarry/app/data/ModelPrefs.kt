package dev.picarry.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 每个节点记住的「上次成功使用」的模型与思考等级。
 *
 * 只在 `model.set` / `thinking.set` 真正成功后才写入；打开选择器、滑动浏览都不写。
 * 思考记录用嵌套对象（provider → modelId → level），不用拼接字符串，避免 id 里
 * 出现分隔符时解析歧义。
 */
@Serializable
data class ModelPrefs(
    val lastModel: Map<String, String> = emptyMap(),
    val lastThinking: Map<String, Map<String, String>> = emptyMap(),
) {
    fun modelFor(provider: String): String? = lastModel[provider]

    fun thinkingFor(provider: String, modelId: String): String? = lastThinking[provider]?.get(modelId)

    fun withModel(provider: String, modelId: String): ModelPrefs =
        copy(lastModel = lastModel + (provider to modelId))

    fun withThinking(provider: String, modelId: String, level: String): ModelPrefs {
        val levels = (lastThinking[provider] ?: emptyMap()) + (modelId to level)
        return copy(lastThinking = lastThinking + (provider to levels))
    }

    /** 丢弃不在当前列表里的模型记录，避免偏好文件随模型更替无限增长。 */
    fun prune(available: Map<String, Set<String>>): ModelPrefs {
        val models = lastModel.filter { (provider, id) -> available[provider]?.contains(id) == true }
        val thinking = lastThinking.mapNotNull { (provider, byModel) ->
            val allowed = available[provider] ?: return@mapNotNull null
            val kept = byModel.filterKeys { it in allowed }
            if (kept.isEmpty()) null else provider to kept
        }.toMap()
        return ModelPrefs(models, thinking)
    }
}

/** JSON 编解码独立成对象，便于在纯 JVM 单测里验证格式（不依赖 Android）。 */
object ModelPrefsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun decode(raw: String?): ModelPrefs {
        if (raw.isNullOrBlank()) return ModelPrefs()
        return runCatching { json.decodeFromString<ModelPrefs>(raw) }.getOrElse { ModelPrefs() }
    }

    fun encode(prefs: ModelPrefs): String = json.encodeToString(prefs)
}

/**
 * 思考等级的强弱顺序，用于「没有任何历史时取最高可用档」。
 *
 * 列表里出现未知等级（pi 新增的档位）时排到最后：它比已知最高档只会更强，
 * 取最高可用档的口径下把它当成更强是合理的。
 */
val THINKING_ORDER: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** 按强弱取最高可用档；列表为空时返回 null（调用方保持当前值不变）。 */
fun highestLevel(levels: List<String>): String? =
    levels.maxByOrNull { level ->
        val idx = THINKING_ORDER.indexOf(level)
        if (idx >= 0) idx else THINKING_ORDER.size
    }

/**
 * 按需求里的「默认选择规则」推导某个 provider/模型下应该高亮的思考等级。
 *
 * 优先级：该模型的历史等级（仅在 levels 内才有效）→ 当前等级（同样需在 levels 内）
 * → 最高可用档。传 null 表示当前没有可用等级。
 */
fun defaultThinkingLevel(
    levels: List<String>,
    historyLevel: String?,
    currentLevel: String?,
): String? {
    if (levels.isEmpty()) return null
    if (historyLevel != null && historyLevel in levels) return historyLevel
    if (currentLevel != null && currentLevel in levels) return currentLevel
    return highestLevel(levels)
}

/** 思考等级的中文名；未知等级直接回显原始 id，不伪造翻译。 */
fun thinkingLabel(level: String): String = when (level) {
    "off" -> "关闭"
    "minimal" -> "极简"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最高"
    else -> level
}
