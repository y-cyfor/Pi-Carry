package dev.picarry.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 1 的默认选择规则与持久化格式（纯 JVM，不依赖 Android）。 */
class ModelPrefsTest {

    @Test
    fun `偏好按 provider 与模型嵌套记录`() {
        val prefs = ModelPrefs()
            .withModel("anthropic", "claude-sonnet-4-5")
            .withThinking("anthropic", "claude-sonnet-4-5", "high")
            .withThinking("anthropic", "claude-opus-4-1", "max")

        assertEquals("claude-sonnet-4-5", prefs.modelFor("anthropic"))
        assertEquals("high", prefs.thinkingFor("anthropic", "claude-sonnet-4-5"))
        assertEquals("max", prefs.thinkingFor("anthropic", "claude-opus-4-1"))
        assertNull(prefs.modelFor("openai"))
        assertNull(prefs.thinkingFor("anthropic", "unknown"))
    }

    @Test
    fun `编码解码往返保留嵌套结构`() {
        val prefs = ModelPrefs()
            .withModel("openai", "gpt-5.1")
            .withThinking("openai", "gpt-5.1", "low")
        val round = ModelPrefsCodec.decode(ModelPrefsCodec.encode(prefs))
        assertEquals(prefs, round)
    }

    @Test
    fun `损坏的 JSON 退化为空偏好而不是抛错`() {
        assertEquals(ModelPrefs(), ModelPrefsCodec.decode("{not json"))
        assertEquals(ModelPrefs(), ModelPrefsCodec.decode(null))
        assertEquals(ModelPrefs(), ModelPrefsCodec.decode(""))
    }

    @Test
    fun `prune 丢掉已不在列表里的模型记录`() {
        val prefs = ModelPrefs()
            .withModel("anthropic", "gone")
            .withModel("openai", "kept")
            .withThinking("anthropic", "gone", "high")
            .withThinking("openai", "kept", "low")
        val pruned = prefs.prune(mapOf("openai" to setOf("kept")))
        assertNull(pruned.modelFor("anthropic"))
        assertEquals("kept", pruned.modelFor("openai"))
        assertTrue(pruned.lastThinking["anthropic"] == null)
        assertEquals("low", pruned.thinkingFor("openai", "kept"))
    }

    @Test
    fun `历史等级优先于当前等级`() {
        val levels = listOf("off", "low", "high")
        assertEquals("high", defaultThinkingLevel(levels, "high", "low"))
        // 历史等级不在 levels 里时退回当前等级。
        assertEquals("low", defaultThinkingLevel(levels, "max", "low"))
        // 两者都不可用时取最高可用档。
        assertEquals("high", defaultThinkingLevel(levels, "max", "xhigh"))
        // 空列表无法推导，调用方保持原值。
        assertNull(defaultThinkingLevel(emptyList(), "high", "low"))
    }

    @Test
    fun `未知等级按更强处理并排在已知最高档之后`() {
        assertEquals("max", highestLevel(listOf("off", "max", "low")))
        // 未知档位大于所有已知档位。
        assertEquals("ultra", highestLevel(listOf("off", "max", "ultra")))
        assertNull(highestLevel(emptyList()))
    }

    @Test
    fun `等级与品牌文案`() {
        assertEquals("关闭", thinkingLabel("off"))
        assertEquals("最高", thinkingLabel("max"))
        assertEquals("weird", thinkingLabel("weird"))
        assertEquals("Anthropic", providerLabelFor("anthropic"))
        assertEquals("xAI", providerLabelFor("x-ai"))
        assertEquals("自定义", providerLabelFor("custom"))
        assertEquals("my-provider", providerLabelFor("my-provider"))
    }

    @Test
    fun `模型身份用复合键且价格格式化正确`() {
        val a = ModelOption("anthropic", "same", "A")
        val b = ModelOption("openai", "same", "B")
        assertTrue(a.key != b.key)

        assertEquals("$3 / $15", ModelOption("p", "m", "n", inputPrice = 3.0, outputPrice = 15.0).priceText)
        assertEquals("$0.15 / $0.6", ModelOption("p", "m", "n", inputPrice = 0.15, outputPrice = 0.6).priceText)
        assertEquals("$2 in", ModelOption("p", "m", "n", inputPrice = 2.0).priceText)
        assertEquals("", ModelOption("p", "m", "n").priceText)
    }
}
