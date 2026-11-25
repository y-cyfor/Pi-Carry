package dev.picarry.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.picarry.app.net.CarryService
import dev.picarry.app.ui.components.CarryBackground
import dev.picarry.app.ui.screens.ChatScreen
import dev.picarry.app.ui.screens.NodesScreen
import dev.picarry.app.ui.screens.PairingScreen
import dev.picarry.app.ui.screens.SessionsScreen
import dev.picarry.app.ui.screens.SettingsScreen
import dev.picarry.app.ui.theme.CarryEase
import dev.picarry.app.ui.theme.PiCarryTheme
import dev.picarry.app.ui.theme.TabbarActiveBg
import dev.picarry.app.ui.theme.TabbarActiveStroke
import dev.picarry.app.ui.theme.TabbarEnd
import dev.picarry.app.ui.theme.TabbarLine
import dev.picarry.app.ui.theme.TabbarMid
import dev.picarry.app.ui.theme.TextMuted
import dev.picarry.app.ui.theme.VioletSoft
import dev.picarry.app.ui.theme.WindowBackgroundDark
import dev.picarry.app.ui.theme.WindowBackgroundLight

private const val KEY_PAIR_CONSUMED = "pair_consumed"

class MainActivity : ComponentActivity() {
    private var pairUri by mutableStateOf<String?>(null)
    private var pairConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先建立 edge-to-edge 系统栏样式，再按用户主题覆盖图标明暗：
        // 反过来的话 enableEdgeToEdge() 会按资源夜间模式把手动设置覆盖掉，强制主题下
        // 首帧出现「浅底 + 浅色图标」（BUG-027 reopened）。
        enableEdgeToEdge()
        // 首帧底色必须跟随用户选的「自动/黑色/白色」，而不是资源限定符里的系统夜间模式：
        // 系统深色 + 应用内选白色（或反之）时，冷启动会先闪一帧相反底色（BUG-027）。
        val startupRepo = (application as PiCarryApp).repo
        val startupDark = when (startupRepo.themeMode.value) {
            "dark" -> true
            "light" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(ColorDrawable(if (startupDark) WindowBackgroundDark else WindowBackgroundLight))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !startupDark
            isAppearanceLightNavigationBars = !startupDark
        }
        // C.9 MSY-04：冷启动不申请 POST_NOTIFICATIONS，等用户在设置页打开
        // 「审批通知」开关时由 SettingsScreen 发起请求。
        ContextCompat.startForegroundService(this, Intent(this, CarryService::class.java))
        // 深链只消费一次：旋转/进程重建后不能再把用户拽回配对页。
        pairConsumed = savedInstanceState?.getBoolean(KEY_PAIR_CONSUMED) ?: false
        if (!pairConsumed) pairUri = intent?.data?.toString()
        setContent {
            val repo = (application as PiCarryApp).repo
            val themeMode by repo.themeMode.collectAsState()
            val dark = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            PiCarryTheme(darkTheme = dark) {
                PiCarryAppRoot(repo, pairUri) {
                    pairUri = null
                    pairConsumed = true
                    intent?.let { it.data = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairConsumed = false
        pairUri = intent.data?.toString()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PAIR_CONSUMED, pairConsumed)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        (application as PiCarryApp).repo.setAppForeground(true)
    }

    override fun onStop() {
        (application as PiCarryApp).repo.setAppForeground(false)
        super.onStop()
    }
}

object Routes {
    const val Nodes = "nodes"

    /** 底部 tab：全量会话（单独 destination，避免 restoreState 把节点过滤参数带回来）。 */
    const val Sessions = "sessions"

    /** 节点卡片进入：只着该节点的会话。 */
    const val SessionsByNode = "sessions?nodeId={nodeId}"
    const val Settings = "settings"
    const val Chat = "chat/{nodeId}/{sessionId}"
    const val Pairing = "pairing?uri={uri}"

    fun sessionsByNode(nodeId: String) =
        "sessions?nodeId=${java.net.URLEncoder.encode(nodeId, "UTF-8")}"

    fun chat(nodeId: String, sessionId: String) = "chat/$nodeId/$sessionId"
    fun pairing(uri: String? = null) = if (uri.isNullOrBlank()) "pairing" else "pairing?uri=${java.net.URLEncoder.encode(uri, "UTF-8")}"
}

@Composable
fun PiCarryAppRoot(
    repo: dev.picarry.app.data.CarryRepository,
    initialPairUri: String?,
    onPairUriConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    // 从相机/浏览器扫描二维码冷启动或热启动时，直接进入配对页；消费后置空，避免旋转重新导航。
    // 热启动时 launchSingleTop 会复用已有的 pairing entry，arguments 仍是上一次的二维码，
    // 用户再扫新码会静默失效（M26）。先在入栈时把旧 destination 出栈，强制新建 entry。
    LaunchedEffect(initialPairUri) {
        if (!initialPairUri.isNullOrBlank()) {
            nav.navigate(Routes.pairing(initialPairUri)) {
                popUpTo(Routes.Pairing) { inclusive = true }
                launchSingleTop = true
            }
            onPairUriConsumed()
        }
    }

    val route = backStack?.destination?.route ?: Routes.Nodes
    val showBar = route in setOf(Routes.Nodes, Routes.Sessions, Routes.SessionsByNode, Routes.Settings)
    // MP-04：tabbar 绝对定位覆盖页面内容（页面内容滚动到渐变下面），不再占据布局高度。
    CarryBackground {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Routes.Nodes,
                modifier = Modifier.fillMaxSize(),
            ) {
                    composable(Routes.Nodes) {
                        NodesScreen(
                            repo = repo,
                            onPair = { nav.navigate(Routes.pairing()) },
                            onOpenSessions = { nodeId -> nav.navigate(Routes.sessionsByNode(nodeId)) },
                        )
                    }
                    composable(Routes.Sessions) {
                        SessionsScreen(
                            repo = repo,
                            onOpenChat = { nodeId, sessionId -> nav.navigate(Routes.chat(nodeId, sessionId)) },
                            onPair = { nav.navigate(Routes.pairing()) },
                        )
                    }
                    composable(
                        Routes.SessionsByNode,
                        arguments = listOf(navArgument("nodeId") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry ->
                        SessionsScreen(
                            repo = repo,
                            filterNodeId = entry.arguments?.getString("nodeId")?.ifBlank { null },
                            onOpenChat = { nodeId, sessionId -> nav.navigate(Routes.chat(nodeId, sessionId)) },
                            onPair = { nav.navigate(Routes.pairing()) },
                        )
                    }
                    composable(Routes.Settings) {
                        SettingsScreen(repo = repo, onPair = { nav.navigate(Routes.pairing()) })
                    }
                    composable(
                        Routes.Chat,
                        arguments = listOf(
                            navArgument("nodeId") { type = NavType.StringType },
                            navArgument("sessionId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        ChatScreen(
                            repo = repo,
                            nodeId = entry.arguments?.getString("nodeId").orEmpty(),
                            sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onMigrated = { newSessionId ->
                                val node = entry.arguments?.getString("nodeId").orEmpty()
                                val old = entry.arguments?.getString("sessionId").orEmpty()
                                nav.navigate(Routes.chat(node, newSessionId)) {
                                    popUpTo(Routes.chat(node, old)) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable(
                        Routes.Pairing,
                        arguments = listOf(navArgument("uri") { type = NavType.StringType; defaultValue = "" }),
                    ) { entry ->
                        PairingScreen(
                            repo = repo,
                            // 只认本次 nav entry 写进去的参数：这样热启动重建 entry 后
                            // 拿到的是新二维码，而不是内存里上一次的 uri（M26）。
                            initialUri = entry.arguments?.getString("uri").orEmpty(),
                            onBack = { nav.popBackStack() },
                            onDone = {
                                nav.navigate(Routes.Nodes) {
                                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            if (showBar) BottomBar(nav, route, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String, modifier: Modifier = Modifier) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // MP-08：底部留白取 max(导航栏 inset, 22dp)。
    val safeBottom = maxOf(navBottom, 22.dp)
    // drawBehind 不是 @Composable，颜色 token 需先取出。
    val tabbarLine = TabbarLine
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp + safeBottom)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to TabbarMid,
                    1f to TabbarEnd,
                )
            )
            .drawBehind {
                drawLine(
                    color = tabbarLine,
                    start = Offset(0f, 0.5.dp.toPx()),
                    end = Offset(size.width, 0.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = safeBottom),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            BarItem(Modifier.weight(1f), "节点", Icons.Outlined.Dns, route == Routes.Nodes) {
                nav.switchTab(Routes.Nodes)
            }
            BarItem(Modifier.weight(1f), "会话", Icons.Outlined.ChatBubbleOutline, route == Routes.Sessions || route == Routes.SessionsByNode) {
                nav.switchTab(Routes.Sessions)
            }
            BarItem(Modifier.weight(1f), "设置", Icons.Outlined.Settings, route == Routes.Settings) {
                nav.switchTab(Routes.Settings)
            }
        }
    }
}

/**
 * 底部 tab 直接回到根目的地再切页。
 * 之前用 saveState/restoreState，从“某节点的会话”回“节点”时会把
 * sessions?nodeId 的保存状态恢复回来，表现为按钮有动效但页面不切换。
 */
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    val poppedToRoot = popBackStack(Routes.Nodes, false)
    if (!poppedToRoot || route != Routes.Nodes) {
        navigate(route) { launchSingleTop = true }
    }
}

/** MP-05/MP-06：图标 20dp、图标座 44×28、激活紫底 .14+内描边 .16，按压只缩图标座。 */
@Composable
private fun BarItem(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val seatScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 160, easing = CarryEase),
        label = "tabSeatScale",
    )
    Column(
        modifier
            .fillMaxHeight()
            .padding(top = 5.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 28.dp)
                .graphicsLayer { scaleX = seatScale; scaleY = seatScale }
                .clip(RoundedCornerShape(9.dp))
                .background(if (active) TabbarActiveBg else Color.Transparent)
                .then(if (active) Modifier.border(1.dp, TabbarActiveStroke, RoundedCornerShape(9.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (active) VioletSoft else TextMuted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) VioletSoft else TextMuted,
        )
    }
}
