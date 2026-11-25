package dev.picarry.app.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.FsListing
import dev.picarry.app.data.FsRoots
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.data.Session
import dev.picarry.app.data.breadcrumbs
import dev.picarry.app.data.displayName
import dev.picarry.app.net.CarryException
import dev.picarry.app.ui.components.ButtonKind
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarrySheetGrabber
import dev.picarry.app.ui.components.CarrySheetHeader
import dev.picarry.app.ui.components.EmptyState
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.components.monoLabelText
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.AccentSoft
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Inter
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val filters = listOf("全部" to "all", "运行中" to "running", "等待审批" to "approval")

// token: 激活 chip 计数 rgba(211,204,255,.75)，Theme.kt 无对应 token。
private val ChipCountActive = Color(0xFFD3CCFF)

// token: 浅色主题的覆盖色 --ink-rgb = 26,26,46。
private val InkOverlayLight = Color(0xFF1A1A2E)

// token: 搜索框输入/占位 12.5sp，Theme 字阶最小 11sp，无 12.5 档。
private val SearchTextSize = 12.5.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    repo: CarryRepository,
    filterNodeId: String? = null,
    onOpenChat: (String, String) -> Unit,
    onPair: () -> Unit,
) {
    val sessions by repo.sessions.collectAsState()
    val migrations by repo.migratedSessions.collectAsState()
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var createSlow by rememberSaveable { mutableStateOf(false) }
    var createHint by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    // 只存 sessionId：Session 本身不可保存，旋转后按 id 回查仍能保持面板打开（BUG-023）。
    var renameSessionId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var showNodePicker by rememberSaveable { mutableStateOf(false) }
    var pickerNodeId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    val nodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dirSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 8 秒只是「慢」的提示阈值，绝不能复位 isCreating：复位会让「使用此目录」
    // 重新可点，用户再点一次就会再发一次 session.create，产生两个新会话（BUG-010）。
    LaunchedEffect(isCreating) {
        if (!isCreating) {
            createSlow = false
            return@LaunchedEffect
        }
        delay(8_000)
        createSlow = true
    }
    val renameTarget = renameSessionId?.let { id -> sessions.firstOrNull { it.id == id } }

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
    val nodeOrder = filtered.map { it.nodeId }.distinct()
    val onlineNodes = nodes.filter { link[it.id] == LinkState.Online }
    val scopedNodeName = filterNodeId?.let { id -> nodes.firstOrNull { it.id == id }?.name ?: id.take(8) }
    val todayActive = scoped.count { isActiveToday(it.updatedAt) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        // MP-03：标题间距由 ScreenHeader 的 10dp 提供；MP-07：底部留 98dp 给覆盖式导航。
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 98.dp),
    ) {
        item(key = "header") {
            Column {
                ScreenHeader(
                    title = scopedNodeName ?: "会话",
                    subtitle = when {
                        filterNodeId != null -> "仅此节点 · ${scoped.size} 个会话"
                        todayActive > 0 -> "按节点分组 · 今天 $todayActive 个活跃"
                        else -> "按节点分组 · ${scoped.size} 个会话"
                    },
                    count = scoped.size.toString().padStart(2, '0'),
                ) {
                    if (isCreating) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Violet, strokeWidth = 2.dp)
                        }
                    } else {
                        HeaderIconButton("新建会话") {
                            createHint = null
                            createError = null
                            when (onlineNodes.size) {
                                0 -> createHint = "没有在线的节点"
                                1 -> pickerNodeId = onlineNodes.first().id
                                else -> showNodePicker = true
                            }
                        }
                    }
                }
                if (createHint != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(createHint!!, style = MaterialTheme.typography.bodySmall, color = Rose)
                }
                if (isCreating && createSlow) {
                    Spacer(Modifier.height(2.dp))
                    // 只提示慢，不解锁按钮：重复提交会真的建出两个会话（BUG-010）。
                    Text("仍在创建，请稍候…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
        item(key = "search") {
            Column {
                Spacer(Modifier.height(2.dp))
                SearchField(query) { query = it }
                Spacer(Modifier.height(12.dp))
            }
        }
        item(key = "filters") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            .height(29.dp)
                            .clip(CircleShape)
                            .background(if (active) Violet.copy(alpha = 0.16f) else inkOverlay(0.02f))
                            .border(1.dp, if (active) Violet.copy(alpha = 0.40f) else Line, CircleShape)
                            .tactilePress(scale = 0.96f, onClick = { filter = id })
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) AccentSoft else TextSecondary,
                            maxLines = 1,
                        )
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) chipCountColor() else TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                // MS-09：空态只留一句短说明，不再追加底部按钮。
                when {
                    nodes.isEmpty() -> EmptyState(
                        Icons.Outlined.Devices,
                        "还没有配对设备",
                        "扫描电脑上的二维码开始",
                    )
                    scoped.isEmpty() -> EmptyState(
                        Icons.Outlined.Forum,
                        "还没有会话",
                        "在电脑上发起会话后会出现在这里",
                    )
                    // 有会话但被搜索词/筛选筛空了：不能说“还没有会话”（BUG-018）。
                    else -> EmptyState(
                        Icons.Outlined.SearchOff,
                        "没有匹配的会话",
                        "换个关键词或筛选",
                    )
                }
            }
        }
        nodeOrder.forEachIndexed { groupIndex, nodeId ->
            val node = nodes.firstOrNull { it.id == nodeId }
            val nodeSessions = filtered.filter { it.nodeId == nodeId }
            item(key = "head-$nodeId") {
                NodeGroupHeader(
                    node = node,
                    nodeId = nodeId,
                    count = nodeSessions.size,
                    state = link[nodeId],
                    topPadding = if (groupIndex == 0) 2.dp else 18.dp,
                )
            }
            items(nodeSessions, key = { it.nodeId + it.id }) { session ->
                SessionRow(
                    session = session,
                    migrated = "${session.nodeId}::${session.id}" in migrations,
                    onClick = { onOpenChat(session.nodeId, session.id) },
                    onMore = { renameSessionId = session.id },
                )
            }
        }
    }

    if (renameTarget != null) {
        RenameSessionSheet(
            session = renameTarget,
            onDismiss = { renameSessionId = null },
            onSave = { name, done ->
                repo.renameSession(renameTarget.nodeId, renameTarget.id, name) { error ->
                    if (error == null) renameSessionId = null
                    done(error)
                }
            },
        )
    }

    if (showNodePicker) {
        ModalBottomSheet(
            onDismissRequest = { showNodePicker = false },
            sheetState = nodeSheetState,
            containerColor = sheetContainerColor(),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            dragHandle = { CarrySheetGrabber() },
            scrimColor = Color.Black.copy(alpha = 0.66f),
        ) {
            SheetSurface {
                NodePickerSheet(onlineNodes, onDismiss = { showNodePicker = false }) { id ->
                    showNodePicker = false
                    createError = null
                    pickerNodeId = id
                }
            }
        }
    }
    val pickerNode = pickerNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
    if (pickerNodeId != null) {
        if (pickerNode == null) {
            LaunchedEffect(pickerNodeId) { pickerNodeId = null }
        } else {
            ModalBottomSheet(
                onDismissRequest = { pickerNodeId = null; createError = null },
                sheetState = dirSheetState,
                containerColor = sheetContainerColor(),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                dragHandle = { CarrySheetGrabber() },
                scrimColor = Color.Black.copy(alpha = 0.66f),
            ) {
                SheetSurface {
                DirectoryPickerSheet(
                    repo = repo,
                    node = pickerNode,
                    isCreating = isCreating,
                    createError = createError,
                    onDismiss = { pickerNodeId = null; createError = null },
                    onCreate = { cwd ->
                        createError = null
                        isCreating = true
                        repo.createSession(
                            pickerNode.id,
                            cwd = cwd,
                            onCreated = { session ->
                                isCreating = false
                                pickerNodeId = null
                                onOpenChat(pickerNode.id, session.id)
                            },
                            onFailed = { message ->
                                isCreating = false
                                createError = message
                            },
                        )
                    },
                )
                }
            }
        }
    }
}

// token: B.5.3 sheet 底 #101019 → #0B0B13（深色）；浅色用纯白。
private val SheetTopDark = Color(0xFF101019)
private val SheetBottomDark = Color(0xFF0B0B13)

/** MB-01..05 sheet 基座：渐变底、顶边 1px 白 .14、body padding 0 18 26。 */
@Composable
private fun sheetContainerColor(): Color = if (isDarkUi()) SheetTopDark else Color.White

@Composable
private fun SheetSurface(content: @Composable () -> Unit) {
    val dark = isDarkUi()
    val background: Brush = if (dark) {
        Brush.verticalGradient(listOf(SheetTopDark, SheetBottomDark))
    } else {
        SolidColor(Color.White)
    }
    val topLine = inkOverlay(if (dark) 0.14f else 0.08f)
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(background)
                drawLine(
                    color = topLine,
                    start = Offset(0f, 0.5.dp.toPx()),
                    end = Offset(size.width, 0.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(start = 18.dp, end = 18.dp, bottom = 26.dp),
    ) {
        content()
    }
}

/** MS-01 右上角非主色 icon-btn：视觉 38×38 圆角 12，AX-02 命中区 48。 */
@Composable
private fun HeaderIconButton(contentDescription: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            // M-P2-11 / AX-02：可点区域 48dp，视觉仍为 38dp（内缩 5dp）。
            .size(48.dp)
            .tactilePress(scale = 0.94f, onClick = onClick)
            .padding(5.dp)
            .clip(shape)
            .background(inkOverlay(0.035f))
            .border(1.dp, Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/** MS-02 搜索框：40 高、圆角 12、白 .035 底、输入与占位 12.5sp。 */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(inkOverlay(0.035f))
            .border(1.dp, Line, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, "搜索会话", tint = TextMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = SearchTextSize, fontFamily = Inter),
            cursorBrush = SolidColor(Violet),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        "搜索会话、路径或消息",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = SearchTextSize),
                        color = TextMuted,
                    )
                }
                inner()
            },
        )
        if (query.isNotBlank()) {
            // 热区宽 44dp（搜索条本身仅 40 高，纵向受设计高度限制）。
            Box(
                Modifier.size(width = 44.dp, height = 40.dp).tactilePress(scale = 0.94f) { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, "清除搜索", tint = TextMuted, modifier = Modifier.size(14.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** MS-04 分组头：18/7 外边距、5dp 呼吸点、mono 11 大写、1dp 连线、计数 11。 */
@Composable
private fun NodeGroupHeader(node: Node?, nodeId: String, count: Int, state: LinkState?, topPadding: Dp) {
    val online = state == LinkState.Online
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = topPadding, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BreathDot(if (online) Mint else TextMuted, sizeDp = 5, glow = online, breathe = online)
        Text(
            monoLabelText(node?.name ?: nodeId.take(8)),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(Modifier.weight(1f).height(1.dp).background(Line))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = TextMuted, maxLines = 1)
    }
}

/** MS-05 / MS-06 会话行：无分隔线，12×10 内边距、13 圆角、按压 .06 底 + .985 缩放。 */
@Composable
private fun SessionRow(session: Session, migrated: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    // MS-07：等待审批（含 pendingApprovals 计数与 status）玫色呼吸；运行中琥珀色不呼吸。
    val waiting = session.pendingApprovals > 0 || session.status == "waiting_approval"
    val dot = when {
        waiting -> Rose
        session.isRunning -> Amber
        session.status == "error" -> Rose
        else -> TextMuted
    }
    val breathe = waiting
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .tactilePress(scale = 0.985f, pressedBackground = inkOverlay(0.06f), onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp)) {
            BreathDot(dot, sizeDp = 7, glow = waiting || session.isRunning || session.status == "error", breathe = breathe)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    session.title,
                    Modifier.weight(1f).alignByBaseline(),
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = (-0.135).sp),
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                MonoLabel(
                    timeAgo(session.updatedAt),
                    color = TextMuted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                session.preview.ifBlank { session.cwd },
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    session.cwd.ifBlank { "~" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 150.dp),
                )
                if (session.pendingApprovals > 0) {
                    FootTag("等待审批", Rose)
                } else if (session.isRunning) {
                    // M-P2-03：E.2 只允许 4 态，未知状态不渲染，避免设计外状态文案。
                    val state = statusText(session.status)
                    if (state != null) FootTag(state, statusColor(session.status))
                }
                if (migrated) FootTag("已迁移", Amber)
                if (!session.writable) FootTag("只读", Cyan)
            }
        }
        SessionMoreButton(onMore)
    }
}

/** MS-08 更多按钮：可视 24、圆角 7，48dp 热区；按压 .07 底 + .94 缩放。 */
@Composable
private fun SessionMoreButton(onMore: () -> Unit) {
    Box(Modifier.offset(x = 10.dp, y = (-2).dp).size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .tactilePress(scale = 0.94f, pressedBackground = inkOverlay(0.07f), onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MoreHoriz, "管理会话", tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/** MS-06 脚注 tag：19 高、11sp、padding 0 6。 */
@Composable
private fun FootTag(text: String, color: Color) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .height(19.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.09f))
            .border(0.6.dp, color.copy(alpha = 0.22f), shape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** MB-20..23 重命名：副标题压缩、44 高输入框、只保留长度提示、按钮 46。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSessionSheet(session: Session, onDismiss: () -> Unit, onSave: (String, (String?) -> Unit) -> Unit) {
    var name by remember(session.id) { mutableStateOf(session.name) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    var saving by remember(session.id) { mutableStateOf(false) }
    var focused by remember(session.id) { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(10.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetContainerColor(),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { CarrySheetGrabber() },
        scrimColor = Color.Black.copy(alpha = 0.66f),
    ) {
        SheetSurface {
          Column(Modifier.fillMaxWidth()) {
            CarrySheetHeader(
                title = "重命名会话",
                subtitle = "只改显示名，不改写记录",
                onClose = onDismiss,
            )
            Text("会话名称", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(5.dp))
            BasicTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                cursorBrush = SolidColor(Violet),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .onFocusChanged { focused = it.isFocused }
                    // 聚焦时补紫色外光（MB-21）。
                    .shadow(
                        elevation = if (focused) 6.dp else 0.dp,
                        shape = fieldShape,
                        ambientColor = Violet.copy(alpha = 0.35f),
                        spotColor = Violet.copy(alpha = 0.55f),
                    )
                    .clip(fieldShape)
                    .background(inkOverlay(0.035f))
                    .border(1.dp, if (focused) Violet.copy(alpha = 0.55f) else LineStrong, fieldShape)
                    .padding(horizontal = 12.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { inner() }
                },
            )
            Spacer(Modifier.height(7.dp))
            // MB-22：只保留长度限制，删除并发写入的实现细节。
            Text("最多 60 个字符", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            if (error != null) {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = Rose,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CarryActionButton("取消", onDismiss, Modifier.weight(1f), kind = ButtonKind.Ghost, height = 46.dp)
                CarryActionButton(
                    if (saving) "保存中…" else "保存",
                    onClick = {
                        if (!saving && name.isNotBlank()) {
                            saving = true
                            error = null
                            onSave(name.trim()) { message -> saving = false; error = message }
                        }
                    },
                    Modifier.weight(1.45f),
                    enabled = name.isNotBlank() && !saving,
                    height = 46.dp,
                )
            }
          }
        }
    }
}

/** MB-90 节点选择：设置行样式，删除长副标题，无涟漪。 */
@Composable
private fun NodePickerSheet(onlineNodes: List<Node>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CarrySheetHeader("选择节点", onClose = onDismiss)
        if (onlineNodes.isEmpty()) {
            Text("没有在线的节点", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            return@Column
        }
        onlineNodes.forEach { node ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(shape)
                    .background(inkOverlay(0.03f))
                    .border(1.dp, Line, shape)
                    .tactilePress(scale = 0.97f, onClick = { onPick(node.id) })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(Mint, 6)
                Spacer(Modifier.width(10.dp))
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Tag("在线", Mint)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

/** MB-91 目录选择：头部只写“选择目录”+ 节点名；行 44 高、白 .03 底；底部按钮 46。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    repo: CarryRepository,
    node: Node,
    isCreating: Boolean,
    createError: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<FsRoots?>(null) }
    var listing by remember { mutableStateOf<FsListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    val canBrowse = node.supports("fs-browse")

    suspend fun load(path: String) {
        loading = true
        error = null
        runCatching { repo.fsList(node.id, path) }
            .onSuccess { listing = it }
            .onFailure { error = (it as? CarryException)?.message ?: it.message ?: "读取目录失败" }
        loading = false
    }

    LaunchedEffect(node.id, reloadTick) {
        if (!canBrowse) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { repo.fsRoots(node.id) }
            .onSuccess { result ->
                roots = result
                load(result.roots.firstOrNull()?.path ?: "")
            }
            .onFailure {
                error = (it as? CarryException)?.message ?: it.message ?: "读取根目录失败"
                loading = false
            }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f),
    ) {
        CarrySheetHeader("选择目录", node.name, onClose = onDismiss)
        if (!canBrowse) {
            Text("节点版本较旧，升级后可选择目录", style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(10.dp))
            DefaultCwdRow(isCreating) { onCreate("") }
            return@Column
        }

        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            CarryActionButton("重试", onClick = {
                if (roots == null) reloadTick += 1 else scope.launch { load(listing?.path ?: "") }
            }, kind = ButtonKind.Ghost)
            Spacer(Modifier.height(8.dp))
        }

        val crumbs = listing?.let { breadcrumbs(it.path, roots?.roots.orEmpty()) }.orEmpty()
        if (crumbs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                crumbs.forEachIndexed { index, crumb ->
                    val last = index == crumbs.lastIndex
                    val crumbModifier = if (last) {
                        Modifier.sizeIn(minHeight = 40.dp)
                    } else {
                        Modifier.sizeIn(minHeight = 40.dp).tactilePress(scale = 0.96f) { scope.launch { load(crumb) } }
                    }
                    Box(
                        crumbModifier.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(displayName(crumb), style = MaterialTheme.typography.labelSmall, color = if (last) TextPrimary else Violet, maxLines = 1)
                    }
                    if (!last) Text("/", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        }

        val quickPaths = remember(roots) { (roots?.roots.orEmpty().map { it.path } + roots?.recent.orEmpty()).distinct() }
        if (quickPaths.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickPaths.forEach { path ->
                    val active = listing?.path == path
                    Box(
                        Modifier
                            .height(29.dp)
                            .clip(CircleShape)
                            .background(if (active) Violet.copy(alpha = 0.16f) else inkOverlay(0.02f))
                            .border(1.dp, if (active) Violet.copy(alpha = 0.40f) else Line, CircleShape)
                            .tactilePress(scale = 0.96f) { scope.launch { load(path) } }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName(path),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) AccentSoft else TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = listing
            when {
                loading && current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Violet, strokeWidth = 2.dp)
                }
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有可选的目录", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (current.entries.isEmpty()) {
                        item { Text("这个目录下没有子目录", style = MaterialTheme.typography.bodySmall, color = TextMuted) }
                    }
                    items(current.entries, key = { it.path }) { entry ->
                        val rowShape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(rowShape)
                                .background(inkOverlay(0.03f))
                                .border(1.dp, Line, rowShape)
                                .tactilePress(scale = 0.97f) { scope.launch { load(entry.path) } }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.hasSessions) {
                                StatusDot(Mint, 4)
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                    if (current.truncated) {
                        item { Text("目录过多，仅显示前 500 项", style = MaterialTheme.typography.bodySmall, color = Amber) }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (createError != null) {
            Text(
                createError,
                style = MaterialTheme.typography.bodySmall,
                color = Rose,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
        }
        val target = listing?.path
        CarryActionButton(
            // M-P2-04 / E.2.7：目录选择主按钮文案“使用此目录”。
            if (isCreating) "创建中…" else "使用此目录",
            onClick = { onCreate(target ?: "") },
            modifier = Modifier.fillMaxWidth(),
            enabled = target != null && !isCreating,
            height = 46.dp,
        )
    }
}

@Composable
private fun DefaultCwdRow(isCreating: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(inkOverlay(0.03f))
            .border(1.dp, Line, shape)
            .tactilePress(scale = 0.97f, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "使用节点默认目录",
            style = MaterialTheme.typography.titleSmall,
            color = if (isCreating) TextMuted else TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
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
private fun chipCountColor(): Color =
    if (isDarkUi()) ChipCountActive.copy(alpha = 0.75f) else AccentSoft.copy(alpha = 0.85f)

/** MS-07 行状态点：7dp，等待审批玫色呼吸（1.6s），其余静止。 */
@Composable
private fun BreathDot(color: Color, sizeDp: Int = 7, glow: Boolean = true, breathe: Boolean = false) {
    val alpha = if (breathe) breathingAlpha() else 1f
    Box(
        Modifier.size(sizeDp.dp).drawBehind {
            val radius = this.size.minDimension / 2f
            if (glow) {
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

/** MS-01 副标题“今天 N 个活跃”的当天判定；非 ISO 文本（如“刚刚”）按今天处理。 */
private fun isActiveToday(iso: String, now: Long = System.currentTimeMillis()): Boolean {
    if (iso.isBlank()) return false
    val epoch = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return true
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}

private fun statusText(status: String): String? = when (status) {
    // M-P2-03 / E.2：只映射 运行中 / 等待审批 / 已完成 / 失败；其余返回 null（不渲染）。
    "streaming", "running", "in_progress" -> "运行中"
    "waiting_approval", "waiting_input" -> "等待审批"
    "completed", "finished", "done", "idle" -> "已完成"
    "error", "failed" -> "失败"
    else -> null
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
    }.getOrDefault(if (iso.length <= 8) iso else "")
}