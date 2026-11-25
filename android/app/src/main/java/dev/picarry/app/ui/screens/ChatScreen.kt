package dev.picarry.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import android.text.method.LinkMovementMethod
import android.util.Base64
import java.util.UUID
import android.widget.TextView
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.res.ResourcesCompat
import java.io.File
import dev.picarry.app.R
import dev.picarry.app.data.Approval
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.ImagePreparer
import dev.picarry.app.data.computeSampleSize
import dev.picarry.app.data.ImagePart
import dev.picarry.app.data.ChatMessage
import dev.picarry.app.data.CommandItem
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.ModelOption
import dev.picarry.app.data.ModelPrefs
import dev.picarry.app.data.SendFailure
import dev.picarry.app.data.ModelPrefsCodec
import dev.picarry.app.data.ProviderGroup
import dev.picarry.app.data.Session
import dev.picarry.app.data.ToolCall
import dev.picarry.app.data.Turn
import dev.picarry.app.data.computeTurns
import dev.picarry.app.data.displayName
import dev.picarry.app.data.formatCost
import dev.picarry.app.data.formatMs
import dev.picarry.app.data.formatPercent
import dev.picarry.app.data.formatSpeed
import dev.picarry.app.data.formatTokens
import dev.picarry.app.data.defaultModelFor
import dev.picarry.app.data.defaultThinkingLevel
import dev.picarry.app.data.groupModelsByProvider
import dev.picarry.app.data.thinkingLabel
import dev.picarry.app.data.withCurrentModel
import dev.picarry.app.ui.components.ButtonKind
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.CarrySheetGrabber
import dev.picarry.app.ui.components.CarrySwitch
import dev.picarry.app.ui.components.CarrySheetHeader
import dev.picarry.app.ui.components.DividerLine
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.Pill
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.components.pressClickable
import dev.picarry.app.ui.components.riskColor
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Ink
import dev.picarry.app.ui.theme.Inter
import dev.picarry.app.ui.theme.JetBrainsMono
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.PanelHigh
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.Surface2
import dev.picarry.app.ui.theme.Surface3
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 流式文本的 Markdown 渲染间隔。
 *
 * 每个 delta 都全量 `markwon.setMarkdown` 是 O(n²)；长回复（代码块/表格）会明显掉帧。
 * 80ms 与 bridge 侧的 delta 合并节奏（50–80ms）对齐：再快也不会有新内容，再慢就开始“打字卡顿”。
 */
private const val STREAM_RENDER_INTERVAL_MS = 80L

/** 待发图片的上传项：选完即上传，发送时只带 blobId。 */
internal data class OutgoingImage(
    val id: String,
    val mimeType: String,
    val bytes: ByteArray,
    val size: Long,
    val blobId: String? = null,
    val uploading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

private val LocalImageBytes = compositionLocalOf<suspend (ImagePart) -> ByteArray?> { { null } }

// 与协议上限保持一致，避免 UI 里散落魔法数字（B-46）。
private const val MAX_IMAGES_PER_MESSAGE = 10
private const val MAX_IMAGE_MB = 20

/** 图片占位文案统一用 KB/MB，避免不足 1 MB 显示成 "0 MB"（R-06/R-15）。 */
private fun formatImageSize(size: Long): String =
    if (size >= 1024L * 1024L) "${size / (1024L * 1024L)} MB"
    else "${(size + 1023L) / 1024L} KB"

private fun pendingDir(context: android.content.Context, nodeId: String, sessionId: String): File {
    fun safe(v: String) = v.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    return File(context.cacheDir, "pending/${safe(nodeId)}_${safe(sessionId)}")
}

/**
 * 旋转/进程重建后恢复待发图片。
 *
 * 已上传成功的项也要恢复磁盘上的小图：只留 blobId 会让缩略图变成灰色占位
 * （BUG-007）。原图字节此时早已丢弃，`<id>.bin` 里是压缩后的缩略图。
 */
private fun loadPendingImages(context: android.content.Context, nodeId: String, sessionId: String): List<OutgoingImage> =
    loadPendingImagesFrom(pendingDir(context, nodeId, sessionId))

/** 从目录恢复待发图；抽出来便于单测 4 列格式与旧格式兼容（B-48/N-12）。 */
internal fun loadPendingImagesFrom(dir: File): List<OutgoingImage> {
    val index = File(dir, "index.txt")
    if (!index.isFile) return emptyList()
    return index.readLines().mapNotNull { line ->
        val p = line.split('\t')
        // 只持久化 4 列：error 可能含 tab/换行，写进 index 会破坏下一行解析（N-12）。
        if (p.size < 4) return@mapNotNull null
        val id = p[0]
        val mime = p[1]
        val blobId = p[2].ifBlank { null }
        val size = p[3].toLongOrNull() ?: return@mapNotNull null
        // 已上传项也有小图；没有文件时才退回“只有 blobId”。
        val bytes = File(dir, "$id.bin").takeIf { it.isFile }?.readBytes()
        if (blobId == null && bytes == null) return@mapNotNull null
        OutgoingImage(
            id = id, mimeType = mime, bytes = bytes ?: ByteArray(0), size = size,
            blobId = blobId, uploading = false, progress = if (blobId != null) 1f else 0f,
        )
    }
}

private fun savePendingImages(context: android.content.Context, nodeId: String, sessionId: String, items: List<OutgoingImage>) =
    savePendingImagesTo(pendingDir(context, nodeId, sessionId), items)

internal fun savePendingImagesTo(dir: File, items: List<OutgoingImage>) {
    if (items.isEmpty()) {
        dir.deleteRecursively()
        return
    }
    dir.mkdirs()
    val index = StringBuilder()
    items.forEach { item ->
        // 已上传项也要写盘：里面是压缩后的小图，用于旋转/重建后仍能看到缩略图（BUG-007）。
        if (item.bytes.isNotEmpty()) {
            val f = File(dir, item.id + ".bin")
            if (!f.isFile || f.length() != item.bytes.size.toLong()) f.writeBytes(item.bytes)
        } else {
            File(dir, item.id + ".bin").delete()
        }
        index.append(item.id).append('\t').append(item.mimeType).append('\t')
            .append(item.blobId.orEmpty()).append('\t').append(item.size).append('\n')
    }
    File(dir, "index.txt").writeText(index.toString())
    val keep = items.mapTo(HashSet()) { it.id + ".bin" }
    dir.listFiles()?.forEach { f -> if (f.name.endsWith(".bin") && f.name !in keep) f.delete() }
}

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
fun ChatScreen(
    repo: CarryRepository,
    nodeId: String,
    sessionId: String,
    onBack: () -> Unit,
    onMigrated: (String) -> Unit = { onBack() },
) {
    val nodes by repo.nodes.collectAsState()
    val sessions by repo.sessions.collectAsState()
    val allMessages by repo.messages.collectAsState()
    val approvals by repo.approvals.collectAsState()
    val migrated by repo.migratedSessions.collectAsState()
    val readySessions by repo.ready.collectAsState()
    val openErrors by repo.openErrors.collectAsState()
    val link by repo.link.collectAsState()
    val connInfos by repo.connInfo.collectAsState()
    val catalogs by repo.models.collectAsState()
    val levelCatalogs by repo.thinkingLevels.collectAsState()
    val runInfo by repo.runInfo.collectAsState()
    val node = nodes.firstOrNull { it.id == nodeId }
    val session = sessions.firstOrNull { it.id == sessionId && it.nodeId == nodeId }
    val key = repo.sessionKey(nodeId, sessionId)
    val messages = allMessages[key].orEmpty()
    val pending = approvals.filter { it.sessionId == sessionId && it.state == "pending" }
    // 模型列表以节点为准：本地硬编码的价格表会过期，节点返回空时更不该假装有模型。
    val models = catalogs[key].orEmpty()
    // 思考等级同样以节点为准：以前这里兜底成一份硬编码档位表，chips 看起来全都可点，
    // 点了必然 `thinking.set` 失败。现在把空表如实传下去，由选择器显示「不可用 + 重试」。
    val levels = levelCatalogs[key].orEmpty()
    val run = runInfo[key]
    val runModel = run?.get("model") as? kotlinx.serialization.json.JsonObject
    val currentProvider = runModel?.get("provider")?.jsonPrimitive?.contentOrNull.orEmpty()
    val currentModelId = runModel?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
    // 复合键：不同 provider 可能有同名 id，只比 id 会误勾选。老旧节点没有 provider 时退化为只比 id。
    val currentKey = if (currentProvider.isBlank()) "" else "$currentProvider|$currentModelId"
    fun isCurrent(m: ModelOption) = if (currentKey.isBlank()) m.id == currentModelId else m.key == currentKey
    val currentName = models.firstOrNull(::isCurrent)?.name ?: currentModelId
    val currentThinking = (run?.get("thinkingLevel") ?: run?.get("thinking"))?.jsonPrimitive?.contentOrNull ?: "high"
    // 每轮总结走 run.summary（权威）与历史消息推导（冷数据）两条路，合成后按消息 id 索引。
    val summaries by repo.runSummaries.collectAsState()
    val turns = remember(messages, summaries) { computeTurns(messages, summaries) }
    val turnByAnchor = remember(turns) { turns.associateBy { it.anchorMessageId } }
    // 轨迹面板也是「用户正在看的弹层」：只存锚点消息 id 而不是 Turn 本身（不可序列化），
    // 重建后从 turns 里找回；该轮已被列表淘汰时自然回退成关闭，语义正确。
    var openTurnAnchor by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    val openTurn = remember(openTurnAnchor, turns) { turns.firstOrNull { it.anchorMessageId == openTurnAnchor } }

    // 草稿跨旋转保留；fork 迁移换路由时由仓库按旧→新会话复制。
    var text by rememberSaveable(sessionId) { mutableStateOf(repo.draftFor(nodeId, sessionId)) }
    var mode by rememberSaveable { mutableStateOf("auto") }
    // 流式操作模式只对当前这次运行有效；运行结束后回到 auto，避免下一条普通消息误用 steer/queue。
    LaunchedEffect(session?.isRunning) {
        if (session?.isRunning != true) mode = "auto"
    }
    // 两个 sheet 是用户正在看的弹层，旋转后重建等于把刚打开的列表吞掉；
    // 下拉菜单（showMenu）属于瞬时交互，重建后关掉更符合预期，故仍用 remember。
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showCommands by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    // 旋转后 sheet 保持打开并自动重新加载，不能直接消失（B-40）。
    var showEntries by rememberSaveable { mutableStateOf(false) }
    var renameText by remember(session?.name) { mutableStateOf(session?.name.orEmpty()) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var entriesPage by remember { mutableStateOf<dev.picarry.app.data.EntriesPage?>(null) }
    var entriesLoading by remember { mutableStateOf(false) }
    var entriesError by remember { mutableStateOf<String?>(null) }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var forking by remember { mutableStateOf(false) }
    // 用户主动关掉「已迁移」横幅后，旋转屏幕不能让它再冒出来（BUG-023）。
    var migrationNotice by rememberSaveable { mutableStateOf(repo.isMigrationTarget(nodeId, sessionId)) }
    // 弹窗只记录审批 id：旋转后按 id 从仓库状态恢复，审批被超时/其它设备解决时对话框自然消失。
    var openApprovalId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    // 待发图片：选完立即分片上传，发送只带 sha/大小；上传/重试都在本页完成。
    // 旋转/进程重建后从缓存目录恢复，不能只靠 remember（B-09）。
    var outgoing by remember(sessionId) { mutableStateOf<List<OutgoingImage>>(emptyList()) }
    var pendingRestored by remember(sessionId) { mutableStateOf(false) }
    // blob.put 要求会话已打开；ready 只有真实快照落地后才为真，
    // 不能用“openSession 已调用”冒充（R-12）。
    val sessionOpened = key in readySessions
    // 上传过程中被移除的图片：完成回调到达时已经不在 outgoing 里，
    // 必须记住并在这里补一次 release（R-13）。
    val removedImageIds = remember(sessionId) { mutableSetOf<String>() }
    var picking by remember { mutableStateOf(false) }
    var usageBytes by remember { mutableStateOf(0L) }
    var usageLimit by remember { mutableStateOf(100L * 1024 * 1024) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var failedSend by remember { mutableStateOf<SendFailure?>(null) }
    val sending = messages.any { it.id.startsWith("local-") }
    val latestText by rememberUpdatedState(text)
    var sentTick by remember { mutableStateOf(0) }
    // 进入会话时先强跟一次底部（历史快照到达后列表本就该停在最新），之后才看是否接近底部。
    // 必须跨旋转保留：复位成 null 会让「首次进入」的强制滚动条件重新成立，把用户上滑阅读的位置拽回底部。
    var followedSession by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    // 「用户是否想跟着最新内容」的粘性开关：旋转后 nearBottom 会因为列表还没布局而短暂为 true，
    // 直接拿它当滚动条件会无条件滚到底，所以把结论单独存成可保存状态。
    var stickBottom by rememberSaveable { mutableStateOf(true) }
    val openApproval = openApprovalId?.let { id -> approvals.firstOrNull { it.id == id && it.state == "pending" } }
    val context = LocalContext.current
    // 服务端持久化了迁移标记；重启/换设备后打开旧会话也必须只读（B-14）。
    val readOnly = migrated.contains(key) || session?.writable == false
    // 打开失败不能只剩空白：显示原因并提供重试（B-07）。
    val openError = openErrors[key]
    // 待发图片落盘恢复/保存：只存尚未上传成功的原图与 blobId 元数据。
    LaunchedEffect(sessionId) {
        outgoing = withContext(Dispatchers.IO) { loadPendingImages(context, nodeId, sessionId) }
        pendingRestored = true
    }
    LaunchedEffect(pendingRestored, outgoing.map { it.id + ":" + (it.blobId ?: "") + ":" + (it.error ?: "") }) {
        if (pendingRestored) {
            withContext(Dispatchers.IO) { savePendingImages(context, nodeId, sessionId, outgoing) }
        }
    }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val menuSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val modelSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val commandSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val renameSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entriesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val turnSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // 只有接近底部才跟随新内容；用户上滑阅读历史时不再被拽回底部。
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    // 空布局时 nearBottom 恒为 true（见上），若此时回写会把用户的上滑阅读状态冲掉，
    // 所以只在列表真的有 item 之后才把 nearBottom 的结论同步进 stickBottom。
    // 用 snapshotFlow 而不是把这些值当 LaunchedEffect 的 key：layoutInfo 每帧都会变，
    // 在组合期直接读会拉高重组频率，这里只在它真变化时收一次回调。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount to nearBottom }
            .collect { (count, bottom) -> if (count > 0) stickBottom = bottom }
    }

    fun updateImage(id: String, transform: (OutgoingImage) -> OutgoingImage) {
        outgoing = outgoing.map { if (it.id == id) transform(it) else it }
    }

    fun uploadOne(item: OutgoingImage) {
        if (outgoing.none { it.id == item.id }) return
        updateImage(item.id) { it.copy(uploading = true, error = null, progress = 0f) }
        scope.launch {
            // 成功后要压一张小图：解码/压缩必须离开主线程，所以和上传放在同一个
            // runCatching 里完成，onSuccess 只做状态更新（BUG-007）。
            runCatching {
                val sha = repo.uploadBlob(nodeId, sessionId, item.bytes, item.mimeType) { sent, total ->
                    // 回调来自 IO 线程：mutableState 必须在主线程写，否则会丢图（B-17）。
                    scope.launch(Dispatchers.Main) {
                        updateImage(item.id) {
                            it.copy(progress = maxOf(it.progress, if (total <= 0) 0f else sent.toFloat() / total))
                        }
                    }
                }
                sha to (withContext(Dispatchers.IO) { thumbnailBytes(item.bytes) } ?: ByteArray(0))
            }.onSuccess { (sha, thumb) ->
                if (removedImageIds.remove(item.id)) {
                    // 上传期间用户已移除：若没有别的待发图复用同一 blob，释放服务端引用。
                    if (outgoing.none { it.id != item.id && it.blobId == sha }) {
                        runCatching { repo.releaseBlob(nodeId, sessionId, sha) }.onSuccess { usageBytes = it }
                    }
                    return@onSuccess
                }
                // 上传完只丢原图字节，留下 ~20KB 的小图：缩略图始终可见（B-15 + BUG-007）。
                updateImage(item.id) { it.copy(blobId = sha, uploading = false, progress = 1f, error = null, bytes = thumb) }
                // 上传完成时服务端就登记了会话引用，用量以服务端为准，避免本地重复累加。
                runCatching { repo.blobUsage(nodeId, sessionId) }.onSuccess {
                    usageBytes = it.first
                    usageLimit = it.second
                }
            }.onFailure { e ->
                removedImageIds.remove(item.id)
                updateImage(item.id) {
                    it.copy(uploading = false, error = e.message ?: "上传失败")
                }
                imageError = "图片上传失败：" + (e.message ?: "网络错误") + "，可重试或移除"
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty() || picking) return@rememberLauncherForActivityResult
        // 上传要求会话已打开；冷启动恢复待发图时不能抢在 openSession 前面（N-08）。
        if (!sessionOpened) {
            imageError = "会话尚未打开，请稍后再选图片"
            return@rememberLauncherForActivityResult
        }
        // 连点两次会在异步 prepare 之前各自通过上限检查（B-41）。
        picking = true
        imageError = null
        scope.launch {
            try {
                if (uris.size + outgoing.size > ImagePreparer.MAX_IMAGES) {
                    imageError = "单条消息最多 " + ImagePreparer.MAX_IMAGES + " 张图片，当前已选 " + outgoing.size + " 张"
                    return@launch
                }
                val (used, limit) = try {
                    repo.blobUsage(nodeId, sessionId)
                } catch (e: Exception) {
                    imageError = "无法获取图片额度：" + (e.message ?: "网络错误")
                    return@launch
                }
                usageBytes = used
                usageLimit = limit
                // 逐张处理、逐张释放：不要先把 10 张 20MB 的图全物化（B-15）。
                for (uri in uris) {
                    if (outgoing.size >= ImagePreparer.MAX_IMAGES) break
                    val p = runCatching { withContext(Dispatchers.Default) { ImagePreparer.prepare(context, uri) } }
                        .onFailure { imageError = "图片处理失败：" + (it.message ?: "未知错误") }
                        .getOrNull() ?: continue
                    // 批次内前面的图可能已上传成功并刷新 usageBytes；用两者较大值，
                    // 避免拿批次开始时的旧用量做预算而漏计（N-11）。
                    val currentUsed = maxOf(used, usageBytes)
                    if (currentUsed + p.bytes.size + outgoing.filter { it.blobId == null }.sumOf { it.size } > limit) {
                        imageError = "会话图片空间不足：已用 " + (currentUsed / 1024 / 1024) +
                            " MB，上限 " + (limit / 1024 / 1024) + " MB"
                        break
                    }
                    val item = OutgoingImage(
                        id = UUID.randomUUID().toString(),
                        mimeType = p.mimeType,
                        bytes = p.bytes,
                        size = p.bytes.size.toLong(),
                    )
                    outgoing = outgoing + item
                    uploadOne(item)
                }
            } finally {
                picking = false
            }
        }
    }

    // 前台且正看着这条会话时不发审批通知（详见 CarryRepository.notifyApproval）。
    DisposableEffect(sessionId) {
        repo.setVisibleSession(sessionId)
        onDispose {
            // 只清自己：新页面可能已经把自己设为可见（B-39）。
            repo.clearVisibleSession(sessionId)
            repo.saveDraft(nodeId, sessionId, latestText)
        }
    }

    LaunchedEffect(nodeId, sessionId) {
        repo.sendFailures.collect { f ->
            if (f.nodeId == nodeId && f.sessionId == sessionId) failedSend = f
        }
    }
    LaunchedEffect(nodeId, sessionId) {
        repo.openSession(nodeId, sessionId)
    }
    LaunchedEffect(nodeId, sessionId) {
        runCatching { repo.blobUsage(nodeId, sessionId) }.onSuccess {
            usageBytes = it.first
            usageLimit = it.second
        }
    }
    // 恢复出来的未上传图片自动续传（B-09）。
    LaunchedEffect(pendingRestored, sessionOpened) {
        if (pendingRestored && sessionOpened) outgoing.filter { it.blobId == null }.forEach(::uploadOne)
    }
    // 另一个设备发起 fork 时服务端会广播 session.migrated：把当前页替换到新会话，草稿跟着走。
    LaunchedEffect(nodeId, sessionId) {
        repo.migrations.collect { m ->
            if (m.nodeId == nodeId && m.fromSessionId == sessionId) {
                repo.saveDraft(nodeId, sessionId, latestText)
                repo.copyDraft(nodeId, sessionId, m.toSessionId)
                // 事件是唯一导航来源：doFork 不再自己跳，避免双路径竞态（R-16）。
                Toast.makeText(context, "已迁移到 fork 会话，草稿已保留", Toast.LENGTH_LONG).show()
                onMigrated(m.toSessionId)
            }
        }
    }

    fun openEntries() {
        showMenu = false
        showEntries = true
        entriesLoading = true
        entriesError = null
        scope.launch {
            runCatching { repo.loadEntries(nodeId, sessionId) }
                .onSuccess {
                    entriesPage = it
                    // 旋转/重新加载时保留用户已选的分支点；只有选中项消失才回退 leaf（B-40）。
                    selectedEntryId = selectedEntryId
                        ?.takeIf { id -> it.entries.any { e -> e.id == id } }
                        ?: it.leafId ?: it.entries.lastOrNull()?.id
                }
                .onFailure { entriesError = it.message ?: "加载失败" }
            entriesLoading = false
        }
    }

    LaunchedEffect(showEntries) {
        if (showEntries && entriesPage == null && !entriesLoading) openEntries()
    }

    fun doFork() {
        val entry = selectedEntryId ?: return
        if (forking) return
        forking = true
        entriesError = null
        scope.launch {
            runCatching { repo.forkSession(nodeId, sessionId, entry) }
                .onSuccess { newId ->
                    repo.saveDraft(nodeId, sessionId, latestText)
                    repo.copyDraft(nodeId, sessionId, newId)
                    showEntries = false
                    // 导航统一由 session.migrated 事件完成；这里只负责草稿与关闭面板。
                }
                .onFailure {
                    entriesError = "分叉失败：" + (it.message ?: "未知错误")
                    forking = false
                }
        }
    }

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.thinking?.length,
        pending.size,
    ) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (stickBottom || followedSession != sessionId) {
            runCatching { listState.animateScrollToItem(messages.size + pending.size) }
            followedSession = sessionId
        }
    }
    // 自己发送时无论当前滚动位置都回到底部，否则看不到刚发出的消息。
    LaunchedEffect(sentTick) {
        if (sentTick == 0) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(messages.size + pending.size) }
    }

    CompositionLocalProvider(LocalImageBytes provides { part ->
        runCatching {
            if (part.isBlob) repo.blobBytes(nodeId, sessionId, part.blobId!!)
            else Base64.decode(part.data, Base64.DEFAULT)
        }.getOrNull()
    }) {
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CarryIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, plain = true, size = 34)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    StatusDot(if (session?.isRunning == true) Amber else if (link[nodeId] == LinkState.Online) Mint else TextMuted, 4)
                    Text(
                        buildString {
                            append(node?.name ?: nodeId.take(8))
                            val detail = connInfos[nodeId]?.detail
                            if (!detail.isNullOrBlank()) append(" · ").append(detail)
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    session?.title ?: "会话",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CarryIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { showMenu = true }, plain = true, size = 34)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatMetaChip(
                currentName.ifBlank { "默认模型" },
                VioletSoft,
                icon = Icons.Outlined.AutoAwesome,
                onClick = { showModels = true },
            )
            ChatMetaChip("thinking · $currentThinking", Cyan, onClick = { showModels = true })
            ChatMetaChip(displayName(session?.cwd.orEmpty()).ifBlank { "~" }, TextMuted)
        }

        if (migrationNotice) {
            NoticeBanner("已迁移到 fork 会话 · 旧会话已停止写入", Amber, onClose = { migrationNotice = false })
        }

        if (session?.writable == false && !migrationNotice) {
            NoticeBanner(
                // 只读原因必须按真实来源判断：这里已经在 `writable == false` 分支内，
                // 再用 readOnly（含 writable 条件）判断会让「本机终端正在写」永不出现（BUG-025）。
                if (migrated.contains(key)) "该会话已迁移，当前为只读" else "本机终端正在写这个会话，当前为只读",
                Amber,
            )
        }
        if (openError != null) {
            NoticeBanner(
                "打开会话失败：$openError",
                Rose,
                action = "重试" to { scope.launch { repo.openSession(nodeId, sessionId) } },
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                when (msg.role) {
                    "user" -> UserBubble(msg)
                    "tool" -> msg.tool?.let { ToolCard(it) }
                    "system" -> SystemBubble(msg)
                    else -> AssistantBlock(msg)
                }
                // 总结行插在本轮最后一条消息之后（任意角色）：用户说话后也能一眼看到刚才那轮花了多少。
                turnByAnchor[msg.id]?.let { turn ->
                    TurnSummaryRow(turn = turn, onClick = { openTurnAnchor = turn.anchorMessageId })
                }
            }
            items(pending, key = { "ap-${it.id}" }) { approval ->
                ApprovalCard(approval) { openApprovalId = approval.id }
            }
            if (messages.isEmpty() && pending.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(alpha = 0.10f)).border(0.8.dp, Violet.copy(alpha = 0.25f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Terminal, null, tint = VioletSoft, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("会话已连接", style = MaterialTheme.typography.titleSmall, color = TextSecondary)
                        Spacer(Modifier.height(2.dp))
                        Text("在下方输入消息，pi 会在电脑上执行", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                    }
                }
            }
        }

        if (sending) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("发送中…", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            }
        }
        failedSend?.let { failure ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Rose.copy(alpha = 0.10f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("发送失败", style = MaterialTheme.typography.labelMedium, color = Rose)
                    Text(
                        failure.error,
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    repo.sendPrompt(failure.nodeId, failure.sessionId, failure.text, failure.mode, failure.images)
                    failedSend = null
                }) { Text("重试", color = Cyan) }
                Icon(
                    Icons.Outlined.Close, "忽略", tint = TextMuted,
                    modifier = Modifier.size(15.dp).clickable { failedSend = null },
                )
            }
        }
        Composer(
            text = text,
            onText = { text = it },
            mode = mode,
            onMode = { mode = it },
            running = session?.isRunning == true,
            readOnly = readOnly,
            pendingImages = outgoing,
            usageBytes = usageBytes,
            usageLimit = usageLimit,
            imageError = imageError,
            onPickImage = { imagePicker.launch("image/*") },
            onRemoveImage = { id ->
                val removed = outgoing.firstOrNull { it.id == id }
                if (removed?.blobId == null) removedImageIds.add(id)
                outgoing = outgoing.filterNot { it.id == id }
                // 已上传的图移除后立即释放服务端额度（B-16）。
                val sha = removed?.blobId
                if (sha != null && outgoing.none { it.blobId == sha }) {
                    scope.launch {
                        runCatching { repo.releaseBlob(nodeId, sessionId, sha) }
                            .onSuccess { usageBytes = it }
                            .onFailure { imageError = "释放图片空间失败：" + (it.message ?: "网络错误") }
                    }
                }
            },
            onRetryImage = { id -> outgoing.firstOrNull { it.id == id }?.let(::uploadOne) },
            onCommands = { showCommands = true },
            onModels = { showModels = true },
            onSend = {
                val ready = outgoing.all { it.blobId != null && !it.uploading && it.error == null }
                if (outgoing.isNotEmpty() && !ready) {
                    imageError = "请等待图片上传完成，或移除上传失败的图片"
                } else {
                    repo.sendPrompt(
                        nodeId,
                        sessionId,
                        text.trim(),
                        mode,
                        outgoing.map { ImagePart(it.mimeType, blobId = it.blobId, size = it.size) },
                    )
                    text = ""
                    failedSend = null
                    // 上一次的图片错误与这次发送无关，不清掉会一直挂在输入框上方（BUG-024）。
                    imageError = null
                    repo.saveDraft(nodeId, sessionId, "")
                    outgoing = emptyList()
                    sentTick += 1
                }
            },
            onStop = { repo.abort(nodeId, sessionId) },
        )
    }
    }

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            sheetState = menuSheetState,
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            scrimColor = Color.Black.copy(alpha = 0.66f),
            dragHandle = null,
        ) {
            SessionMenuSheet(
                name = session?.title ?: "会话",
                readOnly = readOnly,
                onClose = { showMenu = false },
                onRename = {
                    showMenu = false
                    renameText = session?.name.orEmpty()
                    renameError = null
                    showRename = true
                },
                onCopyId = {
                    clipboard.setText(AnnotatedString(sessionId))
                    Toast.makeText(context, "会话 ID 已复制", Toast.LENGTH_SHORT).show()
                    showMenu = false
                },
                onEntries = { openEntries() },
                onCompact = {
                    showMenu = false
                    repo.compact(nodeId, sessionId)
                },
                onCloseSession = {
                    showMenu = false
                    repo.closeSession(nodeId, sessionId) { onBack() }
                },
            )
        }
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }, sheetState = modelSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            ModelSheet(
                repo = repo,
                nodeId = nodeId,
                sessionId = sessionId,
                models = models,
                levels = levels,
                currentProvider = currentProvider,
                currentModelId = currentModelId,
                currentThinking = currentThinking,
                onClose = { showModels = false },
            )
        }
    }
    if (showCommands) {
        ModalBottomSheet(onDismissRequest = { showCommands = false }, sheetState = commandSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            CommandSheet(onClose = { showCommands = false }) { cmd ->
                showCommands = false
                when (cmd.name) {
                    "model" -> showModels = true
                    "compact" -> repo.compact(nodeId, sessionId)
                    else -> text = "/${cmd.name} "
                }
            }
        }
    }
    if (showRename) {
        ModalBottomSheet(
            onDismissRequest = { showRename = false },
            sheetState = renameSheetState,
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            scrimColor = Color.Black.copy(alpha = 0.66f),
            dragHandle = null,
        ) {
            Column(Modifier.fillMaxWidth()) {
                CarrySheetGrabber()
                CarrySheetHeader(
                    title = "重命名会话",
                    subtitle = "只影响列表与对话标题 · 不改变 .jsonl 路径",
                    onClose = { showRename = false },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
                    MonoLabel("会话名称", color = TextMuted)
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface2)
                            .border(0.8.dp, LineStrong, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = renameText,
                            onValueChange = { renameText = it.take(60); renameError = null },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = Inter, lineHeight = 20.sp),
                            cursorBrush = SolidColor(Violet),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        renameError ?: "留空则恢复默认名称；名称最长 60 个字符。",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (renameError != null) Rose else TextMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CarryActionButton(
                            "取消", { showRename = false },
                            modifier = Modifier.weight(1f), kind = ButtonKind.Ghost,
                        )
                        CarryActionButton(
                            "保存", {
                                repo.renameSession(nodeId, sessionId, renameText) { error ->
                                    if (error == null) showRename = false else renameError = error
                                }
                            },
                            modifier = Modifier.weight(1.3f), kind = ButtonKind.Primary,
                        )
                    }
                }
            }
        }
    }

    if (showEntries) {
        ModalBottomSheet(
            onDismissRequest = { if (!forking) showEntries = false },
            sheetState = entriesSheetState,
            containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            scrimColor = Color.Black.copy(alpha = 0.66f),
            dragHandle = null,
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
                CarrySheetGrabber()
                val page = entriesPage
                CarrySheetHeader(
                    title = "分支与 entries",
                    subtitle = if (page == null) "从任意 entry 分叉会复制到该点，原会话保持不变。"
                    else "leaf ${page.leafId?.take(8) ?: "—"} · 共 ${page.entries.size} 条",
                    onClose = { if (!forking) showEntries = false },
                )
                when {
                    entriesLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Violet, strokeWidth = 2.dp)
                    }
                    entriesError != null -> Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp)) {
                        Text(entriesError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Rose)
                        Spacer(Modifier.height(8.dp))
                        CarryActionButton("重试", { openEntries() }, kind = ButtonKind.Ghost)
                    }
                    page != null -> {
                        val children = remember(page) { page.entries.groupingBy { it.parentId }.eachCount() }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            Box(
                                Modifier.padding(start = 32.dp, top = 8.dp, bottom = 8.dp)
                                    .width(1.dp).fillMaxHeight().background(Line),
                            )
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(page.entries.asReversed(), key = { it.id }) { entry ->
                                    val selected = entry.id == selectedEntryId
                                    val indent = (entry.depth * 13).dp
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(start = indent)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(if (selected) Violet.copy(alpha = 0.10f) else Color.Transparent)
                                            .border(
                                                0.7.dp,
                                                if (selected) Violet.copy(alpha = 0.30f) else Color.Transparent,
                                                RoundedCornerShape(11.dp),
                                            )
                                            .clickable { selectedEntryId = entry.id }
                                            .padding(vertical = 8.dp, horizontal = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        val role = if (entry.role.isBlank()) entry.type else entry.role
                                        Box(
                                            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                                                .background(if (selected) Violet.copy(alpha = 0.18f) else Surface2)
                                                .border(0.7.dp, if (selected) Violet.copy(alpha = 0.35f) else Line, RoundedCornerShape(9.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                when {
                                                    role.contains("user", true) -> "U"
                                                    role.contains("tool", true) -> "T"
                                                    else -> "A"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = if (selected) VioletSoft else TextMuted,
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                entry.preview.ifBlank { role },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (selected) TextPrimary else TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                entry.id.take(8) + " · " + role,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = TextMuted,
                                                maxLines = 1,
                                            )
                                        }
                                        if ((children[entry.id] ?: 0) > 1) {
                                            Tag("分支", Amber)
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().background(Surface2.copy(alpha = 0.7f))
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "复制到所选 entry，不移动原会话。",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            CarryActionButton(
                                if (forking) "正在分叉…" else "从 ${selectedEntryId?.take(8) ?: "…"} 分叉",
                                { doFork() },
                                kind = ButtonKind.Primary,
                                enabled = selectedEntryId != null && !forking,
                                height = 38.dp,
                            )
                        }
                    }
                }
            }
        }
    }

    openTurn?.let { turn ->
        ModalBottomSheet(onDismissRequest = { openTurnAnchor = null }, sheetState = turnSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            TurnDetailSheet(turn) { openTurnAnchor = null }
        }
    }
    openApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            onDismiss = { openApprovalId = null },
            onDecide = { decision, remember ->
                repo.resolveApproval(nodeId, approval.id, decision, remember)
                openApprovalId = null
            },
            onExtend = { repo.extendApproval(nodeId, approval.id) },
        )
    }
}

@Composable
private fun ChatMetaChip(
    label: String,
    color: Color,
    running: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (onClick != null) Color.White.copy(alpha = 0.035f) else color.copy(alpha = 0.10f))
            .border(0.7.dp, if (onClick != null) Line else color.copy(alpha = 0.22f), shape)
            .then(if (onClick != null) Modifier.pressClickable(onClick = onClick, scale = 0.96f) else Modifier)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (running) {
            CircularProgressIndicator(color = color, strokeWidth = 1.2.dp, modifier = Modifier.size(9.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = if (onClick != null) TextSecondary else color,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    color: Color,
    action: Pair<String, () -> Unit>? = null,
    onClose: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(shape)
            .background(color.copy(alpha = 0.09f))
            .border(0.7.dp, color.copy(alpha = 0.28f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action.first,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = color,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = action.second).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        if (onClose != null) {
            Icon(
                Icons.Outlined.Close,
                "关闭提示",
                tint = color.copy(alpha = 0.75f),
                modifier = Modifier.size(14.dp).clickable(onClick = onClose),
            )
        }
    }
}

@Composable
private fun SessionMenuSheet(
    name: String,
    readOnly: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onCopyId: () -> Unit,
    onEntries: () -> Unit,
    onCompact: () -> Unit,
    onCloseSession: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "会话操作", subtitle = name, onClose = onClose)
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            ChatMenuRow(Icons.Outlined.Edit, "重命名会话", "只改显示名，不改写记录", enabled = !readOnly, onClick = onRename)
            ChatMenuRow(Icons.Outlined.ContentCopy, "复制会话 ID", "用于脚本或反馈问题", onClick = onCopyId)
            ChatMenuRow(Icons.Outlined.AccountTree, "分支与 entries", "查看消息树，从任意节点分叉", enabled = !readOnly, onClick = onEntries)
            ChatMenuRow(Icons.Outlined.Tune, "压缩上下文", "保留摘要，释放上下文窗口", enabled = !readOnly, onClick = onCompact)
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Line)
            Spacer(Modifier.height(6.dp))
            ChatMenuRow(Icons.Outlined.Close, "关闭会话", "不影响电脑上的 pi 记录", danger = true, onClick = onCloseSession)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> TextMuted.copy(alpha = 0.45f)
        danger -> Rose
        else -> TextSecondary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.pressClickable(onClick = onClick, scale = 0.985f) else Modifier)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = if (danger) 0.12f else 0.06f))
                .border(0.7.dp, tint.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium), color = if (danger) Rose else if (enabled) TextPrimary else TextMuted)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (msg.text.isNotBlank()) {
            Box(
                Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp))
                    .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.30f), Violet.copy(alpha = 0.12f))))
                    .border(0.7.dp, Violet.copy(alpha = 0.30f), RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Column {
                    Text(msg.text, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 21.6.sp), color = TextPrimary)
                    val stamp = msg.tsMs
                    if (stamp > 0) {
                        val clock = remember(stamp) {
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))
                        }
                        Text(
                            clock,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 8.5.sp),
                            color = VioletSoft.copy(alpha = 0.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.align(Alignment.End).padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        if (msg.images.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ImageStrip(msg.images, Modifier.widthIn(max = 320.dp))
        }
    }
}

@Composable
private fun AssistantBlock(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        if (msg.streaming) {
            MonoLabel("pi · 生成中", color = Violet)
            Spacer(Modifier.height(5.dp))
        }
        if (msg.thinking.isNotBlank()) {
            var expanded by remember(msg.id) { mutableStateOf(false) }
            val dashedLine = LineStrong
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = dashedLine,
                            start = Offset(stroke / 2, 0f),
                            end = Offset(stroke / 2, size.height),
                            strokeWidth = stroke,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    .clickable { expanded = !expanded }
                    .padding(start = 11.dp, top = 2.dp, bottom = 2.dp),
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
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = TextMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (msg.text.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            MarkdownBody(msg.text, streaming = msg.streaming)
        }
        if (msg.images.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ImageStrip(msg.images)
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
            .clip(RoundedCornerShape(13.dp))
            .background(Panel.copy(alpha = 0.55f))
            .border(0.7.dp, if (tool.isError) Rose.copy(alpha = 0.4f) else Line, RoundedCornerShape(13.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Cyan.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Terminal, null, tint = if (tool.isError) Rose else Cyan, modifier = Modifier.size(14.dp))
            }
            Text(tool.name, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = TextPrimary)
            if (tool.durationMs > 0) MonoLabel("${tool.durationMs}ms", color = TextMuted)
            Spacer(Modifier.weight(1f))
            when (tool.status) {
                "running" -> CircularProgressIndicator(Modifier.size(12.dp), color = Cyan, strokeWidth = 1.5.dp)
                "error" -> Text("失败", style = MaterialTheme.typography.labelSmall, color = Rose)
                else -> Icon(Icons.Outlined.Check, null, tint = Mint, modifier = Modifier.size(14.dp))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = TextMuted, modifier = Modifier.size(15.dp))
        }
        // 默认只显示一行命令摘要；参数与结果都要点开，避免长输出刷屏。
        val summary = summarizeToolArgs(tool.args)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                summary.take(if (expanded) 4000 else 160),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
                maxLines = if (expanded) 80 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tool.images.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ImageStrip(tool.images, Modifier.clickable(enabled = false) {})
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                if (tool.args.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    MonoLabel("参数", color = TextMuted)
                    Text(
                        tool.args.take(8000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = TextSecondary,
                    )
                }
                if (tool.result.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    MonoLabel(if (tool.isError) "错误输出" else "结果", color = if (tool.isError) Rose else TextMuted)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.28f)).padding(10.dp)) {
                        Text(
                            tool.result.take(20000),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                            color = if (tool.isError) Rose else TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

private fun summarizeToolArgs(args: String): String {
    val trimmed = args.trim()
    if (trimmed.startsWith("{")) {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(trimmed) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        listOf("command", "cmd", "path", "file_path", "pattern", "query", "url").firstNotNullOfOrNull { key ->
            (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }?.let { return it }
    }
    return args.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
}

@Composable
private fun MarkdownBody(text: String, streaming: Boolean = false) {
    val context = LocalContext.current
    val textColor = TextPrimary.toArgb()
    val codeBg = PanelHigh.toArgb()
    val linkColor = Violet.toArgb()
    // 流式期间每个 delta 都会重组，若每次都 markwon.setMarkdown 全量重解析，
    // 长度为 n 的回复是 O(n²) 开销（长代码块/表格时明显掉帧）。
    // 这里把流式渲染节流到 STREAM_RENDER_INTERVAL_MS，非流式（最终消息）立即渲染。
    var shown by remember { mutableStateOf(text) }
    var lastRenderedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(text, streaming) {
        if (!streaming) {
            shown = text
            return@LaunchedEffect
        }
        val nowMs = System.nanoTime() / 1_000_000
        val wait = STREAM_RENDER_INTERVAL_MS - (nowMs - lastRenderedAt)
        if (wait > 0) delay(wait)
        lastRenderedAt = System.nanoTime() / 1_000_000
        shown = text
    }
    val markwon = remember(context, textColor, codeBg, linkColor) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(textColor)
                        .codeBlockTextColor(textColor)
                        .codeBlockBackgroundColor(codeBg)
                        .linkColor(linkColor)
                        .headingBreakHeight(0)
                }
            })
            .build()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.72f)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter_400)
                textSize = 13.5f
                setTextColor(textColor)
                setLinkTextColor(linkColor)
                setPadding(0, 0, 0, 0)
                linksClickable = true
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            // 文本没变就不要全量重解析 Markdown（节流后同一帧可能重复进入 update）。
            val rendered = shown + "\u0000" + textColor + "\u0000" + linkColor
            if (view.tag != rendered) {
                view.tag = rendered
                markwon.setMarkdown(view, shown)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private suspend fun decodeImage(part: ImagePart, loader: suspend (ImagePart) -> ByteArray?): android.graphics.Bitmap? =
    runCatching {
        val bytes = loader(part) ?: return null
        // 原图可能 20MB：先算采样率再解码，避免全分辨率位图把低内存机型打崩（B-03）。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 1600)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

/**
 * 上传成功后保留的小图（约 20 KB）：原图字节可以丢，缩略图必须留到发送前，
 * 否则用户再也无法核对待发的是哪张图（BUG-007）。调用方负责在 IO 线程执行。
 */
private fun thumbnailBytes(bytes: ByteArray, maxSide: Int = 240): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (bitmap == null) {
        null
    } else {
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}.getOrNull()

@Composable
private fun ImageStrip(images: List<ImagePart>, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf<ImagePart?>(null) }
    val loader = LocalImageBytes.current
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEachIndexed { index, part ->
            var retry by remember(part) { mutableStateOf(0) }
            // loaded 区分“还在加载”和“已失败”：两者 bitmap 都是 null，但失败要能重试（N-10）。
            var loaded by remember(part, retry) { mutableStateOf(false) }
            val bitmap by produceState<android.graphics.Bitmap?>(null, part.blobId ?: part.data, retry) {
                value = withContext(Dispatchers.IO) { decodeImage(part, loader) }
                loaded = true
            }
            val image = bitmap
            if (image == null) {
                // 加载中/不可用不能直接消失，否则用户以为图片丢了（B-42）。
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(9.dp)).background(Panel)
                        .border(0.7.dp, Line, RoundedCornerShape(10.dp))
                        .clickable(enabled = loaded && !part.unavailable) { retry += 1 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            part.unavailable && part.size > 0 ->
                                "图片不可用（${formatImageSize(part.size)}）"
                            part.unavailable -> "图片不可用"
                            loaded -> "加载失败，点击重试"
                            else -> "加载中…"
                        },
                        style = MaterialTheme.typography.labelSmall, color = TextMuted,
                    )
                }
            } else {
                androidx.compose.foundation.Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "图片 ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .border(0.7.dp, Line, RoundedCornerShape(9.dp))
                        .clickable { preview = part },
                )
            }
        }
    }
    preview?.let { part ->
        var retry by remember(part) { mutableStateOf(0) }
        var loaded by remember(part, retry) { mutableStateOf(false) }
        val bitmap by produceState<android.graphics.Bitmap?>(null, part.blobId ?: part.data, retry) {
            value = withContext(Dispatchers.IO) { decodeImage(part, loader) }
            loaded = true
        }
        Dialog(onDismissRequest = { preview = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "查看图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                    )
                } ?: Text(
                    when {
                        part.unavailable && part.size > 0 -> "图片不可用（${formatImageSize(part.size)}）"
                        part.unavailable -> "图片不可用"
                        loaded -> "加载失败，点击重试"
                        else -> "加载中…"
                    },
                    color = Color.White,
                    modifier = Modifier
                        .padding(24.dp)
                        .clickable(enabled = loaded && !part.unavailable) { retry += 1 },
                )
                TextButton(onClick = { preview = null }) { Text("关闭", color = Color.White) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: Approval, onClick: () -> Unit) {
    val remaining = rememberCountdown(approval)
    val accent = Rose
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Rose.copy(alpha = 0.12f), Rose.copy(alpha = 0.035f))))
            .border(0.8.dp, Rose.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .drawBehind {
                drawLine(accent.copy(alpha = 0.85f), Offset(1.5.dp.toPx(), 12.dp.toPx()), Offset(1.5.dp.toPx(), size.height - 12.dp.toPx()), 2.dp.toPx())
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(approval.risk, riskColor(approval.risk), filled = true)
            Spacer(Modifier.size(7.dp))
            Text(approval.toolName, style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono), color = TextSecondary)
            Spacer(Modifier.weight(1f))
            MonoLabel(if (remaining <= 0) "已超时" else "${remaining}s", color = if (remaining <= 0) TextMuted else Rose)
        }
        Spacer(Modifier.height(7.dp))
        Text(
            approval.command ?: approval.summary.ifBlank { "高风险操作" },
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono, lineHeight = 17.sp),
            color = TextPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Rose.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth((remaining / 120f).coerceIn(0f, 1f)).fillMaxHeight().background(Rose.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                approval.cwd.ifBlank { approval.path.orEmpty() },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text("审批 →", style = MaterialTheme.typography.labelMedium, color = Rose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalDialog(
    approval: Approval,
    onDismiss: () -> Unit,
    onDecide: (String, Boolean) -> Unit,
    onExtend: () -> Unit,
) {
    var rememberRule by remember(approval.id) { mutableStateOf(false) }
    var remaining by remember(approval.id) { mutableStateOf(approval.remainingSeconds) }
    LaunchedEffect(approval.id, approval.remainingSeconds) {
        remaining = approval.remainingSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    // 服务端到点即超时拒绝：按钮必须一起失效，否则用户以为还能批准。
    val expired = remaining <= 0
    val critical = approval.risk.equals("critical", ignoreCase = true)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        scrimColor = Color.Black.copy(alpha = 0.66f),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            CarrySheetGrabber()
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Rose.copy(alpha = 0.30f), Rose.copy(alpha = 0.10f))))
                        .border(0.8.dp, Rose.copy(alpha = 0.38f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Bolt, null, tint = Rose, modifier = Modifier.size(21.dp)) }
                Column(Modifier.weight(1f)) {
                    Text("需要审批", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        approval.toolName + " · " + displayName(approval.cwd),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (approval.remainingSeconds <= 0) 0f else (remaining.toFloat() / approval.remainingSeconds).coerceIn(0f, 1f) },
                        color = if (expired) TextMuted else Rose,
                        trackColor = Line,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        if (expired) "0" else "$remaining",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (expired) TextMuted else Rose,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(approval.risk, riskColor(approval.risk), filled = true)
                    Tag(approval.category, TextMuted)
                    Spacer(Modifier.weight(1f))
                    MonoLabel(if (expired) "已超时 · 将按拒绝处理" else "批准后立即在本机执行", color = if (expired) TextMuted else Rose)
                }
                Spacer(Modifier.height(11.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .border(0.7.dp, Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("COMMAND", color = TextMuted)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.Lock, null, tint = TextMuted, modifier = Modifier.size(11.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        approval.command ?: approval.summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp),
                        color = TextPrimary,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(9.dp))
                ApprovalKeyValue("CWD", approval.cwd.ifBlank { "—" })
                ApprovalKeyValue("SESSION", approval.sessionId.take(12))
                ApprovalKeyValue("REQUEST", approval.requestedAt)
                Spacer(Modifier.height(9.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2).padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记住此命令", style = MaterialTheme.typography.bodySmall, color = if (critical) TextMuted else TextSecondary)
                        Text(
                            if (critical) "危险命令不允许记住" else "仅记住这一条命令",
                            style = MaterialTheme.typography.labelSmall, color = TextMuted,
                        )
                    }
                    CarrySwitch(checked = rememberRule && !critical, onCheckedChange = { rememberRule = it }, enabled = !critical)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (expired) "已超时：服务端已按拒绝处理，请等待下一次审批请求。"
                    else "本对话都允许：后续普通工具不再询问；rm -rf 等危险命令仍会逐次确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expired) Rose else TextMuted,
                )
                Spacer(Modifier.height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    CarryActionButton(
                        "拒绝", { onDecide("deny", false) },
                        modifier = Modifier.weight(1f), kind = ButtonKind.Ghost, enabled = !expired, height = 44.dp,
                    )
                    CarryActionButton(
                        "延长 120s", { onExtend(); remaining = (remaining + 120).coerceAtMost(300) },
                        modifier = Modifier.weight(1.12f), kind = ButtonKind.Ghost, enabled = !expired && remaining < 300, height = 44.dp,
                    )
                    CarryActionButton(
                        "批准", { onDecide("allow", rememberRule && !critical) },
                        modifier = Modifier.weight(1.35f), kind = ButtonKind.Danger, enabled = !expired, height = 44.dp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "本对话都允许",
                    style = MaterialTheme.typography.labelLarge,
                    color = Cyan,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !expired) { onDecide("allow_always", rememberRule && !critical) }
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ApprovalKeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel(key, color = TextMuted)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
    readOnly: Boolean = false,
    pendingImages: List<OutgoingImage>,
    usageBytes: Long,
    usageLimit: Long,
    imageError: String?,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onRetryImage: (String) -> Unit,
    onCommands: () -> Unit,
    onModels: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    fun mb(v: Long): String = String.format(java.util.Locale.US, "%.1f", v / 1048576.0)
    val canSend = !readOnly && (text.isNotBlank() || pendingImages.isNotEmpty())
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0f), Ink.copy(alpha = 0.96f))))
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
    ) {
        if (running) {
            Row(
                Modifier.fillMaxWidth().height(26.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.height(26.dp).clip(RoundedCornerShape(8.dp)).background(Surface2.copy(alpha = 0.72f)).padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StreamModeButton("插话", mode == "steer", Modifier.width(52.dp)) { onMode("steer") }
                    StreamModeButton("排队", mode == "queue", Modifier.width(52.dp)) { onMode("queue") }
                }
                Spacer(Modifier.weight(1f))
                StreamModeButton("打断", false, Modifier.width(58.dp), danger = true, icon = Icons.Outlined.Stop, onClick = onStop)
            }
            Spacer(Modifier.height(7.dp))
        }
        if (pendingImages.isNotEmpty()) {
            val done = pendingImages.count { it.blobId != null || it.error != null }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("附件 $done/${pendingImages.size}", color = TextMuted)
                Spacer(Modifier.weight(1f))
                MonoLabel("会话 ${mb(usageBytes)}/${mb(usageLimit)} MiB", color = if (usageBytes > usageLimit) Rose else TextMuted)
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(pendingImages, key = { _, item -> item.id }) { index, item ->
                    OutgoingThumb(
                        item = item,
                        index = index,
                        onRemove = { onRemoveImage(item.id) },
                        onRetry = { onRetryImage(item.id) },
                    )
                }
                if (pendingImages.size < MAX_IMAGES_PER_MESSAGE && !readOnly) {
                    item {
                        val dashedLine = LineStrong
                        Box(
                            Modifier.size(76.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color.White.copy(alpha = 0.02f))
                                .drawBehind {
                                    val stroke = 0.8.dp.toPx()
                                    drawRoundRect(
                                        color = dashedLine,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = stroke,
                                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                                        ),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                                    )
                                }
                                .clickable(onClick = onPickImage),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Add, "添加图片", tint = TextMuted, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (usageLimit <= 0) 0f else (usageBytes.toFloat() / usageLimit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = if (usageBytes > usageLimit) Rose else Violet,
                trackColor = Line,
            )
            Spacer(Modifier.height(7.dp))
        }
        if (imageError != null) {
            Text(
                imageError,
                style = MaterialTheme.typography.labelSmall,
                color = Rose,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.035f))
                    .border(0.7.dp, Line, RoundedCornerShape(9.dp))
                    .clickable(enabled = !readOnly, onClick = onPickImage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AddPhotoAlternate, "添加图片", tint = if (readOnly) TextMuted.copy(alpha = 0.4f) else TextSecondary, modifier = Modifier.size(16.dp))
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Brush.verticalGradient(listOf(Surface3, Surface2)))
                    .border(0.8.dp, LineStrong, RoundedCornerShape(19.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                if (text.isBlank()) {
                    Text(
                        if (readOnly) "该会话已迁移，只读"
                        else if (running) "插入一条消息 / 输入指令…" else "给 pi 发消息… 输入 / 唤起命令",
                        style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { value ->
                        if (value == "/" && text.isBlank()) onCommands() else onText(value)
                    },
                    enabled = !readOnly,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontSize = 13.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(Violet),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (canSend) Brush.linearGradient(listOf(Violet, VioletSoft))
                        else Brush.linearGradient(listOf(TextMuted.copy(alpha = 0.14f), TextMuted.copy(alpha = 0.10f)))
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ArrowUpward,
                    "发送",
                    tint = if (canSend) Color.White else TextMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (readOnly) {
            Spacer(Modifier.height(5.dp))
            Text("只读会话 · 图片与消息发送已禁用", style = MaterialTheme.typography.labelSmall, color = Amber)
        }
    }
}

@Composable
private fun StreamModeButton(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val color = if (danger) Rose else if (active) VioletSoft else TextMuted
    Box(
        modifier.height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Violet.copy(alpha = 0.18f) else Color.Transparent)
            .border(0.7.dp, if (active) Violet.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun OutgoingThumb(
    item: OutgoingImage,
    index: Int,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.id, item.bytes.size) {
        value = if (item.bytes.isEmpty()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, 240)
                }
                BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size, opts)
            }.getOrNull()
        }
    }
    Box(
        Modifier.size(76.dp).clip(RoundedCornerShape(9.dp))
            .background(Surface2)
            .border(0.7.dp, if (item.error != null) Rose.copy(alpha = 0.7f) else LineStrong, RoundedCornerShape(9.dp)),
    ) {
        val image = bitmap
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "待发送图片 ${index + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Image,
                null,
                tint = TextMuted,
                modifier = Modifier.align(Alignment.Center).size(19.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(start = 5.dp, end = 5.dp, top = 8.dp, bottom = 3.dp),
        ) {
            Text(
                formatImageSize(item.size),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 1,
            )
        }
        if (item.uploading) {
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = Violet,
                trackColor = Color.Black.copy(alpha = 0.35f),
            )
        }
        if (item.error != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)).clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text("重试", style = MaterialTheme.typography.labelMedium, color = Rose)
            }
        } else if (!item.uploading && item.blobId != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp).clip(CircleShape).background(Mint.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, null, tint = Ink, modifier = Modifier.size(9.dp)) }
        }
        // 17dp 的移除按钮点不到：外面套 44dp 的可点区域，圆形仍按 17dp 绘制（BUG-020）。
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).clickable(onClick = onRemove),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier.padding(top = 3.dp, end = 3.dp).size(17.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, "移除图片", tint = Color.White, modifier = Modifier.size(11.dp)) }
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

/** 按节点隔离的模型偏好存储键。 */
private fun modelPrefsKey(nodeId: String) = "model_prefs_$nodeId"

/**
 * 单页模型选择器：顶部供应商条 + 中部联动 pager + 底部固定思考等级。
 *
 * 三条不变量：
 * 1. 滑动只浏览——切页只按「默认选择规则」更新待选高亮，绝不发 `model.set`；
 * 2. 点选即提交——`model.set` 成功后才拉 `thinking.levels` 并决定等级，两步串行；
 * 3. 偏好只在提交成功后落盘，浏览/打开 sheet 都不写。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    repo: CarryRepository,
    nodeId: String,
    sessionId: String,
    models: List<ModelOption>,
    levels: List<String>,
    currentProvider: String,
    currentModelId: String,
    currentThinking: String,
    onClose: () -> Unit,
) {
    val prefsKey = remember(nodeId) { modelPrefsKey(nodeId) }
    var prefs by remember(prefsKey) { mutableStateOf(ModelPrefsCodec.decode(repo.settingString(prefsKey, ""))) }
    // 当前模型不在列表里时补伪条目：否则用户看不到打勾项，也说不清自己在用哪个模型。
    val groups = remember(models, currentProvider, currentModelId) {
        withCurrentModel(groupModelsByProvider(models), currentProvider, currentModelId, currentModelId)
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var levelsError by remember { mutableStateOf(false) }
    // 可用等级是本地可刷新状态：先用仓库里已有的那份，`model.set` 成功后用 fresh 覆盖。
    // 空表表示「节点还没给出能力」，此时不能渲染 chips——否则全是假可点。
    var levelList by remember(levels) { mutableStateOf(levels) }
    // 待提交意图 `provider|modelId|level`（level 为空表示只提交模型）。
    // 旋转会销毁 sheet 的协程作用域，把「模型已切、等级没切」静默丢掉；写进可保存状态后
    // 重组时重放一次即可补齐（见下方 LaunchedEffect）。
    var inflight by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    val currentKey = if (currentProvider.isBlank()) "" else "$currentProvider|$currentModelId"
    // prune 的入参：当前节点真实返回的 provider→id 集合。`models` 为空时返回 null——
    // 那种情况多半是没拉到 `models.list`，拿空表清偏好会把整张历史抹掉。
    val available = remember(models) {
        models.groupBy { it.provider }
            .mapValues { (_, list) -> list.map { it.id }.toSet() }
            .takeIf { models.isNotEmpty() }
    }

    fun savePrefs(next: ModelPrefs) {
        prefs = next
        repo.setSettingString(prefsKey, ModelPrefsCodec.encode(next))
    }

    /** 清掉已下线模型的偏好记录，否则偏好表会随模型下线上线无限增长。 */
    fun pruned(p: ModelPrefs): ModelPrefs = if (available == null) p else p.prune(available)

    if (groups.isEmpty()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
            CarrySheetGrabber()
            CarrySheetHeader(title = "模型与思考等级", subtitle = "切换只影响下一次运行，不会中断当前生成。", onClose = onClose)
            Column(Modifier.padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(
                "节点没有返回可用模型，请确认 pi 已登录并检查网络后重试。",
                style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
            }
        }
        return
    }

    val initialPage = groups.indexOfFirst { it.provider == currentProvider }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage) { groups.size }
    val tabsState = rememberLazyListState()
    // 顶部标签跟随页面滚到可见位置：滑到第 5 个供应商时标签条不能还停在开头。
    LaunchedEffect(pagerState.currentPage) {
        runCatching { tabsState.animateScrollToItem(pagerState.currentPage) }
    }

    val page = pagerState.currentPage.coerceIn(0, groups.lastIndex)
    val group = groups[page]
    // 待选模型完全由「页面 + 偏好」推导：切页自然回到该供应商的默认项，不需要额外的选中状态。
    val pending = defaultModelFor(group, prefs)
    val pendingLevel = pending?.let {
        defaultThinkingLevel(levelList, prefs.thinkingFor(group.provider, it.id), currentThinking)
    }

    /** 点选模型：model.set → 拉 levels → 按规则 thinking.set，两步串行、sheet 保持打开。 */
    fun commitModel(model: ModelOption) {
        if (busy) return
        error = null
        levelsError = false
        busy = true
        // 先记「只提交模型」的意图：真在 model.set 途中被旋转打断，重放至少把模型补上。
        inflight = "${model.provider}|${model.id}|"
        scope.launch {
            // 步骤名跟着调用链走：applyThinking 失败也走这个 catch，一律说「设置模型失败」会误导。
            var step = "模型"
            try {
                repo.applyModel(nodeId, sessionId, model.provider, model.id)
                val withModel = pruned(prefs.withModel(model.provider, model.id))
                savePrefs(withModel)
                val fresh = repo.fetchThinkingLevels(nodeId, sessionId)
                if (fresh == null) {
                    // 模型已生效，只是等级拉不到：思考区禁用并允许重试，不能谎报整体失败。
                    levelsError = true
                    inflight = null
                    return@launch
                }
                levelList = fresh
                val want = defaultThinkingLevel(
                    fresh,
                    withModel.thinkingFor(model.provider, model.id),
                    currentThinking,
                )
                if (want == null) {
                    // fresh 非空时理论上推不出 null；真出现就只算提交模型，不卡着意图。
                    inflight = null
                    return@launch
                }
                step = "思考等级"
                // 意图补上等级：此刻被打断的话，重放能把两步都补回来。
                inflight = "${model.provider}|${model.id}|$want"
                repo.applyThinking(nodeId, sessionId, want)
                savePrefs(withModel.withThinking(model.provider, model.id, want))
                inflight = null
            } catch (t: Throwable) {
                // 取消（旋转/dispose）不算失败：保留 inflight 交给重组后的重放，
                // 其它异常才算真失败，清掉意图并给出文案。
                if (t is kotlinx.coroutines.CancellationException) throw t
                inflight = null
                error = "设置${step}失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /** 点思考等级：待选模型还没生效时先提交模型，再提交等级；失败不回滚。 */
    fun commitThinking(level: String) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            // 这条链的第一跳可能是 applyModel，失败时不能说成「设置思考等级失败」。
            var step = "思考等级"
            try {
                val model = defaultModelFor(group, prefs) ?: return@launch
                var next = prefs
                if (model.key != currentKey) {
                    step = "模型"
                    // 这条链同样是两步：旋转正好卡在中间时，靠 inflight 让重组后补上等级（同 U-08）。
                    inflight = "${model.provider}|${model.id}|$level"
                    repo.applyModel(nodeId, sessionId, model.provider, model.id)
                    next = next.withModel(model.provider, model.id)
                    savePrefs(next)
                    step = "思考等级"
                }
                repo.applyThinking(nodeId, sessionId, level)
                savePrefs(next.withThinking(model.provider, model.id, level))
                // 这一跳既有模型又有等级：重放里只提交单模型的意图会被这里一并清掉。
                inflight = null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // 取消（旋转/dispose）保留意图交给重放；真失败清掉，别在重组时又跑一遍。
                inflight = null
                error = "设置${step}失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    /** 重新拉一次思考等级：能力缺失时这是唯一出口，不能只留一句「不可用」。 */
    fun retryLevels() {
        if (busy) return
        levelsError = false
        busy = true
        scope.launch {
            try {
                val fresh = repo.fetchThinkingLevels(nodeId, sessionId)
                if (fresh == null) levelsError = true else levelList = fresh
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                levelsError = true
            } finally {
                busy = false
            }
        }
    }

    // 重放被打断的提交链（旋转把 sheet 的协程取消掉时发生）。
    //
    // 为什么重放安全：`model.set` / `thinking.set` 都是「把当前值设成 X」的幂等操作，重复执行
    // 不会累加计费或产生副作用；会话仍由 sessionId 定位，与发起时的 sheet 实例无关。
    // 为什么只在 sheet 存活期间重放：进程被杀/退出后没有任何依据判断用户还想提交，
    // 所以不做跨进程续跑——saved state 恢复后重放一次，成功即清空 inflight。
    LaunchedEffect(inflight) {
        val intent = inflight ?: return@LaunchedEffect
        // 真在提交中（重放自身触发的重组）不要并发插第二次。
        if (busy) return@LaunchedEffect
        val parts = intent.split("|", limit = 3)
        val provider = parts.getOrNull(0).orEmpty()
        val modelId = parts.getOrNull(1).orEmpty()
        val level = parts.getOrNull(2).orEmpty()
        if (provider.isBlank() || modelId.isBlank()) {
            inflight = null
            return@LaunchedEffect
        }
        busy = true
        var step = "模型"
        try {
            repo.applyModel(nodeId, sessionId, provider, modelId)
            // 同样走 prune：重放也是落盘点，不能让已下线模型的记录从这条路漏回来。
            savePrefs(pruned(prefs.withModel(provider, modelId)))
            if (level.isNotBlank()) {
                step = "思考等级"
                repo.applyThinking(nodeId, sessionId, level)
                savePrefs(pruned(prefs.withThinking(provider, modelId, level)))
            }
            error = null
            inflight = null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            inflight = null
            error = "设置${step}失败：${t.message ?: t.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.82f)
            .padding(bottom = 20.dp),
    ) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "模型与思考等级", subtitle = "切换只影响下一次运行，不会中断当前生成。", onClose = onClose)
        Spacer(Modifier.height(2.dp))

        // 顶部供应商条：顺序固定为 models.list 首次出现顺序，不因当前选择重排。
        LazyRow(
            state = tabsState,
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(groups) { index, g ->
                val active = index == page
                // 热区至少 44dp（U-09）：外层 Box 只负责撑热区，内层标签的 padding/圆角/字号一律不动，
                // 避免视觉上「按钮被调高变丑」。
                Box(
                    Modifier
                        .sizeIn(minHeight = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { scope.launch { runCatching { pagerState.animateScrollToPage(index) } } },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) Violet.copy(alpha = 0.18f) else PanelHigh.copy(alpha = 0.5f))
                            .border(0.7.dp, if (active) Violet.copy(alpha = 0.5f) else Line, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            g.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) Violet else TextSecondary,
                            maxLines = 1,
                        )
                        MonoLabel("${g.models.size} 个模型", color = TextMuted)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            ModelPage(
                group = groups[index],
                currentKey = currentKey,
                pendingKey = pending?.key.orEmpty(),
                busy = busy,
                onPick = { commitModel(it) },
            )
        }

        if (error != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Rose.copy(alpha = 0.10f)).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Rose, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(6.dp))
                Text("重试", style = MaterialTheme.typography.labelMedium, color = Rose, modifier = Modifier.clickable {
                    pending?.let { commitModel(it) }
                })
            }
        }

        HorizontalDivider(color = Line.copy(alpha = 0.5f))
        Column(Modifier.padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("思考等级", color = TextSecondary)
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(10.dp), color = Violet, strokeWidth = 1.4.dp)
            }
            Spacer(Modifier.height(7.dp))
            if (levelList.isEmpty()) {
                // 拿不到能力就不渲染 chips：以前兜底成硬编码档位，看起来全可点，点了必然失败。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "思考等级不可用（未获取到模型能力）",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !busy) { retryLevels() },
                        contentAlignment = Alignment.Center,
                    ) { Text("重试", style = MaterialTheme.typography.labelMedium, color = Amber) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    levelList.forEach { level ->
                        val active = !levelsError && level == pendingLevel
                        // 热区至少 44dp（U-09）：外层 Box 撑热区，内层胶囊尺寸与样式不变。
                        // 提交链进行中或等级未知时禁止点击：否则会把一个不属于该模型的等级写进去。
                        Box(
                            Modifier
                                .sizeIn(minHeight = 44.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = !busy && !levelsError) { commitThinking(level) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (active) Violet.copy(alpha = 0.20f) else PanelHigh)
                                    .border(0.7.dp, if (active) Violet.copy(alpha = 0.55f) else Line, RoundedCornerShape(50))
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    thinkingLabel(level),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (active) Violet else TextMuted,
                                )
                                Text(level, style = MaterialTheme.typography.labelSmall, color = TextMuted.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
            if (levelsError) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "思考等级拉取失败，可重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                        modifier = Modifier.weight(1f),
                    )
                    Text("重试", style = MaterialTheme.typography.labelMedium, color = Amber, modifier = Modifier.clickable {
                        pending?.let { commitModel(it) }
                    })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只影响下一次运行；高等级会显著增加思考 token 与首字延迟。",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

/** 单个供应商的模型列表页；超过 30 个模型时才给本地过滤框（不新增 op）。 */
@Composable
private fun ModelPage(
    group: ProviderGroup,
    currentKey: String,
    pendingKey: String,
    busy: Boolean,
    onPick: (ModelOption) -> Unit,
) {
    var query by remember(group.provider) { mutableStateOf("") }
    val visible = remember(group.models, query) {
        if (query.isBlank()) {
            group.models
        } else {
            group.models.filter {
                it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        if (group.models.size > 30) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PanelHigh.copy(alpha = 0.6f))
                    .border(0.7.dp, Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("过滤模型", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    inner()
                },
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(visible, key = { it.key }) { m ->
                val isCurrent = currentKey.isNotEmpty() && m.key == currentKey
                val isPending = m.key == pendingKey
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 7.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(PanelHigh.copy(alpha = if (isPending || isCurrent) 0.7f else 0.35f))
                        .border(
                            0.7.dp,
                            when {
                                isCurrent -> Violet.copy(alpha = 0.5f)
                                isPending -> Violet.copy(alpha = 0.3f)
                                else -> Line
                            },
                            RoundedCornerShape(13.dp),
                        )
                        .clickable(enabled = !busy) { onPick(m) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Violet.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.name.take(1), style = MaterialTheme.typography.titleMedium, color = Violet) }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 伪条目的名字往往就是裸 id，不标注的话用户看不出这个模型已经下线；
                            // 仍然可点：用户可能就想切回来。
                            if (m.unavailable) {
                                Spacer(Modifier.size(6.dp))
                                Pill("当前（不在列表）", Amber)
                            }
                        }
                        MonoLabel(
                            listOfNotNull(
                                m.key,
                                m.context.takeIf { it.isNotBlank() },
                                "reasoning".takeIf { m.reasoning },
                            ).joinToString(" · "),
                            color = TextMuted,
                        )
                    }
                    if (m.priceText.isNotBlank()) {
                        Spacer(Modifier.size(6.dp))
                        MonoLabel(m.priceText, color = TextMuted)
                    }
                    Spacer(Modifier.size(8.dp))
                    // 已生效的当前模型必须有勾；待选只在没有当前模型时用浅色圈提示。
                    when {
                        isCurrent -> Icon(Icons.Outlined.Check, null, tint = Violet, modifier = Modifier.size(17.dp))
                        isPending -> Box(
                            Modifier.size(14.dp).clip(CircleShape).border(1.2.dp, Violet.copy(alpha = 0.5f), CircleShape),
                        )
                        else -> Spacer(Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

/**
 * 每轮总结行：`46分钟25秒 · 4.6k ›`；被中断的轮追加琥珀色「已中断」。
 *
 * 只有该轮至少有一条 assistant 消息才返回内容（纯用户提问不产轮，由
 * [computeTurns] 保证，这里再兜一层避免空行）。
 */
@Composable
private fun TurnSummaryRow(turn: Turn, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "${turn.durationText} · ${turn.tokenText}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted.copy(alpha = 0.85f),
        )
        if (turn.aborted) {
            Text("已中断", style = MaterialTheme.typography.labelSmall, color = Amber)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.ExpandMore, "查看轨迹", tint = TextMuted.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
    }
}

/** 轨迹面板：字段缺失一律显示 `—`，不拿 0 冒充真实值。 */
@Composable
private fun TurnDetailSheet(turn: Turn, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "本轮轨迹", subtitle = if (turn.aborted) "已中断" else null, onClose = onClose)
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
        MonoLabel(if (turn.model.isBlank()) "MODEL —" else "MODEL " + turn.model, color = TextMuted)
        Spacer(Modifier.height(14.dp))
        KeyValue("请求数", if (turn.requests > 0) "${turn.requests}" else "—")
        KeyValue("本轮用时", turn.durationText)
        KeyValue("输入 token", if (turn.inputTokens > 0) formatTokens(turn.inputTokens) else "—")
        KeyValue("输出 token", if (turn.outputTokens > 0) formatTokens(turn.outputTokens) else "—")
        KeyValue("缓存读取", if (turn.cacheReadTokens > 0) formatTokens(turn.cacheReadTokens) else "—")
        KeyValue("缓存写入", if (turn.cacheWriteTokens > 0) formatTokens(turn.cacheWriteTokens) else "—")
        KeyValue("缓存命中率", formatPercent(turn.cacheHitRate))
        KeyValue("平均速度", formatSpeed(turn.avgSpeed))
        KeyValue("首字时长", formatMs(turn.firstTokenMs))
        KeyValue("成本", formatCost(turn.cost))
        Spacer(Modifier.height(10.dp))
        Text(
            "缺失字段来自老节点或历史聊天记录：它们没有上报 usage，不做推算也不显示 0。",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        }
    }
}

@Composable
private fun CommandSheet(onClose: () -> Unit, onPick: (CommandItem) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "命令面板", subtitle = "由 pi 扩展、prompt 模板与 skill 提供。", onClose = onClose)
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
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
}

@Composable
private fun rememberCountdown(approval: Approval): Long {
    var remaining by remember(approval.id) { mutableStateOf(approval.remainingSeconds) }
    LaunchedEffect(approval.id, approval.remainingSeconds) {
        remaining = approval.remainingSeconds
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
