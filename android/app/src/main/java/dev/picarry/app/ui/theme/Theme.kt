package dev.picarry.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * pi carry 的双主题色板。
 *
 * 深色是产品原型的主视觉；浅色不是简单反色，而是把同一套“深空 + 电紫”语言
 * 翻译到纸白底上：保留紫/青/玫瑰作为强调色，但压暗亮度以保证白底可读性。
 */
@Immutable
data class CarryPalette(
    val ink: Color,
    val inkElevated: Color,
    val panel: Color,
    val panelHigh: Color,
    val line: Color,
    val glow: Color,
    val violet: Color,
    val violetSoft: Color,
    val cyan: Color,
    val rose: Color,
    val amber: Color,
    val mint: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

internal val DarkPalette = CarryPalette(
    ink = Color(0xFF05050A),
    inkElevated = Color(0xFF0B0B14),
    panel = Color(0xFF11111D),
    panelHigh = Color(0xFF171728),
    line = Color(0xFF262638),
    glow = Color(0xFF151230),
    violet = Color(0xFF7C6BFF),
    violetSoft = Color(0xFFB9AEFF),
    cyan = Color(0xFF45D9D0),
    rose = Color(0xFFFF6B85),
    amber = Color(0xFFFFB86B),
    mint = Color(0xFF5BE7A9),
    textPrimary = Color(0xFFF2F1FA),
    textSecondary = Color(0xFF9B99AD),
    textMuted = Color(0xFF858296),
)

internal val LightPalette = CarryPalette(
    ink = Color(0xFFF6F5FB),
    inkElevated = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFF0EEF8),
    line = Color(0xFFDCD9E8),
    glow = Color(0xFFE8E2FF),
    violet = Color(0xFF5B4BE8),
    violetSoft = Color(0xFF3B2DB5),
    cyan = Color(0xFF0B7F79),
    rose = Color(0xFFC73552),
    amber = Color(0xFF9A5B00),
    mint = Color(0xFF0A7B52),
    textPrimary = Color(0xFF1B1826),
    textSecondary = Color(0xFF5E5A70),
    textMuted = Color(0xFF6F6B80),
)

private val LocalCarryPalette = staticCompositionLocalOf { DarkPalette }

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2452),
    onPrimaryContainer = DarkPalette.violetSoft,
    secondary = DarkPalette.cyan,
    onSecondary = DarkPalette.ink,
    tertiary = DarkPalette.rose,
    background = DarkPalette.ink,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.panel,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.panelHigh,
    onSurfaceVariant = DarkPalette.textSecondary,
    outline = DarkPalette.line,
    error = DarkPalette.rose,
    surfaceDim = Color(0xFF0B0B14),
    surfaceBright = Color(0xFF24243A),
    surfaceContainerLowest = Color(0xFF05050A),
    surfaceContainerLow = DarkPalette.panel,
    surfaceContainer = Color(0xFF171728),
    surfaceContainerHigh = Color(0xFF1D1D30),
    surfaceContainerHighest = Color(0xFF24243A),
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF241B6B),
    secondary = LightPalette.cyan,
    onSecondary = Color.White,
    tertiary = LightPalette.rose,
    background = LightPalette.ink,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.panel,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.panelHigh,
    onSurfaceVariant = LightPalette.textSecondary,
    outline = LightPalette.line,
    error = LightPalette.rose,
    surfaceDim = Color(0xFFE4E1EE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF6F5FB),
    surfaceContainerHigh = LightPalette.panelHigh,
    surfaceContainerHighest = Color(0xFFEAE7F3),
)

/** 颜色使用 CompositionLocal，主题切换时所有页面自动重组，不需要重建 Activity。 */
val Ink: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.ink

val InkElevated: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.inkElevated

val Panel: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.panel

val PanelHigh: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.panelHigh

val Line: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.line

val Violet: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violet

val VioletSoft: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violetSoft

val Cyan: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.cyan

val Rose: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.rose

val Amber: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.amber

val Mint: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.mint

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textPrimary

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textSecondary

val TextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textMuted

val CarryGlow: Color
    @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.glow

val CarryTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 0.8.sp),
)

@Composable
fun PiCarryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val activity = view.context.findActivity()

    // 状态栏/导航栏图标跟随主题：深色底用浅图标，浅色底用深图标。
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }

    CompositionLocalProvider(LocalCarryPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = CarryTypography,
            content = content,
        )
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
