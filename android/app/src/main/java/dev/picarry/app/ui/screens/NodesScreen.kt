package dev.picarry.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.ConnInfo
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.components.pressClickable
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.Surface2
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            ScreenHeader(
                title = "节点",
                subtitle = "$online 台在线 · 直连优先",
                count = nodes.size.toString().padStart(2, '0'),
            ) {
                CarryIconButton(Icons.Outlined.Add, "配对新节点", onPair, primary = true)
            }
        }
        item { RelayStrip(nodes, link, connInfos) }
        if (nodes.isEmpty() && credentialsLost) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Amber.copy(alpha = 0.10f))
                        .border(0.8.dp, Amber.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.LockOpen, "凭据无法解密", tint = Amber, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("本机凭据无法解密", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "系统加密密钥已被重置（换机、清除数据或恢复备份）。原凭据已另存备份，重新配对不会覆盖它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            }
        }
        if (nodes.isEmpty()) {
            item {
                CarryCard(Modifier.fillMaxWidth(), onClick = onPair, padding = 18.dp) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.QrCode, "配对新的电脑", tint = Violet, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("配对你的第一台电脑", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("在电脑上运行 pi-carry，扫描二维码即可", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }
        itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
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
        }
        // 空态已经有「配对你的第一台电脑」大卡与右上角「+」，再给一个虚线磁贴就是
        // 同一屏三个等价入口（BUG-019）。
        if (nodes.isNotEmpty()) {
            item { PairTile(onPair) }
        }
    }
}

@Composable
private fun RelayStrip(nodes: List<Node>, link: Map<String, LinkState>, conn: Map<String, ConnInfo>) {
    val online = nodes.filter { link[it.id] == LinkState.Online }
    val relay = online.firstOrNull()?.relay
    val fastest = online.mapNotNull { conn[it.id]?.rttMs }.minOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Cyan.copy(alpha = 0.055f))
            .border(0.7.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(if (online.isEmpty()) TextMuted else Mint, 5)
        MonoLabel(if (relay == null) "relay · 直连/局域网" else "relay · $relay", color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Waveform(if (online.isEmpty()) TextMuted else Mint)
        Spacer(Modifier.size(2.dp))
        MonoLabel(if (fastest != null) "${fastest}ms" else if (online.isEmpty()) "待连接" else "已连接", color = Cyan)
    }
}

@Composable
private fun Waveform(color: Color) {
    val heights = listOf(5, 9, 6, 12, 7, 10, 4, 8, 11)
    Row(Modifier.height(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        heights.forEach { h ->
            Box(Modifier.size(width = 1.5.dp, height = h.dp).clip(CircleShape).background(color.copy(alpha = 0.62f)))
        }
    }
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
    CarryCard(Modifier.fillMaxWidth(), onClick = onClick, featured = featured, padding = 15.dp) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp)) { StatusDot(color, 6) }
                Spacer(Modifier.size(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            node.name,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (state == LinkState.Offline) TextMuted else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (featured) Tag("在线", Mint)
                    }
                    Spacer(Modifier.height(2.dp))
                    MonoLabel(
                        "${node.endpointId.take(8)}…${node.endpointId.takeLast(4)} · ${node.platform.ifBlank { "unknown" }}",
                        color = TextMuted,
                    )
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(start = 17.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (state == LinkState.Online && conn != null) Tag(conn.detail, connColor(conn.type))
                Tag(capLabel(node.capability), TextSecondary)
                if (workspaceCount > 0) Tag("workspace ×$workspaceCount", TextSecondary)
                if (state != LinkState.Online) Tag(linkLabel(state), color)
            }
            Spacer(Modifier.height(13.dp))
            Column(Modifier.padding(start = 17.dp).fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(0.7.dp).background(Line))
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            append(sessionCount)
                            append(" 个会话运行中")
                            if (pending > 0) append(" · $pending 个等待审批")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (pending > 0) Amber else TextMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state == LinkState.Online) lastSeenLabel(node.lastSeen) else "点击重连",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = Rose, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PairTile(onPair: () -> Unit) {
    val stroke = LineStrong
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.018f))
            .drawBehind {
                drawRoundRect(
                    color = stroke,
                    topLeft = Offset(0.6f, 0.6f),
                    size = Size(size.width - 1.2f, size.height - 1.2f),
                    cornerRadius = CornerRadius(15.dp.toPx()),
                    style = Stroke(width = 0.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))),
                )
            }
            .pressClickable(onClick = onPair)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "配对", tint = Violet, modifier = Modifier.size(17.dp)) }
        Column(Modifier.weight(1f)) {
            Text("配对新的电脑", style = MaterialTheme.typography.titleSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text("扫描二维码，或输入 6 位配对码", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(14.dp))
    }
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

private fun lastSeenLabel(lastSeen: Long, now: Long = System.currentTimeMillis()): String {
    if (lastSeen <= 0L) return "在线"
    val diff = (now - lastSeen).coerceAtLeast(0L)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        else -> "${minutes / (24 * 60)} 天前"
    }
}
