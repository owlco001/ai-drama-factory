package com.dramafactory.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dramafactory.core.model.CheckpointEntry
import com.dramafactory.core.model.EpisodeCheckpoint
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.provider.CheckpointStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Room数据库（架构§5六张表，schema见assets/drama_factory_schema.sql）。
 */
@Database(
    entities = [
        ProjectEntity::class, AssetEntity::class, ShotEntity::class,
        RenderTaskEntity::class, ProviderConfigEntity::class, EpisodeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DramaDatabase : RoomDatabase() {
    abstract fun dao(): DramaDao

    companion object {
        @Volatile private var instance: DramaDatabase? = null
        fun get(context: Context): DramaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DramaDatabase::class.java, "drama_factory.db")
                .build().also { instance = it }
        }
    }
}

/**
 * Room版CheckpointStore适配器 —— DefaultRenderQueue的持久化后端（复审条件项）。
 *
 * 语义严格对齐core-engine的InMemoryCheckpointStore参考实现：
 * - load-or-merge：SUBMITTED(video_id)/FAILED/BLOCKED权威态保留；仅补缺失镜；
 *   SUBMITTING→RECONCILE（提交结果未知须对账）；COMPLETED但file_size<=0→重置PENDING；
 * - markSubmitting/markSubmitted/markReconcile均为同步落库生死线（Room suspend DAO写事务）；
 * - COMPLETED有效性以render_tasks.file_size>0为准（磁盘文件校验由downloader保证size>0）。
 *
 * 注意：与InMemory版不同，「COMPLETED文件缺失」在Room版以file_size字段判定——
 * 下载器落盘成功必回传size>0并写入本表，故file_size>0即代表文件曾完整落盘。
 */
class RoomCheckpointStore(private val dao: DramaDao) : CheckpointStore {

    /** 串行化合并/翻转操作，防并发loadOrMerge与mark*交错产生脏状态 */
    private val mutex = Mutex()

    override suspend fun loadOrMerge(episodeId: String, shots: List<ShotMeta>): EpisodeCheckpoint = mutex.withLock {
        val existing = dao.renderTasksOf(episodeId)
        if (existing.isNotEmpty()) {
            // 合并路径：持久化行是权威状态
            for (meta in shots) {
                val row = existing.firstOrNull { it.shot_id == meta.shotId }
                when {
                    row == null -> dao.upsertRenderTask(RenderTaskEntity(shot_id = meta.shotId, episode_id = episodeId))
                    row.state == ShotState.SUBMITTING.name -> {
                        // P0-1：SUBMITTING=提交中途被杀，结果未知→待对账，绝不盲目重提
                        dao.upsertRenderTask(row.copy(state = ShotState.RECONCILE.name,
                            blocked_reason = "submit interrupted before video_id; reconcile required"))
                    }
                    row.state == ShotState.COMPLETED.name && (row.file_size <= 0L || row.local_file_uri == null) -> {
                        // 陈旧completed（无文件或0字节）→ 重置PENDING重做
                        dao.upsertRenderTask(row.copy(state = ShotState.PENDING.name,
                            local_file_uri = null, file_size = 0L, provider_task_id = null))
                    }
                }
            }
        } else {
            for (meta in shots) {
                dao.upsertRenderTask(RenderTaskEntity(shot_id = meta.shotId, episode_id = episodeId))
            }
        }
        toEpisodeCheckpoint(episodeId)
    }

    /** P0-1：提交前置意图落库。suspend DAO写返回即已持久化（Room写事务）。 */
    override suspend fun markSubmitting(shotId: String) {
        upsertByShotId(shotId) { it.copy(state = ShotState.SUBMITTING.name, submitted_at = System.currentTimeMillis()) }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markSubmitted(shotId: String, providerTaskId: String) {
        // ★video_id即刻落库：防重复付费生死线
        upsertByShotId(shotId) { it.copy(state = ShotState.SUBMITTED.name, provider_task_id = providerTaskId,
            submitted_at = System.currentTimeMillis()) }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    /** P0-1：已计费但video_id解析失败 → 「待对账」，绝不静默归为FAILED后重提 */
    override suspend fun markReconcile(shotId: String, reason: String) {
        upsertByShotId(shotId) { it.copy(state = ShotState.RECONCILE.name,
            blocked_reason = reason.take(400)) }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markCompleted(shotId: String, localFileUri: String, fileSize: Long) {
        require(fileSize > 0) { "size>0才算completed（架构§5 render_tasks约束）" }
        upsertByShotId(shotId) { it.copy(state = ShotState.COMPLETED.name,
            local_file_uri = localFileUri, file_size = fileSize, completed_at = System.currentTimeMillis()) }
            ?: throw IllegalStateException("shot $shotId not in any checkpoint")
    }

    override suspend fun markFailed(shotId: String, reason: String) {
        // FAILED/BLOCKED为权威态：仅在非终态上写入，不翻转既有FAILED
        upsertByShotIdGuarded(shotId) { row ->
            if (row.state == ShotState.FAILED.name || row.state == ShotState.BLOCKED.name) null
            else row.copy(state = ShotState.FAILED.name, fail_reason = reason.take(400))
        }
    }

    /** 恢复判定：submitted且有video_id的镜全部进re-poll队头，绝不重新submit */
    override suspend fun pendingRepoll(episodeId: String): List<CheckpointEntry> =
        dao.pendingRepoll(episodeId).map { it.toEntry() }

    override suspend fun getEpisode(episodeId: String): EpisodeCheckpoint? = mutex.withLock {
        toEpisodeCheckpointOrNull(episodeId)
    }

    /** 遍历全部episodeId（recoverOnBoot扫描用，P1-6） */
    override suspend fun allEpisodeIds(): List<String> = dao.allEpisodeIds()

    // ---------- 内部工具 ----------

    private suspend fun upsertByShotId(shotId: String, transform: (RenderTaskEntity) -> RenderTaskEntity): RenderTaskEntity? {
        val row = findAny(shotId) ?: return null
        val updated = transform(row)
        dao.upsertRenderTask(updated)
        return updated
    }

    /** transform返回null=放弃写入（守卫型） */
    private suspend fun upsertByShotIdGuarded(shotId: String, transform: (RenderTaskEntity) -> RenderTaskEntity?) {
        val row = findAny(shotId) ?: return
        transform(row)?.let { dao.upsertRenderTask(it) }
    }

    /** 一镜可能出现在多集checkpoint（理论不应发生），取最新submitted_at者 */
    private suspend fun findAny(shotId: String): RenderTaskEntity? =
        dao.renderTasksOfShot(shotId).maxByOrNull { it.submitted_at ?: 0L }

    private suspend fun toEpisodeCheckpointOrNull(episodeId: String): EpisodeCheckpoint? {
        val rows = dao.renderTasksOf(episodeId)
        if (rows.isEmpty()) return null
        return EpisodeCheckpoint(episodeId, rows.map { it.toEntry() }.toMutableList())
    }

    private suspend fun toEpisodeCheckpoint(episodeId: String): EpisodeCheckpoint =
        toEpisodeCheckpointOrNull(episodeId) ?: EpisodeCheckpoint(episodeId, mutableListOf())

    private fun RenderTaskEntity.toEntry() = CheckpointEntry(
        shotId = shot_id,
        state = runCatching { ShotState.valueOf(state) }.getOrDefault(ShotState.PENDING),
        providerTaskId = provider_task_id,
        localFileUri = local_file_uri,
        fileSize = file_size,
        failReason = fail_reason ?: blocked_reason,
        attempt = attempt,
        submittedAt = submitted_at,
    )
}
