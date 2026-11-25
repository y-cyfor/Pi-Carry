package dev.picarry.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.Surface2
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft
import kotlinx.coroutines.delay

internal data class PairInfo(
    val endpointId: String = "",
    val token: String = "",
    val relay: String? = null,
    val addrs: List<String> = emptyList(),
    val name: String = "",
)

/** Endpoint ID 是节点公钥的 64 位十六进制串，手输时先校验再落库。 */
private val ENDPOINT_ID = Regex("^[0-9a-fA-F]{64}$")

@Composable
fun PairingScreen(repo: CarryRepository, initialUri: String, onBack: () -> Unit, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val link by repo.link.collectAsState()
    // 旋转/进程重建后输入不能丢：基本类型都存 rememberSaveable。
    var endpoint by rememberSaveable(initialUri) { mutableStateOf(parsePairUri(initialUri)?.endpointId.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var uriText by rememberSaveable(initialUri) { mutableStateOf(initialUri) }
    var error by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    var waitingId by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    // PairInfo 不是可保存类型：令牌与直连地址按链接文本重新解析，避免退化到只剩 Endpoint ID。
    val parsed = remember(uriText) { parsePairUri(uriText) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (!content.isNullOrBlank()) {
            // 先解析再写：扫到无效内容时不能把之前已扫到的有效链接/令牌冲掉。
            val info = parsePairUri(content)
            if (info == null) {
                error = "二维码不是有效的 pi-carry 配对链接"
            } else {
                uriText = content
                endpoint = info.endpointId
                error = null
            }
        }
    }

    LaunchedEffect(waitingId, link) {
        val id = waitingId ?: return@LaunchedEffect
        when (link[id]) {
            LinkState.Online -> { waitingId = null; onDone() }
            LinkState.Error -> { waitingId = null; error = "无法连接节点，请确认电脑端已启动并处于同一网络" }
            else -> Unit
        }
    }
    // 没有超时兜底时，连接既不成功也不失败就会一直停在“正在建立加密连接”。
    LaunchedEffect(waitingId) {
        if (waitingId == null) return@LaunchedEffect
        delay(PAIR_TIMEOUT_MS)
        if (waitingId != null) {
            waitingId = null
            error = "等待连接超时（30 秒），请确认电脑端已启动并与手机在同一网络后重试"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        Row(
            Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CarryIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, size = 34)
            Spacer(Modifier.width(10.dp))
            Text(
                "配对新节点",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            CarryIconButton(Icons.Outlined.QrCodeScanner, "扫描二维码", onClick = {
                scanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("扫描 pi-carry 配对二维码")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }, size = 34)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            // 「二维码 10 分钟内有效」只在二维码区下方出现一次：同一信息重复两遍
            // 会让用户以为是两个不同的有效期（BUG-017）。
            "在电脑上运行 pi-carry pair，然后扫描二维码",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(198.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .clickable {
                        scanner.launch(
                            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("扫描 pi-carry 配对二维码")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // 设计稿的白色 QR 区在这里承担“取景/扫码”入口，而不是展示一个假二维码。
                // 内框必须给底部的「点击打开相机」留出空间，否则边框会横穿文字（BUG-009）。
                Box(
                    Modifier.size(140.dp).border(1.dp, Color(0xFFDADAE3), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null, tint = Color(0xFF1A1A2E), modifier = Modifier.size(62.dp))
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MonoLabel("点击打开相机", color = Color(0xFF51516A))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (parsed != null) "已读取二维码链接" else "二维码 10 分钟内有效",
                style = MaterialTheme.typography.labelLarge,
                color = if (parsed != null) Cyan else TextMuted,
            )
            // 粘贴入口只保留这一个：之前底部还有一个「从剪贴板读取链接」，
            // 同一动作两个入口、且「或手动输入 6 位码」旁边放的是粘贴动作（BUG-017）。
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        val text = clipboard.getText()?.text.orEmpty()
                        val info = parsePairUri(text)
                        if (info == null) {
                            error = "剪贴板不是 pi-carry 配对链接"
                        } else {
                            uriText = text
                            endpoint = info.endpointId
                            error = null
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Outlined.ContentPaste, null, tint = Cyan, modifier = Modifier.size(13.dp))
                Text("从剪贴板粘贴链接", style = MaterialTheme.typography.labelMedium, color = Cyan)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PairStep(1, "扫描")
            Spacer(Modifier.width(16.dp))
            PairStep(2, "校验指纹")
            Spacer(Modifier.width(16.dp))
            PairStep(3, "命名节点")
        }

        Spacer(Modifier.height(22.dp))
        // 这一行只是 6 位码的标题：粘贴链接的动作已挪到二维码区下方，
        // 标签与按钮语义不再错位（BUG-017）。
        MonoLabel("或手动输入 6 位码", color = TextMuted)
        Spacer(Modifier.height(7.dp))
        CarryCard(Modifier.fillMaxWidth(), padding = 12.dp) {
            Column {
                MonoLabel("ENDPOINT ID", color = TextMuted)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.trim().lowercase(); uriText = "" },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.2.sp),
                    cursorBrush = SolidColor(Violet),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    decorationBox = { inner ->
                        if (endpoint.isBlank()) Text("64 位十六进制公钥", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                        inner()
                    },
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        CodeBoxes(code) { code = it.filter(Char::isDigit).take(6) }
        Spacer(Modifier.height(7.dp))
        Text(
            "扫码会自动使用二维码里的一次性令牌；6 位码是没有摄像头时的备用方式。",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )

        if (uriText.isNotBlank() && parsed == null) {
            Spacer(Modifier.height(8.dp))
            Text("链接无法解析，请检查是否完整", style = MaterialTheme.typography.bodySmall, color = Amber)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Cyan.copy(alpha = 0.06f))
                .border(0.7.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(11.dp))
                .padding(11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(14.dp))
            Text(
                "二维码只包含节点公钥与一次性配对令牌；配对后所有流量走 iroh 加密通道。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        Spacer(Modifier.height(16.dp))
        CarryActionButton(
            if (waitingId != null) "正在建立加密连接…" else "完成配对",
            onClick = {
                val info = parsed
                val id = info?.endpointId ?: endpoint.trim()
                if (id.isBlank()) { error = "请扫描二维码或输入 Endpoint ID"; return@CarryActionButton }
                if (!ENDPOINT_ID.matches(id)) { error = "Endpoint ID 必须是 64 位十六进制"; return@CarryActionButton }
                val token = info?.token.orEmpty()
                val c = code.trim()
                if (token.isBlank() && c.length != 6) { error = "请输入 6 位配对码"; return@CarryActionButton }
                error = null
                val node = Node(
                    id = id,
                    // 二维码里的 name 是电脑端配置的节点名：用它代替 id 前 8 位，多台机器才分得清。
                    name = info?.name?.trim()?.takeIf { it.isNotEmpty() } ?: id.take(8),
                    endpointId = id,
                    token = token,
                    code = c.ifBlank { null },
                    addrs = info?.addrs.orEmpty(),
                    // 手动 6 位码没有二维码里的直连地址，默认走 n0 relay 才能连上。
                    relay = info?.relay ?: if (token.isBlank()) "n0" else null,
                    platform = "desktop",
                )
                repo.addNode(node)
                waitingId = id
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = waitingId == null,
            height = 46.dp,
        )
        if (waitingId != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(13.dp), color = Violet, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(7.dp))
                Text("等待节点确认…", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.width(14.dp))
                Text(
                    "取消",
                    style = MaterialTheme.typography.labelMedium,
                    color = Rose,
                    modifier = Modifier.clickable {
                        val id = waitingId
                        waitingId = null
                        error = null
                        // 取消后停掉这次连接尝试，否则后台继续连上而界面已经放弃。
                        if (id != null && link[id] != LinkState.Online) repo.disconnect(id)
                    }.padding(horizontal = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun PairStep(number: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(17.dp).clip(CircleShape).background(Violet.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = VioletSoft,
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun CodeBoxes(code: String, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .clickable { focusRequester.requestFocus() },
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            repeat(6) { index ->
                val char = code.getOrNull(index)?.toString().orEmpty()
                val active = focused && (index == code.length.coerceAtMost(5))
                Box(
                    Modifier
                        .width(43.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) Violet.copy(alpha = 0.08f) else Surface2.copy(alpha = 0.55f))
                        .border(
                            if (active) 1.2.dp else 0.8.dp,
                            if (active) Violet else LineStrong,
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        char,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (char.isBlank()) TextMuted else VioletSoft,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        BasicTextField(
            value = code,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .matchParentSize()
                .alpha(0.01f)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
        )
    }
}

/** Manual 配对等待的上限：超时后释放按钮并提示，避免一直卡在连接中。 */
private const val PAIR_TIMEOUT_MS = 30_000L

internal fun parsePairUri(raw: String): PairInfo? {
    val text = raw.trim()
    if (text.isBlank()) return null
    return runCatching {
        val uri = Uri.parse(text)
        if (uri.scheme != "pi-carry" && uri.scheme != "picarry") return null
        // 深链只允许 pair 这个 host：其它 host 的链接不是配对链接。
        if (uri.host != "pair") return null
        // 与桌面端 pair_uri::parse 一致：只接受 v=1。忽略 v 会让未来协议的字段
        // 被旧客户端按旧语义静默解析（BUG-026）。
        if (uri.getQueryParameter("v") != "1") return null
        val endpoint = (uri.getQueryParameter("endpoint") ?: uri.getQueryParameter("id") ?: return null).lowercase()
        if (!ENDPOINT_ID.matches(endpoint)) return null
        PairInfo(
            endpointId = endpoint,
            token = uri.getQueryParameter("token").orEmpty(),
            relay = uri.getQueryParameter("relay"),
            addrs = uri.getQueryParameters("addr").filter { it.isNotBlank() },
            name = uri.getQueryParameter("name").orEmpty(),
        )
    }.getOrNull()
}
