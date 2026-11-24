package dev.picarry.app.net

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.picarry.app.MainActivity
import dev.picarry.app.PiCarryApp

/** 开机后恢复常驻连接服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val service = Intent(context, CarryService::class.java)
        val started = runCatching { ContextCompat.startForegroundService(context, service) }
        started.onFailure { Log.e("BootReceiver", "启动前台服务失败", it) }
        if (started.isFailure) {
            // Android 12+ 从后台启动前台服务可能被系统直接拒绝（OEM 电池策略最常见），
            // 用户看不到任何提示就“开机后一直连不上”，所以留一条可查的通知。
            runCatching { notifyBootFailure(context) }
        }
    }

    private fun notifyBootFailure(context: Context) {
        // 与 CarryRepository.notifyApproval 一致：Android 13+ 未授予通知权限时
        // notify() 会抛 SecurityException，开机场景没有用户在场，必须自己先拦。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, PiCarryApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("pi carry 未能开机自启")
            .setContentText("系统拒绝了后台启动，请手动打开应用并允许后台运行")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(1002, notification)
    }
}
