package dev.picarry.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Ink
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft

/** `mutableStateOf<String?>` 存不进 Bundle；空串代表“无值”。 */
internal val NullableStringSaver = Saver<String?, String>(save = { it.orEmpty() }, restore = { it.ifBlank { null } })

/**
 * 设计稿背景：顶部紫色光晕 + 右上青色微光 + 墨色底。
 * 不使用图片资源，主题切换时自动重绘。
 */
@Composable
fun CarryBackground(content: @Composable () -> Unit) {
    val bg = Ink
    val glow = Violet
    val cool = Cyan
    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.20f), glow.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.5f, -size.height * 0.08f),
                    radius = size.width * 1.12f,
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(cool.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 1.05f, size.height * 0.06f),
                    radius = size.width * 0.9f,
                )
            )
        }
        content()
    }
}

/**
 * 设计中的 9.5px mono 小标签，用大写呈现，但中文与含数字的数值保持原样。
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

/** 设计稿中的 tag：21dp 高、6dp 圆角、mono 9.5。 */
@Composable
fun Tag(
    text: String,
    color: Color = TextSecondary,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val bg = if (filled) color.copy(alpha = 0.18f) else color.copy(alpha = 0.09f)
    Row(
        modifier = modifier
            .height(21.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.6.dp, color.copy(alpha = if (filled) 0.38f else 0.18f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
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
    Row(
        modifier = modifier
            .height(29.dp)
            .clip(CircleShape)
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(0.8.dp, if (filled) color.copy(alpha = 0.30f) else LineStrong, CircleShape)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) color else TextSecondary, maxLines = 1)
    }
}

/**
 * 通用按压反馈；设计稿 active scale 约 .94–.985。
 *
 * `interactionSource` 必须与真正触发事件的 `clickable` 共用一个实例：`Modifier.clickable`
 * 默认会自己新建一个 source，这里若另建一个，`collectIsPressedAsState()` 永远收不到
 * 任何 `PressInteraction`，缩放会恒为 1（BUG-015）。能用 [pressClickable] 就用它。
 */
@Composable
fun Modifier.pressScale(scale: Float = 0.975f, interactionSource: MutableInteractionSource? = null): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(if (pressed) scale else 1f, label = "pressScale")
    return this.graphicsLayer { scaleX = value; scaleY = value }
}

/** 「按下缩放 + 点击」一步到位，保证两者共用同一个 interactionSource（见 [pressScale]）。 */
@Composable
fun Modifier.pressClickable(
    onClick: () -> Unit,
    scale: Float = 0.975f,
    enabled: Boolean = true,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(if (pressed) scale else 1f, label = "pressScale")
    return this
        .graphicsLayer { scaleX = value; scaleY = value }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/** 18dp 圆角、顶部微高光的内容卡片。 */
@Composable
fun CarryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = Line,
    featured: Boolean = false,
    padding: Dp = 15.dp,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val top = if (featured) Violet.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.045f)
    val bottom = if (featured) Violet.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.014f)
    val border = if (featured) Violet.copy(alpha = 0.28f) else borderColor
    val base = Modifier
        .shadow(if (featured) 16.dp else 8.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .background(Panel.copy(alpha = if (featured) 0.72f else 0.62f))
        .border(0.8.dp, border, shape)
    val clickable = if (onClick != null) {
        base.pressClickable(onClick = onClick)
    } else base
    Box(clickable.padding(padding)) { content() }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = Violet, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(if (size >= 34) 10.dp else 8.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(0.7.dp, tint.copy(alpha = 0.16f), RoundedCornerShape(if (size >= 34) 10.dp else 8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}

/** 设计稿 38x38 的 icon button。 */
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
) {
    val shape = RoundedCornerShape(12.dp)
    val iconTint = tint ?: if (primary) Color.White else TextSecondary
    val bg = when {
        primary -> Brush.linearGradient(listOf(Violet, lerp(Violet, Color.Black, 0.2f)))
        plain -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        else -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.02f)))
    }
    Box(
        modifier
            .size(size.dp)
            .clip(shape)
            .background(bg)
            .then(if (primary || plain) Modifier else Modifier.border(0.8.dp, LineStrong, shape))
            .pressClickable(onClick = onClick, scale = 0.94f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size((size * 0.47).dp))
    }
}

/** 页面主标题：h1 25、右上角 mono 计数。 */
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
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.labelLarge, color = TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 18.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(text, color = TextMuted)
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
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(5.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

enum class ButtonKind { Primary, Ghost, Danger }

/** 设计稿 44–46dp 操作按钮。 */
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
    val shape = RoundedCornerShape(13.dp)
    val accent = if (kind == ButtonKind.Danger) Rose else Violet
    val colors = when (kind) {
        ButtonKind.Ghost -> ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.035f),
            contentColor = TextSecondary,
            disabledContainerColor = Color.White.copy(alpha = 0.02f),
            disabledContentColor = TextMuted,
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        )
    }
    val paint = if (kind == ButtonKind.Ghost) {
        Modifier
    } else {
        Modifier
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.2f)))
                else Brush.linearGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.25f))),
            )
    }
    // Material3 Button 自己也有 interactionSource：必须把同一个实例交给它，
    // 否则 pressScale 收不到按压事件（BUG-015）。
    val buttonSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.height(height).pressScale(0.97f, buttonSource).then(paint),
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 16.dp),
        border = if (kind == ButtonKind.Ghost) BorderStroke(0.8.dp, LineStrong) else null,
        interactionSource = buttonSource,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp))
            Spacer(Modifier.size(6.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
    }
}

/** 38x22 的设计稿开关。 */
@Composable
fun CarrySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val bg = if (checked) {
        Brush.linearGradient(listOf(Violet, VioletSoft))
    } else {
        Brush.linearGradient(listOf(TextMuted.copy(alpha = 0.25f), TextMuted.copy(alpha = 0.25f)))
    }
    Box(
        Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(bg)
            .then(
                if (onCheckedChange != null) {
                    Modifier.pressClickable(onClick = { onCheckedChange(!checked) }, scale = 0.94f, enabled = enabled)
                } else {
                    Modifier
                },
            ),
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

/** 统一 bottom sheet 外观，供所有 ModalBottomSheet 使用。 */
@Composable
fun CarrySheetGrabber() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 38.dp, height = 4.dp).clip(CircleShape).background(TextMuted.copy(alpha = 0.42f)))
    }
}

@Composable
fun CarrySheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // 可点区域 48dp（U-11/BUG-020）；可视圆形仍是 30dp。
        Box(
            Modifier.size(48.dp).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.04f))
                    .semantics { contentDescription = "关闭" },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
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

@Composable
fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(Line))
}

@Composable
fun Ellipsis(text: String, max: Int = 1) {
    Text(text, maxLines = max, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}

/** 设置页的行卡片，行之间用细分隔线连接。 */
@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Panel.copy(alpha = 0.58f),
        border = BorderStroke(0.8.dp, Line),
    ) {
        Column(Modifier.padding(horizontal = 13.dp)) { content() }
    }
}
