package dev.picarry.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 客户端密钥必须跨连接、跨进程重建保持稳定，否则电脑会把同一台手机识别成多台设备。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientSecretTest {
    private fun freshContext(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE).edit().clear().commit()
        return context
    }

    @Test
    fun clientSecretIsStableAndValid() {
        val context = freshContext()
        val first = NodeStore(context).clientSecret()
        val second = NodeStore(context).clientSecret()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun corruptedSecretIsRegenerated() {
        val context = freshContext()
        context.getSharedPreferences("pi_carry", Context.MODE_PRIVATE).edit()
            .putString("client_secret", "not-base64")
            .commit()

        assertEquals(32, NodeStore(context).clientSecret().size)
    }
}
