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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.ui.components.CarryCard
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
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Panel
import dev.picarry.app.ui.theme.PanelHigh
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.TextSecondary
import dev.picarry.app.ui.theme.Violet

@Composable
fun SettingsScreen(repo: CarryRepository, onPair: () -> Unit) {
    val context = LocalContext.current
    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0.1.3"
    val nodes by repo.nodes.collectAsState()
    val link by repo.link.collectAsState()
    val themeMode by repo.themeMode.collectAsState()
    var approvalNotify by rememberSaveable { mutableStateOf(repo.settingBool("notify_approval", true)) }
    var confirmingRemove by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        ScreenHeader(
            title = "设置",
            subtitle = "传输、通知与安全边界",
            actions = { Pill("服务运行中", Mint, filled = true) },
        )
        Spacer(Modifier.height(8.dp))

        SectionLabel("外观")
        CarryCard(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Palette, Violet, 32)
                    Spacer(Modifier.size(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("主题", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when (themeMode) {
                                "dark" -> "始终使用黑色主题"
                                "light" -> "始终使用白色主题"
                                else -> "跟随系统当前的黑夜 / 白天模式"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ThemeModeSelector(themeMode, onSelect = repo::setThemeMode)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("传输")
        CarryCard(Modifier.fillMaxWidth()) {
            Column {
                SettingRow(
                    icon = Icons.Outlined.Router,
                    title = "自托管 relay",
                    subtitle = nodes.firstOrNull { it.relay != null }?.relay ?: "未配置 · 使用直连",
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
                    subtitle = "carry.v1 · 4-byte framing",
                    trailing = { MonoLabel("v1", color = Cyan) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("通知")
        CarryCard(Modifier.fillMaxWidth()) {
            Column {
                SettingToggle(
                    icon = Icons.Outlined.Notifications,
                    title = "审批通知",
                    subtitle = "高风险操作立即提醒",
                    checked = approvalNotify,
                    onChange = { approvalNotify = it; repo.setSettingBool("notify_approval", it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("后台与电池")
        CarryCard(Modifier.fillMaxWidth()) {
            Column {
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
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
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
                        trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("安全", trailing = "${nodes.size} 个节点")
        if (nodes.isEmpty()) {
            CarryCard(Modifier.fillMaxWidth(), onClick = onPair) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.Devices, Violet)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("还没有配对设备", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text("扫描电脑上的二维码开始", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted)
                }
            }
        } else {
            nodes.forEach { node ->
                CarryCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(if (node.platform.contains("mac", true)) Icons.Outlined.Devices else Icons.Outlined.Lock, Cyan)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(node.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    StatusDot(if (link[node.id] == LinkState.Online) Mint else TextMuted, 5)
                                }
                                Spacer(Modifier.height(2.dp))
                                MonoLabel("${node.capability} · ${node.endpointId.take(10)}", color = TextMuted)
                            }
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).clickable {
                                    confirmingRemove = if (confirmingRemove == node.id) null else node.id
                                },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.DeleteOutline, "移除", tint = if (confirmingRemove == node.id) Rose else TextMuted, modifier = Modifier.size(18.dp)) }
                        }
                        if (confirmingRemove == node.id) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Rose.copy(alpha = 0.08f))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("移除后本机将无法连接该节点", style = MaterialTheme.typography.bodySmall, color = Rose, modifier = Modifier.weight(1f))
                                Text("取消", style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.clickable { confirmingRemove = null }.padding(6.dp))
                                Text("移除", style = MaterialTheme.typography.labelMedium, color = Rose, modifier = Modifier.clickable {
                                    repo.removeNode(node.id); confirmingRemove = null
                                }.padding(6.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .border(0.7.dp, Line, RoundedCornerShape(13.dp))
                    .clickable(onClick = onPair)
                    .padding(13.dp),
                horizontalArrangement = Arrangement.Center,
            ) { Text("+ 配对新节点", style = MaterialTheme.typography.bodyMedium, color = Violet) }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("关于")
        CarryCard(Modifier.fillMaxWidth()) {
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
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
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
            .clip(RoundedCornerShape(14.dp))
            .background(PanelHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label, icon) ->
            val selected = current == key
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Panel else Color.Transparent)
                    .then(
                        if (selected) Modifier.border(0.7.dp, Violet.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                        else Modifier
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (selected) Violet else TextMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) TextPrimary else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(text)
        Spacer(Modifier.weight(1f))
        if (trailing != null) MonoLabel(trailing, color = TextMuted)
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
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, TextSecondary, 32)
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, Violet, 32)
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Violet,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = Panel,
                uncheckedBorderColor = Line,
            ),
        )
    }
}
