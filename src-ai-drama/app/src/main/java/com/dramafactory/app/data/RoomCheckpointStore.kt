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
        // T014：成片库表（v5，finished_films）
        FinishedFilmEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class DramaDatabase : RoomDatabase() {
    abstract fun dao(): DramaDao

    /** T014：成片库 DAO（与主 DramaDao 独立暴露）。 */
    abstract fun movieLibraryDao(): MovieLibraryDao

    companion object {
        @Volatile private var instance: DramaDatabase? = null
        fun get(context: Context): DramaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, DramaDatabase::class.java, "drama_factory.db")
                // 第六轮：v1→v2 新增 assets.source/image_uri/video_uri/reference_image_uri
                // 及 shots.first_image_uri/last_image_uri/reference_video_uri 列，旧库破坏性迁移。
                // 第九轮：v2→v3 新增 QualityEngine 列（assets 质量闸门 + episodes 按剧集放行）。
                // 第十轮：v3→v4 shots 新增 visual_prompt + duration_seconds。
                // T014：v4→v5 新增成片库表 finished_films。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build().also { instance = it }
        }

        /** v1→v2：仅新增可空列（默认null），直接ALTER TABLE，保留既有数据 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN source TEXT NOT NULL DEFAULT 'generated'")
                db.execSQL("ALTER TABLE assets ADD COLUMN image_uri TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN video_uri TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN reference_image_uri TEXT")
                db.execSQL("ALTER TABLE shots ADD COLUMN first_image_uri TEXT")
                db.execSQL("ALTER TABLE shots ADD COLUMN last_image_uri TEXT")
                db.execSQL("ALTER TABLE shots ADD COLUMN reference_video_uri TEXT")
            }
        }

        /** 第九轮 QualityEngine（v2→v3）：新增资产质量闸门列 + 按剧集放行跨时代器物列。 */
        /** 第十轮 AI分镜（v3→v4）：shots 增加视觉指令与时长列。 */
        @SuppressWarnings("unused")
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shots ADD COLUMN visual_prompt TEXT")
                db.execSQL("ALTER TABLE shots ADD COLUMN duration_seconds REAL NOT NULL DEFAULT 6.0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE assets ADD COLUMN quality_score REAL")
                db.execSQL("ALTER TABLE assets ADD COLUMN audit_state TEXT NOT NULL DEFAULT 'pending'")
                db.execSQL("ALTER TABLE assets ADD COLUMN defects_json TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN q_reject_reason TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN g1_error_code TEXT")
                db.execSQL("ALTER TABLE assets ADD COLUMN face_ratio REAL")
                db.execSQL("ALTER TABLE assets ADD COLUMN pose_role TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN allowed_cross_era TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /** T014：v4→v5 —— 新增成片库表 finished_films（架构§4.2 Room 迁移 SQL）。
         * 列与 FinishedFilmEntity 严格一致：film_id / episode_id / project_id /
         * file_path / file_size / duration_ms / created_at。
         * AI 编排阶段位点（last_success_stage 等）复用 episodes.stage_flags JSON
         * 承载，避免二次迁移。
         */
        @SuppressWarnings("unused")
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS finished_films (
                        film_id      TEXT PRIMARY KEY,
                        episode_id   TEXT NOT NULL,
                        project_id   TEXT NOT NULL,
                        file_path    TEXT NOT NULL,
                        file_size    INTEGER NOT NULL DEFAULT 0,
                        duration_ms  INTEGER NOT NULL DEFAULT 0,
                        created_at   INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ff_episode ON finished_films(episode_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_ff_project ON finished_films(project_id)")
            }
        }

        /** AI 编排阶段位点（P0-2 断点续跑）：复用 episodes.stage_flags JSON 承载。
         * 写入示例：{"ai_managed":true,"last_success_stage":"GENERATE_STORYBOARD","failed_stage":"ENQUEUE_RENDER"}
         * 读取/写入均通过本 helper，保证 key 名与 AiOrchestrator 的断点续跑契约一致。
         */
        @Suppress("unused")
        object AiStageFlags {
            const val AI_MANAGED = "ai_managed"
            const val LAST_SUCCESS_STAGE = "last_success_stage"
            const val FAILED_STAGE = "failed_stage"
            const val ASSET_COUNT = "asset_count"
            const val SHOT_COUNT = "shot_count"
            const val RENDER_ENQUEUED = "render_enqueued"
            const val PROJECT_ID = "project_id"
            const val SCRIPT = "script"

            /** 简单 key 拼装：避免引入 JSON 库。仅用于 stage_flags 这一轻量字典场景。 */
            fun put(base: String, key: String, value: String): String {
                if (base.isBlank() || base == "{}") return "{\"$key\":\"$value\"}"
                // 已有花括号：直接追加一个逗号分隔项
                val inside = base.trim().trimStart('{').trimEnd('}').trim()
                return "{${inside},\"$key\":\"$value\"}"
            }

            fun putInt(base: String, key: String, value: Int): String {
                if (base.isBlank() || base == "{}") return "{\"$key\":$value}"
                val inside = base.trim().trimStart('{').trimEnd('}').trim()
                return "{${inside},\"$key\":$value}"
            }

            fun putBool(base: String, key: String, value: Boolean): String {
                if (base.isBlank() || base == "{}") return "{\"$key\":$value}"
                val inside = base.trim().trimStart('{').trimEnd('}').trim()
                return "{${inside},\"$key\":$value}"
            }

            /** 宽松取字符串值：查找 "key":"value" 或 "key":value（无引号） */
            fun getString(flags: String?, key: String): String? {
                val f = flags ?: return null
                val qKey = "\"$key\""
                val idx = f.indexOf(qKey)
                if (idx < 0) return null
                val rest = f.substring(idx + qKey.length).trimStart().trimStart(',', ':')
                return when {
                    rest.startsWith("\"") -> rest.substring(1).takeWhile { it != '"' }
                    rest.startsWith("[") -> rest.takeWhile { it != ']' }.plus("]")
                    rest.startsWith("{") -> {
                        var depth = 0; var i = 0
                        while (i < rest.length) {
                            when (rest[i]) {
                                '{' -> depth++
                                '}' -> { depth--; if (depth == 0) return rest.substring(0, i + 1) }
                            }
                            i++
                        }
                        rest
                    }
                    else -> rest.takeWhile { it != ',' && it != '}' }
                }
            }

            fun getInt(flags: String?, key: String): Int =
                getString(flags, key)?.toIntOrNull() ?: 0
            fun getBool(flags: String?, key: String): Boolean =
                "true" == getString(flags, key)
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
