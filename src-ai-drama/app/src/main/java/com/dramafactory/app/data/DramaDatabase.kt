package com.dramafactory.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room实体×6 —— 与架构§5建表SQL逐句对应（drama_factory.db, WAL）。
 * 完整schema见 src/main/assets/drama_factory_schema.sql。
 */

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val project_id: String,
    val name: String,
    val style_preset: String = "cinema",
    val episode_plan: Int = 1,
    val budget_shots: Int = 50,
    val created_at: Long,
)

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val asset_id: String,
    val project_id: String,
    val kind: String,               // character/scene/prop/local
    val parent_id: String? = null,
    val pose_role: String? = null,
    val prompt: String,
    val file_uri: String? = null,
    val remote_url: String? = null,
    val g1_state: String = "none",  // none/pass/rejected
    val g2_score: Double? = null,
    val g2_defects: String? = null, // defects非空直接拒（pavo实战）
    // ---- QualityEngine（第九轮）：资产质量闸门落库 ----
    /** G1+G2 综合质量评分 0~1（G2 多模态打分） */
    val quality_score: Double? = null,
    /** 审计状态：pending / approved / rejected（对齐 pavo approved/rejected）；defaultValue 须与 MIGRATION_2_3 一致 */
    @ColumnInfo(defaultValue = "pending")
    val audit_state: String = "pending",
    /** G2 缺陷词列表（JSON 字符串），非空即 DEFECT_DETECTED 直接拒 */
    val defects_json: String? = null,
    /** 拒绝原因（error_code 或可读原因） */
    val q_reject_reason: String? = null,
    /** G1 文件级硬校验错误码（如 NOT_SQUARE / FORMAT_UNSUPPORTED） */
    val g1_error_code: String? = null,
    /** 人脸占比（P1 Bitmap 近似，0~1） */
    val face_ratio: Double? = null,
    val review_state: String = "none", // none/keep/regen（F04人工评审）
    val reject_reason: String? = null,
    val seed: Long? = null,
    val updated_at: Long,
    // ---- 第六轮：本地上传 / 图生图 / 视频参考 扩展字段 ----
    /** 资产来源：generated(引擎生成) / local(用户本地上传) */
    @ColumnInfo(defaultValue = "generated")
    val source: String = "generated",
    /** 本地上传图片URI（MediaStore或app内部存储）；source=local且kind依赖此 */
    val image_uri: String? = null,
    /** 本地上传视频URI（相册/拍摄）；source=local 视频资产 */
    val video_uri: String? = null,
    /** 图生图参考图URI：生成图像时作为 input_images 上传给图像API */
    val reference_image_uri: String? = null,
    // ---- v1.9.12：LLM 扩写视觉描述永久落盘（重启/重进项目不再惰性重扩）----
    /** LLM 扩写后的视觉描述（生图实际用的主体描述）；null=未扩写，生图时惰性实时扩写并回填 */
    val enriched_prompt: String? = null,
)

@Entity(tableName = "shots")
data class ShotEntity(
    @PrimaryKey val shot_id: String,
    val episode_id: String,
    val project_id: String,
    val shot_no: Int,
    val dialogue: String? = null,
    val narration: String? = null,
    val action: String? = null,
    val beat_ref: String? = null,
    val carry_over: String? = null,
    val first_asset_ids: String = "[]",
    val last_asset_ids: String = "[]",
    val sb_check: String = "pending",  // 六铁律: pass/error(JSON)
    // ---- 第六轮：图生视频 / 视频参考 扩展字段 ----
    /** 图生视频首帧URI（本地上传选图）；与 first_asset_ids 并存，优先以显式参考图为准 */
    val first_image_uri: String? = null,
    /** 图生视频尾帧URI（本地上传选图）；两者齐备→AgnesVideoAdapter keyframes 模式 */
    val last_image_uri: String? = null,
    /** 视频参考URI（部分供应商支持的视频参考输入；仅当模型标记支持时落库） */
    val reference_video_uri: String? = null,
    // ---- 第十轮：AI导演视觉指令（运镜/景别/构图，无光影词）----
    val visual_prompt: String? = null,
    /** 每镜时长（秒），渲染prompt组装与预算估算用；defaultValue须与MIGRATION_3_4一致 */
    @ColumnInfo(defaultValue = "6.0")
    val duration_seconds: Double = 6.0,
)

/** 渲染任务checkpoint——断点续传核心表，provider_task_id为防重复付费生死线 */
@Entity(tableName = "render_tasks")
data class RenderTaskEntity(
    @PrimaryKey val shot_id: String,
    val episode_id: String,
    val state: String = "PENDING",       // PENDING/SUBMITTED/COMPLETED/FAILED/BLOCKED
    val provider_task_id: String? = null,// ★video_id：SUBMITTED即写
    val attempt: Int = 0,
    val blocked_reason: String? = null,
    val fail_reason: String? = null,
    val local_file_uri: String? = null,
    val file_size: Long = 0,             // size>0才算completed
    val submitted_at: Long? = null,
    val completed_at: Long? = null,
)

@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val config_id: String,
    val channel: String,             // video/text/image（三通道独立,Q6）
    val provider_id: String,         // agnes / openai_compat / ...
    val model: String,
    val key_cipher: ByteArray,       // AES密文；明文只在Keystore解密瞬间存在
    val key_masked: String,          // sk-***abc UI展示
    val extra_params: String = "{}",
    val is_verified: Boolean = false,
    val updated_at: Long,
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val episode_id: String,
    val project_id: String,
    val ep_no: Int,
    val script_json: String? = null,
    val storyboard_report: String? = null,
    val review_passed: Boolean = false,  // 资产评审全过才置true（花钱前人工闸门）
    val stage_flags: String = "{}",
    // ---- QualityEngine（第九轮）：时代红线按剧集放行 ----
    /** 本集允许出现的跨时代器物清单（JSON 数组字符串）；空=全禁（默认西汉红线）。defaultValue 须与 MIGRATION_2_3 一致 */
    @ColumnInfo(defaultValue = "[]")
    val allowed_cross_era: String = "[]",
)

/** 第十三轮：分镜预览用渲染状态投影 */
data class RenderStateRow(
    @ColumnInfo(name = "shot_id") val shotId: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "local_file_uri") val localFileUri: String?,
)

/** QualityEngine 资产质量状态投影（供 UI 展示评分/拒绝原因/姿态）。 */
data class AssetQualityRow(
    @ColumnInfo(name = "asset_id") val assetId: String = "",
    @ColumnInfo(name = "audit_state") val auditState: String = "pending",
    @ColumnInfo(name = "quality_score") val qualityScore: Double? = null,
    @ColumnInfo(name = "q_reject_reason") val rejectReason: String? = null,
    @ColumnInfo(name = "g1_error_code") val g1ErrorCode: String? = null,
    @ColumnInfo(name = "defects_json") val defectsJson: String? = null,
    @ColumnInfo(name = "pose_role") val poseRole: String? = null,
)

@Dao
interface DramaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProject(p: ProjectEntity)
    @Query("SELECT * FROM projects ORDER BY created_at DESC") suspend fun listProjects(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE project_id=:id") suspend fun project(id: String): ProjectEntity?
    @Query("DELETE FROM projects WHERE project_id=:id") suspend fun deleteProject(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAsset(a: AssetEntity)
    @Query("SELECT * FROM assets WHERE project_id=:projectId AND kind=:kind") suspend fun assetsOf(projectId: String, kind: String): List<AssetEntity>

    /** 第十一轮：项目全部资产（重进项目回填用） */
    @Query("SELECT * FROM assets WHERE project_id=:projectId ORDER BY updated_at") suspend fun assetsAllOf(projectId: String): List<AssetEntity>
    @Query("UPDATE assets SET review_state=:state WHERE asset_id=:assetId") suspend fun setReviewState(assetId: String, state: String)
    /** 第六轮：本地上传/图生图/视频参考 字段落库更新（局部UPDATE，避免整行重建） */
    @Query("UPDATE assets SET source=:source, image_uri=:imageUri, video_uri=:videoUri, reference_image_uri=:referenceImageUri, prompt=:prompt, updated_at=:updatedAt WHERE asset_id=:assetId")
    suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long)

    /** 第十一轮：编辑资产描述（点卡片进入编辑后保存） */
    @Query("UPDATE assets SET prompt=:prompt, updated_at=:updatedAt WHERE asset_id=:assetId")
    suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long)

    /** 第十一轮：生成结果落盘（remote_url + file_uri 预留本地路径） */
    @Query("UPDATE assets SET remote_url=:remoteUrl, updated_at=:updatedAt WHERE asset_id=:assetId")
    suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long)

    /** ★F2 修复：读单资产的生成图 remote_url，供 AI 管线质量审计（AssetAuditor.audit）下载多模态打分。 */
    @Query("SELECT remote_url FROM assets WHERE asset_id=:assetId")
    suspend fun assetRemoteUrl(assetId: String): String?

    /** 第十一轮：删除资产（含参考图子卡由外层逐个删） */
    @Query("DELETE FROM assets WHERE asset_id=:assetId") suspend fun deleteAsset(assetId: String)
    @Query("UPDATE assets SET reference_image_uri=:referenceImageUri, updated_at=:updatedAt WHERE asset_id=:assetId")
    suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long)

    /** v1.9.12：LLM 扩写视觉描述落盘（enriched_prompt 列）。enrichedPrompt=null=清空、回到裸词。 */
    @Query("UPDATE assets SET enriched_prompt=:enrichedPrompt, updated_at=:updatedAt WHERE asset_id=:assetId")
    suspend fun setAssetEnrichedPrompt(assetId: String, enrichedPrompt: String?, updatedAt: Long)

    // ---- QualityEngine（第九轮）：资产质量闸门落库 ----
    /** G1+G2 审计结果落库（质量评分/状态/缺陷/拒绝原因/错误码/人脸占比/姿态） */
    @Query("""
        UPDATE assets SET quality_score=:qualityScore, audit_state=:auditState,
        defects_json=:defectsJson, q_reject_reason=:rejectReason, g1_error_code=:g1ErrorCode,
        face_ratio=:faceRatio, pose_role=:poseRole, g2_score=:qualityScore, updated_at=:updatedAt
        WHERE asset_id=:assetId
    """)
    suspend fun setAssetQuality(
        assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?,
        rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long,
    )

    /** 读取某资产质量状态（UI 展示评分/拒绝原因） */
    @Query("SELECT asset_id, audit_state, quality_score, q_reject_reason, g1_error_code, defects_json, pose_role FROM assets WHERE asset_id=:assetId")
    suspend fun assetQuality(assetId: String): AssetQualityRow?

    /** 某项目全部资产质量状态（资产库页批量展示） */
    @Query("SELECT asset_id, audit_state, quality_score, q_reject_reason, g1_error_code, pose_role FROM assets WHERE project_id=:projectId")
    suspend fun assetQualities(projectId: String): List<AssetQualityRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertShot(s: ShotEntity)
    @Query("SELECT * FROM shots WHERE episode_id=:episodeId ORDER BY shot_no") suspend fun shotsOf(episodeId: String): List<ShotEntity>
    /** 第十轮：重新生成分镜前清空本集旧镜（保留render_tasks历史对账） */
    @Query("DELETE FROM shots WHERE episode_id=:episodeId") suspend fun deleteShotsOf(episodeId: String)
    /** 第十二轮：删除单镜 */
    @Query("DELETE FROM shots WHERE shot_id=:shotId") suspend fun deleteShot(shotId: String)

    /** 第十三轮：本集全部镜的渲染结果（分镜页预览用） */
    @Query("SELECT shot_id, state, local_file_uri FROM render_tasks WHERE episode_id=:episodeId")
    suspend fun renderStatesOf(episodeId: String): List<RenderStateRow>
    /** 第六轮：图生视频首/尾帧 + 视频参考落库 */
    @Query("UPDATE shots SET first_image_uri=:first, last_image_uri=:last WHERE shot_id=:shotId")
    suspend fun setShotKeyframes(shotId: String, first: String?, last: String?)
    @Query("UPDATE shots SET reference_video_uri=:uri WHERE shot_id=:shotId")
    suspend fun setShotReferenceVideo(shotId: String, uri: String?)
    /** 第六轮：读单镜已设关键帧（图生视频） */
    @Query("SELECT * FROM shots WHERE shot_id=:shotId")
    suspend fun shotKeyframes(shotId: String): ShotEntity?
    /** 第六轮：读单镜已设视频参考URI */
    @Query("SELECT reference_video_uri FROM shots WHERE shot_id=:shotId")
    suspend fun shotReferenceVideo(shotId: String): String?

    // ---- QualityEngine（第九轮）：时代红线按剧集放行 ----
    @Query("UPDATE episodes SET allowed_cross_era=:allowed WHERE episode_id=:episodeId")
    suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String)
    @Query("SELECT allowed_cross_era FROM episodes WHERE episode_id=:episodeId")
    suspend fun episodeAllowedCrossEra(episodeId: String): String?

    // checkpoint读写：markSubmitted/markCompleted等经此落库（Room事务保证同步持久化）
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertRenderTask(t: RenderTaskEntity)
    @Query("SELECT * FROM render_tasks WHERE episode_id=:ep") suspend fun renderTasksOf(ep: String): List<RenderTaskEntity>
    @Query("SELECT * FROM render_tasks WHERE shot_id=:shotId") suspend fun renderTask(shotId: String): RenderTaskEntity?
    /** RoomCheckpointStore.findAny用：一镜可能存在于多集checkpoint，取最新者 */
    @Query("SELECT * FROM render_tasks WHERE shot_id=:shotId") suspend fun renderTasksOfShot(shotId: String): List<RenderTaskEntity>
    /** recoverOnBoot全量扫描（P1-6） */
    @Query("SELECT DISTINCT episode_id FROM render_tasks") suspend fun allEpisodeIds(): List<String>
    /** 渲染队列页：某集全部镜状态实时刷新 */
    @Query("SELECT * FROM render_tasks WHERE episode_id=:ep ORDER BY shot_id") suspend fun renderTasksOfEpOrdered(ep: String): List<RenderTaskEntity>
    /** 恢复判定：submitted且有video_id的镜全部进re-poll队头 */
    @Query("SELECT * FROM render_tasks WHERE episode_id=:ep AND state='SUBMITTED' AND provider_task_id IS NOT NULL")
    suspend fun pendingRepoll(ep: String): List<RenderTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProviderConfig(c: ProviderConfigEntity)
    @Query("SELECT * FROM provider_configs WHERE channel=:channel AND is_verified=1 LIMIT 1")
    suspend fun verifiedConfig(channel: String): ProviderConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEpisode(e: EpisodeEntity)
    @Query("SELECT * FROM episodes WHERE episode_id=:id") suspend fun episode(id: String): EpisodeEntity?
    /** 第十轮：项目下全部剧集（按集号升序） */
    @Query("SELECT * FROM episodes WHERE project_id=:projectId ORDER BY ep_no") suspend fun episodesOf(projectId: String): List<EpisodeEntity>
}
