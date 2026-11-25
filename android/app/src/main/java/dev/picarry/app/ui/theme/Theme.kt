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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.picarry.app.R

/**
 * 与 `design/mobile-ui/0.2.0-design.html` 一致的双主题色板。
 * 深色是主视觉；浅色把同一套紫/青/玫瑰语言压暗到纸白底上。
 */
@Immutable
data class CarryPalette(
    val ink: Color,
    val inkElevated: Color,
    val panel: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val lineStrong: Color,
    val glow: Color,
    val violet: Color,
    val violetSoft: Color,
    val accentSoft: Color,
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
    inkElevated = Color(0xFF090910),
    panel = Color(0xFF090910),
    surface = Color(0xFF101019),
    surface2 = Color(0xFF15151F),
    surface3 = Color(0xFF1B1B27),
    line = Color(0x13FFFFFF),
    lineStrong = Color(0x24FFFFFF),
    glow = Color(0xFF191632),
    violet = Color(0xFF7C6BFF),
    violetSoft = Color(0xFFA79BFF),
    accentSoft = Color(0xFFC3BAFF),
    cyan = Color(0xFF45D9D0),
    rose = Color(0xFFFF6B85),
    amber = Color(0xFFFFB86B),
    mint = Color(0xFF5BE7A9),
    textPrimary = Color(0xFFF2F2F7),
    textSecondary = Color(0xFFA6A6BC),
    textMuted = Color(0xFF77778F),
)

internal val LightPalette = CarryPalette(
    ink = Color(0xFFF1F1F4),
    inkElevated = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F4F7),
    surface3 = Color(0xFFECECF2),
    line = Color(0x1A1A1A2E),
    lineStrong = Color(0x291A1A2E),
    glow = Color(0xFFF7DDE3),
    violet = Color(0xFFC82444),
    violetSoft = Color(0xFFB01F3C),
    accentSoft = Color(0xFF9E1D37),
    cyan = Color(0xFF067873),
    rose = Color(0xFFC82444),
    amber = Color(0xFF946200),
    mint = Color(0xFF0B7A50),
    textPrimary = Color(0xFF1A1A2E),
    textSecondary = Color(0xFF51516A),
    textMuted = Color(0xFF66667C),
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
    surface = DarkPalette.surface,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.surface2,
    onSurfaceVariant = DarkPalette.textSecondary,
    outline = DarkPalette.lineStrong,
    error = DarkPalette.rose,
    surfaceDim = DarkPalette.inkElevated,
    surfaceBright = DarkPalette.surface3,
    surfaceContainerLowest = DarkPalette.ink,
    surfaceContainerLow = DarkPalette.panel,
    surfaceContainer = DarkPalette.surface,
    surfaceContainerHigh = DarkPalette.surface2,
    surfaceContainerHighest = DarkPalette.surface3,
)

private val LightScheme = lightColorScheme(
    primary = LightPalette.violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9DCE1),
    onPrimaryContainer = Color(0xFF74172A),
    secondary = LightPalette.cyan,
    onSecondary = Color.White,
    tertiary = LightPalette.rose,
    background = LightPalette.ink,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.surface,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.surface2,
    onSurfaceVariant = LightPalette.textSecondary,
    outline = LightPalette.lineStrong,
    error = LightPalette.rose,
    surfaceDim = Color(0xFFE4E4EA),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightPalette.panel,
    surfaceContainer = LightPalette.surface2,
    surfaceContainerHigh = Color(0xFFF0F0F4),
    surfaceContainerHighest = LightPalette.surface3,
)

val Ink: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.ink
val InkElevated: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.inkElevated
val Panel: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.panel
val PanelHigh: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.surface2
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.surface2
val Surface3: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.surface3
val Line: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.line
val LineStrong: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.lineStrong
val Violet: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violet
val VioletSoft: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violetSoft
val AccentSoft: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.accentSoft
val Cyan: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.cyan
val Rose: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.rose
val Amber: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.amber
val Mint: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.mint
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textSecondary
val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.textMuted
val CarryGlow: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.glow

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.Normal),
    Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_600, FontWeight.SemiBold),
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
    Font(R.font.jetbrains_mono_600, FontWeight.SemiBold),
)

val CarryTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp),
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-0.88).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 23.2.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.5.sp, lineHeight = 17.sp),
    // 小标签字号下限 11sp：U-11 记的门槛就是 11sp，本轮重构把 labelSmall 降到 9sp、
    // labelMedium 降到 9.5sp，低于可访问性下限（BUG-020）。行高同步上调，避免裁字。
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)

@Composable
fun PiCarryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val activity = view.context.findActivity()

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
