package com.dramafactory.core.model

import kotlinx.serialization.Serializable

/**
 * 核心数据模型 —— 严格按架构文档§3签名。
 */

// ============ 单镜状态机（架构§7.2） ============
enum class ShotState { PENDING, SUBMITTED, COMPLETED, FAILED, BLOCKED }

// ============ 通道类型（三通道独立适配器，决议Q6） ============
enum class ChannelKind { VIDEO, TEXT, IMAGE }

@Serializable
data class ConnectionInfo(val ok: Boolean, val latencyMs: Long = 0, val detail: String = "")

@Serializable
data class ModelSpec(val id: String, val label: String)

/** 视频提交请求（架构§3 VideoSubmitRequest，字段一致） */
data class VideoSubmitRequest(
    val shotId: String,
    val prompt: String,                  // 已含中文配音主导开头+显式中文指令
    val negativePrompt: String? = null,
    val firstImageUri: String? = null,   // keyframes 模式首帧(data URI)
    val lastImageUri: String? = null,    // 尾帧；两者齐备才发 mode=keyframes
    val width: Int = 448, val height: Int = 832,
    val numFrames: Int = 121, val frameRate: Float = 24f,
    val generateAudio: Boolean = true,
)

/** 轮询结果三态 */
sealed interface PollResult {
    data class InProgress(val progress: Int?) : PollResult
    data class Completed(val videoUrl: String) : PollResult
    data class Failed(val reason: String) : PollResult
}

/** 文本通道请求/响应（enable_thinking=false 约定在Provider实现内） */
data class ChatRequest(
    val messages: List<ChatMessage>,
    val model: String = "agnes-2.5-flash",
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val enableThinking: Boolean = false, // 默认false：避免reasoning吃空content的静默空响应
)
data class ChatMessage(val role: String, val content: String)
data class ChatResponse(val content: String, val raw: String)

/** 图像生成请求（6pose包/场景/道具母图/i2i合成） */
data class ImageGenRequest(
    val prompt: String,
    val size: String = "1024x1024",
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
    /** 408/5xx/网络瞬断 —— 可重试，指数退避 */
    class TransientError(msg: String) : ProviderError(msg)
}

// ============ 断点续传 Checkpoint（架构§3 CheckpointStore配套类型） ============
data class ShotMeta(val shotId: String, val episodeId: String, val prompt: String)

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

// ============ 渲染队列快照（通知栏与UI共用） ============
data class QueueSnapshot(
    val episodeId: String? = null,
    val totalShots: Int = 0,
    val completedShots: Int = 0,
    val running: Boolean = false,
    val pausedReason: String? = null,
    val etaSeconds: Long? = null,
)
