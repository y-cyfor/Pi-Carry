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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.CwdGroup
import dev.picarry.app.data.FsListing
import dev.picarry.app.data.FsRoots
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.data.Session
import dev.picarry.app.data.breadcrumbs
import dev.picarry.app.data.decodeCollapsed
import dev.picarry.app.data.displayName
import dev.picarry.app.data.encodeCollapsed
import dev.picarry.app.data.groupSessionsByCwd
import dev.picarry.app.data.isGroupCollapsed
import dev.picarry.app.data.toggleCollapsed
import dev.picarry.app.net.CarryException
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.EmptyState
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.PanelHigh
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
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
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    // createSession 是 fire-and-forget：用进行中标记防双击建出两个会话。
    var isCreating by rememberSaveable { mutableStateOf(false) }
    var createHint by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    // 创建会话的失败只进日志，没有回调可等：最长 8 秒后自动解除禁用，避免永久卡在 loading。
    LaunchedEffect(isCreating) {
        if (isCreating) {
            delay(8_000)
            isCreating = false
        }
    }

    // 折叠集改动后立即持久化（forceExpand 时不写回，见下方组头点击）。
    var collapsed by remember { mutableStateOf(decodeCollapsed(repo.settingString("collapsed_cwd_groups", ""))) }
    // 目录选择：先定节点（多节点才需要选），再开目录 sheet。
    var showNodePicker by remember { mutableStateOf(false) }
    var pickerNodeId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    // 节点选择与目录选择是两个连续的 sheet：分开启状态，避免共用状态时第二个 sheet 一进入就处在 hidden。
    val nodeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dirSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
    // 搜索/筛选激活时强制展开，且组头点击不写回持久化状态。
    val forceExpand = query.isNotBlank() || filter != "all"
    val cwdGroups = groupSessionsByCwd(filtered, collapsed, forceExpand)
    val groupsByNode = cwdGroups.groupBy { it.nodeId }
    // 节点顺序沿用会话语料顺序（与改造前一致），不因组排序变化而乱跳。
    val nodeOrder = filtered.map { it.nodeId }.distinct()
    val onlineNodes = nodes.filter { link[it.id] == LinkState.Online }
    val scopedNodeName = filterNodeId?.let { id ->
        nodes.firstOrNull { it.id == id }?.name ?: id.take(8)
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp),
    ) {
        item {
            ScreenHeader(
                title = scopedNodeName ?: "会话",
                subtitle = if (filterNodeId == null) "按节点分组 · 共 ${sessions.size} 个会话"
                else "节点会话 · ${scoped.size} 个会话",
                actions = {
                    Box(
                        // 热区至少 44dp（U-11）：38dp 热区在异形屏边缘很难点中，图标仍 20dp。
                        Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .clip(CircleShape)
                            .background(Violet.copy(alpha = if (isCreating) 0.07f else 0.16f))
                            .border(0.7.dp, Violet.copy(alpha = if (isCreating) 0.2f else 0.45f), CircleShape)
                            .clickable(enabled = !isCreating) {
                                createHint = null
                                createError = null
                                when (onlineNodes.size) {
                                    0 -> createHint = "没有在线的节点，请先配对或等待连接恢复"
                                    1 -> pickerNodeId = onlineNodes.first().id
                                    else -> showNodePicker = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Violet, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Add, "新建会话", tint = Violet, modifier = Modifier.size(20.dp))
                        }
                    }
                },
            )
            if (createHint != null) {
                Spacer(Modifier.height(6.dp))
                Text(createHint!!, style = MaterialTheme.typography.bodySmall, color = Rose)
            }
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
                Icon(Icons.Outlined.Search, "搜索会话", tint = TextMuted, modifier = Modifier.size(17.dp))
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
                    Box(
                        // 热区至少 44dp（U-11）：小号文字按钮不能只靠文字尺寸。
                        Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).clickable { query = "" },
                        contentAlignment = Alignment.CenterEnd,
                    ) { Text("清除", style = MaterialTheme.typography.labelSmall, color = Violet) }
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
                    // 热区 44dp 包在外面，胶囊本身维持 0.1.3 的紧凑高度：
                    // 把 44dp 加在背景/边框上会让筛选条视觉上突然变胖。
                    Box(
                        Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .clickable { filter = id },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) Violet.copy(alpha = 0.18f) else Color.Transparent)
                                .border(0.7.dp, if (active) Violet.copy(alpha = 0.6f) else Line, RoundedCornerShape(50))
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
        }
        if (filtered.isEmpty()) {
            item {
                // 一个节点都没配对时，“点击右上角新建”是做不到的操作，直接引导去配对。
                if (nodes.isEmpty()) {
                    EmptyState(Icons.Outlined.Devices, "还没有配对设备", "会话来自已配对的电脑，请先扫描电脑上的二维码配对")
                } else {
                    EmptyState(Icons.Outlined.Forum, "还没有会话", "在电脑上的 pi 会话会自动出现在这里，也可以点击右上角新建")
                }
            }
        }
        nodeOrder.forEach { nodeId ->
            val nodeGroups = groupsByNode[nodeId].orEmpty()
            val node = nodes.firstOrNull { it.id == nodeId }
            item(key = "head-$nodeId") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (link[nodeId] == LinkState.Online) Mint else TextMuted, 6)
                    Spacer(Modifier.size(7.dp))
                    Text(node?.name ?: nodeId.take(8), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    MonoLabel("${nodeGroups.sumOf { it.count }}", color = TextMuted)
                }
            }
            nodeGroups.forEach { group ->
                val groupCollapsed = isGroupCollapsed(collapsed, group, forceExpand)
                item(key = "cwd-${group.nodeId}-${group.key}") {
                    CwdGroupHeader(group, groupCollapsed) {
                        val next = toggleCollapsed(collapsed, group, forceExpand)
                        // forceExpand 下 toggleCollapsed 原样返回，不写回持久化（搜索清空后恢复原折叠集）。
                        if (!forceExpand) {
                            collapsed = next
                            repo.setSettingString("collapsed_cwd_groups", encodeCollapsed(next))
                        }
                    }
                }
                // 折叠时完全不渲染会话行，不靠高度动画隐藏。
                if (!groupCollapsed) {
                    items(group.sessions, key = { it.nodeId + it.id }) { session ->
                        SessionRow(session) { onOpenChat(session.nodeId, session.id) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
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

    if (showNodePicker) {
        ModalBottomSheet(
            onDismissRequest = { showNodePicker = false },
            sheetState = nodeSheetState,
            containerColor = Panel,
        ) {
            NodePickerSheet(onlineNodes) { id ->
                showNodePicker = false
                createError = null
                pickerNodeId = id
            }
        }
    }
    val pickerNode = pickerNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
    if (pickerNodeId != null) {
        if (pickerNode == null) {
            // 节点在选目录期间被解绑：直接收掉 sheet，避免停在空白页。
            LaunchedEffect(pickerNodeId) { pickerNodeId = null }
        } else {
            ModalBottomSheet(
                onDismissRequest = { pickerNodeId = null },
                sheetState = dirSheetState,
                containerColor = Panel,
            ) {
                DirectoryPickerSheet(
                    repo = repo,
                    node = pickerNode,
                    isCreating = isCreating,
                    createError = createError,
                    onCreate = { cwd ->
                        createError = null
                        isCreating = true
                        repo.createSession(
                            pickerNode.id,
                            cwd = cwd,
                            onCreated = { session ->
                                isCreating = false
                                pickerNodeId = null
                                // 目录选定后直接进会话，省掉“列表刷新后再找一遍新会话”。
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

/** cwd 组头：目录名 + 完整路径 + 会话数，点击折叠/展开。 */
@Composable
private fun CwdGroupHeader(group: CwdGroup, collapsedGroup: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 热区至少 44dp：组头是折叠开关，不能只靠 15sp 文字的行高。
            .sizeIn(minHeight = 44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Panel.copy(alpha = 0.45f))
            .border(0.7.dp, Line.copy(alpha = 0.6f), RoundedCornerShape(13.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(group.label, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1)
            if (group.cwd.isNotBlank()) {
                // 用 labelSmall 而不是 MonoLabel：MonoLabel 会 uppercase，路径不该被改大小写。
                Text(
                    group.cwd,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MonoLabel("${group.count}", color = TextMuted)
        Spacer(Modifier.size(4.dp))
        Icon(
            Icons.Outlined.ExpandMore,
            if (collapsedGroup) "展开该目录" else "折叠该目录",
            tint = TextMuted,
            modifier = Modifier.size(16.dp).rotate(if (collapsedGroup) -90f else 0f),
        )
    }
    // 折叠与展开都补一段间距：展开时下方是会话卡，折叠时直接接下个组头。
    Spacer(Modifier.height(8.dp))
}

/** 多节点时的节点选择：先选在哪台电脑上建会话。 */
@Composable
private fun NodePickerSheet(onlineNodes: List<Node>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        Text("选择节点", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("新会话建在所选节点上。", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.height(12.dp))
        onlineNodes.forEach { node ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(PanelHigh.copy(alpha = 0.5f))
                    .border(0.7.dp, Line, RoundedCornerShape(13.dp))
                    .clickable { onPick(node.id) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Computer, null, tint = Violet, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text(node.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                StatusDot(Mint, 6)
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}

/**
 * 目录选择 sheet。
 *
 * 缺 `fs-browse` 能力的节点不发 `fs.roots`/`fs.list`（老节点会直接回错误），
 * 只给一项「使用节点默认目录」（`cwd = ""`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryPickerSheet(
    repo: CarryRepository,
    node: Node,
    isCreating: Boolean,
    createError: String?,
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
                // `fs.list` 的 path 留空会由服务端回退到默认目录，所以根列表为空也能开。
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
            .fillMaxHeight(0.85f)
            .padding(horizontal = 18.dp)
            .padding(bottom = 20.dp),
    ) {
        Text("选择目录", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("在 ${node.name} 上的工作目录新建会话。", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.height(12.dp))

        if (!canBrowse) {
            Text("节点版本较旧，升级后可选择目录", style = MaterialTheme.typography.bodySmall, color = Amber)
            Spacer(Modifier.height(10.dp))
            DefaultCwdRow(isCreating) { onCreate("") }
            return@Column
        }

        if (error != null) {
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    // 热区至少 44dp（U-11）：加载失败后的重试是唯一出口，不能只靠胶囊本身的高度。
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Violet.copy(alpha = 0.16f))
                    .border(0.7.dp, Violet.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable {
                        // 根目录都没拿到时只能重拉 roots；已在某个目录里失败就重试该目录，不退回根。
                        if (roots == null) {
                            reloadTick += 1
                        } else {
                            scope.launch { load(listing?.path ?: "") }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) { Text("重试", style = MaterialTheme.typography.bodySmall, color = Violet) }
            Spacer(Modifier.height(8.dp))
        }

        // 面包屑：每级都能点，点击即 fs.list 该级（服务端只给 parent，逐级回退会多几次往返）。
        val crumbs = listing?.let { breadcrumbs(it.path, roots?.roots.orEmpty()) }.orEmpty()
        if (crumbs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { index, crumb ->
                    val last = index == crumbs.lastIndex
                    Box(
                        Modifier
                            .sizeIn(minHeight = 44.dp)
                            .clickable(enabled = !last) { scope.launch { load(crumb) } }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName(crumb),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (last) TextPrimary else Violet,
                            maxLines = 1,
                        )
                    }
                    if (!last) Text("/", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val quickPaths = remember(roots) {
            (roots?.roots.orEmpty().map { it.path } + roots?.recent.orEmpty()).distinct()
        }
        if (quickPaths.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickPaths.forEach { path ->
                    val active = listing?.path == path
                    Box(
                        Modifier
                            .sizeIn(minHeight = 44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Violet.copy(alpha = 0.18f) else Color.Transparent)
                            .border(0.7.dp, if (active) Violet.copy(alpha = 0.55f) else Line, RoundedCornerShape(50))
                            .clickable { scope.launch { load(path) } }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            displayName(path),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active) TextPrimary else TextSecondary,
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
                        item {
                            Text("这个目录下没有子目录", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                    }
                    items(current.entries, key = { it.path }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 44.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(PanelHigh.copy(alpha = 0.45f))
                                .border(0.7.dp, Line, RoundedCornerShape(13.dp))
                                .clickable { scope.launch { load(entry.path) } }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = Amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.hasSessions) {
                                // 已有会话的目录打个点，避免为了看一眼就点进去。
                                StatusDot(Mint, 4)
                                Spacer(Modifier.size(8.dp))
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (current.truncated) {
                        item {
                            Text("目录过多，仅显示前 500 项", style = MaterialTheme.typography.bodySmall, color = Amber)
                        }
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
        Box(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Violet.copy(alpha = if (target == null || isCreating) 0.09f else 0.9f))
                .clickable(enabled = target != null && !isCreating) { onCreate(target ?: "") }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCreating) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Violet, strokeWidth = 2.dp)
            } else {
                Text(
                    "在此目录新建",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (target == null) TextMuted else Color.White,
                )
            }
        }
    }
}

/** 缺 `fs-browse` 时唯一可选项：不传 cwd，由节点自己决定目录。 */
@Composable
private fun DefaultCwdRow(isCreating: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(PanelHigh.copy(alpha = 0.45f))
            .border(0.7.dp, Line, RoundedCornerShape(13.dp))
            .clickable(enabled = !isCreating, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Folder, null, tint = Violet, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            "使用节点默认目录",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
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
                Icon(Icons.Outlined.ChevronRight, "打开会话", tint = TextMuted, modifier = Modifier.size(16.dp))
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
                // displayName 同时认 `/` 与 `\`：Windows 路径按 `/` 切分会整条返回，卡片上就会出现一整条路径。
                // 这里不用 MonoLabel：它会 uppercase，把 `Documents` 变成 `DOCUMENTS`。
                Text(
                    displayName(session.cwd).ifBlank { "~" },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
