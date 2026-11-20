package dev.picarry.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Session
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.EmptyState
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

private val filters = listOf("全部" to "all", "运行中" to "running", "等待审批" to "approval")

@Composable
fun SessionsScreen(
    repo: CarryRepository,
    filterNodeId: String? = null,
    onOpenChat: (String, String) -> Unit,
    onPair: () -> Unit,
) {
    val sessions by repo.sessions.collectAsState()
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }

    // 从节点卡片进来时只看该节点的会话；底部 tab 不传 nodeId，保持全量。
    val scoped = if (filterNodeId == null) sessions else sessions.filter { it.nodeId == filterNodeId }
    val filtered = scoped.filter { s ->
        val matchQuery = query.isBlank() ||
            s.title.contains(query, true) ||
            s.preview.contains(query, true) ||
            s.cwd.contains(query, true)
        val matchFilter = when (filter) {
            "running" -> s.isRunning
            "approval" -> s.pendingApprovals > 0
            else -> true
        }
        matchQuery && matchFilter
    }
    val grouped = filtered.groupBy { it.nodeId }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = "会话",
                subtitle = if (filterNodeId == null) "按节点分组 · 今天 ${sessions.size} 个会话"
                else "${nodes.firstOrNull { it.id == filterNodeId }?.name ?: filterNodeId.take(8)} · ${scoped.size} 个会话",
                actions = {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Violet.copy(alpha = 0.16f))
                            .border(0.7.dp, Violet.copy(alpha = 0.45f), CircleShape)
                            .clickable {
                                val online = nodes.firstOrNull { link[it.id] == LinkState.Online }
                                if (online != null) repo.createSession(online.id)
                            },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, "新建会话", tint = Violet, modifier = Modifier.size(20.dp)) }
                },
            )
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Panel.copy(alpha = 0.7f))
                    .border(0.7.dp, Line.copy(alpha = 0.8f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = TextMuted, modifier = Modifier.size(17.dp))
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontFamily = FontFamily.SansSerif),
                    cursorBrush = SolidColor(Violet),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isBlank()) Text("搜索会话、路径或消息…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                        inner()
                    },
                )
                if (query.isNotBlank()) {
                    Text("清除", style = MaterialTheme.typography.labelSmall, color = Violet, modifier = Modifier.clickable { query = "" })
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filters.forEach { (label, id) ->
                    val active = filter == id
                    val count = when (id) {
                        "running" -> scoped.count { it.isRunning }
                        "approval" -> scoped.count { it.pendingApprovals > 0 }
                        else -> scoped.size
                    }
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Violet.copy(alpha = 0.18f) else Color.Transparent)
                            .border(0.7.dp, if (active) Violet.copy(alpha = 0.6f) else Line, RoundedCornerShape(50))
                            .clickable { filter = id }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = if (active) TextPrimary else TextSecondary)
                        Text("$count", style = MaterialTheme.typography.labelSmall, color = if (active) Violet else TextMuted)
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(Icons.Outlined.Forum, "还没有会话", "在电脑上的 pi 会话会自动出现在这里，也可以点击右上角新建")
            }
        }
        grouped.forEach { (nodeId, list) ->
            val node = nodes.firstOrNull { it.id == nodeId }
            item(key = "head-$nodeId") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (link[nodeId] == LinkState.Online) Mint else TextMuted, 6)
                    Spacer(Modifier.size(7.dp))
                    Text(node?.name ?: nodeId.take(8), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    MonoLabel("${list.size}", color = TextMuted)
                }
            }
            items(list, key = { it.nodeId + it.id }) { session ->
                SessionRow(session) { onOpenChat(session.nodeId, session.id) }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (nodes.isEmpty()) {
            item {
                CarryCard(Modifier.fillMaxWidth(), onClick = onPair) {
                    Text("先配对一个节点 →", style = MaterialTheme.typography.titleMedium, color = Violet)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    CarryCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (session.isRunning) statusColor(session.status) else TextMuted, 6)
                Spacer(Modifier.size(8.dp))
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (session.pendingApprovals > 0) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(Rose.copy(alpha = 0.2f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) { Text("${session.pendingApprovals} 待审", style = MaterialTheme.typography.labelSmall, color = Rose) }
                } else {
                    MonoLabel(timeAgo(session.updatedAt), color = TextMuted)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(5.dp))
            Text(
                session.preview.ifBlank { session.cwd },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(session.cwd.substringAfterLast('/').ifBlank { "~" }, color = TextMuted)
                if (session.isRunning) {
                    Text(statusText(session.status), style = MaterialTheme.typography.labelSmall, color = statusColor(session.status))
                }
                Spacer(Modifier.weight(1f))
                if (!session.writable) Text("只读", style = MaterialTheme.typography.labelSmall, color = Cyan)
                MonoLabel("${session.messageCount} 条", color = TextMuted)
            }
        }
    }
}

private fun statusText(status: String) = when (status) {
    "streaming" -> "生成中"
    "waiting_approval" -> "等待审批"
    "waiting_input" -> "等待输入"
    "error" -> "出错"
    else -> "空闲"
}

private fun timeAgo(iso: String): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val t = java.time.Instant.parse(iso)
        val mins = (System.currentTimeMillis() - t.toEpochMilli()) / 60000
        when {
            mins < 1 -> "刚刚"
            mins < 60 -> "${mins} 分钟前"
            mins < 24 * 60 -> "${mins / 60} 小时前"
            else -> "${mins / 1440} 天前"
        }
    }.getOrDefault("")
}
