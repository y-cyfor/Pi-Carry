package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单页选择器的分组与默认选择规则；纯 JVM 逻辑，不依赖 Compose。 */
class ModelGroupsTest {

    private fun model(provider: String, id: String, name: String = id) =
        ModelOption(provider = provider, id = id, name = name)

    @Test
    fun groupsKeepFirstSeenOrder() {
        val groups = groupModelsByProvider(
            listOf(
                model("openai", "gpt-5.1"),
                model("anthropic", "claude-sonnet-4-5"),
                model("openai", "gpt-5.1-mini"),
            ),
        )
        assertEquals(listOf("openai", "anthropic"), groups.map { it.provider })
        assertEquals(2, groups.first().models.size)
        assertEquals("OpenAI", groups.first().label)
    }

    @Test
    fun defaultModelPrefersHistoryThenFirst() {
        val group = groupModelsByProvider(
            listOf(model("openai", "a"), model("openai", "b"), model("openai", "c")),
        ).single()
        assertEquals("a", defaultModelFor(group, ModelPrefs())?.id)
        val prefs = ModelPrefs().withModel("openai", "b")
        assertEquals("b", defaultModelFor(group, prefs)?.id)
        // 历史里的模型已下架：回退到第一项，不能返回 null。
        val stale = ModelPrefs().withModel("openai", "gone")
        assertEquals("a", defaultModelFor(group, stale)?.id)
    }

    @Test
    fun defaultModelOfEmptyGroupIsNull() {
        assertNull(defaultModelFor(ProviderGroup("openai", "OpenAI", emptyList()), ModelPrefs()))
    }

    @Test
    fun currentModelOutsideListBecomesFirstEntry() {
        val groups = groupModelsByProvider(listOf(model("openai", "a"), model("google", "g")))
        val withPseudo = withCurrentModel(groups, "openai", "legacy-1", "legacy-1")
        val openai = withPseudo.first { it.provider == "openai" }
        assertEquals(listOf("legacy-1", "a"), openai.models.map { it.id })
        // 伪条目必须自带「不在列表」标记，行内才能标出来；真实条目不能误标。
        assertTrue(openai.models.first().unavailable)
        assertFalse(openai.models[1].unavailable)
    }

    @Test
    fun `两个 provider 有同名 id 时仍按 provider 区分`() {
        val groups = groupModelsByProvider(
            listOf(
                model("anthropic", "same", "A"),
                model("anthropic", "sonnet"),
                model("openai", "same", "B"),
                model("openai", "gpt"),
            ),
        )
        val anthropic = groups.first { it.provider == "anthropic" }
        val openai = groups.first { it.provider == "openai" }
        // 偏好按 provider 存：把 openai 选成 gpt 后，anthropic 那页同名模型不被顶掉，仍取第一项。
        val prefs = ModelPrefs().withModel("openai", "gpt")
        assertEquals("gpt", defaultModelFor(openai, prefs)?.id)
        assertEquals("same", defaultModelFor(anthropic, prefs)?.id)
        assertEquals("anthropic|same", defaultModelFor(anthropic, prefs)?.key)
        // 当前模型只在别的 provider 下同名：不能当成「已在列表」，必须给 google 单独建页。
        val withPseudo = withCurrentModel(groups, "google", "same", "same")
        assertEquals(listOf("anthropic", "openai", "google"), withPseudo.map { it.provider })
        assertEquals(2, withPseudo.first { it.provider == "openai" }.models.size)
        assertTrue(withPseudo.last().models.single().unavailable)
    }

    @Test
    fun currentModelOfUnknownProviderGetsOwnPage() {
        val groups = groupModelsByProvider(listOf(model("openai", "a")))
        val withPseudo = withCurrentModel(groups, "custom-node", "m1", "本地模型")
        assertEquals(listOf("openai", "custom-node"), withPseudo.map { it.provider })
        assertEquals("本地模型", withPseudo.last().models.single().name)
        assertTrue(withPseudo.last().models.single().unavailable)
    }

    @Test
    fun existingCurrentModelIsNotDuplicated() {
        val groups = groupModelsByProvider(listOf(model("openai", "a")))
        assertEquals(groups, withCurrentModel(groups, "openai", "a", "a"))
    }

    @Test
    fun blankCurrentModelKeepsGroups() {
        val groups = groupModelsByProvider(listOf(model("openai", "a")))
        assertEquals(groups, withCurrentModel(groups, "", "", ""))
    }

    @Test
    fun pillTextJoinsProviderAndTruncates() {
        assertEquals("anthropic/claude-sonnet-4-5", modelPillText("anthropic", "claude-sonnet-4-5", 40))
        assertEquals("默认", modelPillText("", "默认"))
        val long = modelPillText("openrouter", "a-very-long-model-identifier", 12)
        assertEquals(12, long.length)
        assertEquals("…", long.takeLast(1))
    }
}
