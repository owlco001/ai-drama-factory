package com.dramafactory.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 渲染Foreground Service桩 —— PRD F09/架构§7.1。
 *
 * 职责：常驻通知「第X集 · 12/24镜完成 · ETA约35分钟」（≥5分钟刷新）；
 * 持有RenderQueue单消费者协程作用域；进程被杀后下次启动由
 * PipelineOrchestrator.recoverOnBoot()读checkpoint续传。
 * 本桩为MVP骨架：队列执行器注入点已留好，通知更新逻辑就位。
 */
class RenderForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "render_progress"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.dramafactory.action.START_RENDER"
        const val ACTION_STOP = "com.dramafactory.action.STOP_RENDER"
        const val EXTRA_EPISODE_ID = "episode_id"
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "渲染进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI短剧渲染队列进度与事件通知"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: "?"
                startForeground(NOTIFICATION_ID, buildProgress(episodeId, done = 0, total = 0, etaMin = null))
                // TODO(下一迭代)：在此绑定RenderQueue.state Flow → 常驻通知实时刷新
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_STICKY   // 进程被杀后系统尝试重建服务，配合checkpoint续传
    }

    /** 常驻进度通知：「第X集 · N/M镜 · ETA约Y分钟」 */
    private fun buildProgress(episodeId: String, done: Int, total: Int, etaMin: Int?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AI短剧工厂 · 渲染中")
            .setContentText("第$episodeId 集 · $done/$total 镜完成" + (etaMin?.let { " · ETA约$it 分钟" } ?: ""))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (total > 0) total else 1, done, total == 0)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
