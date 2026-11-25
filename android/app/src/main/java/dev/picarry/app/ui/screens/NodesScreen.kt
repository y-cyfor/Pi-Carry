package dev.picarry.app.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.ConnInfo
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

// token: relay 条文字 11sp #9FE9E4（深色）/ #0B7570（浅色），Theme.kt 无对应 token。
private val RelayTextDark = Color(0xFF9FE9E4)
private val RelayTextLight = Color(0xFF0B7570)

// token: 亮色主题的覆盖色 --ink-rgb = 26,26,46；深色主题为 255,255,255。
private val InkOverlayLight = Color(0xFF1A1A2E)

/** MN-01 页面主操作：可视 38×38、圆角 12、紫渐变、白 + 图标 20；AX-02 命中区 48。 */
@Composable
private fun HeaderAddButton(contentDescription: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val accent = Violet
    val gradient = listOf(accent.copy(alpha = 0.9f), lerp(accent, Color.Black, 0.2f).copy(alpha = 0.95f))
    val stroke = lerp(accent, Color.White, 0.35f).copy(alpha = 0.6f)
    Box(
        Modifier
            // M-P2-11 / AX-02：可点区域 48dp，视觉仍为 38dp（内缩 5dp）。
            .size(48.dp)
            .tactilePress(scale = 0.94f, onClick = onClick)
            .padding(5.dp)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = accent.copy(alpha = 0.32f),
                spotColor = accent.copy(alpha = 0.85f),
            )
            .clip(shape)
            .drawBehind {
                // 主操作渐变 linear-gradient(150deg, accent .9, accent-深 .95)
                drawRect(
                    Brush.linearGradient(
                        colors = gradient,
                        start = Offset.Zero,
                        end = Offset(size.width * 0.5f, size.height * 0.866f),
                    ),
                )
                // 顶部 1px 内高光 rgba(255,255,255,.25)
                drawRect(Color.White.copy(alpha = 0.25f), size = Size(size.width, 1.dp.toPx()))
            }
            .border(1.dp, stroke, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun NodesScreen(repo: CarryRepository, onPair: () -> Unit, onOpenSessions: (String) -> Unit) {
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    val connInfos by repo.connInfo.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val errors by repo.errors.collectAsState()
    val online = nodes.count { link[it.id] == LinkState.Online }
    val credentialsLost = nodes.isEmpty() && repo.credentialsLost()

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        // MP-03：标题间距由 ScreenHeader 的 10dp 提供，根容器只补状态栏 inset。
        // MP-07：底部留 98dp，最后一项能滚到导航上方而不被遮挡。
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 98.dp),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = "节点",
                subtitle = "$online 台在线 · 直连优先",
                count = nodes.size.toString().padStart(2, '0'),
            ) {
                HeaderAddButton("配对新节点", onPair)
            }
        }
        item(key = "relay") {
            Column {
                // MN-02：margin 2 0 16。
                Spacer(Modifier.height(2.dp))
                RelayStrip(nodes, link, connInfos)
                Spacer(Modifier.height(16.dp))
            }
        }
        if (nodes.isEmpty() && credentialsLost) {
            item(key = "credentials") {
                CredentialLostCard()
                Spacer(Modifier.height(11.dp))
            }
        }
        if (nodes.isEmpty()) {
            // MN-09：空态只留一张主卡片作为唯一入口（不再追加底部按钮）。
            item(key = "empty") { PairEmptyCard(onPair) }
        }
        itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
            Column {
                NodeCard(
                    node = node,
                    state = link[node.id] ?: LinkState.Offline,
                    conn = connInfos[node.id],
                    sessionCount = sessions.count { it.nodeId == node.id && it.isRunning },
                    pending = sessions.filter { it.nodeId == node.id }.sumOf { it.pendingApprovals },
                    workspaceCount = sessions.filter { it.nodeId == node.id }.map { it.cwd }.distinct().size,
                    error = errors[node.id],
                    featured = index == 0 && link[node.id] == LinkState.Online,
                    onClick = {
                        if (link[node.id] == LinkState.Online) onOpenSessions(node.id) else repo.reconnect(node.id)
                    },
                )
                if (index != nodes.lastIndex) {
                    Spacer(Modifier.height(11.dp))
                } else {
                    // 节点卡 gap 11 + 配对磁贴 margin-top 12。
                    Spacer(Modifier.height(23.dp))
                    PairTile(onPair)
                }
            }
        }
    }
}

/** MN-10 凭据丢失：琥珀错误卡，标题 + 一句后果，不写推测性排障长句。 */
@Composable
private fun CredentialLostCard() {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Amber.copy(alpha = 0.10f))
            .border(1.dp, Amber.copy(alpha = 0.42f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.LockOpen, "凭据无法解密", tint = Amber, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text("本机凭据无法解密", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "系统密钥已重置。重新配对不会覆盖原备份。",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

/** MN-02 relay 条：横向青色渐变、1dp .16 描边、9×12 内边距，波形在线时运动。 */
@Composable
private fun RelayStrip(nodes: List<Node>, link: Map<String, LinkState>, conn: Map<String, ConnInfo>) {
    val online = nodes.filter { link[it.id] == LinkState.Online }
    val relay = online.firstOrNull()?.relay
    val fastest = online.mapNotNull { conn[it.id]?.rttMs }.minOrNull()
    val active = online.isNotEmpty()
    val shape = RoundedCornerShape(12.dp)
    val labelColor = relayLabelColor()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(Cyan.copy(alpha = 0.09f), Cyan.copy(alpha = 0.02f)),
                ),
            )
            .border(1.dp, Cyan.copy(alpha = 0.16f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(if (active) Mint else TextMuted, sizeDp = 5, glow = active)
        Text(
            if (relay == null) "relay · 直连/局域网" else "relay · $relay",
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.33.sp),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Waveform(active)
        Spacer(Modifier.width(2.dp))
        Text(
            when {
                fastest != null -> "${fastest}ms"
                active -> "已连接"
                // MN-02：离线时文案固定为“待连接”，不解释原因。
                else -> "待连接"
            },
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.33.sp),
            color = if (active) Cyan else TextMuted,
            maxLines = 1,
        )
    }
}

/** MN-02 波形：9 根 2dp 柱，高度 5/9/6/12/7/10/4/8/11，逐柱延迟，在线时呼吸。 */
@Composable
private fun Waveform(active: Boolean) {
    val heights = remember { listOf(5, 9, 6, 12, 7, 10, 4, 8, 11) }
    val phase = if (active) wavePhase() else 0f
    val color = if (active) relayLabelColor() else TextMuted
    Row(
        Modifier.height(13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEachIndexed { index, heightDp ->
            // 每根柱子错开 0.085 个周期，近似设计稿的 nth-child 延迟。
            val local = (phase + index * 0.085f) % 1f
            val scale = if (active) {
                0.55f + 0.45f * (0.5f + 0.5f * kotlin.math.sin(local * 2f * Math.PI.toFloat()))
            } else {
                0.55f
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(heightDp.dp)
                    .graphicsLayer { scaleY = scale }
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 0.75f else 0.45f)),
            )
        }
    }
}

@Composable
private fun wavePhase(): Float {
    val transition = rememberInfiniteTransition(label = "wave")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    return value
}

@Composable
private fun NodeCard(
    node: Node,
    state: LinkState,
    conn: ConnInfo?,
    sessionCount: Int,
    pending: Int,
    workspaceCount: Int,
    error: String?,
    featured: Boolean,
    onClick: () -> Unit,
) {
    val color = when (state) {
        LinkState.Online -> Mint
        LinkState.Connecting -> Cyan
        LinkState.Error -> Rose
        LinkState.Offline -> TextMuted
    }
    val offline = state == LinkState.Offline
    CarryCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        featured = featured,
        padding = 0.dp,
    ) {
        Box(Modifier.padding(start = 15.dp, top = 15.dp, end = 15.dp, bottom = 13.dp)) {
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp)) { StatusDot(color, sizeDp = 7, glow = state == LinkState.Online) }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                node.name,
                                Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.23).sp,
                                ),
                                color = if (offline) TextMuted else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // MN-04：featured 不显示重复“在线”Tag；无“本机”数据时不伪造 Tag。
                        }
                        Spacer(Modifier.height(3.dp))
                        MonoLabel(
                            "${node.endpointId} · ${node.platform.ifBlank { "unknown" }}",
                            color = TextMuted,
                        )
                    }
                    Box(Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
                // MN-05：margin 12 0 0 18，gap 6。
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(start = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state == LinkState.Online && conn != null) Tag(conn.detail, connColor(conn.type))
                    Tag(
                        capLabel(node.capability),
                        if (offline) TextMuted else TextSecondary,
                    )
                    if (workspaceCount > 0) {
                        Tag("workspace ×$workspaceCount", if (offline) TextMuted else TextSecondary)
                    }
                    if (state != LinkState.Online) Tag(linkLabel(state), color)
                }
                // MN-06：margin 13 0 0 18，padding-top 10，1dp Line 顶线。
                Spacer(Modifier.height(13.dp))
                Column(Modifier.padding(start = 18.dp).fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            footSummary(sessionCount, pending),
                            style = MaterialTheme.typography.labelLarge,
                            color = TextMuted,
                        )
                        Spacer(Modifier.weight(1f))
                        val seen = lastSeenLabel(node.lastSeen)
                        if (seen != null) {
                            Text(
                                seen,
                                style = MaterialTheme.typography.labelLarge,
                                color = TextMuted,
                            )
                        }
                    }
                }
                if (error != null) {
                    // MN-11：错误行 11.5sp 玫色、最多两行、与脚注间距 8。
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Rose,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** MN-08 配对磁贴：虚线 1dp、15 圆角、13×14 内边距。 */
@Composable
private fun PairTile(onPair: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    val dash = inkOverlay(0.16f)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(inkOverlay(0.015f))
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(size.width - 1f, size.height - 1f),
                    cornerRadius = CornerRadius(15.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
                    ),
                )
            }
            .tactilePress(scale = 0.985f, pressedBackground = inkOverlay(0.03f), onClick = onPair)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "配对", tint = Violet, modifier = Modifier.size(17.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                "配对新的电脑",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.5.sp),
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "扫描二维码，或输入 6 位配对码",
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
            )
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(14.dp))
    }
}

/** MN-09 空态主卡：图标 48、标题 15.5、短说明 11.5，整卡可点进入配对。 */
@Composable
private fun PairEmptyCard(onPair: () -> Unit) {
    CarryCard(Modifier.fillMaxWidth(), onClick = onPair, padding = 0.dp) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.QrCode, "配对新的电脑", tint = Violet, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("配对你的第一台电脑", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "扫描电脑上的二维码，或输入 6 位配对码",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

/** MN-06 脚注：数字加粗 TextSecondary，其余 TextMuted，不把整段染琥珀。 */
@Composable
private fun footSummary(sessionCount: Int, pending: Int): androidx.compose.ui.text.AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = TextSecondary, fontWeight = FontWeight.Medium)) { append("$sessionCount") }
        append(" 个会话运行中")
        if (pending > 0) {
            append(" · ")
            withStyle(SpanStyle(color = TextSecondary, fontWeight = FontWeight.Medium)) { append("$pending") }
            append(" 个等待审批")
        }
    }

@Composable
private fun Modifier.tactilePress(
    scale: Float = 0.975f,
    pressedBackground: Color = Color.Transparent,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = tween(durationMillis = 150, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "pressScale",
    )
    return this
        .graphicsLayer { scaleX = value; scaleY = value }
        .background(if (pressed) pressedBackground else Color.Transparent)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
private fun isDarkUi(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** token: --ink-rgb 覆盖色；深色白、浅色 #1A1A2E。 */
@Composable
private fun inkOverlay(alpha: Float): Color =
    (if (isDarkUi()) Color.White else InkOverlayLight).copy(alpha = alpha)

@Composable
private fun relayLabelColor(): Color = if (isDarkUi()) RelayTextDark else RelayTextLight

@Composable
private fun StatusDot(color: Color, sizeDp: Int = 7, glow: Boolean = true, breathe: Boolean = false) {
    val alpha = if (breathe) breathingAlpha() else 1f
    Box(
        Modifier.size(sizeDp.dp).drawBehind {
            val radius = this.size.minDimension / 2f
            if (glow) {
                // 设计稿 .dot 光晕：0 0 0 3px color .1 + 0 0 12px color .75 的近似。
                drawCircle(color.copy(alpha = 0.10f * alpha), radius = radius + 3.dp.toPx())
                drawCircle(color.copy(alpha = 0.20f * alpha), radius = radius + 1.5.dp.toPx())
            }
            drawCircle(color.copy(alpha = alpha), radius = radius)
        },
    )
}

@Composable
private fun breathingAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "breathe")
    val value by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    return value
}

private fun capLabel(cap: String) = when (cap) {
    "owner" -> "权限 owner"
    "full" -> "权限 full"
    "read" -> "权限 read"
    else -> "权限 control"
}

private fun linkLabel(state: LinkState) = when (state) {
    LinkState.Online -> "在线"
    LinkState.Connecting -> "连接中"
    LinkState.Error -> "连接失败"
    LinkState.Offline -> "离线"
}

@Composable
private fun connColor(type: String): Color = when (type) {
    "relay" -> Amber
    "p2p" -> Violet
    else -> Mint
}

/** MN-06 脚注右端相对时间；无 lastSeen 数据返回 null，调用处隐藏（不伪造“在线”等状态）。 */
private fun lastSeenLabel(lastSeen: Long, now: Long = System.currentTimeMillis()): String? {
    if (lastSeen <= 0L) return null
    val diff = (now - lastSeen).coerceAtLeast(0L)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}
