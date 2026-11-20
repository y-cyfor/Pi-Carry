package dev.picarry.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.CarryGlow
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Ink
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

/** `mutableStateOf<String?>` 存不进 Bundle；空串代表“无值”。 */
internal val NullableStringSaver = Saver<String?, String>(save = { it.orEmpty() }, restore = { it.ifBlank { null } })

@Composable
fun CarryBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(CarryGlow, Ink, Ink),
                    radius = 1400f,
                )
            )
    ) { content() }
}

@Composable
fun MonoLabel(text: String, color: Color = TextMuted, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun StatusDot(color: Color, size: Int = 6) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun Pill(
    text: String,
    color: Color = Violet,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bg = if (filled) color.copy(alpha = 0.18f) else Color.Transparent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(0.7.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun CarryCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    borderColor: Color = Line.copy(alpha = 0.75f),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = Panel.copy(alpha = 0.86f),
            border = androidx.compose.foundation.BorderStroke(0.7.dp, borderColor),
        ) { Box(Modifier.padding(16.dp)) { content() } }
    } else {
        Box(
            modifier
                .clip(shape)
                .background(Panel.copy(alpha = 0.86f))
                .border(0.7.dp, borderColor, shape)
                .padding(16.dp)
        ) { content() }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = Violet, size: Int = 38) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(text)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, TextMuted, 52)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = TextMuted)
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
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(Line.copy(alpha = 0.6f)))
}

@Composable
fun Ellipsis(text: String, max: Int = 1) {
    Text(text, maxLines = max, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
}
