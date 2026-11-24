package dev.picarry.app.data

/**
 * 一个供应商下的模型页。
 *
 * 页面顺序固定为 `models.list` 里首次出现的顺序：如果按当前选择重排，
 * 每切一次模型整页都会跳一下，用户下一次就找不到刚才点的那一项。
 */
data class ProviderGroup(
    val provider: String,
    val label: String,
    val models: List<ModelOption>,
)

/** 按 provider 分组，保留首次出现顺序（LinkedHashMap 的插入序）。 */
fun groupModelsByProvider(models: List<ModelOption>): List<ProviderGroup> {
    val byProvider = LinkedHashMap<String, MutableList<ModelOption>>()
    models.forEach { model ->
        byProvider.getOrPut(model.provider) { mutableListOf() }.add(model)
    }
    return byProvider.map { (provider, list) ->
        ProviderGroup(provider = provider, label = list.first().providerLabel, models = list)
    }
}

/**
 * 默认选择规则：该 provider 本地历史里上次成功使用的模型若仍在列表里就用它，
 * 否则取列表第一项。列表为空时返回 null（调用方保持原待选）。
 */
fun defaultModelFor(group: ProviderGroup, prefs: ModelPrefs): ModelOption? {
    val history = prefs.modelFor(group.provider)
    return group.models.firstOrNull { it.id == history } ?: group.models.firstOrNull()
}

/**
 * 当前模型不在 `models.list` 里时，为它补一个「当前（不在列表）」伪条目。
 *
 * 不补的话用户看不到打勾项，也说不清自己现在用的是哪个模型；这样也能让
 * 它在自己的 provider 页里单独建页，保证勾选可见。
 */
fun withCurrentModel(
    groups: List<ProviderGroup>,
    provider: String,
    modelId: String,
    name: String,
): List<ProviderGroup> {
    if (provider.isBlank() || modelId.isBlank()) return groups
    if (groups.any { it.provider == provider && it.models.any { m -> m.id == modelId } }) return groups
    // unavailable 让 UI 在行内标出「当前（不在列表）」：光多一行正常的模型名，用户
    // 看不出这个模型其实已经下线（名字往往回退成裸 id）。
    val pseudo = ModelOption(
        provider = provider,
        id = modelId,
        name = name.ifBlank { modelId },
        unavailable = true,
    )
    val index = groups.indexOfFirst { it.provider == provider }
    return if (index < 0) {
        groups + ProviderGroup(provider, pseudo.providerLabel, listOf(pseudo))
    } else {
        groups.mapIndexed { i, g ->
            if (i == index) g.copy(models = listOf(pseudo) + g.models) else g
        }
    }
}

/** 顶部胶囊文案：`provider/name`，过长截断（胶囊本身只有一行）。 */
fun modelPillText(provider: String, name: String, max: Int = 24): String {
    val full = if (provider.isBlank()) name else "$provider/$name"
    return if (full.length <= max) full else full.take(max - 1) + "…"
}
