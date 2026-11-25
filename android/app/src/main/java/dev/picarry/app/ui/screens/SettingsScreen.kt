package dev.picarry.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.picarry.app.data.CarryRepository
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.ui.components.ButtonKind
import dev.picarry.app.ui.components.CarryActionButton
import dev.picarry.app.ui.components.CarrySwitch
import dev.picarry.app.ui.components.MonoLabel
import dev.picarry.app.ui.components.NullableStringSaver
import dev.picarry.app.ui.components.ScreenHeader
import dev.picarry.app.ui.components.StatusDot
import dev.picarry.app.ui.components.Tag
import dev.picarry.app.ui.theme.AccentSoft
import dev.picarry.app.ui.theme.Amber
import dev.picarry.app.ui.theme.Cyan
import dev.picarry.app.ui.theme.Inter
import dev.picarry.app.ui.theme.JetBrainsMono
import dev.picarry.app.ui.theme.Line
import dev.picarry.app.ui.theme.Mint
import dev.picarry.app.ui.theme.Rose
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.TextPrimary
import dev.picarry.app.ui.theme.Violet
import dev.picarry.app.ui.theme.VioletSoft

// token: 设置行标题 11.5/500/-.005em（B.3.2 设置行字阶，全局 titleSmall 为 13.5 偏大）。
private val SetTitleStyle = TextStyle(
    fontFamily = Inter,
    fontSize = 11.5.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.06).sp,
)

// token: 设置行副标题 11sp mono（设计稿 9.5px mono，A.3.3 抬到 11sp）。
private val SetSubtitleStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
)

private fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

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
    // pill 只表达连接状态（E.2.6）：已连接 / 连接中 / 未连接。
    val headerStatus = when {
        nodes.isEmpty() -> Triple("未连接", TextMuted, false)
        link.any { it.value == LinkState.Connecting } -> Triple("连接中", Amber, false)
        onlineCount > 0 -> Triple("已连接", Mint, true)
        else -> Triple("未连接", TextMuted, false)
    }
    var approvalNotify by rememberSaveable { mutableStateOf(repo.settingBool("notify_approval", true)) }
    var confirmingRemove by rememberSaveable(stateSaver = NullableStringSaver) { mutableStateOf<String?>(null) }
    val piVersion = remember(nodes) { nodes.map { it.piVersion }.firstOrNull { it.isNotBlank() } }

    // MSY-04：通知权限改成「打开审批通知开关时」才请求，不在设置页冷启动弹窗。
    // 从系统设置返回时刷新真实状态，避免显示过期值。
    var notificationsEnabled by remember { mutableStateOf(notificationsAllowed(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = notificationsAllowed(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = notificationsAllowed(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionMissing = Build.VERSION.SDK_INT >= 33 && !notificationsEnabled &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val relayAddress = nodes.firstOrNull { !it.relay.isNullOrBlank() }?.relay
    val relayMode = nodes.map { it.relayMode }.firstOrNull { it.isNotBlank() }.orEmpty()

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        // MP-03：标题距状态栏 10dp 由「状态栏 inset（statusBarsPadding）+ ScreenHeader 自带 10dp」提供，
        // 不再叠加固定 spacer（Robolectric 无 inset 时即 10dp，与设计稿 66-56=10 对齐）。
        ScreenHeader(
            title = "设置",
            actions = { Tag(headerStatus.first, headerStatus.second, filled = headerStatus.third) },
        )
        Spacer(Modifier.height(2.dp))

        SettingGroupLabel("外观")
        SettingsCard {
            SettingRow(
                icon = Icons.Outlined.Palette,
                title = "主题",
                trailing = { ThemeSegment(themeMode, onSelect = repo::setThemeMode) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingGroupLabel("传输")
        SettingsCard {
            SettingRow(
                icon = Icons.Outlined.Router,
                title = "自托管 relay",
                subtitle = relayAddress ?: "未配置",
                trailing = {
                    MonoLabel(
                        when (relayMode) {
                            "disabled" -> "直连"
                            "custom" -> "自定义"
                            else -> "n0"
                        },
                        color = TextMuted,
                    )
                },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Outlined.Lan,
                title = "直连优先",
                subtitle = "UDP 直连优先",
                trailing = { MonoLabel("默认开启", color = TextMuted) },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Outlined.SettingsEthernet,
                title = "协议版本",
                subtitle = buildString {
                    append("carry.v1")
                    if (piVersion != null) append(" · pi $piVersion")
                },
                trailing = { MonoLabel("只读", color = TextMuted) },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingGroupLabel("通知与后台")
        SettingsCard {
            SettingToggleRow(
                icon = Icons.Outlined.Notifications,
                title = "审批通知",
                subtitle = if (approvalNotify && notificationPermissionMissing) "系统通知已关闭" else "高风险操作立即提醒",
                checked = approvalNotify,
                onChange = { checked ->
                    approvalNotify = checked
                    repo.setSettingBool("notify_approval", checked)
                    // 用户主动打开时才请求权限：拒绝后只就地显示状态，不反复弹。
                    if (checked && notificationPermissionMissing) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Outlined.PowerSettingsNew,
                title = "前台服务",
                subtitle = "connectedDevice",
                trailing = { Tag("运行中", Mint) },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Outlined.BatterySaver,
                title = "电池优化",
                subtitle = "忽略电池优化，保持后台连接",
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
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("去设置", style = MaterialTheme.typography.labelLarge, color = TextMuted)
                        Chevron()
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= 33) {
                RowDivider()
                SettingRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "通知权限",
                    subtitle = "系统通知设置",
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                if (notificationsEnabled) "已允许" else "已关闭",
                                style = SetSubtitleStyle,
                                color = if (notificationsEnabled) Mint else TextMuted,
                            )
                            Chevron()
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SettingGroupLabel("设备与安全")
        if (credentialsPlaintext) {
            CredentialsPlaintextCard()
            Spacer(Modifier.height(8.dp))
        }
        if (nodes.isEmpty()) {
            EmptyDevicesCard(onPair)
        } else {
            SettingsCard {
                nodes.forEachIndexed { index, node ->
                    if (index > 0) RowDivider()
                    NodeRow(
                        node = node,
                        online = link[node.id] == LinkState.Online,
                        confirming = confirmingRemove == node.id,
                        onToggleConfirm = { confirmingRemove = if (confirmingRemove == node.id) null else node.id },
                        onRemove = { repo.removeNode(node.id); confirmingRemove = null },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            CarryActionButton(
                "配对新节点",
                onClick = onPair,
                modifier = Modifier.fillMaxWidth(),
                kind = ButtonKind.Ghost,
                height = 44.dp,
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingGroupLabel("关于")
        SettingsCard {
            SettingRow(
                icon = Icons.Outlined.Info,
                title = "pi carry",
                subtitle = buildString {
                    append(appVersion)
                    if (piVersion != null) append(" · pi $piVersion")
                },
            )
            RowDivider()
            SettingRow(
                icon = Icons.Outlined.Code,
                title = "开源许可",
                subtitle = "MIT · 无账号 · 无云端会话",
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/y-cyfor/Pi-Carry/"))) }
                },
                trailing = { Chevron() },
            )
        }
        // MG-17：页脚整段删除；MP-07：底部留 98dp，最后一张卡能滚到覆盖式导航上方而不被遮挡。
        Spacer(Modifier.height(98.dp))
    }
}

/** MG-02：分组标题是 eyebrow（11sp mono、字距 2sp、text-3），不再带横线。 */
@Composable
private fun SettingGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = TextMuted,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 2.dp),
    )
}

/** MG-03：14 圆角扁平卡，行之间用 1px line 分隔。 */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDark) Color.White.copy(alpha = 0.025f) else Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** MG-04：28dp 图标块，紫底 .10/紫边 .12，图标 14（安全/密钥类用青色，危险行用玫色）。 */
@Composable
private fun SettingRowIcon(
    icon: ImageVector,
    tint: Color = Violet,
    iconTint: Color = if (tint == Violet) VioletSoft else tint,
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
    }
}

/** 设置行：11.5/500 标题 + 11 mono 单行副标题；可点行带背景按压反馈（A.3.4，无涟漪）。 */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = Violet,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed && onClick != null) Color.White.copy(alpha = 0.03f) else Color.Transparent,
        tween(140),
        label = "settingRowPress",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowIcon(icon, iconTint)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SetTitleStyle, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = SetSubtitleStyle,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = source,
                indication = null,
                onValueChange = onChange,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowIcon(icon)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SetTitleStyle, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = SetSubtitleStyle, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        CarrySwitch(checked = checked, onCheckedChange = null)
    }
}

/** MG-06：3 段主题控（视觉 26/22 高），外层 44dp 触控热区（A.3.3）。 */
@Composable
private fun ThemeSegment(current: String, onSelect: (String) -> Unit) {
    val options = listOf("auto" to "自动", "dark" to "深色", "light" to "浅色")
    Box(Modifier.width(132.dp).height(44.dp)) {
        // 视觉容器：26 高（按钮 22 + 上下 2 padding）。
        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Line, RoundedCornerShape(9.dp))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (key, label) ->
                val selected = current == key
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) Violet.copy(alpha = 0.22f) else Color.Transparent)
                        .then(
                            if (selected) {
                                Modifier.border(1.dp, Violet.copy(alpha = 0.34f), RoundedCornerShape(7.dp))
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = SetSubtitleStyle,
                        color = if (selected) AccentSoft else TextMuted,
                    )
                }
            }
        }
        // 触控层：44dp 高，横向与视觉按钮对齐，透明无涟漪。
        Row(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (key, label) ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = current == key,
                            interactionSource = source,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { onSelect(key) },
                        )
                        // 视觉文字在下面一层，这里补无障碍名称。
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

/** 凭据未加密提示：琥珀小卡（C.6 MG-15 派生）。 */
@Composable
private fun CredentialsPlaintextCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Amber.copy(alpha = 0.06f))
            .border(1.dp, Amber.copy(alpha = 0.20f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowIcon(Icons.Outlined.Security, tint = Amber)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("凭据未加密存储", style = SetTitleStyle, color = Amber)
            Spacer(Modifier.height(2.dp))
            Text(
                "Keystore 不可用，凭据仅在本机编码保存。",
                style = MaterialTheme.typography.labelLarge,
                color = Amber.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyDevicesCard(onPair: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.025f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPair)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingRowIcon(Icons.Outlined.Devices, tint = Cyan)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("还没有配对设备", style = SetTitleStyle, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text("扫描电脑上的二维码开始", style = SetSubtitleStyle, color = TextMuted)
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

@Composable
private fun NodeRow(
    node: Node,
    online: Boolean,
    confirming: Boolean,
    onToggleConfirm: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingRowIcon(
                if (node.platform.contains("mac", true)) Icons.Outlined.Devices else Icons.Outlined.Lock,
                tint = Cyan,
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(node.name, style = SetTitleStyle, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusDot(if (online) Mint else TextMuted, 5)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${node.capability} · ${node.endpointId.take(10)}",
                    style = SetSubtitleStyle,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 删除按钮保持 44dp 热区。
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleConfirm,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    "移除 ${node.name}",
                    tint = if (confirming) Rose else TextMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (confirming) {
            Spacer(Modifier.height(9.dp))
            // E.5 危险操作：标题 + 后果 + 取消/玫色动作。
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Rose.copy(alpha = 0.08f))
                    .border(1.dp, Rose.copy(alpha = 0.22f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("移除节点？", style = SetTitleStyle, color = Rose)
                Spacer(Modifier.height(2.dp))
                Text(
                    "该节点上的会话将不再显示；重新连接需要重新配对。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Rose.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ConfirmAction("取消", TextMuted, onClick = onToggleConfirm)
                    Spacer(Modifier.width(6.dp))
                    ConfirmAction("移除", Rose, onClick = onRemove, filled = true)
                }
            }
        }
    }
}

@Composable
private fun ConfirmAction(text: String, color: Color, onClick: () -> Unit, filled: Boolean = false) {
    Box(
        Modifier
            .sizeIn(minWidth = 64.dp, minHeight = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (filled) {
                    Modifier
                        .background(color.copy(alpha = 0.16f))
                        .border(0.8.dp, color.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun Chevron(size: Int = 14) {
    Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(size.dp))
}
