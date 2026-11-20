package dev.picarry.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.picarry.app.data.Approval
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.ImageCompressor
import dev.picarry.app.data.ChatMessage
import dev.picarry.app.data.CommandItem
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.ModelOption
import dev.picarry.app.data.Session
import dev.picarry.app.data.ToolCall
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.Pill
import dev.picarry.app.ui.components.riskColor
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Ink
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.PanelHigh
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val fallbackModels = listOf(
    ModelOption("anthropic", "claude-sonnet-4-5", "Claude Sonnet 4.5", "200k ctx", "$3 / $15"),
    ModelOption("anthropic", "claude-opus-4-1", "Claude Opus 4.1", "200k ctx", "$15 / $75"),
    ModelOption("openai", "gpt-5.1-codex", "GPT-5.1 Codex", "272k ctx", "$1.25 / $10"),
    ModelOption("google", "gemini-2.5-pro", "Gemini 2.5 Pro", "1M ctx", "$1.25 / $10"),
)
private val fallbackThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

private val commands = listOf(
    CommandItem("compact", "压缩上下文，释放 token 空间"),
    CommandItem("fork", "从当前消息分叉新会话，原会话保持不变", "builtin"),
    CommandItem("model", "切换本次运行的模型"),
    CommandItem("review", "审查未提交的改动，给出可执行结论", "skill"),
    CommandItem("test", "运行受影响的测试，失败时自动展开日志", "skill"),
    CommandItem("carry", "输出诊断摘要：relay、cwd、journal 水位", "prompt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repo: CarryRepository, nodeId: String, sessionId: String, onBack: () -> Unit) {
    val nodes by repo.nodes.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val allMessages by repo.messages.collectAsState()
    val approvals by repo.approvals.collectAsState()
    val link by repo.link.collectAsState()
    val catalogs by repo.models.collectAsState()
    val levelCatalogs by repo.thinkingLevels.collectAsState()
    val runInfo by repo.runInfo.collectAsState()
    val node = nodes.firstOrNull { it.id == nodeId }
    val session = sessions.firstOrNull { it.id == sessionId && it.nodeId == nodeId }
    val key = repo.sessionKey(nodeId, sessionId)
    val messages = allMessages[key].orEmpty()
    val pending = approvals.filter { it.sessionId == sessionId && it.state == "pending" }
    val models = catalogs[key].orEmpty().ifEmpty { fallbackModels }
    val levels = levelCatalogs[key].orEmpty().ifEmpty { fallbackThinkingLevels }
    val run = runInfo[key]
    val currentModel = (run?.get("model") as? kotlinx.serialization.json.JsonObject)
        ?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
    val currentThinking = (run?.get("thinkingLevel") ?: run?.get("thinking"))?.jsonPrimitive?.contentOrNull ?: "high"

    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var showModels by remember { mutableStateOf(false) }
    var showCommands by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var openApproval by remember { mutableStateOf<Approval?>(null) }
    var pendingImage by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 解码 + 缩放 + JPEG + Base64 是重计算，放默认调度器，回来再更新 Compose 状态。
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { ImageCompressor.compress(context, uri) } }
            result.onSuccess { (mime, data) ->
                pendingImage = Triple(mime, data, "image")
                imageError = null
            }.onFailure { imageError = it.message ?: "图片处理失败" }
        }
    }

    // 前台且正看着这条会话时不发审批通知（详见 CarryRepository.notifyApproval）。
    DisposableEffect(sessionId) {
        repo.setVisibleSession(sessionId)
        onDispose { repo.setVisibleSession(null) }
    }

    LaunchedEffect(nodeId, sessionId) { repo.openSession(nodeId, sessionId) }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, pending.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.size + pending.size) }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ArrowBack, "返回", tint = TextSecondary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        session?.title ?: "会话",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (session?.isRunning == true) {
                        CircularProgressIndicator(Modifier.size(11.dp), color = Violet, strokeWidth = 1.5.dp)
                    }
                }
                MonoLabel(node?.name ?: nodeId.take(8), color = TextMuted)
            }
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { showMenu = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, "更多", tint = TextSecondary)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(text = { Text("复制会话 ID") }, onClick = {
                    clipboard.setText(AnnotatedString(sessionId)); showMenu = false
                }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = TextMuted) })
                DropdownMenuItem(text = { Text("压缩上下文") }, onClick = {
                    repo.compact(nodeId, sessionId); showMenu = false
                }, leadingIcon = { Icon(Icons.Outlined.Tune, null, tint = TextMuted) })
                DropdownMenuItem(text = { Text("关闭会话") }, onClick = {
                    repo.closeSession(nodeId, sessionId); showMenu = false; onBack()
                }, leadingIcon = { Icon(Icons.Outlined.Close, null, tint = Rose) })
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                session?.status?.let(::statusLabel) ?: if (link[nodeId] == LinkState.Online) "在线" else "离线",
                statusColor(session?.status ?: "idle"),
                filled = true,
            )
            Pill(
                "模型 · ${models.firstOrNull { it.id == currentModel }?.name ?: currentModel.ifBlank { "默认" }}",
                Violet,
            )
            Pill("thinking · $currentThinking", Cyan)
            MonoLabel(session?.cwd?.substringAfterLast('/')?.ifBlank { "~" } ?: "~", color = TextMuted)
        }

        if (session?.writable == false) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Amber.copy(alpha = 0.10f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("本机终端正在写这个会话，当前为只读", style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user" -> UserBubble(msg)
                    "tool" -> msg.tool?.let { ToolCard(it) }
                    "system" -> SystemBubble(msg)
                    else -> AssistantBlock(msg)
                }
            }
            items(pending, key = { "ap-${it.id}" }) { approval ->
                ApprovalCard(approval) { openApproval = approval }
            }
            if (messages.isEmpty() && pending.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Terminal, null, tint = TextMuted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("会话已连接", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                        Text("在下方输入消息，pi 会在电脑上执行", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                }
            }
        }

        Composer(
            text = text,
            onText = { text = it },
            mode = mode,
            onMode = { mode = it },
            running = session?.isRunning == true,
            pendingImage = pendingImage,
            imageError = imageError,
            onPickImage = { imagePicker.launch("image/*") },
            onClearImage = { pendingImage = null },
            onCommands = { showCommands = true },
            onModels = { showModels = true },
            onSend = {
                repo.sendPrompt(nodeId, sessionId, text.trim(), mode, listOfNotNull(pendingImage))
                text = ""
                pendingImage = null
            },
            onStop = { repo.abort(nodeId, sessionId) },
        )
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }, sheetState = sheetState, containerColor = Panel) {
            ModelSheet(
                models = models,
                levels = levels,
                currentModel = currentModel,
                currentThinking = currentThinking,
                onModel = { m -> repo.setModel(nodeId, sessionId, m.provider, m.id); showModels = false },
                onThinking = { level -> repo.setThinking(nodeId, sessionId, level); showModels = false },
            )
        }
    }
    if (showCommands) {
        ModalBottomSheet(onDismissRequest = { showCommands = false }, sheetState = sheetState, containerColor = Panel) {
            CommandSheet { cmd ->
                showCommands = false
                when (cmd.name) {
                    "model" -> showModels = true
                    "compact" -> repo.compact(nodeId, sessionId)
                    else -> text = "/${cmd.name} "
                }
            }
        }
    }
    openApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            onDismiss = { openApproval = null },
            onDecide = { allow, remember ->
                repo.resolveApproval(nodeId, approval.id, allow, remember)
                openApproval = null
            },
        )
    }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.88f), Color(0xFF5B4BE0))))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
private fun AssistantBlock(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Violet.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Violet))
            }
            MonoLabel(if (msg.streaming) "pi · 生成中" else "pi", color = if (msg.streaming) Violet else TextMuted)
            if (msg.streaming) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Violet))
            }
        }
        if (msg.thinking.isNotBlank()) {
            var expanded by remember(msg.id) { mutableStateOf(false) }
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Panel.copy(alpha = 0.65f))
                    .border(0.7.dp, Line.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .clickable { expanded = !expanded }
                    .padding(11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(6.dp))
                    MonoLabel("思考", color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                }
                AnimatedVisibility(visible = expanded) {
                    Text(
                        msg.thinking,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
        if (msg.text.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            SelectionContainer {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
        }
        if (msg.error) {
            Spacer(Modifier.height(5.dp))
            Text("发送失败", style = MaterialTheme.typography.labelSmall, color = Rose)
        }
    }
}

@Composable
private fun SystemBubble(msg: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Rose.copy(alpha = 0.08f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg.text, style = MaterialTheme.typography.bodySmall, color = if (msg.error) Rose else TextSecondary)
    }
}

@Composable
private fun ToolCard(tool: ToolCall) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel.copy(alpha = 0.72f))
            .border(0.7.dp, if (tool.isError) Rose.copy(alpha = 0.4f) else Line, RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, null, tint = if (tool.isError) Rose else Cyan, modifier = Modifier.size(13.dp))
            }
            Text(tool.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            if (tool.durationMs > 0) MonoLabel("${tool.durationMs}ms", color = TextMuted)
            Spacer(Modifier.weight(1f))
            when (tool.status) {
                "running" -> CircularProgressIndicator(Modifier.size(12.dp), color = Cyan, strokeWidth = 1.5.dp)
                "error" -> Text("失败", style = MaterialTheme.typography.labelSmall, color = Rose)
                else -> Icon(Icons.Outlined.Check, null, tint = Mint, modifier = Modifier.size(14.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(15.dp))
        }
        val detail = if (expanded) tool.result.ifBlank { tool.args } else tool.args
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(
                detail.take(if (expanded) 8000 else 140),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (tool.isError) Rose else TextMuted,
                maxLines = if (expanded) 200 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ApprovalCard(approval: Approval, onClick: () -> Unit) {
    val remaining = rememberCountdown(approval)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Rose.copy(alpha = 0.055f))
            .border(0.8.dp, Rose.copy(alpha = 0.45f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bolt, null, tint = Rose, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(6.dp))
            Text("待审批 · ${approval.toolName}", style = MaterialTheme.typography.titleMedium, color = Rose)
            Spacer(Modifier.weight(1f))
            MonoLabel("${remaining}s", color = Rose)
        }
        Spacer(Modifier.height(6.dp))
        Text(approval.summary.ifBlank { approval.command ?: "高风险操作" }, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 2)
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(approval.risk, riskColor(approval.risk), filled = true)
            Pill(approval.category, TextMuted)
            Spacer(Modifier.weight(1f))
            Text("查看并处理 →", style = MaterialTheme.typography.labelMedium, color = Rose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalDialog(approval: Approval, onDismiss: () -> Unit, onDecide: (Boolean, Boolean) -> Unit) {
    var rememberRule by remember { mutableStateOf(false) }
    var remaining by remember(approval.id) { mutableStateOf(approval.remainingSeconds) }
    LaunchedEffect(approval.id) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("待审批操作", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    MonoLabel("${approval.toolName} · ${approval.cwd.substringAfterLast('/')}", color = TextMuted)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).border(1.dp, Rose.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("$remaining", style = MaterialTheme.typography.labelLarge, color = Rose) }
            }
        },
        text = {
            Column {
                Text("批准后立即在电脑本机执行", style = MaterialTheme.typography.bodyMedium, color = Rose)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(approval.risk, riskColor(approval.risk), filled = true)
                    Pill(approval.category, TextMuted)
                    Spacer(Modifier.weight(1f))
                    MonoLabel("${approval.remainingSeconds}s 超时拒绝", color = TextMuted)
                }
                Spacer(Modifier.height(12.dp))
                KeyValue("COMMAND", approval.command ?: approval.summary)
                KeyValue("CWD", approval.cwd.ifBlank { "—" })
                KeyValue("SESSION", approval.sessionId.take(12))
                KeyValue("REQUEST", approval.requestedAt)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PanelHigh).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("记住此命令", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = rememberRule,
                        onCheckedChange = { rememberRule = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Violet, uncheckedTrackColor = Line),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecide(true, rememberRule) }) { Text("批准并执行", color = Rose, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = { onDecide(false, false) }) { Text("拒绝", color = TextSecondary) }
        },
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        MonoLabel(key, color = TextMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = TextPrimary, maxLines = 4)
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    mode: String,
    onMode: (String) -> Unit,
    running: Boolean,
    pendingImage: Triple<String, String, String>?,
    imageError: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onCommands: () -> Unit,
    onModels: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.copy(alpha = 0.94f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChip("自动", mode == "auto") { onMode("auto") }
            ModeChip("插话", mode == "steer") { onMode("steer") }
            ModeChip("排队", mode == "queue") { onMode("queue") }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onCommands), contentAlignment = Alignment.Center) {
                Text("/", style = MaterialTheme.typography.titleMedium, color = TextMuted)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onModels), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Tune, null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (pendingImage != null || imageError != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Image, null, tint = Cyan, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(6.dp))
                Text(if (pendingImage != null) "已附加 1 张图片" else imageError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = if (pendingImage != null) Cyan else Rose)
                Spacer(Modifier.weight(1f))
                if (pendingImage != null) Icon(Icons.Outlined.Close, null, tint = TextMuted, modifier = Modifier.size(15.dp).clickable(onClick = onClearImage))
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Panel).clickable(onClick = onPickImage), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Image, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Panel)
                    .border(0.7.dp, Line, RoundedCornerShape(19.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                if (text.isBlank()) {
                    Text(if (running) "插入一条消息 / 输入指令…" else "发消息给 pi，/ 唤起命令…", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.SansSerif),
                    cursorBrush = SolidColor(Violet),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (running) Rose else Violet)
                    .clickable { if (running && text.isBlank()) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (running && text.isBlank()) Icons.Outlined.StopCircle else Icons.Outlined.ArrowUpward,
                    if (running && text.isBlank()) "停止" else "发送",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Violet.copy(alpha = 0.18f) else Color.Transparent)
            .border(0.7.dp, if (active) Violet.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) Violet else TextMuted) }
}

@Composable
private fun ModelSheet(
    models: List<ModelOption>,
    levels: List<String>,
    currentModel: String,
    currentThinking: String,
    onModel: (ModelOption) -> Unit,
    onThinking: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        Text("模型与思考等级", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text("切换只影响下一次运行，不会中断当前生成。", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.height(14.dp))
        models.forEach { m ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(PanelHigh.copy(alpha = if (m.id == currentModel) 0.7f else 0.35f))
                    .border(0.7.dp, if (m.id == currentModel) Violet.copy(alpha = 0.5f) else Line, RoundedCornerShape(13.dp))
                    .clickable { onModel(m) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Violet.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Text(m.name.take(1), style = MaterialTheme.typography.titleMedium, color = Violet)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    MonoLabel("${m.provider} · ${m.context}", color = TextMuted)
                }
                MonoLabel(m.price, color = TextMuted)
                Spacer(Modifier.size(8.dp))
                if (m.id == currentModel) Icon(Icons.Outlined.Check, null, tint = Violet, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.height(12.dp))
        MonoLabel("思考等级")
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            levels.forEach { level ->
                val active = level == currentThinking
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Violet.copy(alpha = 0.20f) else PanelHigh)
                        .border(0.7.dp, if (active) Violet.copy(alpha = 0.55f) else Line, RoundedCornerShape(50))
                        .clickable { onThinking(level) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(level, style = MaterialTheme.typography.labelMedium, color = if (active) Violet else TextMuted) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("高等级会显著增加思考 token 与首字延迟。", style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }
}

@Composable
private fun CommandSheet(onPick: (CommandItem) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("命令面板", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            MonoLabel("COMMANDS", color = TextMuted)
        }
        Spacer(Modifier.height(4.dp))
        Text("由 pi 扩展、prompt 模板与 skill 提供。", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(Modifier.height(12.dp))
        commands.forEach { cmd ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onPick(cmd) }
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Violet.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    Text("/", style = MaterialTheme.typography.titleMedium, color = Violet)
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(cmd.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
                Pill(cmd.source, TextMuted)
            }
            HorizontalDivider(color = Line.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun rememberCountdown(approval: Approval): Long {
    var remaining by remember(approval.id) { mutableStateOf(approval.remainingSeconds) }
    LaunchedEffect(approval.id) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    return remaining
}

private fun statusLabel(status: String) = when (status) {
    "streaming" -> "生成中"
    "waiting_approval" -> "等待审批"
    "waiting_input" -> "等待输入"
    "error" -> "出错"
    else -> "空闲"
}
