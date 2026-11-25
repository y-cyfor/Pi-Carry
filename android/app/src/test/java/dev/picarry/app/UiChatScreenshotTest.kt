package dev.picarry.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.picarry.app.data.Approval
import dev.picarry.app.data.ChatMessage
import dev.picarry.app.data.ConnInfo
import dev.picarry.app.data.LinkState
import dev.picarry.app.data.Node
import dev.picarry.app.data.Session
import dev.picarry.app.data.ToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用反射把假数据放进真实仓库，渲染包含消息、工具调用和审批的聊天页。
 * 仅用于无模拟器环境下的 UI 冒烟截图，不修改生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiChatScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val node = Node(
        id = "n1",
        name = "Studio-PC",
        endpointId = "studio-pc-endpoint-0001",
        platform = "windows",
        capability = "full",
        appVersion = "0.2.0",
    )

    private val session = Session(
        id = "s1",
        nodeId = "n1",
        nodeName = "Studio-PC",
        cwd = "C:/Users/cyfor/Desktop/vibe-Project/pi_carry",
        name = "UI 回归测试",
        preview = "把 README 里的版本号全部更新到 0.2.0",
        updatedAt = "刚刚",
        messageCount = 4,
        status = "waiting_approval",
        writable = true,
    )

    private val messages = listOf(
        ChatMessage(
            id = "m1", sessionId = "s1", role = "user",
            text = "把 README 里的版本号全部更新到 0.2.0，并检查五语言是否一致。",
        ),
        ChatMessage(
            id = "m2", sessionId = "s1", role = "assistant",
            thinking = "先确认版本号的唯一来源，再检查各语言文件。",
            text = "我会先定位版本号来源，然后逐项检查。\n\n- `Cargo.toml`\n- `android/app/build.gradle.kts`\n- 五个 README",
        ),
        ChatMessage(
            id = "m3", sessionId = "s1", role = "tool",
            tool = ToolCall(
                id = "t1", name = "read", args = "{\"path\":\"README.md\"}",
                result = "version: 0.2.0\nlocale: zh-CN", status = "success", durationMs = 42,
            ),
        ),
        ChatMessage(
            id = "m4", sessionId = "s1", role = "assistant",
            text = "已经确认三处版本号一致；接下来只需要更新 README 中的安装包名称。",
        ),
    )

    private val approval = Approval(
        id = "a1", sessionId = "s1", nodeId = "n1",
        toolName = "bash", category = "shell", risk = "high",
        summary = "更新 README 中的版本号", command = "sed -i 's/0.1.4/0.2.0/g' README*.md",
        cwd = "C:/Users/cyfor/Desktop/vibe-Project/pi_carry",
        remainingSeconds = 87,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> put(name: String, value: T) {
        val field = (compose.activity.application as PiCarryApp).repo.javaClass.getDeclaredField(name)
        field.isAccessible = true
        (field.get((compose.activity.application as PiCarryApp).repo) as MutableStateFlow<T>).value = value
    }

    private fun shoot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun seedChat() {
        put("_nodes", listOf(node))
        put("_nodesLoaded", true)
        put("_sessions", listOf(session))
        put("_messages", mapOf("n1::s1" to messages))
        put("_approvals", listOf(approval))
        put("_ready", setOf("n1::s1"))
        put("_link", mapOf("n1" to LinkState.Online))
        put("_connInfo", mapOf("n1" to ConnInfo(type = "lan", rttMs = 12)))
        compose.waitForIdle()
    }

    private fun openChat() {
        compose.onNodeWithText("会话").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("UI 回归测试").performClick()
        compose.waitForIdle()
        put("_openErrors", emptyMap<String, String>())
        put("_opening", emptySet<String>())
        compose.waitForIdle()
    }

    @Test
    fun captureChatWithMessages() {
        seedChat()
        shoot("07-nodes-data")

        compose.onNodeWithText("会话").performClick()
        compose.waitForIdle()
        shoot("08-sessions-data")
        compose.onNodeWithText("UI 回归测试").performClick()
        compose.waitForIdle()
        put("_openErrors", emptyMap<String, String>())
        put("_opening", emptySet<String>())
        compose.waitForIdle()
        shoot("05-chat")

        compose.onNodeWithText("审批 →").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("需要审批").assertIsDisplayed()
        shoot("06-approval")
    }

    @Test
    fun chatSheetsAndComposerAreInteractive() {
        seedChat()
        openChat()

        compose.onNodeWithContentDescription("更多").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("会话操作").assertIsDisplayed()
        compose.onNodeWithText("重命名会话").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("默认模型", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("模型与思考等级").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("关闭").onFirst().performClick()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("/")
        compose.waitForIdle()
        compose.onNodeWithText("命令面板").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("关闭").onFirst().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("审批 →").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("需要审批").assertIsDisplayed()
    }
}
