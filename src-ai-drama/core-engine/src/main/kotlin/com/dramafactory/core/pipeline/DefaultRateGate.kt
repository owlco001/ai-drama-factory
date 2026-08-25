package com.dramafactory.core.pipeline

import com.dramafactory.core.model.ChannelKind
import com.dramafactory.core.provider.RateGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 提交限速门 —— 语义对齐 pavo-drama agnes_client.wait_video_submit_slot。
 *
 * Agnes 视频队列限速约1次/120s。Mutex 保护的单例时间戳：
 * 先等门再干活，杜绝并发调用烧配额（架构§4.1）。
 * 默认间隔 120_000ms，非法配置值兜底回 120s（对齐pavo env解析容错）。
 */
class DefaultRateGate(
    intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val sleeper: suspend (Long) -> Unit = { delayMs ->
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
    },
) : RateGate {

    companion object {
        const val DEFAULT_INTERVAL_MS = 120_000L
        /** 非法值兜底：≤0 或超24h一律回落默认120s */
        fun sanitizeInterval(ms: Long): Long =
            if (ms <= 0 || ms > 86_400_000L) DEFAULT_INTERVAL_MS else ms
    }

    val intervalMs: Long = sanitizeInterval(intervalMs)

    // 进程内互斥 + 全局时间戳（对应pavo _video_submit_lock/_last_video_submit_ts）
    private val mutex = Mutex()
    private var lastSubmitAt: Long = 0L

    override suspend fun awaitSlot(channel: ChannelKind) {
        // 仅VIDEO通道受限；TEXT/IMAGE通道直通
        if (channel != ChannelKind.VIDEO) return
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (lastSubmitAt != 0L) {
                val elapsed = now - lastSubmitAt
                if (elapsed < intervalMs) {
                    sleeper(intervalMs - elapsed)   // 阻塞至距上次提交≥interval
                }
            }
            lastSubmitAt = System.currentTimeMillis() // 占坑即记时，防止并发穿透
        }
    }

    /** 仅测试用：重置门内时间戳 */
    suspend fun resetForTest() = mutex.withLock { lastSubmitAt = 0L }
}
