package dev.picarry.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.picarry.app.data.CarryRepository

class PiCarryApp : Application() {
    lateinit var repo: CarryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = CarryRepository(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "连接服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与 pi 节点的连接"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVAL, "审批通知", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "pi 请求执行高风险操作"
            }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "carry_service"
        const val CHANNEL_APPROVAL = "carry_approval"
    }
}
