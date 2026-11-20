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
        assertTrue("$name 弱文字对比度不足", contrast(palette.textMuted, palette.panel) >= 4.5)
        assertTrue("$name 紫色强调对比度不足", contrast(palette.violet, palette.ink) >= 4.5)
        assertTrue("$name 绿色状态对比度不足", contrast(palette.mint, palette.ink) >= 4.5)
        assertTrue("$name 玫瑰错误对比度不足", contrast(palette.rose, palette.ink) >= 4.5)
    }

    @Test
    fun darkPaletteMeetsContrastTargets() = assertReadable(DarkPalette, "深色")

    @Test
    fun lightPaletteMeetsContrastTargets() = assertReadable(LightPalette, "浅色")
}
