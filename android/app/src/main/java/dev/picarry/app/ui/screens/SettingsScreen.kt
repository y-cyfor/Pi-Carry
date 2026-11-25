package dev.picarry.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarryCard
import dev.picarry.app.ui.components.CarrySwitch
import dev.picarry.app.ui.components.CarryIconButton
import dev.picarry.app.ui.components.DividerLine
import dev.picarry.app.ui.components.IconBadge
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.Pill
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.LineStrong
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.PanelHigh
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.Surface2
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft

@Composable
fun SettingsScreen(repo: CarryRepository, onPair: () -> Unit) {
    val context = LocalContext.current
    // 兜底不能写死版本号：0.2.0 的硬编码在 0.3.0 包里会把版本显示成错的（BUG-021）。
    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "—"
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    val themeMode by repo.themeMode.collectAsState()
    val credentialsPlaintext by repo.credentialsPlaintext.collectAsState()
    // 页头状态必须反映真实连接：硬编码「服务运行中」会在断网时骗用户。
    val onlineCount = link.count { it.value == LinkState.Online }
    val headerPill = when {
        nodes.isEmpty() -> Triple("未连接", TextMuted, false)
        onlineCount == nodes.size -> Triple("全部在线", Mint, true)
        onlineCount > 0 -> Triple("$onlineCount/${nodes.size} 在线", Amber, false)
        else -> Triple("离线", Rose, false)
    }
    var approvalNotify by rememberSaveable { mutableStateOf(repo.settingBool("notify_approval", true)) }
    var confirmingRemove by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(26.dp))
        ScreenHeader(
            title = "设置",
            subtitle = "传输、通知与安全边界",
            actions = { Pill(headerPill.first, headerPill.second, filled = headerPill.third) },
        )
        Spacer(Modifier.height(2.dp))

        GroupLabel("外观与连接")
        CarryCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column {
                SettingRow(
                    icon = Icons.Outlined.Palette,
                    title = "主题",
                    subtitle = when (themeMode) {
                        "dark" -> "始终使用黑色主题"
                        "light" -> "始终使用白色主题"
                        else -> "跟随系统黑 / 白模式"
                    },
                    trailing = {},
                )
                ThemeModeSelector(themeMode, onSelect = repo::setThemeMode)
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.Router,
                    title = "自托管 relay",
                    subtitle = nodes.firstOrNull { it.relay != null }?.relay
                        ?: nodes.map { it.relayMode }.firstOrNull { it.isNotBlank() }
                            ?.let { mode ->
                                when (mode) {
                                    "n0", "default" -> "未配置 · 走 n0 公共 relay"
                                    "disabled" -> "未配置 · 仅直连（局域网可用）"
                                    else -> "未配置 · $mode"
                                }
                            }
                        // 没有节点时并不知道节点走哪种 relay，不能声称「使用直连」：
                        // 桌面端默认就是 n0 公共 relay（BUG-022）。
                        ?: "未配置 · 配对后显示",
                    trailing = { MonoLabel("n0 / custom", color = TextMuted) },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.Lan,
                    title = "直连优先",
                    subtitle = "始终先试 UDP 直连，失败再走 relay",
                    trailing = { MonoLabel("默认开启", color = TextMuted) },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    title = "协议版本",
                    subtitle = buildString {
                        append("carry.v1 · 4-byte framing")
                        val pi = nodes.map { it.piVersion }.firstOrNull { it.isNotBlank() }
                        if (pi != null) append(" · pi $pi")
                    },
                    trailing = { MonoLabel("v1", color = Cyan) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        GroupLabel("通知与后台")
        CarryCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column {
                SettingToggle(
                    icon = Icons.Outlined.Notifications,
                    title = "审批通知",
                    subtitle = "高风险操作立即提醒",
                    checked = approvalNotify,
                    onChange = { approvalNotify = it; repo.setSettingBool("notify_approval", it) },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.BatterySaver,
                    title = "前台服务",
                    subtitle = "connectedDevice · 常驻通知",
                    trailing = { Pill("运行中", Mint, filled = true) },
                )
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.BatterySaver,
                    title = "电池优化",
                    subtitle = "小米 / vivo 需允许后台运行",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    trailing = { Chevron() },
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    DividerLine()
                    SettingRow(
                        icon = Icons.Outlined.Notifications,
                        title = "通知权限",
                        subtitle = "在系统设置中管理",
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        trailing = { Chevron() },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("安全 · ${nodes.size} 个节点", color = TextMuted)
            Spacer(Modifier.weight(1f))
            if (nodes.isNotEmpty()) {
                // 热区撑到 44dp：仅靠 6dp padding 时这一行只有约 23dp 高（U-11 残项）。
                Box(
                    Modifier
                        .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onPair),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+ 配对",
                        style = MaterialTheme.typography.labelMedium,
                        color = Violet,
                        modifier = Modifier.padding(horizontal = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 凭据实际未加密时必须让用户看见：Keystore 不可用时仍可配对（兜底），
        // 但用户有权知道 token/secret 只是 Base64 存在本机，不是加密的。
        if (credentialsPlaintext) {
            CarryCard(Modifier.fillMaxWidth(), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Security, Amber, 28)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("凭据未加密存储", style = MaterialTheme.typography.titleSmall, color = Amber)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Keystore 不可用，token 与密钥只做编码处理。请勿在不可信设备上保留本应用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (nodes.isEmpty()) {
            CarryCard(Modifier.fillMaxWidth(), onClick = onPair, padding = 13.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Devices, Violet, 28)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("还没有配对设备", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("扫描电脑上的二维码开始", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            CarryCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column {
                    nodes.forEachIndexed { index, node ->
                        if (index > 0) DividerLine()
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(
                                    if (node.platform.contains("mac", true)) Icons.Outlined.Devices else Icons.Outlined.Lock,
                                    Cyan,
                                    28,
                                )
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(node.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        StatusDot(if (link[node.id] == LinkState.Online) Mint else TextMuted, 5)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    MonoLabel("${node.capability} · ${node.endpointId.take(10)}", color = TextMuted)
                                }
                                Box(
                                    Modifier.size(44.dp).clip(CircleShape).clickable {
                                        confirmingRemove = if (confirmingRemove == node.id) null else node.id
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        "移除 ${node.name}",
                                        tint = if (confirmingRemove == node.id) Rose else TextMuted,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                            if (confirmingRemove == node.id) {
                                Spacer(Modifier.height(9.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Rose.copy(alpha = 0.08f))
                                        .border(0.7.dp, Rose.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("移除后无法连接该节点", style = MaterialTheme.typography.labelSmall, color = Rose, modifier = Modifier.weight(1f))
                                    // 「取消 / 移除」原来只有 8dp padding（约 27dp 高），撑到 44dp（U-11 残项）。
                                    Box(
                                        Modifier
                                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable { confirmingRemove = null },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("取消", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                                    }
                                    Box(
                                        Modifier
                                            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable {
                                                repo.removeNode(node.id); confirmingRemove = null
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("移除", style = MaterialTheme.typography.labelMedium, color = Rose)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            CarryActionButton(
                "配对新节点",
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                kind = dev.picarry.app.ui.components.ButtonKind.Ghost,
                height = 44.dp,
            )
        }

        Spacer(Modifier.height(18.dp))
        GroupLabel("关于")
        CarryCard(Modifier.fillMaxWidth(), padding = 0.dp) {
            Column {
                SettingRow(icon = Icons.Outlined.Science, title = "pi carry", subtitle = "远程驾驶舱 · Android", trailing = { MonoLabel(appVersion, color = TextMuted) })
                DividerLine()
                SettingRow(icon = Icons.Outlined.Security, title = "隐私", subtitle = "无账号、无云同步、凭据仅存本机", trailing = {})
                DividerLine()
                SettingRow(
                    icon = Icons.Outlined.Lock,
                    title = "开源许可",
                    subtitle = "iroh4k · Kotlin · Compose",
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/y-cyfor/Pi-Carry/"))) }
                    },
                    trailing = { Chevron() },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "pi carry $appVersion · 本地优先的远程驾驶舱",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun GroupLabel(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MonoLabel(text, color = TextMuted)
        Box(Modifier.weight(1f).height(0.7.dp).background(Line))
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(15.dp))
}

@Composable
private fun ThemeModeSelector(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("auto", "自动", Icons.Outlined.BrightnessAuto),
        Triple("dark", "黑色", Icons.Outlined.DarkMode),
        Triple("light", "白色", Icons.Outlined.LightMode),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 13.dp, end = 13.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelHigh.copy(alpha = 0.7f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label, icon) ->
            val selected = current == key
            Row(
                Modifier
                    .weight(1f)
                    // 30dp 高的分段低于 48dp 触屏下限：可视高度保持，靠外层撑到 44dp
                    // （U-11/BUG-020）。3 段并排时再高会挤压整卡高度，44dp 是折中。
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) Violet.copy(alpha = 0.16f) else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(0.8.dp, Violet.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .clickable { onSelect(key) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (selected) VioletSoft else TextMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) VioletSoft else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, TextSecondary, 28)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(8.dp))
        trailing()
    }
}

@Composable
private fun SettingToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, Violet, 28)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(Modifier.height(1.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        CarrySwitch(checked = checked, onCheckedChange = null)
    }
}
