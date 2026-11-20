package dev.picarry.app.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

private data class PairInfo(
    val endpointId: String = "",
    val token: String = "",
    val relay: String? = null,
    val addrs: List<String> = emptyList(),
    val name: String = "",
)

@Composable
fun PairingScreen(repo: CarryRepository, initialUri: String, onBack: () -> Unit, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val link by repo.link.collectAsState()
    var endpoint by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var uriText by remember { mutableStateOf(initialUri) }
    var error by remember { mutableStateOf<String?>(null) }
    var waitingId by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<PairInfo?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (!content.isNullOrBlank()) {
            uriText = content
            val info = parsePairUri(content)
            if (info == null) error = "二维码不是有效的 pi-carry 配对链接" else {
                parsed = info
                endpoint = info.endpointId
                error = null
            }
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri.isNotBlank()) {
            parsePairUri(initialUri)?.let {
                parsed = it
                endpoint = it.endpointId
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

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ArrowBack, "返回", tint = TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            MonoLabel("配对 · PAIRING")
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("配对新节点", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text("在电脑上运行 pi-carry，二维码 10 分钟内有效。", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(214.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Panel.copy(alpha = 0.8f))
                .border(0.7.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .clickable {
                    scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描 pi-carry 配对二维码").setBeepEnabled(false).setOrientationLocked(false))
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(78.dp).clip(RoundedCornerShape(20.dp)).background(Violet.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.QrCodeScanner, null, tint = Violet, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.height(12.dp))
                Text("扫描电脑上的二维码", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("点击打开相机", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
        }

        Spacer(Modifier.height(22.dp))
        MonoLabel("或手动输入")
        Spacer(Modifier.height(8.dp))
        Field(endpoint, { endpoint = it.trim(); parsed = null }, "Endpoint ID（64 位十六进制）")
        Spacer(Modifier.height(9.dp))
        Field(code, { code = it.filter(Char::isDigit).take(6) }, "6 位配对码", KeyboardType.NumberPassword, monospace = true, big = true)
        if (parsed == null && endpoint.isBlank() && uriText.isBlank()) {
            // 保持提示，减少空白感
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Panel.copy(alpha = 0.55f))
                .border(0.7.dp, Line, RoundedCornerShape(13.dp))
                .clickable {
                    val text = clipboard.getText()?.text.orEmpty()
                    uriText = text
                    val info = parsePairUri(text)
                    if (info == null) error = "剪贴板不是 pi-carry 配对链接" else {
                        parsed = info; endpoint = info.endpointId; error = null
                    }
                }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.ContentPaste, null, tint = Cyan, modifier = Modifier.size(17.dp))
            Text("从剪贴板粘贴配对链接", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            MonoLabel("paste")
        }

        if (uriText.isNotBlank() && parsed == null) {
            Spacer(Modifier.height(8.dp))
            Text("链接无法解析，请检查是否完整", style = MaterialTheme.typography.bodySmall, color = Amber)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = Rose)
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Cyan.copy(alpha = 0.06f))
                .border(0.7.dp, Cyan.copy(alpha = 0.18f), RoundedCornerShape(13.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Lock, null, tint = Cyan, modifier = Modifier.size(15.dp))
            Text(
                "二维码只包含节点公钥与一次性配对令牌；配对后手机保存节点公钥，所有流量走 iroh 加密通道。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val info = parsed
                val id = info?.endpointId?.takeIf { it.isNotBlank() } ?: endpoint.ifBlank { "" }
                if (id.isBlank()) { error = "请扫描二维码或输入 Endpoint ID"; return@Button }
                val token = info?.token.orEmpty()
                val c = code.trim()
                if (token.isBlank() && c.length != 6) { error = "请输入 6 位配对码"; return@Button }
                error = null
                val node = Node(
                    id = id,
                    name = id.take(8),
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
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White),
            enabled = waitingId == null,
        ) {
            if (waitingId != null) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text("正在建立加密连接…", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("完成配对", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    monospace: Boolean = false,
    big: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(Panel.copy(alpha = 0.72f))
            .border(0.7.dp, Line, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = if (big) 13.dp else 11.dp),
    ) {
        if (value.isBlank()) Text(placeholder, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = if (big) 22.sp else 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                letterSpacing = if (big) 6.sp else 0.sp,
            ),
            cursorBrush = SolidColor(Violet),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun parsePairUri(raw: String): PairInfo? {
    val text = raw.trim()
    if (text.isBlank()) return null
    return runCatching {
        val uri = Uri.parse(text)
        if (uri.scheme != "pi-carry" && uri.scheme != "picarry") return null
        val endpoint = uri.getQueryParameter("endpoint") ?: uri.getQueryParameter("id") ?: return null
        if (endpoint.length < 16) return null
        PairInfo(
            endpointId = endpoint,
            token = uri.getQueryParameter("token").orEmpty(),
            relay = uri.getQueryParameter("relay"),
            addrs = uri.getQueryParameters("addr").filter { it.isNotBlank() },
            name = uri.getQueryParameter("name").orEmpty(),
        )
    }.getOrNull()
}
