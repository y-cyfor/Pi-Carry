package dev.picarry.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.picarry.app.data.CarryRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 主题模式属于用户设置，必须默认自动、持久化，并拒绝非法值。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ThemeModeTest {
    private fun freshContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE).edit().clear().commit()
        return context
    }

    @Test
    fun defaultsToAutoAndPersistsExplicitChoice() {
        val context = freshContext()
        val first = CarryRepository(context)
        assertEquals("auto", first.themeMode.value)

        first.setThemeMode("dark")
        assertEquals("dark", first.themeMode.value)

        val second = CarryRepository(context)
        assertEquals("dark", second.themeMode.value)
    }

    @Test
    fun invalidStoredValueFallsBackToAutoAndInvalidWritesAreIgnored() {
        val context = freshContext()
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", "purple").commit()

        val repo = CarryRepository(context)
        assertEquals("auto", repo.themeMode.value)
        repo.setThemeMode("sepia")
        assertEquals("auto", repo.themeMode.value)
    }
}
