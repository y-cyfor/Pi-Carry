package dev.picarry.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.FsListing
import dev.picarry.app.data.FsRoots
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.data.Session
import dev.picarry.app.data.breadcrumbs
import dev.picarry.app.data.displayName
import dev.picarry.app.net.CarryException
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.CarrySheetGrabber
import dev.picarry.app.ui.components.CarrySheetHeader
import dev.picarry.app.ui.components.EmptyState
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.components.pressClickable
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Inter
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.Surface2
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val filters = listOf("全部" to "all", "运行中" to "running", "等待审批" to "approval")

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
    // 只存 sessionId：Session 本身不可保存，旋转后按 id 回查即可保持面板打开（BUG-023）。
    var renameSessionId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var showNodePicker by rememberSaveable { mutableStateOf(false) }
    var pickerNodeId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    val nodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dirSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 8 秒只是「慢」的提示阈值，绝不能复位 isCreating：复位会让「在此目录新建」
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

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 26.dp),
    ) {
        item {
            ScreenHeader(
                title = scopedNodeName ?: "会话",
                subtitle = if (filterNodeId == null) "按节点分组 · ${sessions.size} 个会话" else "节点会话 · ${scoped.size} 个会话",
                count = scoped.size.toString().padStart(2, '0'),
            ) {
                if (isCreating) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Violet, strokeWidth = 2.dp)
                    }
                } else {
                    CarryIconButton(Icons.Outlined.Add, "新建会话", onClick = {
                        createHint = null
                        createError = null
                        when (onlineNodes.size) {
                            0 -> createHint = "没有在线的节点，请先配对或等待连接恢复"
                            1 -> pickerNodeId = onlineNodes.first().id
                            else -> showNodePicker = true
                        }
                    })
                }
            }
            if (createHint != null) {
                Spacer(Modifier.height(4.dp))
                Text(createHint!!, style = MaterialTheme.typography.bodySmall, color = Rose)
            }
            if (isCreating && createSlow) {
                Spacer(Modifier.height(4.dp))
                // 只提示慢，不解锁按钮：重复提交会真的建出两个会话（BUG-010）。
                Text("节点响应较慢，仍在等待创建结果…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }
        item { SearchField(query) { query = it } }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 14.dp),
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
                            .background(if (active) Violet.copy(alpha = 0.16f) else Color.Transparent)
                            .border(0.8.dp, if (active) Violet.copy(alpha = 0.30f) else LineStrong, CircleShape)
                            .pressClickable(onClick = { filter = id }, scale = 0.96f)
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) VioletSoft else TextSecondary,
                        )
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) VioletSoft else TextMuted,
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                if (nodes.isEmpty()) {
                    EmptyState(Icons.Outlined.Devices, "还没有配对设备", "会话来自已配对的电脑，请先扫描电脑上的二维码配对")
                } else if (scoped.isEmpty()) {
                    EmptyState(Icons.Outlined.Forum, "还没有会话", "在电脑上的 pi 会话会自动出现在这里，也可以点击右上角新建")
                } else {
                    // 有会话但被搜索词/筛选筛空了：不能说“还没有会话”，那会让用户以为会话丢了（BUG-018）。
                    EmptyState(Icons.Outlined.SearchOff, "没有匹配的会话", "换个关键词，或把筛选切回「全部」")
                }
            }
        }
        nodeOrder.forEach { nodeId ->
            val node = nodes.firstOrNull { it.id == nodeId }
            val nodeSessions = filtered.filter { it.nodeId == nodeId }
            item(key = "head-$nodeId") { NodeGroupHeader(node, nodeId, nodeSessions.size, link[nodeId]) }
            items(nodeSessions, key = { it.nodeId + it.id }) { session ->
                SessionRow(
                    session = session,
                    migrated = "${session.nodeId}::${session.id}" in migrations,
                    onClick = { onOpenChat(session.nodeId, session.id) },
                    onMore = { renameSessionId = session.id },
                )
            }
        }
        if (nodes.isEmpty()) {
            item {
                Spacer(Modifier.height(12.dp))
                CarryActionButton("先配对一个节点", onPair, Modifier.fillMaxWidth(), kind = dev.picarry.app.ui.components.ButtonKind.Ghost)
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
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            dragHandle = { CarrySheetGrabber() },
            scrimColor = Color.Black.copy(alpha = 0.66f),
        ) {
            NodePickerSheet(onlineNodes, onDismiss = { showNodePicker = false }) { id ->
                showNodePicker = false
                createError = null
                pickerNodeId = id
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
                containerColor = Panel,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                dragHandle = { CarrySheetGrabber() },
                scrimColor = Color.Black.copy(alpha = 0.66f),
            ) {
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

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2.copy(alpha = 0.52f))
            .border(0.8.dp, Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, "搜索会话", tint = TextMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.size(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontFamily = Inter),
            cursorBrush = SolidColor(Violet),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isBlank()) Text("搜索会话、路径或消息…", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                inner()
            },
        )
        if (query.isNotBlank()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "清除搜索", tint = TextMuted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun NodeGroupHeader(node: Node?, nodeId: String, count: Int, state: LinkState?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StatusDot(if (state == LinkState.Online) Mint else TextMuted, 5)
        Text(
            node?.name ?: nodeId.take(8),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(0.7.dp).background(Line))
        MonoLabel("$count", color = TextMuted)
    }
}

@Composable
private fun SessionRow(session: Session, migrated: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val dot = when {
        session.pendingApprovals > 0 -> Rose
        session.isRunning -> Amber
        session.status == "error" -> Rose
        else -> TextMuted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .pressClickable(onClick = onClick, scale = 0.985f)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp)) { StatusDot(dot, 6) }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                MonoLabel(timeAgo(session.updatedAt), color = TextMuted)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                session.preview.ifBlank { session.cwd },
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    session.cwd.ifBlank { "~" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.pendingApprovals > 0) {
                    Tag("等待审批", Rose)
                } else if (session.isRunning) {
                    Tag(statusText(session.status), statusColor(session.status))
                }
                if (migrated) Tag("已迁移", Amber)
                if (!session.writable) Tag("只读", Cyan)
            }
        }
        // 24dp 的图标按钮点起来容易打偏：可点区域撑到 48dp，图标与底色仍按原尺寸绘制（BUG-020）。
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.MoreHoriz, "管理会话", tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 27.dp).height(0.7.dp).background(Line.copy(alpha = 0.7f)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSessionSheet(session: Session, onDismiss: () -> Unit, onSave: (String, (String?) -> Unit) -> Unit) {
    var name by remember(session.id) { mutableStateOf(session.name) }
    var error by remember(session.id) { mutableStateOf<String?>(null) }
    var saving by remember(session.id) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = { CarrySheetGrabber() },
        scrimColor = Color.Black.copy(alpha = 0.66f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            CarrySheetHeader(
                title = "重命名会话",
                subtitle = "只影响列表与对话标题 · 不改变 .jsonl 路径",
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(Surface2.copy(alpha = 0.55f))
                    .border(0.8.dp, LineStrong, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(1.dp))
            Text(
                "名称可以随时修改；并发重命名以服务端最后写入为准。",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (error != null) {
                Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CarryActionButton("取消", onDismiss, Modifier.weight(1f), kind = dev.picarry.app.ui.components.ButtonKind.Ghost)
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
                )
            }
        }
    }
}

@Composable
private fun NodePickerSheet(onlineNodes: List<Node>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        CarrySheetHeader("选择节点", "新会话建在所选节点上。", onClose = onDismiss)
        onlineNodes.forEach { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface2.copy(alpha = 0.45f))
                    .border(0.8.dp, Line, RoundedCornerShape(12.dp))
                    .clickable { onPick(node.id) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(Mint, 6)
                Spacer(Modifier.size(10.dp))
                Text(node.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                Tag("在线", Mint)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

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
            .fillMaxHeight(0.88f)
            .padding(horizontal = 18.dp)
            .padding(bottom = 20.dp),
    ) {
        CarrySheetHeader("选择目录", "在 ${node.name} 上的工作目录新建会话。", onClose = onDismiss)
        if (!canBrowse) {
            Text("节点版本较旧，升级后可选择目录", style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(10.dp))
            DefaultCwdRow(isCreating) { onCreate("") }
            return@Column
        }

        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
            Spacer(Modifier.height(8.dp))
            CarryActionButton("重试", onClick = {
                if (roots == null) reloadTick += 1 else scope.launch { load(listing?.path ?: "") }
            }, kind = dev.picarry.app.ui.components.ButtonKind.Ghost)
            Spacer(Modifier.height(8.dp))
        }

        val crumbs = listing?.let { breadcrumbs(it.path, roots?.roots.orEmpty()) }.orEmpty()
        if (crumbs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                crumbs.forEachIndexed { index, crumb ->
                    val last = index == crumbs.lastIndex
                    Box(
                        Modifier.sizeIn(minHeight = 40.dp).clickable(enabled = !last) { scope.launch { load(crumb) } }.padding(horizontal = 4.dp),
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
                            .background(if (active) Violet.copy(alpha = 0.16f) else Color.Transparent)
                            .border(0.8.dp, if (active) Violet.copy(alpha = 0.30f) else LineStrong, CircleShape)
                            .clickable { scope.launch { load(path) } }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(displayName(path), style = MaterialTheme.typography.labelLarge, color = if (active) VioletSoft else TextSecondary, maxLines = 1)
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
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Surface2.copy(alpha = 0.42f))
                                .border(0.8.dp, Line, RoundedCornerShape(12.dp))
                                .clickable { scope.launch { load(entry.path) } }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (entry.hasSessions) {
                                StatusDot(Mint, 4)
                                Spacer(Modifier.size(8.dp))
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
            Text(createError, style = MaterialTheme.typography.bodySmall, color = Rose)
            Spacer(Modifier.height(8.dp))
        }
        val target = listing?.path
        CarryActionButton(
            if (isCreating) "创建中…" else "在此目录新建",
            onClick = { onCreate(target ?: "") },
            modifier = Modifier.fillMaxWidth(),
            enabled = target != null && !isCreating,
            height = 46.dp,
        )
    }
}

@Composable
private fun DefaultCwdRow(isCreating: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface2.copy(alpha = 0.45f))
            .border(0.8.dp, Line, RoundedCornerShape(12.dp))
            .clickable(enabled = !isCreating, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("使用节点默认目录", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
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
