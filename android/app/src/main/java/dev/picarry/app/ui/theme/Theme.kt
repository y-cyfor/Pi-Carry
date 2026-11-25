package dev.picarry.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.picarry.app.R

/**
 * 与 `design/mobile-ui/0.2.0-0.3.x-design.html` 一致的双主题色板。
 * 深色是主视觉；浅色把同一套紫/青/玫瑰语言压暗到纸白底上。
 *
 * 0.3.2 B.1.1/B.1.2 只改值、新增字段，**不重命名、不删除**任何现有属性：
 * 并行开发的页面与测试都在引用这些名字。
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
    // ---- 0.3.2 新增（B.1.1/B.1.2 + C.1 MP-04/MP-05）----
    /** violet-3：淡紫填充底（深 .16 / 浅 .10）。 */
    val violet3: Color = Color(0x297C6BFF),
    /** 浅色下可直接当正文/标签色的紫（深色沿用 violet-2）。 */
    val violetText: Color = violetSoft,
    /** 浅色下可直接当正文/标签色的玫瑰（深色沿用 rose）。 */
    val roseText: Color = rose,
    /** 浅色下可直接当正文/标签色的薄荷（深色沿用 mint）。 */
    val mintText: Color = mint,
    /** MP-04 tabbar 三段渐变中段。 */
    val tabbarMid: Color = Color(0xDB090910),
    /** MP-04 tabbar 三段渐变终点。 */
    val tabbarEnd: Color = Color(0xFA08080E),
    /** MP-04 tabbar 顶部 1px 发丝线。 */
    val tabbarLine: Color = Color(0x0DFFFFFF),
    /** MP-05 激活 tab 的图标座底色（violet .14 / 浅色 .10）。 */
    val tabbarActiveBg: Color = Color(0x247C6BFF),
    /** MP-05 激活 tab 的图标座描边（violet .16 / 浅色 .18）。 */
    val tabbarActiveStroke: Color = Color(0x297C6BFF),
    /** 青色标签在浅色下的正文可读色（深色用设计稿的浅色变体）。 */
    val cyanText: Color = cyan,
    /** 琥珀标签在浅色下的正文可读色（深色用设计稿的浅色变体）。 */
    val amberText: Color = amber,
    /** 主操作按钮渐变起点（设计 .btn-primary：深紫 / 浅色深藏青）。 */
    val actionPrimaryStart: Color = Color(0xFF8D7DFF),
    /** 主操作按钮渐变终点。 */
    val actionPrimaryEnd: Color = Color(0xFF5F4DF0),
    /** 危险操作按钮渐变起点。 */
    val actionDangerStart: Color = Color(0xFFFF7C92),
    /** 危险操作按钮渐变终点。 */
    val actionDangerEnd: Color = Color(0xFFE6445F),
    /** 危险按钮文字色（深色深字 / 浅色白字）。 */
    val onDanger: Color = Color(0xFF1A0810),
    /** Ghost 按钮底色。 */
    val ghostFill: Color = Color(0x09FFFFFF),
    /** Ghost 按钮描边。 */
    val ghostBorder: Color = Color(0x24FFFFFF),
    /** 当前是否为深色主题（B.5.1/B.5.2 的分支依据之一）。 */
    val isDark: Boolean = true,
)

internal val DarkPalette = CarryPalette(
    ink = Color(0xFF0A0A12),
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
    textMuted = Color(0xFF6B6B82),
    violet3 = Color(0x297C6BFF),
    violetText = Color(0xFFA79BFF),
    roseText = Color(0xFFFF6B85),
    mintText = Color(0xFF5BE7A9),
    tabbarMid = Color(0xDB090910),
    tabbarEnd = Color(0xFA08080E),
    tabbarLine = Color(0x0DFFFFFF),
    tabbarActiveBg = Color(0x247C6BFF),
    tabbarActiveStroke = Color(0x297C6BFF),
    cyanText = Color(0xFF8DEAE3),
    amberText = Color(0xFFFFD3A3),
    actionPrimaryStart = Color(0xFF8D7DFF),
    actionPrimaryEnd = Color(0xFF5F4DF0),
    actionDangerStart = Color(0xFFFF7C92),
    actionDangerEnd = Color(0xFFE6445F),
    onDanger = Color(0xFF1A0810),
    ghostFill = Color(0x09FFFFFF),
    ghostBorder = Color(0x24FFFFFF),
    isDark = true,
)

internal val LightPalette = CarryPalette(
    ink = Color(0xFFF1F1F4),
    inkElevated = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F4F7),
    surface3 = Color(0xFFEAEAEF),
    line = Color(0x1A1A1A2E),
    lineStrong = Color(0x331A1A2E),
    glow = Color(0xFFF7DDE3),
    violet = Color(0xFFE94560),
    violetSoft = Color(0xFFD63A52),
    accentSoft = Color(0xFFB62F45),
    cyan = Color(0xFF0F9B95),
    rose = Color(0xFFE94560),
    amber = Color(0xFFB87A12),
    mint = Color(0xFF0F9B62),
    textPrimary = Color(0xFF1A1A2E),
    textSecondary = Color(0xFF51516A),
    textMuted = Color(0xFF6F6F84),
    violet3 = Color(0x1AE94560),
    // A.3.3：浅色正文必须压到 #A92B40 一级，不能直接拿填充色 #E94560 写字。
    violetText = Color(0xFFA92B40),
    roseText = Color(0xFFA92B40),
    mintText = Color(0xFF0B7550),
    tabbarMid = Color(0xE6F8F9FA),
    tabbarEnd = Color(0xFCF8F9FA),
    tabbarLine = Color(0x111A1A2E),
    tabbarActiveBg = Color(0x1AE94560),
    tabbarActiveStroke = Color(0x2EE94560),
    cyanText = Color(0xFF0B7A75),
    amberText = Color(0xFF84550C),
    actionPrimaryStart = Color(0xFF2B2B44),
    actionPrimaryEnd = Color(0xFF1A1A2E),
    actionDangerStart = Color(0xFFEF5B74),
    actionDangerEnd = Color(0xFFD92F4D),
    onDanger = Color(0xFFFFFFFF),
    ghostFill = Color(0xFFFFFFFF),
    ghostBorder = Color(0x241A1A2E),
    isDark = false,
)

private val LocalCarryPalette = staticCompositionLocalOf { DarkPalette }

/** BUG-027 冷启动首帧底色：与 B.1.1/B.1.2 的应用底色保持一致。 */
internal val WindowBackgroundDark = 0xFF0A0A12.toInt()
internal val WindowBackgroundLight = 0xFFF1F1F4.toInt()

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
val Violet3: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violet3
val VioletText: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.violetText
val RoseText: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.roseText
val MintText: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.mintText
val TabbarMid: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.tabbarMid
val TabbarEnd: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.tabbarEnd
val TabbarLine: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.tabbarLine
val TabbarActiveBg: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.tabbarActiveBg
val TabbarActiveStroke: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.tabbarActiveStroke
val CyanText: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.cyanText
val AmberText: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.amberText
val ActionPrimaryStart: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.actionPrimaryStart
val ActionPrimaryEnd: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.actionPrimaryEnd
val ActionDangerStart: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.actionDangerStart
val ActionDangerEnd: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.actionDangerEnd
val OnDanger: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.onDanger
val GhostFill: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.ghostFill
val GhostBorder: Color @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.ghostBorder
val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalCarryPalette.current.isDark

/**
 * A.3.3：把强调色 token 转成该主题下可直接当 11–13.5sp 正文/标签色使用的颜色。
 * 深色取设计稿的浅色变体；浅色取压暗后的可读色，保证对比度 ≥4.5。
 */
@Composable
@ReadOnlyComposable
fun readableAccent(color: Color): Color {
    val palette = LocalCarryPalette.current
    return when (color) {
        palette.violet -> if (palette.isDark) palette.accentSoft else palette.violetText
        palette.rose -> if (palette.isDark) lerp(palette.rose, Color.White, 0.4f) else palette.roseText
        palette.cyan -> palette.cyanText
        palette.amber -> palette.amberText
        palette.mint -> if (palette.isDark) lerp(palette.mint, Color.White, 0.4f) else palette.mintText
        else -> if (palette.isDark) lerp(color, Color.White, 0.35f) else lerp(color, palette.textPrimary, 0.42f)
    }
}

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
    // B.3.2：25sp / 1.1 行高；-0.035em。
    headlineLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 27.5.sp, letterSpacing = (-0.88).sp),
    headlineMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
    // sheet 标题：17 / 600 / -0.025em。
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.43).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.5.sp, lineHeight = 19.sp, letterSpacing = (-0.31).sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 23.2.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 21.sp),
    // B.3.2：小正文 11sp / 17sp，和标签下限对齐。
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 17.sp),
    // 小标签字号下限 11sp：U-11 记的门槛就是 11sp，本轮重构把 labelSmall 降到 9sp、
    // labelMedium 降到 9.5sp，低于可访问性下限（BUG-020）。行高同步上调，避免裁字。
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)

/**
 * C.7 MG-02 / B.3.2：分组标题用 mono 11、字距约 2sp、大写。
 * 与 `labelMedium` 的区别只在字距，用于 eyebrow/分组标题。
 */
val EyebrowText = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 2.sp,
)

/** C.7 MG-05：设置/菜单行标题 11.5sp（不放大到全局 titleSmall 13.5）。 */
val SettingsRowTitle = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Medium,
    fontSize = 11.5.sp,
    lineHeight = 16.sp,
    letterSpacing = (-0.06).sp,
)

/** B.3.2：按钮文字 12.5sp / 600。 */
val ButtonText = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
)

/**
 * B.6 MO-01：统一缓动曲线 `cubic-bezier(.2, 0, 0, 1)`。
 * 所有 animate*AsState 必须显式给 tween（140–340ms），不得吃默认 spring。
 */
val CarryEase: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A.3.4 / MO-01 / AC-01：全局禁用 Material 涟漪。
 * 按压反馈一律由组件自身的 scale + 背景透明度变化承担。
 * 采用 IndicationNodeFactory（foundation 1.7+）返回一个不绘制任何东西的 Modifier.Node。
 */
private object NoIndication : IndicationNodeFactory {
    private class EmptyNode : Modifier.Node(), DelegatableNode

    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyNode()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

@OptIn(ExperimentalMaterial3Api::class)
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

    CompositionLocalProvider(
        LocalCarryPalette provides if (darkTheme) DarkPalette else LightPalette,
        LocalIndication provides NoIndication,
        LocalRippleConfiguration provides null,
    ) {
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
