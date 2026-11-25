package dev.picarry.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.Base64
import java.util.UUID
import android.widget.TextView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import dev.picarry.app.ui.components.riskColor
import dev.picarry.app.ui.components.statusColor
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.AccentSoft
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
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Code
import org.commonmark.node.StrongEmphasis
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

// ---------------------------------------------------------------------------
// 0.3.2 设计 token（Theme.kt 暂无同名 token；本文件私有，可随 Theme 迁移）
// ---------------------------------------------------------------------------
// token: MC-07 用户气泡正文 #EEEAFF
private val UserBubbleInk = Color(0xFFEEEAFF)
// token: MC-07 用户气泡时间 rgba(220,214,255,.5)
private val UserBubbleStamp = Color(0xFFDCD6FF)
// token: MC-08 AI 正文 #DCDCE8
private val AssistantInk = Color(0xFFDCDCE8)
// token: MC-08 代码块底 #07070D
private val CodeBlockBg = Color(0xFF07070D)
// token: MC-08 代码块文字 #B9B5CC
private val CodeBlockInk = Color(0xFFB9B5CC)
// token: MC-11 工具输出 #9A9AB4
private val ToolOutputInk = Color(0xFF9A9AB4)
// token: MC-12 审批命令 #FFD7DE
private val ApprovalCommandInk = Color(0xFFFFD7DE)
// token: MC-12 审批工具名 #FFC2CD
private val ApprovalToolInk = Color(0xFFFFC2CD)
// token: MC-12 审批脚注 rgba(255,190,200,.6)
private val ApprovalFootInk = Color(0xFFFFBEC8)
// token: MQ-04 打断文字 #FF9AAC
private val StopInk = Color(0xFFFF9AAC)
// token: B.5.1 玻璃卡片底白 .022 / .045
private val GlassWhisper = Color.White.copy(alpha = 0.022f)
// token: 输入框内高光白 .06
private val InnerHighlight = Color.White.copy(alpha = 0.06f)
// token: B.6 标准曲线 cubic-bezier(.2,0,0,1)
private val ChatEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)
// token: MQ-01 输入区渐变 rgba(9,9,16,.92) / rgba(8,8,14,.99)
private val ComposerGradMid = Color(0xFF090910)
private val ComposerGradEnd = Color(0xFF08080E)
// token: MQ-05 输入框渐变 rgba(27,27,39,.85) → rgba(21,21,31,.92)
private val InputGradTop = Color(0xFF1B1B27)
private val InputGradBottom = Color(0xFF15151F)
// token: MQ-08 发送 #8D7DFF → #5F4DF0
private val SendGradStart = Color(0xFF8D7DFF)
private val SendGradEnd = Color(0xFF5F4DF0)

// token: MB-01 sheet 渐变 #101019 → #0B0B13
private val SheetGradTop = Color(0xFF101019)
private val SheetGradBottom = Color(0xFF0B0B13)

// token: MB-01 sheet 顶部 1px 白 .14
private val SheetHairline = Color.White.copy(alpha = 0.14f)

// token: MC-06 新消息浮标底 rgba(20,18,34,.92)
private val NewMessagePillBg = Color(0xEB141222)
// token: MC-08 行内 code 紫底（violet .14 叠在 panel 上）
private val InlineCodeBg = Color(0xFF191732)
// token: B.5.3 图片预览遮罩 rgb(3,3,8) @66%
private val PhotoScrim = Color(0xFF030308)
// token: MB-71 审批后果主文案 #FFC4CE
private val ApprovalSheetPrimaryInk = Color(0xFFFFC4CE)
// token: MB-72 审批命令块原文 #FFD9E0
private val ApprovalSheetCommandInk = Color(0xFFFFD9E0)
// token: MC-11 状态 tag 成功文字（design .tag.green #A6F4CF）
private val ToolTagGreenInk = Color(0xFFA6F4CF)
// token: MC-11 状态 tag 失败文字（design .tag.rose #FFADBC）
private val ToolTagRoseInk = Color(0xFFFFADBC)

/** 设计稿按压：整体缩放 + 背景无涟漪（AC-01/H.5），显式 tween 而非默认 spring。 */
@Composable
private fun Modifier.chatPress(onClick: () -> Unit, scale: Float = 0.985f, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val value by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = tween(durationMillis = 160, easing = ChatEase),
        label = "chatPress",
    )
    return this.graphicsLayer { scaleX = value; scaleY = value }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** 无缩放、无涟漪的点按区域（行内文字、图标等）。 */
@Composable
private fun Modifier.chatClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** 审批风险的中文标签（H.7：英文只保留数据值，风险标签必须中文）。 */
private fun riskLabel(risk: String): String = when (risk.lowercase()) {
    "critical" -> "高风险 · 不可逆"
    "high" -> "高风险"
    "medium" -> "中风险"
    "low" -> "低风险"
    else -> "需审批"
}

/** 风险色：critical/high 玫、medium 琥珀、low 薄荷。 */
@Composable
@ReadOnlyComposable
private fun chatRiskColor(risk: String): Color = when (risk.lowercase()) {
    "critical", "high" -> Rose
    "medium" -> Amber
    "low" -> Mint
    else -> Amber
}

/** MC-11：名称后的状态胶囊文案（自动允许/成功/已应用/失败）。 */
private fun toolTagLabel(tool: ToolCall): String = when {
    tool.status == "running" -> "运行中"
    tool.isError || tool.status == "error" -> "失败"
    tool.name.contains("apply", ignoreCase = true) || tool.name.contains("patch", ignoreCase = true) -> "已应用"
    else -> "成功"
}

/** MC-11：右侧「状态文字」文案；成功后统一显示“成功”。 */
private fun toolStateLabel(tool: ToolCall): String = when {
    tool.status == "running" -> "运行中"
    tool.isError || tool.status == "error" -> "失败"
    else -> "成功"
}

/** MC-11：状态色（成功绿/失败玫/运行紫）。 */
@Composable
@ReadOnlyComposable
private fun toolStateColor(tool: ToolCall): Color = when {
    tool.status == "running" -> VioletSoft
    tool.isError || tool.status == "error" -> Rose
    else -> Mint
}

/** MC-11：状态 tag 按设计 `.tag` 形状（21dp 高、圆角 6dp、mono 11、1dp 描边）。 */
@Composable
private fun ToolStateTag(tool: ToolCall) {
    val running = tool.status == "running"
    val failed = tool.isError || tool.status == "error"
    val bg = when {
        running -> Violet.copy(alpha = 0.12f)
        failed -> Rose.copy(alpha = 0.10f)
        else -> Mint.copy(alpha = 0.09f)
    }
    val stroke = when {
        running -> Violet.copy(alpha = 0.30f)
        failed -> Rose.copy(alpha = 0.28f)
        else -> Mint.copy(alpha = 0.26f)
    }
    val ink = when {
        running -> AccentSoft
        failed -> ToolTagRoseInk
        else -> ToolTagGreenInk
    }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier.height(21.dp).clip(shape).background(bg).border(1.dp, stroke, shape).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            toolTagLabel(tool),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = ink,
        )
    }
}

private fun startOfLocalDay(ms: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = ms
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** MC-05 日期分隔文案：今天 9:38 / 昨天 22:14 / 11月20日。 */
private fun chatDateLabel(tsMs: Long): String {
    if (tsMs <= 0) return ""
    val today = startOfLocalDay(System.currentTimeMillis())
    val day = startOfLocalDay(tsMs)
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(tsMs))
    return when (day) {
        today -> "今天 $clock"
        today - 86_400_000L -> "昨天 $clock"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault()).format(java.util.Date(tsMs))
    }
}

private sealed interface ChatRow { val key: String }

private data class ChatDateRow(val label: String) : ChatRow {
    override val key: String get() = "date:$label"
}

private data class ChatMessageRow(val message: ChatMessage) : ChatRow {
    override val key: String get() = message.id
}

/** 在消息之间插入日期分隔（MC-05）；无时间戳的本地消息不硬造日期。 */
private fun buildChatRows(messages: List<ChatMessage>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var lastDay = Long.MIN_VALUE
    messages.forEach { msg ->
        val ts = if (msg.tsMs > 0) msg.tsMs else msg.createdAt
        if (ts > 0) {
            val day = startOfLocalDay(ts)
            if (day != lastDay) {
                rows += ChatDateRow(chatDateLabel(ts))
                lastDay = day
            }
        }
        rows += ChatMessageRow(msg)
    }
    return rows
}

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
        // 已上传项也要写盘：里面是压缩后的小图，旋转/重建后仍能看到缩略图（BUG-007）。
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

    // MC-05：把消息按日期切成「日期分隔行 + 消息行」，只在跨天时插一条。
    val chatRows = remember(messages) { buildChatRows(messages) }
    // MC-06：用户上滑阅读时累计新消息条数；回到底部（stickBottom）后清零。
    var seenMessageCount by remember(sessionId) { mutableStateOf(-1) }
    var unseenMessages by remember(sessionId) { mutableStateOf(0) }
    LaunchedEffect(messages.size) {
        val previous = seenMessageCount
        if (previous >= 0 && messages.size > previous && !stickBottom) {
            unseenMessages += messages.size - previous
        }
        seenMessageCount = messages.size
    }
    LaunchedEffect(stickBottom) {
        if (stickBottom) unseenMessages = 0
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
                Toast.makeText(context, "已迁移到新分支", Toast.LENGTH_LONG).show()
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
        Spacer(Modifier.height(36.dp))
        Row(
            Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CarryIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, plain = true, size = 34)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlowDot(if (session?.isRunning == true) Amber else if (link[nodeId] == LinkState.Online) Mint else TextMuted)
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
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp,
                        letterSpacing = (-0.31).sp,
                        lineHeight = 19.sp,
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CarryIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { showMenu = true }, plain = true, size = 34)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatMetaChip(
                currentName.ifBlank { "默认模型" },
                icon = Icons.Outlined.AutoAwesome,
                onClick = { showModels = true },
            )
            ChatMetaChip("思考 $currentThinking", onClick = { showModels = true })
            ChatMetaChip(displayName(session?.cwd.orEmpty()).ifBlank { "~" })
        }

        if (migrationNotice) {
            MigrationBanner(onClose = { migrationNotice = false })
        }

        if (session?.writable == false && !migrationNotice) {
            ReadOnlyBar(
                // 只读原因必须按真实来源判断：这里已经在 `writable == false` 分支内，
                // 再用 readOnly（含 writable 条件）判断会让「本机终端正在写」永不出现（BUG-025）。
                if (migrated.contains(key)) "只读 · 该会话已迁移" else "只读 · 节点未开放写入",
            )
        }
        if (openError != null) {
            NoticeBanner(
                "打开会话失败：$openError",
                Rose,
                action = "重试" to { scope.launch { repo.openSession(nodeId, sessionId) } },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
            ) {
                items(chatRows, key = { it.key }) { row ->
                    when (row) {
                        is ChatDateRow -> DateSeparator(row.label)
                        is ChatMessageRow -> {
                            val msg = row.message
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
                    }
                }
                items(pending, key = { "ap-${it.id}" }) { approval ->
                    ApprovalCard(approval) { openApprovalId = approval.id }
                }
                if (messages.isEmpty() && pending.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                                    .background(Violet.copy(alpha = 0.10f))
                                    .border(0.8.dp, Violet.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Terminal, null, tint = VioletSoft, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "会话已连接",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text("输入消息开始", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                        }
                    }
                }
            }
            // 顶部 26dp 渐隐遮罩：列表滚到头部下方时柔化边界（MC-17），不拦截触摸。
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(26.dp)
                    .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.92f), Ink.copy(alpha = 0f)))),
            )
            // MC-06 新消息浮标：不在底部且有新消息时出现，点按回到底部。
            androidx.compose.animation.AnimatedVisibility(
                visible = unseenMessages > 0 && !stickBottom,
                enter = fadeIn(tween(durationMillis = 180, easing = ChatEase)),
                exit = fadeOut(tween(durationMillis = 180, easing = ChatEase)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                NewMessagePill(
                    count = unseenMessages,
                    onClick = {
                        unseenMessages = 0
                        scope.launch { runCatching { listState.animateScrollToItem(chatRows.size + pending.size) } }
                        stickBottom = true
                    },
                )
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
            // MC-18：发送失败只在列表上方保留这一处，助手块里不再重复。
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(9.dp)).background(Rose.copy(alpha = 0.08f)).padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "发送失败：${failure.error}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Rose,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.height(44.dp).clip(RoundedCornerShape(8.dp))
                        .chatClick {
                            repo.sendPrompt(failure.nodeId, failure.sessionId, failure.text, failure.mode, failure.images)
                            failedSend = null
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("重试", style = MaterialTheme.typography.labelLarge, color = Cyan) }
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
            SheetSurface {
            SessionMenuSheet(
                name = session?.title ?: "会话",
                nodeLabel = node?.name.orEmpty(),
                sessionId = sessionId,
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
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }, sheetState = modelSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            SheetSurface {
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
    }
    if (showCommands) {
        ModalBottomSheet(onDismissRequest = { showCommands = false }, sheetState = commandSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            SheetSurface {
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
            SheetSurface {
            Column(Modifier.fillMaxWidth()) {
                CarrySheetGrabber()
                CarrySheetHeader(
                    title = "重命名会话",
                    subtitle = "只改显示名，不改写记录",
                    onClose = { showRename = false },
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
                    MonoLabel("会话名称", color = TextMuted)
                    Spacer(Modifier.height(5.dp))
                    val renameFieldSource = remember { MutableInteractionSource() }
                    val renameFocused by renameFieldSource.collectIsFocusedAsState()
                    val renameGlowColor = Violet.copy(alpha = 0.18f) // drawBehind 不能读 Composable token
                    Box(
                        Modifier.fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.035f))
                            .border(
                                1.dp,
                                if (renameFocused) Violet.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(10.dp),
                            )
                            .drawBehind {
                                if (renameFocused) {
                                    val outline = Path().apply {
                                        addRoundRect(
                                            RoundRect(
                                                left = 0f,
                                                top = 0f,
                                                right = size.width,
                                                bottom = size.height,
                                                radiusX = 10.dp.toPx(),
                                                radiusY = 10.dp.toPx(),
                                            ),
                                        )
                                    }
                                    drawPath(outline, renameGlowColor, style = Stroke(width = 3.dp.toPx()))
                                }
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = renameText,
                            onValueChange = { renameText = it.take(60); renameError = null },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontFamily = Inter, lineHeight = 20.sp),
                            cursorBrush = SolidColor(Violet),
                            singleLine = true,
                            interactionSource = renameFieldSource,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        renameError ?: "最多 60 个字符",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (renameError != null) Rose else TextMuted,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CarryActionButton(
                            "取消", { showRename = false },
                            modifier = Modifier.weight(1f), kind = ButtonKind.Ghost, height = 46.dp,
                        )
                        CarryActionButton(
                            "保存", {
                                repo.renameSession(nodeId, sessionId, renameText) { error ->
                                    if (error == null) showRename = false else renameError = error
                                }
                            },
                            modifier = Modifier.weight(1.3f), kind = ButtonKind.Primary, height = 46.dp,
                        )
                    }
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
            SheetSurface(Modifier.fillMaxHeight(0.82f)) {
                CarrySheetGrabber()
                val page = entriesPage
                CarrySheetHeader(
                    title = "分支与历史",
                    subtitle = page?.let { "leaf ${it.leafId?.take(8) ?: "—"} · 共 ${it.entries.size} 条" },
                    onClose = { if (!forking) showEntries = false },
                )
                when {
                    entriesLoading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Violet, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                    entriesError != null -> Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp)) {
                        Text(entriesError.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Rose)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.height(44.dp).chatClick { openEntries() }, contentAlignment = Alignment.CenterStart) {
                            Text("重试", style = MaterialTheme.typography.labelLarge, color = Cyan)
                        }
                    }
                    page != null -> {
                        val children = remember(page) { page.entries.groupingBy { it.parentId }.eachCount() }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            // MB-31：主干左 21dp
                            Box(
                                Modifier.padding(start = 21.dp, top = 8.dp, bottom = 8.dp)
                                    .width(1.dp).fillMaxHeight().background(Line),
                            )
                            LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 18.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (page.entries.isEmpty()) {
                                    item {
                                        Box(
                                            Modifier.fillParentMaxWidth().padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("没有可用的 entry", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        }
                                    }
                                }
                                items(page.entries.asReversed(), key = { it.id }) { entry ->
                                    val selected = entry.id == selectedEntryId
                                    val indent = (entry.depth * 13).dp
                                    val role = if (entry.role.isBlank()) entry.type else entry.role
                                    val roleColor = when {
                                        role.contains("user", true) -> Violet
                                        role.contains("tool", true) -> Mint
                                        else -> Cyan
                                    }
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .padding(start = indent)
                                            .alpha(if (selected) 1f else 0.52f)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(if (selected) Violet.copy(alpha = 0.09f) else Color.Transparent)
                                            .border(
                                                1.dp,
                                                if (selected) Violet.copy(alpha = 0.28f) else Color.Transparent,
                                                RoundedCornerShape(11.dp),
                                            )
                                            .chatClick { selectedEntryId = entry.id }
                                            .padding(start = 0.dp, top = 9.dp, bottom = 9.dp, end = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                                                .background(roleColor.copy(alpha = 0.14f))
                                                .border(1.dp, roleColor.copy(alpha = 0.22f), RoundedCornerShape(9.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                when {
                                                    role.contains("user", true) -> "U"
                                                    role.contains("tool", true) -> "T"
                                                    else -> "A"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = roleColor,
                                            )
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                listOf(role, entry.timestamp).filter { it.isNotBlank() }.joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                entry.preview.ifBlank { entry.id.take(8) },
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 17.8.sp),
                                                color = if (selected) TextPrimary else TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if ((children[entry.id] ?: 0) > 1) Tag("分支", Amber)
                                        if (selected) Icon(Icons.Outlined.Check, "已选中", tint = VioletSoft, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        // MB-33：底栏 margin 0 14 24、渐变、14 圆角
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 26.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.verticalGradient(listOf(Surface2, Panel)))
                                .border(1.dp, LineStrong, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "从 ${selectedEntryId?.take(8) ?: "…"} 分叉",
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                                    color = TextPrimary,
                                )
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    "新会话复制到该消息 · 旧会话保留",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            Box(
                                Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
                                    .background(Brush.linearGradient(listOf(SendGradStart, SendGradEnd)))
                                    .chatPress(
                                        onClick = { if (selectedEntryId != null && !forking) doFork() },
                                        scale = 0.97f,
                                        enabled = selectedEntryId != null && !forking,
                                    )
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (forking) "正在分叉…" else "分叉",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    openTurn?.let { turn ->
        ModalBottomSheet(onDismissRequest = { openTurnAnchor = null }, sheetState = turnSheetState, containerColor = Panel,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), scrimColor = Color.Black.copy(alpha = 0.66f), dragHandle = null) {
            SheetSurface {
            TurnDetailSheet(turn) { openTurnAnchor = null }
            }
        }
    }
    openApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            deviceCount = nodes.size,
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
private fun GlowDot(color: Color, size: Int = 5) {
    Box(
        Modifier.size(size.dp).drawBehind {
            drawCircle(color.copy(alpha = 0.30f), radius = this.size.minDimension * 1.7f)
            drawCircle(color, radius = this.size.minDimension / 2f)
        },
    )
}

/** MC-02：高 24、圆角 99、白 .02 底 + 1px line；按压白 .06、无涟漪。 */
@Composable
private fun ChatMetaChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    running: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(99)
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (pressed && onClick != null) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.02f))
            .border(1.dp, Line, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (running) {
            CircularProgressIndicator(color = Violet, strokeWidth = 1.2.dp, modifier = Modifier.size(10.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = VioletSoft, modifier = Modifier.size(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextSecondary,
            maxLines = 1,
        )
    }
}

/** MC-03：迁移横幅两行 + 图标块；不要行首圆点。 */
@Composable
private fun MigrationBanner(onClose: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp)
            .clip(shape)
            .background(Amber.copy(alpha = 0.07f))
            .border(1.dp, Amber.copy(alpha = 0.30f), shape)
            .padding(start = 10.dp, top = 9.dp, bottom = 9.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(Amber.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.AccountTree, null, tint = Amber, modifier = Modifier.size(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                "已迁移到新分支",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = Amber,
            )
            Spacer(Modifier.height(1.dp))
            Text("草稿已保留", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Box(Modifier.size(44.dp).chatClick(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "关闭提示", tint = TextMuted, modifier = Modifier.size(15.dp))
        }
    }
}

/** MC-04：只读单行条（P0）。 */
@Composable
private fun ReadOnlyBar(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Amber.copy(alpha = 0.07f))
            .border(1.dp, Amber.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Outlined.Lock, null, tint = Amber, modifier = Modifier.size(13.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = Amber,
        )
    }
}

/** MC-05：日期分隔行 —— 左右 1px 渐变线 + mono 11。 */
@Composable
private fun DateSeparator(label: String) {
    if (label.isBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.09f)))),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
            color = TextMuted,
        )
        Box(
            Modifier.weight(1f).height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent))),
        )
    }
}

/** MC-06：新消息浮标；a11y 热区 44，可视高度 28。 */
@Composable
private fun NewMessagePill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(28.dp).clip(CircleShape)
                .background(NewMessagePillBg) // token: MC-06 浮标底 rgba(20,18,34,.92)
                .border(1.dp, Violet.copy(alpha = 0.35f), CircleShape)
                .chatPress(onClick = onClick, scale = 0.96f)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                if (count > 1) "$count 条新消息" else "回到最新",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = AccentSoft,
            )
            Icon(Icons.Outlined.ExpandMore, null, tint = AccentSoft, modifier = Modifier.size(14.dp))
        }
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

/**
 * MB-01..05：sheet 内容根 —— 渐变底 #101019→#0B0B13 + 顶部 1px 白 .14 高光。
 * ModalBottomSheet 的 containerColor 只能纯色，故在这层实现设计稿的玻璃渐变；
 * 26dp 顶部圆角由 ModalBottomSheet 的 shape 裁剪，这里不重复 clip。
 * 体内 18dp 横向与 26dp 底部留白由各 sheet 的内容列提供（见各 sheet 的 body padding）。
 */
@Composable
private fun SheetSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(SheetGradTop, SheetGradBottom)))
            .drawBehind {
                drawLine(
                    color = SheetHairline,
                    start = Offset(0f, 0.5f),
                    end = Offset(size.width, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        content()
    }
}

@Composable
private fun SessionMenuSheet(
    name: String,
    nodeLabel: String,
    sessionId: String,
    readOnly: Boolean,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onCopyId: () -> Unit,
    onEntries: () -> Unit,
    onCompact: () -> Unit,
    onCloseSession: () -> Unit,
) {
    // MB-10/11：副标题补节点与会话 id；5 行无行间线，整行可点。
    val subtitle = listOf(name, nodeLabel, sessionId.take(8))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "会话操作", subtitle = subtitle, onClose = onClose)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            ChatMenuRow(Icons.Outlined.Edit, "重命名会话", "只改显示名，不改写记录", enabled = !readOnly, onClick = onRename)
            ChatMenuRow(Icons.Outlined.ContentCopy, "复制会话 ID", "复制给脚本或反馈问题", onClick = onCopyId)
            ChatMenuRow(Icons.Outlined.AccountTree, "分支与 entries", "查看消息树，从任意节点分叉", enabled = !readOnly, onClick = onEntries)
            ChatMenuRow(Icons.Outlined.Tune, "压缩上下文", "保留摘要，释放上下文窗口", enabled = !readOnly, onClick = onCompact)
            ChatMenuRow(Icons.Outlined.Close, "关闭会话", "不影响电脑上的 pi 记录", danger = true, onClick = onCloseSession)
        }
        Spacer(Modifier.height(26.dp))
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
            .clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.chatPress(onClick = onClick, scale = 0.985f) else Modifier)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (danger) Rose.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.05f))
                .border(1.dp, tint.copy(alpha = if (danger) 0.18f else 0.10f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                color = if (danger) Rose else if (enabled) TextPrimary else TextMuted,
            )
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMessage) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMax = maxWidth * 0.86f
        val shape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (msg.text.isNotBlank()) {
                Box(
                    Modifier
                        .widthIn(max = bubbleMax)
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(Violet.copy(alpha = 0.26f), Violet.copy(alpha = 0.13f))))
                        .border(1.dp, Violet.copy(alpha = 0.30f), shape)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Column {
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 21.6.sp),
                            color = UserBubbleInk,
                        )
                        val stamp = msg.tsMs
                        if (stamp > 0) {
                            val clock = remember(stamp) {
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))
                            }
                            Text(
                                clock,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                color = UserBubbleStamp.copy(alpha = 0.5f),
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
}

@Composable
private fun AssistantBlock(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        if (msg.thinking.isNotBlank()) {
            var expanded by remember(msg.id) { mutableStateOf(false) }
            val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(durationMillis = 200, easing = ChatEase), label = "thinking-chevron")
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = 0.16f),
                            start = Offset(stroke / 2f, 0f),
                            end = Offset(stroke / 2f, size.height),
                            strokeWidth = stroke,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                        )
                    }
                    .chatClick { expanded = !expanded }
                    .padding(start = 11.dp, top = 2.dp, bottom = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Psychology, null, tint = TextMuted, modifier = Modifier.size(13.dp))
                    MonoLabel("思考", color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        tint = TextMuted,
                        modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(durationMillis = 300, easing = ChatEase)) + fadeIn(tween(durationMillis = 200, easing = ChatEase)),
                    exit = shrinkVertically(tween(durationMillis = 300, easing = ChatEase)) + fadeOut(tween(durationMillis = 160, easing = ChatEase)),
                ) {
                    Text(
                        msg.thinking,
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.5.sp),
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
    }
}

@Composable
private fun SystemBubble(msg: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Rose.copy(alpha = 0.08f)).padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (msg.error) {
            Icon(Icons.Outlined.Info, null, tint = Rose, modifier = Modifier.size(14.dp))
        }
        Text(
            msg.text,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
            color = if (msg.error) Rose else TextSecondary,
        )
    }
}

@Composable
private fun ToolCard(tool: ToolCall) {
    var expanded by remember(tool.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(durationMillis = 200, easing = ChatEase), label = "tool-chevron")
    val done = tool.status == "success" || tool.status == "completed" || (tool.durationMs > 0 && !tool.isError)
    val accent = when {
        tool.isError -> Rose
        done -> Mint
        else -> Violet
    }
    val shape = RoundedCornerShape(13.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassWhisper)
            .border(1.dp, if (tool.isError) Rose.copy(alpha = 0.32f) else Line, shape),
    ) {
        Row(
            Modifier.fillMaxWidth().chatClick { expanded = !expanded }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = if (done) 0.08f else 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = accent, modifier = Modifier.size(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // MC-11：名称后状态 tag（设计 .tag：21 高、6dp 圆角、紫/绿/玫描边）。
                    ToolStateTag(tool)
                }
                val summary = summarizeToolArgs(tool.args)
                if (summary.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        summary.take(if (expanded) 4000 else 160),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = TextMuted,
                        maxLines = if (expanded) 80 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                // MC-11：右侧统一为「状态文字 + 时长」mono 11sp。
                Text(
                    toolStateLabel(tool),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = toolStateColor(tool),
                )
                if (tool.durationMs > 0) {
                    Text(
                        "${tool.durationMs}ms",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = TextMuted,
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = rotation },
                )
            }
        }
        if (tool.images.isNotEmpty()) {
            Box(Modifier.padding(start = 11.dp, end = 11.dp, bottom = 10.dp)) {
                ImageStrip(tool.images, Modifier.clickable(enabled = false) {})
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(durationMillis = 320, easing = ChatEase)) + fadeIn(tween(durationMillis = 200, easing = ChatEase)),
            exit = shrinkVertically(tween(durationMillis = 320, easing = ChatEase)) + fadeOut(tween(durationMillis = 160, easing = ChatEase)),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = Color.White.copy(alpha = 0.075f),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    .padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 11.dp),
            ) {
                if (tool.args.isNotBlank()) {
                    Text(
                        tool.args.take(8000),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.7.sp),
                        color = TextSecondary,
                    )
                }
                if (tool.result.isNotBlank()) {
                    if (tool.args.isNotBlank()) Spacer(Modifier.height(9.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color.Black.copy(alpha = 0.28f))
                            .padding(10.dp),
                    ) {
                        Text(
                            tool.result.take(20000),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.7.sp),
                            color = if (tool.isError) Rose else ToolOutputInk,
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
private fun MarkdownBody(text: String, streaming: Boolean = false, bodyColor: Color = AssistantInk) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textColor = bodyColor.toArgb()
    val codeInk = VioletSoft.toArgb()
    val blockBg = CodeBlockBg.toArgb()
    val blockInk = CodeBlockInk.toArgb()
    val linkColor = Violet.toArgb()
    val cursorInk = VioletSoft.toArgb() // 流式光标色（非 composable 回调内使用）
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
    // MC-09：流式光标 7×14 紫块 blink 1s（steps(1)）。用半个周期切换可见性模拟 steps。
    var cursorOn by remember { mutableStateOf(true) }
    LaunchedEffect(streaming) {
        cursorOn = true
        while (streaming) {
            delay(500)
            cursorOn = !cursorOn
        }
    }
    // toArgb() 是 @ReadOnlyComposable，必须在 remember 外层先算好（remember 回调不是可组合上下文）。
    val inlineCodeFill = InlineCodeBg.toArgb()
    val inlineCodeStroke = Violet.copy(alpha = 0.16f).toArgb()
    val strongInk = Color.White.toArgb()
    val markwon = remember(context, textColor, codeInk, blockBg, blockInk, linkColor, density) {
        val inlineCodeSpan = RoundedInlineCodeSpan(
            fillColor = inlineCodeFill,
            strokeColor = inlineCodeStroke,
            textColorInt = codeInk,
            textSizePx = with(density) { 11.5.sp.toPx() },
            typeface = Typeface.MONOSPACE,
            radiusPx = with(density) { 4.dp.toPx() },
            padHorizontalPx = with(density) { 5.dp.toPx() },
            padVerticalPx = with(density) { 1.5.dp.toPx() },
            strokeWidthPx = with(density) { 1.dp.toPx() },
        )
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBlockTextColor(blockInk)
                        .codeBlockBackgroundColor(blockBg)
                        .codeBlockTextSize(with(density) { 10.5.sp.roundToPx() })
                        .linkColor(linkColor)
                        .isLinkUnderlined(false)
                        .headingBreakHeight(0)
                }

                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // MC-08：行内 code 自绘圆角紫底 + 1px 紫边 + 1.5/5 内边距（Markwon 默认只有方角底色）。
                    builder.setFactory(Code::class.java) { _, _ -> arrayOf<Any>(inlineCodeSpan) }
                    // MC-08：strong 白 600（Markwon 默认只加粗，文字仍是正文色）。
                    builder.addFactory(StrongEmphasis::class.java) { _, _ ->
                        arrayOf<Any>(ForegroundColorSpan(strongInk))
                    }
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
            val rendered = shown + "\u0000" + textColor + "\u0000" + linkColor + "\u0000" + streaming + "\u0000" + cursorOn
            if (view.tag != rendered) {
                view.tag = rendered
                val builder = SpannableStringBuilder(markwon.toMarkdown(shown))
                while (builder.isNotEmpty() && (builder[builder.length - 1] == '\n' || builder[builder.length - 1] == ' ')) {
                    builder.delete(builder.length - 1, builder.length)
                }
                if (streaming && builder.isNotEmpty() && cursorOn) {
                    builder.append("\u2589")
                    builder.setSpan(
                        ForegroundColorSpan(cursorInk),
                        builder.length - 1,
                        builder.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                view.text = builder
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * MC-08 行内 code 自绘 span：圆角紫底 + 1px 紫边 + 1.5/5 内边距 + mono 11.5。
 * Markwon 自带 CodeSpan 只会给文字上底色（方角、无内边距），这里用 ReplacementSpan 接管整段绘制。
 */
private class RoundedInlineCodeSpan(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val textColorInt: Int,
    private val textSizePx: Float,
    private val typeface: Typeface,
    private val radiusPx: Float,
    private val padHorizontalPx: Float,
    private val padVerticalPx: Float,
    private val strokeWidthPx: Float,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        paint.typeface = typeface
        paint.textSize = textSizePx
        return (paint.measureText(text, start, end) + padHorizontalPx * 2f).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        paint.typeface = typeface
        paint.textSize = textSizePx
        val metrics = paint.fontMetrics
        val left = x
        val right = x + paint.measureText(text, start, end) + padHorizontalPx * 2f
        val rect = RectF(left, y + metrics.ascent - padVerticalPx, right, y + metrics.descent + padVerticalPx)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx
        paint.color = strokeColor
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.style = Paint.Style.FILL
        paint.color = textColorInt
        canvas.drawText(text, start, end, left + padHorizontalPx, y.toFloat(), paint)
    }
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
                        .border(1.dp, Line, RoundedCornerShape(9.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = loaded && !part.unavailable,
                        ) { retry += 1 },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
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
                        .border(1.dp, Line, RoundedCornerShape(9.dp))
                        .chatClick { preview = part },
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
        // MB-80：点遮罩关闭；内容用 panel 底 + 16 圆角，禁止整屏纯黑。
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(PhotoScrim.copy(alpha = 0.66f)) // token: B.5.3 scrim
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { preview = null },
                contentAlignment = Alignment.Center,
            ) {
                val previewMaxHeight = maxHeight
                Box(
                    Modifier.fillMaxWidth(0.92f).widthIn(max = 640.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Panel).padding(8.dp),
                ) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "查看图片",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = previewMaxHeight * 0.84f),
                        )
                    } else {
                        Text(
                            when {
                                part.unavailable -> "图片不可用"
                                loaded -> "加载失败，点击重试"
                                else -> "加载中…"
                            },
                            color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).size(44.dp).chatClick { preview = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)))
                        Icon(Icons.Outlined.Close, "关闭图片预览", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: Approval, onClick: () -> Unit) {
    val remaining = rememberCountdown(approval)
    val shape = RoundedCornerShape(16.dp)
    val accentBar = Rose.copy(alpha = 0.85f) // drawBehind 内使用，不能在绘制回调里读 token
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Rose.copy(alpha = 0.15f), Rose.copy(alpha = 0.035f), Color.White.copy(alpha = 0.01f)),
                ),
            )
            .border(1.dp, Rose.copy(alpha = 0.32f), shape)
            .drawBehind {
                drawLine(
                    accentBar,
                    Offset(1.dp.toPx(), 14.dp.toPx()),
                    Offset(1.dp.toPx(), size.height - 14.dp.toPx()),
                    2.dp.toPx(),
                )
            }
            .chatPress(onClick = onClick, scale = 0.985f)
            .padding(start = 15.dp, end = 13.dp, top = 12.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Tag(riskLabel(approval.risk), chatRiskColor(approval.risk), filled = true)
            Text(
                approval.toolName,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.5.sp),
                color = ApprovalToolInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Outlined.Schedule, null, tint = Rose.copy(alpha = 0.8f), modifier = Modifier.size(11.dp))
            Text(
                if (remaining <= 0) "已超时" else "${remaining}s",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.sp),
                color = if (remaining <= 0) TextMuted else Rose,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            approval.command ?: approval.summary.ifBlank { "高风险操作" },
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.5.sp, lineHeight = 16.3.sp),
            color = ApprovalCommandInk,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 9.dp),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(Rose.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth((remaining / 120f).coerceIn(0f, 1f)).fillMaxHeight().background(Rose.copy(alpha = 0.8f)))
        }
        Spacer(Modifier.height(8.dp))
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
            Text("审批 →", style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono), color = ApprovalFootInk)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalDialog(
    approval: Approval,
    deviceCount: Int,
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
    val critical = approval.risk.lowercase() == "critical"
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Panel,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        scrimColor = Color.Black.copy(alpha = 0.66f),
        dragHandle = null,
    ) {
        SheetSurface {
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
                        .border(1.dp, Rose.copy(alpha = 0.38f), RoundedCornerShape(13.dp)),
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
                        trackColor = Rose.copy(alpha = 0.16f),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        if (expired) "0" else "$remaining",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                        color = if (expired) TextMuted else Rose,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
                // MB-71：批准后果 + 自动拒绝时间，必须一眼可见。
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Rose.copy(alpha = 0.055f))
                        .border(1.dp, Rose.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (expired) "已超时 · 将按拒绝处理" else "批准后立即在电脑本机执行",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                            color = ApprovalSheetPrimaryInk, // token: MB-71 主文案
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (expired) "服务端已按拒绝处理，请等待下一次审批请求" else "120 秒内未响应将自动拒绝",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Tag(riskLabel(approval.risk), chatRiskColor(approval.risk), filled = true)
                }
                Spacer(Modifier.height(11.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("COMMAND", color = TextMuted)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.Lock, null, tint = TextMuted, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("SHA-256 已校验", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        approval.command ?: approval.summary,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 18.7.sp,
                        ),
                        color = ApprovalSheetCommandInk, // token: MB-72 命令原文
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ApprovalKeyValue("CWD", approval.cwd.ifBlank { "—" })
                ApprovalKeyValue("SESSION", approval.sessionId.take(16), last = approval.requestedAt.isBlank())
                // M-P2-05：REQUEST 为空时不渲染空标签行（不留空 key + 分隔线）。
                if (approval.requestedAt.isNotBlank()) {
                    ApprovalKeyValue("REQUEST", approval.requestedAt, last = true)
                }
                Spacer(Modifier.height(12.dp))
                if (critical) {
                    val criticalDashedColor = TextMuted.copy(alpha = 0.45f) // drawBehind 不能读 Composable token
                    // MB-74：critical 操作不支持记住 —— 虚线禁用行、18dp 勾选框内锁图标、opacity .55、不可点。
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.02f))
                            .drawBehind {
                                val width = 1.dp.toPx()
                                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                                val outline = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            left = 0f,
                                            top = 0f,
                                            right = size.width,
                                            bottom = size.height,
                                            radiusX = 10.dp.toPx(),
                                            radiusY = 10.dp.toPx(),
                                        ),
                                    )
                                }
                                drawPath(
                                    path = outline,
                                    color = criticalDashedColor,
                                    style = Stroke(width = width, pathEffect = dash),
                                )
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                            .alpha(0.55f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                                .border(1.dp, TextMuted, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Lock, null, tint = TextMuted, modifier = Modifier.size(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("critical 操作不支持记住", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "即使已记住同类命令，critical 操作仍必须逐次确认。",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("记住此命令", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        // M-P2-11：可视开关 38×22 不变，外层扩到 44dp 命中区。
                        Box(
                            Modifier.size(44.dp).chatClick(enabled = !expired) { rememberRule = !rememberRule },
                            contentAlignment = Alignment.Center,
                        ) {
                            CarrySwitch(checked = rememberRule, onCheckedChange = { rememberRule = it }, enabled = !expired)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    ApprovalGhostButton(
                        "拒绝",
                        enabled = !expired,
                        modifier = Modifier.weight(1f),
                    ) { onDecide("deny", false) }
                    ApprovalGhostButton(
                        "延长 120s",
                        enabled = !expired && remaining < 300,
                        tint = Amber,
                        modifier = Modifier.weight(1.15f),
                    ) { onExtend(); remaining = (remaining + 120).coerceAtMost(300) }
                    ApprovalSolidButton(
                        "批准",
                        enabled = !expired,
                        modifier = Modifier.weight(1.35f),
                    ) { onDecide("allow", rememberRule && !critical) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "广播至 $deviceCount 台已配对设备 · 首个响应生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        }
    }
}

@Composable
private fun ApprovalGhostButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.height(44.dp).clip(shape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, LineStrong, shape)
            .alpha(if (enabled) 1f else 0.45f)
            .chatPress(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = tint)
    }
}

@Composable
private fun ApprovalSolidButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.height(44.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Rose.copy(alpha = 0.92f), Rose.copy(alpha = 0.66f))))
            .border(1.dp, Rose.copy(alpha = 0.4f), shape)
            .alpha(if (enabled) 1f else 0.45f)
            .chatPress(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White)
    }
}

@Composable
private fun ApprovalKeyValue(key: String, value: String, last: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                key,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
                modifier = Modifier.width(78.dp),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        if (!last) DividerLine()
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 17.sp),
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
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
    val inputSource = remember { MutableInteractionSource() }
    val inputFocused by inputSource.collectIsFocusedAsState()
    val inputBorderColor = if (inputFocused) Violet.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.14f)
    val inputGlowColor = Violet.copy(alpha = 0.18f)
    val focusedNow = inputFocused
    val readonlyDash = Amber.copy(alpha = 0.22f)
    Column(
        Modifier
            .fillMaxWidth()
            // MQ-01：transparent → rgba(9,9,16,.92) 34% → rgba(8,8,14,.99)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.34f to ComposerGradMid.copy(alpha = 0.92f),
                    1f to ComposerGradEnd.copy(alpha = 0.99f),
                ),
            )
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 26.dp),
    ) {
        if (running) {
            Row(
                Modifier.fillMaxWidth().height(24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.height(24.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(1.dp, Line, RoundedCornerShape(8.dp))
                        .padding(2.dp),
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
                MonoLabel(
                    "附件 $done/${pendingImages.size} · ${formatImageSize(pendingImages.sumOf { it.size })}",
                    color = TextMuted,
                )
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
                        Box(
                            Modifier.size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.02f))
                                .drawBehind {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.24f),
                                        style = Stroke(
                                            width = 1.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                                        ),
                                        cornerRadius = CornerRadius(8.dp.toPx()),
                                    )
                                }
                                .chatClick { onPickImage() },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Add, "添加图片", tint = TextMuted, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // MQ-10：4dp 用量条，紫→青；超限玫
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                val ratio = if (usageLimit <= 0) 0f else (usageBytes.toFloat() / usageLimit).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(CircleShape)
                        .background(
                            if (usageBytes > usageLimit) {
                                SolidColor(Rose)
                            } else {
                                Brush.horizontalGradient(listOf(Violet, Cyan))
                            },
                        ),
                )
            }
            Spacer(Modifier.height(7.dp))
        }
        if (imageError != null) {
            // MQ-13：输入区上方图片错误条，只说明原因
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Rose.copy(alpha = 0.06f))
                    .border(1.dp, Rose.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(Icons.Outlined.Info, null, tint = Rose, modifier = Modifier.size(14.dp))
                Text(imageError, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            Spacer(Modifier.height(7.dp))
        }
        if (readOnly) {
            // MQ-11：只读态虚线条（P0），替换输入行，底部不再重复提示
            Box(
                Modifier.fillMaxWidth().height(44.dp)
                    .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
                    .drawBehind {
                        drawRoundRect(
                            color = readonlyDash,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                            ),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = Amber, modifier = Modifier.size(14.dp))
                    Text("只读 · 无法发送", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // MQ-06：添加图片 31dp 图标，44dp 热区，透明底
                Box(
                    Modifier.size(44.dp).chatPress(onClick = onPickImage, scale = 0.94f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, "添加图片", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
                // MQ-05：19 圆角、内高光、聚焦紫边+光
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(19.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(InputGradTop.copy(alpha = 0.85f), InputGradBottom.copy(alpha = 0.92f)),
                            ),
                        )
                        .border(1.dp, inputBorderColor, RoundedCornerShape(19.dp))
                        .drawBehind {
                            if (focusedNow) {
                                drawRoundRect(
                                    color = inputGlowColor,
                                    style = Stroke(width = 3.dp.toPx()),
                                    cornerRadius = CornerRadius(19.dp.toPx()),
                                )
                            }
                            drawLine(
                                Color.White.copy(alpha = 0.06f),
                                Offset(14.dp.toPx(), 1.dp.toPx()),
                                Offset(size.width - 14.dp.toPx(), 1.dp.toPx()),
                                1.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                ) {
                    if (text.isBlank()) {
                        Text(
                            if (running) "插入一条消息…" else "给 pi 发消息… 输入 / 唤起命令",
                            style = MaterialTheme.typography.bodyMedium, color = TextMuted,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { value ->
                            if (value == "/" && text.isBlank()) onCommands() else onText(value)
                        },
                        interactionSource = inputSource,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontSize = 13.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(Violet),
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // MQ-08：32dp 渐变发送按钮（#8D7DFF → #5F4DF0）
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (canSend) {
                                Brush.linearGradient(listOf(SendGradStart, SendGradEnd))
                            } else {
                                Brush.linearGradient(listOf(TextMuted.copy(alpha = 0.14f), TextMuted.copy(alpha = 0.10f)))
                            },
                        )
                        .drawBehind {
                            if (canSend) {
                                drawLine(
                                    Color.White.copy(alpha = 0.22f),
                                    Offset(6.dp.toPx(), 1.dp.toPx()),
                                    Offset(size.width - 6.dp.toPx(), 1.dp.toPx()),
                                    1.dp.toPx(),
                                )
                            }
                        }
                        .chatPress(onClick = onSend, scale = 0.94f, enabled = canSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        "发送",
                        tint = if (canSend) Color.White else TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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
    // MQ-02/MQ-04：高 22、11sp，激活紫底 .20 + 内描边 .28；打断玫边 .26 + 玫底 .07。
    val color = if (danger) StopInk else if (active) VioletSoft else TextMuted
    val bg = when {
        danger -> Rose.copy(alpha = 0.07f)
        active -> Violet.copy(alpha = 0.20f)
        else -> Color.Transparent
    }
    val borderColor = when {
        danger -> Rose.copy(alpha = 0.26f)
        active -> Violet.copy(alpha = 0.28f)
        else -> Color.Transparent
    }
    Row(
        modifier.height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp)
            .chatClick(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
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
        Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
            .background(Surface2)
            .border(1.dp, if (item.error != null) Rose.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
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
                modifier = Modifier.align(Alignment.Center).size(15.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
        ) {
            Text(
                formatImageSize(item.size),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
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
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)).chatClick(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text("重试", style = MaterialTheme.typography.labelSmall, color = Rose)
            }
        } else if (!item.uploading && item.blobId != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).size(14.dp).clip(CircleShape).background(Mint.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, null, tint = Ink, modifier = Modifier.size(9.dp)) }
        }
        // 17dp 的移除按钮点不到：外面套 44dp 的可点区域，圆形仍按 17dp 绘制（BUG-020）。
        Box(
            Modifier.align(Alignment.TopEnd).size(44.dp).chatClick(onClick = onRemove),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                Modifier.padding(top = 2.dp, end = 2.dp).size(17.dp).clip(CircleShape)
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
    // 重组时重放一次补齐（见下方 LaunchedEffect）。
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
            CarrySheetHeader(title = "模型与思考等级", subtitle = "切换在下一轮对话生效", onClose = onClose)
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(
                "节点未返回可用模型，请重试",
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
            .padding(bottom = 26.dp),
    ) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "模型与思考等级", subtitle = "切换在下一轮对话生效", onClose = onClose)
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

        Spacer(Modifier.height(16.dp))
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
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .chatClick(enabled = !busy) { retryLevels() },
                        contentAlignment = Alignment.Center,
                    ) { Text("重试", style = MaterialTheme.typography.labelSmall, color = Amber) }
                }
            } else if (levelList.size == 5) {
                // MB-42：恰好 5 档时用 5 列网格（按钮 30 高、圆角 8、mono 11、热区 44）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    levelList.forEach { level ->
                        val active = !levelsError && level == pendingLevel
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .chatClick(enabled = !busy && !levelsError) { commitThinking(level) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (active) Violet.copy(alpha = 0.20f) else PanelHigh)
                                    .border(1.dp, if (active) Violet.copy(alpha = 0.34f) else Line, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    thinkingLabel(level),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = if (active) Violet else TextMuted,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    levelList.forEach { level ->
                        val active = !levelsError && level == pendingLevel
                        // 热区至少 44dp（U-09）：外层 Box 撑热区，内层胶囊尺寸与样式不变。
                        // 提交链进行中或等级未知时禁止点按：否则会把一个不属于该模型的等级写进去。
                        Box(
                            Modifier
                                .sizeIn(minHeight = 44.dp)
                                .clip(RoundedCornerShape(50))
                                .chatClick(enabled = !busy && !levelsError) { commitThinking(level) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (active) Violet.copy(alpha = 0.20f) else PanelHigh)
                                    .border(1.dp, if (active) Violet.copy(alpha = 0.34f) else Line, RoundedCornerShape(50))
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    thinkingLabel(level),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = if (active) Violet else TextMuted,
                                )
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
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                            .chatClick { pending?.let { commitModel(it) } },
                        contentAlignment = Alignment.Center,
                    ) { Text("重试", style = MaterialTheme.typography.labelSmall, color = Amber) }
                }
            }
            Spacer(Modifier.height(6.dp))
            // MB-43：提示框（白 .025 + 1px line + 圆角 10）
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.025f))
                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "高等级会显著增加思考 token 与首字延迟。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
            }
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
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isPending || isCurrent) Violet.copy(alpha = 0.10f) else PanelHigh.copy(alpha = 0.35f),
                        )
                        .border(
                            1.dp,
                            when {
                                isCurrent -> Violet.copy(alpha = 0.28f)
                                isPending -> Violet.copy(alpha = 0.20f)
                                else -> Line
                            },
                            RoundedCornerShape(12.dp),
                        )
                        .chatClick(enabled = !busy) { onPick(m) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Violet.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) { Text(m.name.take(1), style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp), color = Violet) }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                m.name,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
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
                        Spacer(Modifier.height(1.dp))
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
                        MonoLabel(m.priceText, color = TextMuted)
                    }
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
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .chatClick(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val summaryParts = listOf(turn.durationText, turn.tokenText)
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "—" }
            if (summaryParts.isNotEmpty()) {
                Text(
                    summaryParts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted,
                )
            }
            if (turn.aborted) {
                Text("已中断", style = MaterialTheme.typography.labelSmall, color = Amber)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, "查看轨迹", tint = TextMuted.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
        }
    }
}

/** 轨迹面板：字段缺失一律显示 `—`，不拿 0 冒充真实值。 */
@Composable
private fun TurnDetailSheet(turn: Turn, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "本轮轨迹", subtitle = if (turn.aborted) "已中断" else null, onClose = onClose)
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
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
            "旧节点未上报 usage，缺失项显示 —",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        }
    }
}

@Composable
private fun CommandSheet(onClose: () -> Unit, onPick: (CommandItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val visible = remember(query) {
        commands.filter {
            it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        CarrySheetGrabber()
        CarrySheetHeader(title = "命令面板", onClose = onClose)
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 26.dp)) {
            // MB-50：42dp 搜索框，紫色边与底，mono 12.5，前缀 / 15
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Violet),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Violet.copy(alpha = 0.07f))
                    .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                decorationBox = { inner ->
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("/", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = Violet)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text("搜索命令", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                            inner()
                        }
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            visible.forEach { cmd ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .chatClick { onPick(cmd) }
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "/",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = Violet,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            cmd.name,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                            ),
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(cmd.description, style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Tag(cmd.source, TextMuted)
                }
            }
            Spacer(Modifier.height(8.dp))
            // MB-52：底栏计数
            Text(
                "${visible.size} 条命令 · ${commands.map { it.source }.distinct().size} 个来源",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
            )
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
