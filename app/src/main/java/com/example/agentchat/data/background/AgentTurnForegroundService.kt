package com.example.agentchat.data.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.agentchat.MainActivity
import com.example.agentchat.domain.agent.TurnExecutionLifecycle
import java.util.concurrent.atomic.AtomicInteger

class ForegroundAgentTurnController(context: Context) : TurnExecutionLifecycle, Application.ActivityLifecycleCallbacks {
    private val appContext = context.applicationContext
    private val startedActivities = AtomicInteger(0)

    init {
        (appContext as? Application)?.registerActivityLifecycleCallbacks(this)
        createCompletionNotificationChannel()
    }

    override fun onTurnStarted(conversationId: String) {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, AgentTurnForegroundService::class.java).setAction(AgentTurnForegroundService.ACTION_START),
            )
        }
    }

    override fun onTurnFinished(conversationId: String?, completed: Boolean) {
        val shouldNotify = completed && startedActivities.get() == 0 && !conversationId.isNullOrBlank()
        appContext.stopService(Intent(appContext, AgentTurnForegroundService::class.java))
        if (shouldNotify) postCompletionNotification(conversationId!!)
    }

    override fun onActivityStarted(activity: android.app.Activity) { startedActivities.incrementAndGet() }
    override fun onActivityStopped(activity: android.app.Activity) { startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: android.app.Activity) = Unit
    override fun onActivityPaused(activity: android.app.Activity) = Unit
    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: android.app.Activity) = Unit

    private fun createCompletionNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "对话完成提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Agent 对话在后台完成时提醒用户"
            }
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun postCompletionNotification(conversationId: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setContentTitle("对话已完成")
            .setContentText("点击查看刚刚完成的对话")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(COMPLETION_NOTIFICATION_ID + (conversationId.hashCode() and 0x7fff), notification)
    }

    companion object {
        private const val COMPLETION_CHANNEL_ID = "agent_turn_completed"
        private const val COMPLETION_NOTIFICATION_ID = 2303
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
