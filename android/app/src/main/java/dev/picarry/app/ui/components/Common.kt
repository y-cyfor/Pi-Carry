package dev.picarry.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.picarry.app.ui.theme.AccentSoft
import dev.picarry.app.ui.theme.ActionDangerEnd
import dev.picarry.app.ui.theme.ActionDangerStart
import dev.picarry.app.ui.theme.ActionPrimaryEnd
import dev.picarry.app.ui.theme.ActionPrimaryStart
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.ButtonText
import dev.picarry.app.ui.theme.CarryEase
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.EyebrowText
import dev.picarry.app.ui.theme.GhostBorder
import dev.picarry.app.ui.theme.GhostFill
import dev.picarry.app.ui.theme.Ink
import dev.picarry.app.ui.theme.IsDark
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.OnDanger
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft
import dev.picarry.app.ui.theme.readableAccent

/** `mutableStateOf<String?>` 存不进 Bundle；空串代表“无值”。 */
internal val NullableStringSaver = Saver<String?, String>(save = { it.orEmpty() }, restore = { it.ifBlank { null } })

/**
 * MP-01：应用背景 #0A0A12 + 顶部紫色径向光 + 右上青色微光（浅色 rose .055 / cyan .045）。
 * 不使用图片资源，主题切换时自动重绘。
 */
@Composable
fun CarryBackground(content: @Composable () -> Unit) {
    val bg = Ink
    val warm = Violet
    val cool = Cyan
    val dark = IsDark
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            if (dark) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(warm.copy(alpha = 0.20f), warm.copy(alpha = 0.07f), Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.height * 0.08f),
                        radius = size.width * 1.12f,
                    )
                )
            } else {
                // B.1.2 / 设计稿浅色 .screen-glass：rose .055 单段径向。
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(warm.copy(alpha = 0.055f), Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.height * 0.08f),
                        radius = size.width * 1.12f,
                    )
                )
            }
            drawRect(
                Brush.radialGradient(
                    colors = listOf(cool.copy(alpha = if (dark) 0.07f else 0.045f), Color.Transparent),
                    center = Offset(size.width * 1.05f, size.height * 0.06f),
                    radius = size.width * 0.9f,
                )
            )
        }
        content()
    }
}

/**
 * 设计中的 mono 小标签，用大写呈现，但中文与含数字的数值保持原样。
 *
 * 含数字的标签（`42ms`、`87s`、`n0 / custom`、`v1`）大写后会把单位和协议常量一起
 * 改写（`42MS`、`N0`），看起来像写错了；设计稿写的就是 `12ms`（BUG-016）。
 */
@Composable
fun MonoLabel(text: String, color: Color = TextMuted, modifier: Modifier = Modifier) {
    Text(monoLabelText(text), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier, maxLines = 1)
}

/** [MonoLabel] 的纯文本规则，抽出来便于单测。 */
internal fun monoLabelText(text: String): String =
    if (text.any { it.code in 0x4E00..0x9FFF || it.isDigit() }) text else text.uppercase()

@Composable
fun StatusDot(color: Color, size: Int = 6) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/**
 * 设计稿 tag：21dp 高、6dp 圆角、mono 11、1dp 描边。
 * 彩色标签的文字用 [readableAccent] 压到可读（A.3.3 / 设计 .tag.* 变体）。
 */
@Composable
fun Tag(
    text: String,
    color: Color = TextSecondary,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val dark = IsDark
    val neutral = color == TextSecondary || color == TextMuted
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        neutral -> TextPrimary.copy(alpha = 0.03f)
        dark -> color.copy(alpha = if (filled) 0.18f else 0.12f)
        else -> color.copy(alpha = if (filled) 0.12f else 0.07f)
    }
    val stroke = when {
        neutral -> Line
        dark -> color.copy(alpha = if (filled) 0.38f else 0.30f)
        else -> color.copy(alpha = if (filled) 0.30f else 0.22f)
    }
    val content = if (neutral) color else readableAccent(color)
    Row(
        modifier = modifier
            .height(21.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** 兼容旧调用的胶囊；尺寸对齐设计稿的 filter chip（29dp）。 */
@Composable
fun Pill(
    text: String,
    color: Color = Violet,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dark = IsDark
    Row(
        modifier = modifier
            .height(29.dp)
            .clip(CircleShape)
            .background(if (filled) color.copy(alpha = if (dark) 0.16f else 0.10f) else Color.Transparent)
            .border(1.dp, if (filled) color.copy(alpha = if (dark) 0.40f else 0.32f) else Line, CircleShape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) (if (dark) AccentSoft else readableAccent(color)) else TextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * 通用按压反馈；设计稿 active scale 约 .94–.985（B.5.4）。
 *
 * B.6：按压/颜色过渡 140–160ms，缓动固定 [CarryEase]；不得吃默认 spring。
 * `interactionSource` 必须与真正触发事件的 `clickable` 共用一个实例（BUG-015）。
 */
@Composable
fun Modifier.pressScale(scale: Float = 0.975f, interactionSource: MutableInteractionSource? = null): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = tween(durationMillis = 150, easing = CarryEase),
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = value; scaleY = value }
}

/** 「按下缩放 + 点击」一步到位；MO-01/AC-01：不画 Material 涟漪。 */
@Composable
fun Modifier.pressClickable(
    onClick: () -> Unit,
    scale: Float = 0.975f,
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = tween(durationMillis = 150, easing = CarryEase),
        label = "pressScale",
    )
    return this
        .graphicsLayer { scaleX = value; scaleY = value }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * B.5.1/B.5.2 玻璃卡片：18dp 圆角、半透明渐变、1px 描边、顶部内高光、极小投影。
 * featured 卡是 150deg 紫/玫渐变 + 右上角 110×110 径向光。
 */
@Composable
fun CarryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = Line,
    featured: Boolean = false,
    padding: Dp = 15.dp,
    content: @Composable () -> Unit,
) {
    val dark = IsDark
    val shape = RoundedCornerShape(18.dp)
    val edge = when {
        featured -> (if (dark) Violet else Rose).copy(alpha = if (dark) 0.34f else 0.24f)
        else -> borderColor
    }
    val brush: Brush = when {
        dark && featured -> Brush.linearGradient(
            colors = listOf(Violet.copy(alpha = 0.16f), Violet.copy(alpha = 0.03f), Color.White.copy(alpha = 0.015f)),
        )
        dark -> Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.045f), Color.White.copy(alpha = 0.014f)),
        )
        featured -> Brush.linearGradient(
            colors = listOf(Rose.copy(alpha = 0.07f), Rose.copy(alpha = 0.015f), Color.White),
        )
        else -> SolidColor(Color.White)
    }
    val sheen = if (dark) Color.White.copy(alpha = 0.045f) else Color.White
    val shadowColor = if (dark) Color.Black.copy(alpha = 0.45f) else TextPrimary.copy(alpha = 0.10f)
    val glowColor = if (dark) Violet.copy(alpha = 0.24f) else Rose.copy(alpha = 0.13f)
    val base = Modifier
        .shadow(
            elevation = if (featured) 3.dp else 2.dp,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
        .clip(shape)
        .background(brush)
        .drawBehind {
            if (featured) {
                val radius = 55.dp.toPx()
                val center = Offset(size.width - radius + 1.dp.toPx(), radius - 1.dp.toPx())
                drawCircle(
                    brush = Brush.radialGradient(listOf(glowColor, Color.Transparent), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )
            }
            val inset = 18.dp.toPx()
            drawLine(
                color = sheen,
                start = Offset(inset, 0.5.dp.toPx()),
                end = Offset(size.width - inset, 0.5.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        .border(1.dp, edge, shape)
    val painted = if (onClick != null) base.pressClickable(onClick = onClick, scale = 0.985f) else base
    Box(painted.padding(padding)) { content() }
}

/** C.7 MG-04：28dp 行图标块（紫底 .10 / 紫边 .12），也服务 EMPTY 状态。 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Violet, size: Int = 38) {
    val radius = if (size >= 40) 12.dp else 8.dp
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(radius))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.12f), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}

/**
 * 设计稿 38x38 的 icon button（深色白 .04 底 / 浅色纯白底）。
 *
 * AX-02：可视保持 [size]（34/38），触控命中区 ≥ [hitSize]（默认 44dp，关键动作可传 48dp）。
 * 用 `requiredSize` 让可点层溢出固定高度的页头行而不改变布局尺寸，截图/排版不变。
 */
@Composable
fun CarryIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    plain: Boolean = false,
    size: Int = 38,
    tint: Color? = null,
    hitSize: Dp = 44.dp,
) {
    val dark = IsDark
    val shape = RoundedCornerShape(12.dp)
    val iconTint = tint ?: if (primary) Color.White else TextSecondary
    val bg: Brush = when {
        primary -> Brush.linearGradient(listOf(ActionPrimaryStart, ActionPrimaryEnd))
        plain -> SolidColor(Color.Transparent)
        else -> Brush.linearGradient(listOf(GhostFill, GhostFill))
    }
    val stroke = when {
        primary -> if (dark) VioletSoft.copy(alpha = 0.6f) else TextPrimary
        plain -> Color.Transparent
        else -> if (dark) Line else GhostBorder
    }
    // 外层维持可视尺寸，不改变页头/行布局；内层 requiredSize 忽略父约束，命中区 44/48。
    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(hitSize)
                .pressClickable(onClick = onClick, scale = 0.94f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(size.dp)
                    .clip(shape)
                    .background(bg)
                    .then(if (stroke == Color.Transparent) Modifier else Modifier.border(1.dp, stroke, shape)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size((size * 0.47).dp))
            }
        }
    }
}

/** 页面主标题：h1 25、右上角 mono 计数（MP-03：状态栏 inset + 10 由外层负责）。 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    count: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                if (count != null) {
                    Text(
                        count,
                        style = MaterialTheme.typography.labelMedium,
                        color = VioletSoft,
                        modifier = Modifier.padding(start = 3.dp, top = 3.dp),
                    )
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** C.7 MG-02：分组标题 mono 11 / 字距约 2sp / TextMuted，下边距 8，不加横线。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(monoLabelText(text), style = EyebrowText, color = TextMuted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 42.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, TextMuted, 52)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(5.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = TextMuted, textAlign = TextAlign.Center)
    }
}

enum class ButtonKind { Primary, Ghost, Danger }

/** 设计稿 44–46dp 操作按钮（B.5.4：按压 .975；主按钮 150deg 渐变）。 */
@Composable
fun CarryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 44.dp,
) {
    val dark = IsDark
    val shape = RoundedCornerShape(13.dp)
    val start = if (kind == ButtonKind.Danger) ActionDangerStart else ActionPrimaryStart
    val end = if (kind == ButtonKind.Danger) ActionDangerEnd else ActionPrimaryEnd
    val contentColor = when (kind) {
        ButtonKind.Ghost -> TextSecondary
        ButtonKind.Danger -> OnDanger
        ButtonKind.Primary -> Color.White
    }
    val colors = when (kind) {
        ButtonKind.Ghost -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = TextMuted,
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = contentColor.copy(alpha = 0.7f),
        )
    }
    val paint: Modifier = if (kind == ButtonKind.Ghost) {
        Modifier.background(GhostFill, shape).border(1.dp, GhostBorder, shape)
    } else {
        Modifier
            .shadow(
                elevation = if (dark) 10.dp else 6.dp,
                shape = shape,
                clip = false,
                ambientColor = start.copy(alpha = 0.35f),
                spotColor = start.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(Brush.linearGradient(listOf(start, end)))
    }
    // Material3 Button 自己也有 interactionSource：必须把同一个实例交给它（BUG-015）。
    val buttonSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(height)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .pressScale(0.975f, buttonSource)
            .then(paint),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 16.dp),
        interactionSource = buttonSource,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = ButtonText)
    }
}

/**
 * 38x22 的设计稿开关。
 *
 * AX-02：独立可点时（onCheckedChange != null）命中区 ≥ [hitSize]（默认 44dp）；
 * 可视 38×22 不变，用 requiredSize 溢出，不撑高设置行/审批行。
 */
@Composable
fun CarrySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    hitSize: Dp = 44.dp,
) {
    val dark = IsDark
    val bg = if (checked) {
        Brush.linearGradient(listOf(Violet, ActionPrimaryStart))
    } else {
        SolidColor(if (dark) Color.White.copy(alpha = 0.12f) else TextPrimary.copy(alpha = 0.16f))
    }
    Box(
        Modifier.size(width = 38.dp, height = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .requiredSize(hitSize)
                .then(
                    if (onCheckedChange != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = enabled,
                            role = Role.Switch,
                            onClick = { onCheckedChange(!checked) },
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 38.dp, height = 22.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .shadow(2.dp, CircleShape)
                )
            }
        }
    }
}

/** B.5.3：统一 bottom sheet 拖拽条（38×4，白 .18 / 浅色 ink .18，上 9 下 3）。 */
@Composable
fun CarrySheetGrabber() {
    val grab = if (IsDark) Color.White.copy(alpha = 0.18f) else TextPrimary.copy(alpha = 0.18f)
    Box(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 38.dp, height = 4.dp).clip(CircleShape).background(grab))
    }
}

/** B.5.3 / 设计 .sheet-head：padding 10 20 12、标题 17/600、副标题 11、圆形 30 关闭。 */
@Composable
fun CarrySheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // 可点区域 48dp（U-11/BUG-020）；可视圆形 30dp，背景 ink .06。
        // AC-01：无涟漪，按压只缩关闭圆（.94，tween 150 + CarryEase）。
        Box(
            Modifier.size(48.dp).pressClickable(onClick = onClose, scale = 0.94f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape)
                    .background(if (IsDark) Color.White.copy(alpha = 0.06f) else TextPrimary.copy(alpha = 0.06f))
                    .semantics { contentDescription = "关闭" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
@ReadOnlyComposable
fun riskColor(risk: String): Color = when (risk) {
    "low" -> Mint
    "medium" -> Cyan
    "critical" -> Rose
    else -> Amber
}

@Composable
@ReadOnlyComposable
fun stateColor(state: String): Color = when (state) {
    "Online" -> Mint
    "Connecting" -> Cyan
    "Error" -> Rose
    else -> TextMuted
}

@Composable
@ReadOnlyComposable
fun statusColor(status: String): Color = when (status) {
    "streaming" -> Violet
    "waiting_approval" -> Rose
    "error" -> Rose
    "waiting_input" -> Cyan
    else -> TextMuted
}

/** 卡内行分隔（AC-02：页面级分隔线已移除，保留下来的只有卡内行分隔，1dp Line）。 */
@Composable
fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun Ellipsis(text: String, max: Int = 1) {
    Text(text, maxLines = max, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}

/** C.7 MG-03：设置页扁平分组卡（14dp 圆角、1px Line 描边、无阴影；浅色纯白 + 1dp 微投影）。 */
@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (IsDark) Color.White.copy(alpha = 0.025f) else Color.White,
        border = BorderStroke(1.dp, Line),
        shadowElevation = if (IsDark) 0.dp else 1.dp,
    ) {
        Column(Modifier.padding(horizontal = 13.dp)) { content() }
    }
}
