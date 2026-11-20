package dev.picarry.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.Pill
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

@Composable
fun NodesScreen(repo: CarryRepository, onPair: () -> Unit, onOpenSessions: (String) -> Unit) {
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val errors by repo.errors.collectAsState()
    val online = nodes.count { link[it.id] == LinkState.Online }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "节点",
                subtitle = "$online 在线 · 直连优先",
                actions = {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Violet.copy(alpha = 0.18f))
                            .border(0.7.dp, Violet.copy(alpha = 0.5f), CircleShape)
                            .clickable(onClick = onPair),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, "配对节点", tint = Violet, modifier = Modifier.size(20.dp)) }
                },
            )
        }
        item { RelayBar(nodes, link) }
        if (nodes.isEmpty()) {
            item {
                CarryCard(Modifier.fillMaxWidth(), onClick = onPair) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AddCircleOutline, null, tint = Violet, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("配对你的第一台电脑", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("在电脑上运行 pi-carry，扫描二维码即可", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }
        items(nodes, key = { it.id }) { node ->
            NodeCard(
                node = node,
                state = link[node.id] ?: LinkState.Offline,
                sessionCount = sessions.count { it.nodeId == node.id && it.isRunning },
                pending = sessions.filter { it.nodeId == node.id }.sumOf { it.pendingApprovals },
                error = errors[node.id],
                onClick = {
                    if (link[node.id] == LinkState.Online) onOpenSessions(node.id) else repo.reconnect(node.id)
                },
            )
        }
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(0.7.dp, Line, RoundedCornerShape(18.dp))
                    .clickable(onClick = onPair)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Violet.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.QrCode, null, tint = Violet, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text("配对新节点", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text("扫描二维码，或输入 6 位配对码", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Icon(Icons.Outlined.Add, null, tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RelayBar(nodes: List<Node>, link: Map<String, LinkState>) {
    val online = nodes.filter { link[it.id] == LinkState.Online }
    val relay = online.firstOrNull()?.relay
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel.copy(alpha = 0.6f))
            .border(0.7.dp, Line.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Outlined.Router, null, tint = if (relay == null) Mint else Cyan, modifier = Modifier.size(14.dp))
        MonoLabel(if (relay == null) "relay · 直连/局域网" else "relay · $relay", color = TextSecondary)
        Spacer(Modifier.weight(1f))
        StatusDot(if (online.isEmpty()) TextMuted else Mint, 5)
        MonoLabel(if (online.isEmpty()) "待连接" else "${online.size} 活跃", color = TextMuted)
    }
}

@Composable
private fun NodeCard(
    node: Node,
    state: LinkState,
    sessionCount: Int,
    pending: Int,
    error: String?,
    onClick: () -> Unit,
) {
    val color = when (state) {
        LinkState.Online -> Mint
        LinkState.Connecting -> Cyan
        LinkState.Error -> Rose
        LinkState.Offline -> TextMuted
    }
    CarryCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.30f), Cyan.copy(alpha = 0.13f)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(platformIcon(node.platform), null, tint = TextPrimary, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(node.name, style = MaterialTheme.typography.titleLarge, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (node.capability == "full") Pill("full access", Violet) else Pill(capLabel(node.capability), Cyan)
                    }
                    Spacer(Modifier.height(3.dp))
                    MonoLabel("${node.platform.ifBlank { "unknown" }} · ${node.endpointId.take(8)}", color = TextMuted)
                }
                StatusDot(color, 6)
                Spacer(Modifier.size(6.dp))
                Text(linkLabel(state), style = MaterialTheme.typography.labelMedium, color = color)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta("会话", "$sessionCount 运行中")
                Meta("待审批", if (pending > 0) "$pending" else "无", if (pending > 0) Rose else TextMuted)
                Spacer(Modifier.weight(1f))
                MonoLabel(if (state == LinkState.Online) "刚刚" else "点击重连", color = TextMuted)
            }
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = Rose, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String, valueColor: Color = TextSecondary) {
    Column(Modifier.padding(end = 20.dp)) {
        MonoLabel(label, color = TextMuted)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = valueColor)
    }
}

private fun capLabel(cap: String) = when (cap) {
    "owner" -> "owner"
    "full" -> "full access"
    "read" -> "只读"
    else -> "control"
}

private fun linkLabel(state: LinkState) = when (state) {
    LinkState.Online -> "在线"
    LinkState.Connecting -> "连接中"
    LinkState.Error -> "连接失败"
    LinkState.Offline -> "离线"
}

private fun platformIcon(platform: String) = when {
    platform.contains("mac", true) -> Icons.Outlined.Laptop
    platform.contains("linux", true) -> Icons.Outlined.Dns
    platform.contains("android", true) -> Icons.Outlined.Memory
    else -> Icons.Outlined.Computer
}

