package dev.picarry.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 无模拟器环境下的 UI 冒烟：真实 Activity + 真实主题渲染成截图，便于与设计稿核对。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun shoot(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun captureRootTabs() {
        compose.waitForIdle()
        compose.onNodeWithText("直连优先", substring = true).assertIsDisplayed()
        shoot("01-nodes")
        compose.onNodeWithText("会话").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("搜索会话", substring = true).assertIsDisplayed()
        shoot("02-sessions")
        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
        // E.2.6：设置页头不再有副标题，改为校验首个分组标题「外观」仍正常渲染。
        compose.onNodeWithText("外观").assertIsDisplayed()
        shoot("03-settings")
        compose.onNodeWithText("节点").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("配对新节点").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("配对新节点").assertIsDisplayed()
        compose.onNodeWithText("或手动输入 6 位码").assertIsDisplayed()
        compose.onNodeWithText("校验指纹").assertIsDisplayed()
        compose.onNodeWithText("完成配对").assertIsDisplayed()
        shoot("04-pairing")
    }
}
