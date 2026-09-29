package com.example.agentchat.data.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.agentchat.domain.agent.TurnExecutionLifecycle

class ForegroundAgentTurnController(context: Context) : TurnExecutionLifecycle {
    private val appContext = context.applicationContext

    override fun onTurnStarted() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, AgentTurnForegroundService::class.java).setAction(AgentTurnForegroundService.ACTION_START),
            )
        }
    }

    override fun onTurnFinished() {
        appContext.stopService(Intent(appContext, AgentTurnForegroundService::class.java))
    }
}

class AgentTurnForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("卡皮巴拉")
            .setContentText("正在处理对话，退到后台也会继续")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "对话任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持正在进行的 Agent 对话任务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.agentchat.action.START_TURN"
        const val ACTION_STOP = "com.example.agentchat.action.STOP_TURN"
        private const val CHANNEL_ID = "agent_turn"
        private const val NOTIFICATION_ID = 1303
    }
}
