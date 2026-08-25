package com.dramafactory.core.storage

import com.dramafactory.core.model.CheckpointEntry
import com.dramafactory.core.model.EpisodeCheckpoint
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.provider.CheckpointStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存CheckpointStore（JVM测试/无Room环境用）——语义与app层Room实现一致。
 *
 * load-or-merge 语义（继承 pavo task_queue.create_checkpoint）：
 * - 已有checkpoint → 权威态复用：SUBMITTED(video_id)/FAILED/BLOCKED 原样保留，绝不重建；
 * - 仅补缺失镜；
 * - COMPLETED 但文件缺失或 size=0 → 重置 PENDING 重做。
 */
class InMemoryCheckpointStore : CheckpointStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, EpisodeCheckpoint>()
    /** 模拟磁盘上的clip文件（shotId→size），供loadOrMerge校验COMPLETED有效性 */
    val diskFiles = mutableMapOf<String, Long>()

    override suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint = mutex.withLock {
        val existing = store[episodeId]
        if (existing != null) {
            // 合并路径：持久化checkpoint是权威状态
            for (meta in shots) {
                val entry = existing.byId(meta.shotId)
                if (entry == null) {
                    existing.shots.add(CheckpointEntry(meta.shotId))
                } else if (entry.state == ShotState.COMPLETED) {
                    // 陈旧completed（文件缺失/0字节）→ 重置PENDING
                    val sizeOnDisk = entry.localFileUri?.let { diskFiles[it] } ?: 0L
                    if (sizeOnDisk <= 0L || entry.fileSize <= 0L) {
                        entry.state = ShotState.PENDING
                        entry.localFileUri = null; entry.fileSize = 0L; entry.providerTaskId = null
                    }
                }
            }
            existing
        } else {
            val cp = EpisodeCheckpoint(episodeId, shots.map { CheckpointEntry(it.shotId) }.toMutableList())
            store[episodeId] = cp
            cp
        }
    }

    override suspend fun markSubmitted(shotId: String, providerTaskId: String) = mutex.withLock {
        findAny(shotId)?.apply {
            state = ShotState.SUBMITTED
            this.providerTaskId = providerTaskId   // ★video_id即刻落库：防重复付费生死线
            submittedAt = System.currentTimeMillis()
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markCompleted(shotId: String, localFileUri: String, fileSize: Long) = mutex.withLock {
        require(fileSize > 0) { "size>0才算completed（架构§5 render_tasks约束）" }
        findAny(shotId)?.apply {
            state = ShotState.COMPLETED
            this.localFileUri = localFileUri
            this.fileSize = fileSize
            diskFiles[localFileUri] = fileSize
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markFailed(shotId: String, reason: String) = mutex.withLock {
        findAny(shotId)?.apply { state = ShotState.FAILED; failReason = reason }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    /** 恢复判定：SUBMITTED且有video_id的镜全部进re-poll队头，绝不重新submit（US5零重复付费） */
    override suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry> = mutex.withLock {
        store[episodeId]?.shots
            ?.filter { it.state == ShotState.SUBMITTED && !it.providerTaskId.isNullOrBlank() }
            ?: emptyList()
    }

    private fun findAny(shotId: String): CheckpointEntry? =
        store.values.asSequence().flatMap { it.shots.asSequence() }.firstOrNull { it.shotId == shotId }

    fun getEpisode(episodeId: String): EpisodeCheckpoint? = store[episodeId]
}
