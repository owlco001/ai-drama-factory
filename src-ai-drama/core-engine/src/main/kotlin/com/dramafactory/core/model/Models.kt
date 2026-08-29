package com.dramafactory.core.model

import kotlinx.serialization.Serializable

/**
 * 核心数据模型 —— 严格按架构文档§3签名。
 */

// ============ 单镜状态机（架构§7.2） ============
enum class ShotState { PENDING, SUBMITTING, SUBMITTED, COMPLETED, FAILED, BLOCKED, RECONCILE }

// ============ 通道类型（三通道独立适配器，决议Q6） ============
enum class ChannelKind { VIDEO, TEXT, IMAGE }

@Serializable
data class ConnectionInfo(val ok: Boolean, val latencyMs: Long = 0, val detail: String = "")

@Serializable
data class ModelSpec(val id: String, val label: String) {
    /**
     * 第六轮：是否支持视频参考输入（video reference）。
     * MVP：Agnes 视频模型标记 true；其余供应商/自定义模型默认 false，
     * UI 据此决定是否显示「上传参考视频」入口（仅当模型标记支持时显示）。
     */
    var supportsVideoReference: Boolean = false
}

/** 视频提交请求（架构§3 VideoSubmitRequest，字段一致） */
@Serializable
data class VideoSubmitRequest(
    val shotId: String,
    val prompt: String,                  // 已含中文配音主导开头+显式中文指令
    val negativePrompt: String? = null,
    val firstImageUri: String? = null,   // keyframes 模式首帧(data URI)
    val lastImageUri: String? = null,    // 尾帧；两者齐备才发 mode=keyframes
    val width: Int = 448, val height: Int = 832,
    val numFrames: Int = 121, val frameRate: Float = 24f,
    val generateAudio: Boolean = true,
    // ---- 第六轮：图生视频 / 视频参考 扩展 ----
    /** 图生视频单参考图（非keyframes模式时作为起始帧，对齐 pavo image 参数） */
    val referenceImageUri: String? = null,
    /** 视频参考URI（部分供应商支持的视频参考输入；仅当模型标记 supportsVideoReference 时填充） */
    val referenceVideoUri: String? = null,
    // ---- v1.7.2：角色/场景资产参考图注入（套用 pavo 锁脸逻辑：每镜 i2i 绑定角色参考图，保证跨镜长相一致）----
    /** 多图 i2i 参考图（角色主锚图/场景图），非空即注入视频生成的 image 数组，使角色长相跨镜一致 */
    val inputImages: List<String> = emptyList(),
)

/** 轮询结果三态 */
sealed interface PollResult {
    data class InProgress(val progress: Int?) : PollResult
    data class Completed(val videoUrl: String) : PollResult
    data class Failed(val reason: String) : PollResult
}

/** 文本通道请求/响应（enable_thinking=false 约定在Provider实现内） */
@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String = "agnes-2.5-flash",
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val enableThinking: Boolean = false, // 默认false：避免reasoning吃空content的静默空响应
)
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** 第十轮：可选图像输入（data URI 或 http URL）；非空时 Provider 按 OpenAI 视觉格式组装 content */
    val imageUrl: String? = null,
)
@Serializable
data class ChatResponse(val content: String, val raw: String)

/** 图像生成请求（6pose包/场景/道具母图/i2i合成） */
data class ImageGenRequest(
    val prompt: String,
    val size: String = "512x512",
    val inputImages: List<String> = emptyList(),  // URL或data URI，非空即i2i
    val negativePrompt: String? = null,
)

// ============ Provider错误分类（架构§2：Auth/Quota/Validation/Transient） ============
sealed class ProviderError(message: String) : Exception(message) {
    /** 401 —— Key失效：队列自动pause+全局横幅，不烧重试（架构§4.8） */
    class AuthError(msg: String) : ProviderError(msg)
    /** 429 —— 配额/RPM超限：HTTP层立即上抛，视频提交走专用长退避（架构§4.2） */
    class QuotaError(msg: String) : ProviderError(msg)
    /** 400/422 —— 参数非法 */
    class ValidationError(msg: String) : ProviderError(msg)
    /** 408/5xx/网络瞬断 —— 可重试，指数退避。retryable 显式标注可重试性（不再靠消息字符串判定） */
    class TransientError(msg: String, val retryable: Boolean = false) : ProviderError(msg)
    /**
     * P0-1：请求已到达服务端且可能已计费（2xx响应但video_id解析失败、
     * 或提交中途网络断开结果未知）。调用方必须落库待对账，绝不静默重提。
     */
    class ReconcileRequired(val rawBody: String = "", msg: String) : ProviderError(msg)
}

// ============ 断点续传 Checkpoint（架构§3 CheckpointStore配套类型） ============
data class ShotMeta(val shotId: String, val episodeId: String, val prompt: String)

@Serializable
data class CheckpointEntry(
    val shotId: String,
    var state: ShotState = ShotState.PENDING,
    var providerTaskId: String? = null,   // ★video_id：SUBMITTED即刻写入，防重复付费生死线
    var localFileUri: String? = null,
    var fileSize: Long = 0L,              // size>0才算completed
    var failReason: String? = null,
    var attempt: Int = 0,
    var submittedAt: Long? = null,
)

@Serializable
data class EpisodeCheckpoint(
    val episodeId: String,
    val shots: MutableList<CheckpointEntry>,
) {
    fun byId(shotId: String): CheckpointEntry? = shots.firstOrNull { it.shotId == shotId }
    val completedCount get() = shots.count { it.state == ShotState.COMPLETED && it.fileSize > 0 }
}

// ============ 预算闸门（决议Q2：MVP条数型） ============
data class BudgetUsage(val used: Int, val limit: Int, val priceEstimateYuan: Double = 0.0) {
    val overLimit get() = used >= limit
}

// ============ 编排器阶段（架构§4.1七阶段） ============
enum class PipelineStage { S1_PROJECT, S2_IMPORT, S3_ASSETS, S4_REVIEW, S5_STORYBOARD, S6_RENDER, S7_OUTPUT }

data class GateReport(
    val stage: PipelineStage,
    val reviewPassed: Boolean = false,
    val storyboardPassed: Boolean = false,
    val keyValid: Boolean = false,
    val budgetOk: Boolean = false,
) {
    /** 渲染放行 = 四闸门全绿 */
    val renderAllowed get() = reviewPassed && storyboardPassed && keyValid && budgetOk
}

// ============ QualityEngine：资产质量闸门状态（对齐 pavo G1+G2） ============
/** 资产审计状态机：pending → approved / rejected（未经 G1 直接 rejected；G2 defects 直接 rejected） */
enum class AuditState { PENDING, APPROVED, REJECTED }

/** 资产质量审计结果（App 侧落库显示用）。 */
data class AssetQuality(
    val qualityScore: Double = 0.0,    // G2 多模态打分 0~1
    val auditState: AuditState = AuditState.PENDING,
    val defects: List<String> = emptyList(),   // G2 缺陷词（非空直接拒）
    val rejectReason: String? = null,          // 拒绝原因（error_code 或 reason）
    val g1ErrorCode: String? = null,           // G1 文件级硬校验错误码
    val faceRatio: Double? = null,             // 人脸占比（P1）
    val poseRole: String? = null,              // 角色 6 姿态之一
    val updatedAt: Long = 0L,
)

// ============ 渲染队列快照（通知栏与UI共用） ============
data class QueueSnapshot(
    val episodeId: String? = null,
    val totalShots: Int = 0,
    val completedShots: Int = 0,
    val running: Boolean = false,
    val pausedReason: String? = null,
    val etaSeconds: Long? = null,
)
