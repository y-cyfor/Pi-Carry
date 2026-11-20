package dev.picarry.app.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** 开机后恢复常驻连接服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, CarryService::class.java)
        runCatching { ContextCompat.startForegroundService(context, service) }
            .onFailure { Log.e("BootReceiver", "启动前台服务失败", it) }
    }
}
