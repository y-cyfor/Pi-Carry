package dev.picarry.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.picarry.app.MainActivity
import dev.picarry.app.PiCarryApp

/** 常驻前台服务：让连接在切后台后仍保持。 */
class CarryService : Service() {
    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, PiCarryApp.CHANNEL_SERVICE)
            .setContentTitle("pi carry")
            .setContentText("正在后台保持与 pi 节点的连接")
            .setSmallIcon(dev.picarry.app.R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
