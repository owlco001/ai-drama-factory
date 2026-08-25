package com.dramafactory.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dramafactory.app.ui.RenderRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 渲染Foreground Service —— PRD F09/架构§7.1（v0.2真实接线）。
 *
 * 职责：常驻通知「第X集 · 12/24镜完成 · ETA约35分钟」；订阅RenderQueue.state Flow
 * 实时刷新通知；持有队列协程作用域；进程被杀后下次启动由
 * PipelineOrchestrator.recoverOnBoot()读checkpoint续传（START_STICKY）。
 */
class RenderForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "render_progress"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.dramafactory.action.START_RENDER"
        const val ACTION_STOP = "com.dramafactory.action.STOP_RENDER"
        const val EXTRA_EPISODE_ID = "episode_id"

        /** UI入口：启动服务并绑定某集进度通知 */
        fun start(context: Context, episodeId: String) {
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_START; putExtra(EXTRA_EPISODE_ID, episodeId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RenderForegroundService::class.java).apply { action = ACTION_STOP })
        }
    }

    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private var episodeId: String? = null

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
                episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: "?"
                startForeground(NOTIFICATION_ID, buildProgress(done = 0, total = 0))
                // ★真实接线：订阅队列快照Flow → 常驻通知实时刷新
                watchJob?.cancel()
                val ep = episodeId ?: return START_STICKY
                watchJob = serviceScope.launch {
                    RenderRuntime.queueFor(ep).state.collect { snap ->
                        // ETA：剩余镜数×平均单镜耗时估算（轮询30s+生成≈5分钟/镜，保守取300s）
                        val remaining = snap.totalShots - snap.completedShots
                        val etaMin = if (remaining > 0) (remaining * 300 / 60).toInt() else null
                        notificationManager.notify(NOTIFICATION_ID,
                            buildProgress(done = snap.completedShots, total = snap.totalShots, etaMin = etaMin,
                                pausedReason = snap.pausedReason))
                        // 队列跑完自动撤下前台服务
                        if (!snap.running && snap.pausedReason == null &&
                            snap.totalShots in 1..snap.completedShots) {
                            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                        }
                    }
                }
            }
            ACTION_STOP -> {
                watchJob?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_STICKY   // 进程被杀后系统尝试重建服务，配合checkpoint续传
    }

    /** 常驻进度通知：「第X集 · N/M镜 · ETA约Y分钟」；暂停时显示原因引导用户去设置页 */
    private fun buildProgress(done: Int, total: Int, etaMin: Int? = null, pausedReason: String? = null): Notification {
        val text = when (pausedReason) {
            "budget_exceeded" -> "预算已达上限，等待确认后继续"
            "auth_401" -> "API Key失效，请到设置页更新"
            "manual" -> "已暂停，可在渲染页恢复"
            else -> "共$total 镜 · 已完成$done" + (etaMin?.let { " · ETA约$it 分钟" } ?: "")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AI短剧工厂 · 渲染中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (total > 0) total else 1, done, total == 0)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
