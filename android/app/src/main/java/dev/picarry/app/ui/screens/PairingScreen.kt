package dev.picarry.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Shield
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.ButtonKind
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Inter
import dev.picarry.app.ui.theme.JetBrainsMono
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
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

// token: 信任提示正文 #9FE0DC（深色）/ #0B6F6B（浅色），Theme.kt 未收录（C.6 MT-09）。
private val TrustTextDark = Color(0xFF9FE0DC)
private val TrustTextLight = Color(0xFF0B6F6B)

/** 11sp/1.6 的小正文：设计稿正文 10px 字号，按 A.3.3 抬到 11sp。 */
private val TrustTextStyle = TextStyle(
    fontFamily = Inter,
    fontSize = 11.sp,
    lineHeight = 17.6.sp,
    letterSpacing = 0.sp,
)

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
    // 深链只在进入时预填：用户自己扫码/粘贴之后，这一行不能再声称来自外部链接。
    val fromDeepLink = initialUri.isNotBlank() && parsed != null && uriText == initialUri
    // 只解析初始链接失败时才有这个提示：扫码/粘贴都会先校验再写 uriText。
    val linkInvalid = uriText.isNotBlank() && parsed == null
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (!content.isNullOrBlank()) {
            // 先解析再写：扫到无效内容时不能把之前已扫到的有效链接/令牌冲掉。
            val info = parsePairUri(content)
            if (info == null) {
                error = "配对失败：二维码不是 pi-carry 配对链接"
            } else {
                uriText = content
                endpoint = info.endpointId
                error = null
            }
        }
    }
    val launchScanner: () -> Unit = {
        scanner.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描 pi-carry 配对二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    LaunchedEffect(waitingId, link) {
        val id = waitingId ?: return@LaunchedEffect
        when (link[id]) {
            LinkState.Online -> { waitingId = null; onDone() }
            LinkState.Error -> { waitingId = null; error = "配对失败：无法连接节点" }
            else -> Unit
        }
    }
    // 没有超时兜底时，连接既不成功也不失败就会一直停在「正在建立加密连接」。
    LaunchedEffect(waitingId) {
        if (waitingId == null) return@LaunchedEffect
        delay(PAIR_TIMEOUT_MS)
        if (waitingId != null) {
            waitingId = null
            error = "配对失败：等待连接超时（30 秒）"
        }
    }

    val cancelWaiting: () -> Unit = {
        val id = waitingId
        waitingId = null
        error = null
        // 取消后停掉这次连接尝试，否则后台继续连上而界面已经放弃。
        if (id != null && link[id] != LinkState.Online) repo.disconnect(id)
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
        // MT-01（M-P2-01）：pair-head 设计 padding-top 64 含 56 状态栏 → 状态栏 inset + 8dp。
        // statusBarsPadding() 已提供 inset，这里只补 8dp，不再叠加固定 26dp（真机标题 y≈64dp）。
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            CarryIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, size = 34)
            Spacer(Modifier.width(10.dp))
            Text(
                "配对新节点",
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.57).sp),
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            CarryIconButton(Icons.Outlined.QrCodeScanner, "扫描二维码", onClick = launchScanner, size = 34)
        }
        Spacer(Modifier.height(6.dp))
        // 首屏只留一行说明；失效提示只在深链文本本身解析失败时出现。
        Text(
            buildAnnotatedString {
                append("在电脑上运行 ")
                withStyle(SpanStyle(fontFamily = JetBrainsMono, color = TextSecondary)) { append("pi-carry pair") }
                append("，二维码 10 分钟内有效")
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (fromDeepLink) {
            Spacer(Modifier.height(4.dp))
            Text(
                "来自外部链接",
                style = MaterialTheme.typography.labelLarge,
                color = TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (linkInvalid) {
            Spacer(Modifier.height(6.dp))
            Text(
                "链接无效",
                style = MaterialTheme.typography.bodySmall,
                color = Rose,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // 白色扫码卡是相机入口，不是展示用的假二维码。
            Box(
                Modifier
                    .size(198.dp)
                    .shadow(
                        elevation = 24.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color.Black.copy(alpha = 0.85f),
                        spotColor = Color.Black.copy(alpha = 0.85f),
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = launchScanner,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PhotoCamera,
                    "打开相机扫描二维码",
                    tint = Violet,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (parsed != null) {
                Text("已读取链接", style = MaterialTheme.typography.labelLarge, color = Cyan)
                Spacer(Modifier.height(2.dp))
            }
            // 粘贴入口只保留这一个文本按钮，不加底色（MT-11）。
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        val text = clipboard.getText()?.text.orEmpty()
                        val info = parsePairUri(text)
                        if (info == null) {
                            error = "配对失败：剪贴板里没有配对链接"
                        } else {
                            uriText = text
                            endpoint = info.endpointId
                            error = null
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Outlined.ContentPaste, null, tint = Cyan, modifier = Modifier.size(13.dp))
                Text("从剪贴板粘贴链接", style = MaterialTheme.typography.labelLarge, color = Cyan)
            }
        }

        Spacer(Modifier.height(4.dp))
        StepsRow()

        Spacer(Modifier.height(22.dp))
        // 这一行只是 6 位码的标题：粘贴动作已在二维码区下方，标签与按钮语义不再错位。
        Text(
            "或手动输入 6 位码",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        CodeBoxes(code, isDark) { code = it.filter(Char::isDigit).take(6) }

        Spacer(Modifier.height(14.dp))
        EndpointIdCard(endpoint) { endpoint = it.trim().lowercase(); uriText = "" }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(Cyan.copy(alpha = 0.055f))
                .border(1.dp, Cyan.copy(alpha = 0.16f), RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Shield,
                null,
                tint = if (isDark) TrustTextDark else TrustTextLight,
                modifier = Modifier.size(14.dp).padding(top = 1.dp),
            )
            Text(
                "二维码只含 EndpointId 与一次性 nonce；配对后公钥固定在手机上，流量端到端加密。",
                style = TrustTextStyle,
                color = if (isDark) TrustTextDark else TrustTextLight,
            )
        }

        Spacer(Modifier.height(16.dp))
        // 取消 + 完成配对并排；等待中取消保留在左按钮，不再额外放一个取消入口。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CarryActionButton(
                "取消",
                onClick = { if (waitingId != null) cancelWaiting() else onBack() },
                modifier = Modifier.weight(1f),
                kind = ButtonKind.Ghost,
                height = 46.dp,
            )
            CarryActionButton(
                if (waitingId != null) "正在建立加密连接…" else "完成配对",
                onClick = {
                    val info = parsed
                    val id = info?.endpointId ?: endpoint.trim()
                    if (id.isBlank()) { error = "配对失败：请输入 Endpoint ID"; return@CarryActionButton }
                    if (!ENDPOINT_ID.matches(id)) { error = "配对失败：Endpoint ID 必须是 64 位十六进制"; return@CarryActionButton }
                    val token = info?.token.orEmpty()
                    val c = code.trim()
                    if (token.isBlank() && c.length != 6) { error = "配对失败：请输入 6 位配对码"; return@CarryActionButton }
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
                modifier = Modifier.weight(1.45f),
                enabled = waitingId == null,
                height = 46.dp,
            )
        }
        if (waitingId != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(13.dp), color = Violet, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(7.dp))
                Text("等待节点确认…", style = MaterialTheme.typography.labelMedium, color = TextMuted)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/** 设计稿 steps：`1 扫描 — 2 校验指纹 — 3 命名节点`，步骤之间用 1px line 连接。 */
@Composable
private fun StepsRow() {
    val label = MaterialTheme.typography.labelLarge
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        StepNumber("1")
        Text("扫描", style = label, color = TextMuted)
        StepConnector(Modifier.weight(1f))
        StepNumber("2")
        Text("校验指纹", style = label, color = TextMuted)
        StepConnector(Modifier.weight(1f))
        StepNumber("3")
        Text("命名节点", style = label, color = TextMuted)
    }
}

@Composable
private fun StepNumber(number: String) {
    Text(
        number,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
        color = TextSecondary,
    )
}

@Composable
private fun StepConnector(modifier: Modifier) {
    Box(modifier.height(1.dp).background(Line))
}

/** 设计稿没有 Endpoint ID 卡（派生）：保留手动配对能力，但压成扁平卡、只留占位说明。 */
@Composable
private fun EndpointIdCard(value: String, onChange: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Text(
            "ENDPOINT ID",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
            color = TextMuted,
        )
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 11.sp,
                fontFamily = JetBrainsMono,
                letterSpacing = 0.sp,
            ),
            cursorBrush = SolidColor(Violet),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isBlank()) {
                        Text("64 位十六进制", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun CodeBoxes(code: String, isDark: Boolean, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // 深色下用白 .04 提亮，浅色下用黑 .04 压暗；两边都是设计稿的 ink .04。
    val idleBg = if (isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.04f)
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focusRequester.requestFocus() },
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        ) {
            repeat(6) { index ->
                val char = code.getOrNull(index)?.toString().orEmpty()
                val active = focused && (index == code.length.coerceAtMost(5))
                Box(
                    Modifier
                        .width(43.dp)
                        .height(54.dp)
                        .then(
                            if (active) {
                                Modifier.shadow(
                                    elevation = 8.dp,
                                    shape = RoundedCornerShape(11.dp),
                                    ambientColor = Violet.copy(alpha = 0.55f),
                                    spotColor = Violet.copy(alpha = 0.55f),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (active) Violet.copy(alpha = 0.08f) else idleBg)
                        .border(
                            1.dp,
                            if (active) Violet.copy(alpha = 0.75f) else LineStrong,
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        char,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = JetBrainsMono,
                            fontSize = 20.sp,
                        ),
                        color = TextPrimary,
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
