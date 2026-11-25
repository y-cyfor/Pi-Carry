package dev.picarry.app

import androidx.compose.ui.graphics.Color
import dev.picarry.app.ui.theme.CarryPalette
import dev.picarry.app.ui.theme.DarkPalette
import dev.picarry.app.ui.theme.LightPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题不是只换色：正文/说明文字在两种底色上都必须可读。
 * 这里用 WCAG 相对亮度计算关键组合的对比度，防止后续调色把浅色模式调成“灰字白底”。
 */
class ThemePaletteTest {
    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val x = luminance(a)
        val y = luminance(b)
        return if (x > y) (x + 0.05) / (y + 0.05) else (y + 0.05) / (x + 0.05)
    }

    private fun assertReadable(palette: CarryPalette, name: String) {
        assertTrue("$name 主文字对比度不足", contrast(palette.textPrimary, palette.ink) >= 7.0)
        assertTrue("$name 次文字对比度不足", contrast(palette.textSecondary, palette.panel) >= 4.5)
        // B.1.1 把深色 text-3/muted 定为设计稿的 #6B6B82（在 #090910 上实测约 3.8:1），
        // 它只用于 mono 辅助标签/元信息；按 AX-05 的「辅助文字与图标 ≥3:1」守卫。
        // 正文级弱文字仍由 textSecondary 的 4.5:1 断言覆盖。
        assertTrue("$name 弱文字对比度不足", contrast(palette.textMuted, palette.panel) >= 3.0)
        // B.1.2 浅色 violet/rose/mint 是填充/描边 token，不能直接当 11–13.5sp 正文色；
        // 正文可用性由 *_Text 变体守卫（阈值仍是 4.5，不是放宽）。
        assertTrue("$name 紫色强调对比度不足", contrast(palette.violetText, palette.ink) >= 4.5)
        assertTrue("$name 绿色状态对比度不足", contrast(palette.mintText, palette.ink) >= 4.5)
        assertTrue("$name 玫瑰错误对比度不足", contrast(palette.roseText, palette.ink) >= 4.5)
    }

    @Test
    fun darkPaletteMeetsContrastTargets() = assertReadable(DarkPalette, "深色")

    @Test
    fun lightPaletteMeetsContrastTargets() = assertReadable(LightPalette, "浅色")
}
