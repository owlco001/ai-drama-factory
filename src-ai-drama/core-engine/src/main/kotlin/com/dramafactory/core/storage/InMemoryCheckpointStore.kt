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
 * ★P0-1：本类即「持久化适配器」的参考语义实现。生产环境必须用 Room + 事务
 * 同步落盘（markSubmitting/markSubmitted/markReconcile 均为同步落库生死线）。
 * 接口契约：所有写方法返回时数据必须已可从进程崩溃中恢复；kill -9 后重启的
 * 恢复路径由 CheckpointRecoveryTest 集成测试覆盖。
 *
 * load-or-merge 语义（继承 pavo task_queue.create_checkpoint）：
 * - 已有checkpoint → 权威态复用：SUBMITTED(video_id)/FAILED/BLOCKED 原样保留，绝不重建；
 * - SUBMITTING → 恢复时翻转 RECONCILE（提交结果未知，须先对账再决定是否重提）；
 * - 仅补缺失镜；
 * - COMPLETED 但文件缺失或 size=0 → 重置 PENDING 重做。
 */
class InMemoryCheckpointStore : CheckpointStore {
    private val mutex = Mutex()
    private val store = mutableMapOf<String, EpisodeCheckpoint>()
    /** 模拟磁盘上的clip文件（shotId→size），供loadOrMerge校验COMPLETED有效性 */
    private val diskFiles = mutableMapOf<String, Long>()

    /** 测试辅助：模拟磁盘文件存在性 */
    fun putDiskFile(uri: String, size: Long) { diskFiles[uri] = size }
    fun removeDiskFile(uri: String) { diskFiles.remove(uri) }

    override suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint = mutex.withLock {
        val existing = store[episodeId]
        if (existing != null) {
            // 合并路径：持久化checkpoint是权威状态
            for (meta in shots) {
                val entry = existing.byId(meta.shotId)
                when {
                    entry == null -> existing.shots.add(CheckpointEntry(meta.shotId))
                    // P0-1：SUBMITTING=提交中途被杀，结果未知→待对账，绝不盲目重提
                    entry.state == ShotState.SUBMITTING -> {
                        entry.state = ShotState.RECONCILE
                        entry.failReason = "submit interrupted before video_id; reconcile required"
                    }
                    entry.state == ShotState.COMPLETED -> {
                        // 陈旧completed（文件缺失/0字节）→ 重置PENDING
                        val sizeOnDisk = entry.localFileUri?.let { diskFiles[it] } ?: 0L
                        if (sizeOnDisk <= 0L || entry.fileSize <= 0L) {
                            entry.state = ShotState.PENDING
                            entry.localFileUri = null; entry.fileSize = 0L; entry.providerTaskId = null
                        }
                    }
                    else -> {}
                }
            }
            existing
        } else {
            val cp = EpisodeCheckpoint(episodeId, shots.map { CheckpointEntry(it.shotId) }.toMutableList())
            store[episodeId] = cp
            cp
        }
    }

    /** P0-1：提交前置意图落库。同步、原子（mutex内一次写入）。 */
    override suspend fun markSubmitting(shotId: String): Unit = mutex.withLock {
        findAny(shotId)?.apply {
            state = ShotState.SUBMITTING
            submittedAt = System.currentTimeMillis()
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markSubmitted(shotId: String, providerTaskId: String): Unit = mutex.withLock {
        findAny(shotId)?.apply {
            state = ShotState.SUBMITTED
            this.providerTaskId = providerTaskId   // ★video_id即刻落库：防重复付费生死线
            submittedAt = System.currentTimeMillis()
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    /** P0-1：已计费但video_id解析失败 → 标记「待对账」，绝不静默归为FAILED后重提 */
    override suspend fun markReconcile(shotId: String, reason: String): Unit = mutex.withLock {
        findAny(shotId)?.apply {
            state = ShotState.RECONCILE
            failReason = reason.take(400)
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markCompleted(shotId: String, localFileUri: String, fileSize: Long): Unit = mutex.withLock {
        require(fileSize > 0) { "size>0才算completed（架构§5 render_tasks约束）" }
        findAny(shotId)?.apply {
            state = ShotState.COMPLETED
            this.localFileUri = localFileUri
            this.fileSize = fileSize
            diskFiles[localFileUri] = fileSize
        } ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markFailed(shotId: String, reason: String): Unit = mutex.withLock {
        findAny(shotId)?.apply { state = ShotState.FAILED; failReason = reason }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    /** 恢复判定：SUBMITTED且有video_id的镜全部进re-poll队头，绝不重新submit（US5零重复付费） */
    override suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry> = mutex.withLock {
        store[episodeId]?.shots
            ?.filter { it.state == ShotState.SUBMITTED && !it.providerTaskId.isNullOrBlank() }
            ?.map { it.copy() }
            ?: emptyList()
    }

    /** P1-6/P2-5：锁内快照拷贝，不暴露内部可变结构 */
    override suspend fun getEpisode(episodeId: String): EpisodeCheckpoint? = mutex.withLock {
        store[episodeId]?.let { cp ->
            EpisodeCheckpoint(cp.episodeId, cp.shots.map { it.copy() }.toMutableList())
        }
    }

    /** P1-6：recoverOnBoot 扫描全部集ID */
    override suspend fun allEpisodeIds(): List<String> = mutex.withLock {
        store.keys.toList()
    }

    private fun findAny(shotId: String): CheckpointEntry? =
        store.values.asSequence().flatMap { it.shots.asSequence() }.firstOrNull { it.shotId == shotId }
}
