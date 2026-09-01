package com.dramafactory.core.provider

import com.dramafactory.core.model.ChannelKind
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
import com.dramafactory.core.model.ConnectionInfo
import com.dramafactory.core.model.ImageGenRequest
import com.dramafactory.core.model.ModelSpec
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.VideoSubmitRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * 供应商三通道核心接口 —— 严格按架构文档§3签名。
 */

interface VideoProvider {
    val id: String                       // "agnes" / 未来 "kling"...
    /** 测试连通（最小成本请求）。Key由KeyVault按configId取得 */
    suspend fun validateKey(key: String): Result<ConnectionInfo>
    fun listModels(): List<ModelSpec>
    /**
     * 提交一镜视频任务。实现内部必须：
     * ① 先过 120s 提交限速门（RateGate）
     * ② keyframes 双帧模式 + generate_audio=true + 中文配音指令注入（决议Q9）
     * 返回 providerTaskId(video_id)。调用方拿到后【立即】落库 submitted 态。
     */
    suspend fun submitVideo(req: VideoSubmitRequest): String
    /** 轮询任务。返回终态(completed带url / failed带reason)或进行中(progress) */
    suspend fun pollResult(providerTaskId: String): PollResult
}

/** 文本通道：writer / spec提取 / G2审计 / 忠实性比对 */
interface TextProvider {
    val id: String
    suspend fun chat(req: ChatRequest): ChatResponse      // enable_thinking=false 约定
}

/** 图像通道：6pose包 / 场景 / 道具母图 / i2i合成 */
interface ImageProvider {
    val id: String
    suspend fun generateImage(req: ImageGenRequest): String  // url or data uri
}

// ============ 提交限速门（继承 pavo wait_video_submit_slot 语义） ============
interface RateGate {
    /** 视频提交前阻塞至距上次提交 ≥ intervalMs（默认120_000，可配）。进程内互斥+全局时间戳 */
    suspend fun awaitSlot(channel: ChannelKind = ChannelKind.VIDEO)
}

// ============ 渲染队列 ============
interface RenderQueue {
    /** 总进度/ETA/各镜状态（通知栏与UI共用） */
    val state: StateFlow<com.dramafactory.core.model.QueueSnapshot>
    /** 入队前过 StoryboardGate+BudgetGuard+Key有效；shots由分镜层给出 */
    suspend fun enqueueEpisode(episodeId: String, shots: List<com.dramafactory.core.model.ShotMeta>)
    /** 弱网/预算超限/401 自动触发 */
    suspend fun pause()
    /** 预算超限需 confirm=true 才恢复 */
    suspend fun resume(confirmedByUser: Boolean = false)
    fun cancelShot(shotId: String)
}

// ============ 断点续传 Checkpoint（继承 pavo load-or-merge 语义） ============
interface CheckpointStore {
    /**
     * load-or-merge：复用既有 checkpoint，保留 SUBMITTED/FAILED/BLOCKED 权威态，
     * 仅补缺失镜；"COMPLETED 但文件缺失/0字节" 重置为 PENDING。
     * 恢复语义补充（P0-1）：SUBMITTING 态视为「提交意图已落盘但结果未知」——
     * 实现方恢复时应标记待对账（RECONCILE），绝不盲目重提。
     */
    suspend fun loadOrMerge(episodeId: String, shots: List<com.dramafactory.core.model.ShotMeta>): com.dramafactory.core.model.EpisodeCheckpoint
    /**
     * 提交前置意图落库：submitVideo 调用【之前】同步写 SUBMITTING(shotId, submittedAt)。
     * 实现必须同步持久化（Room + 事务）；进程在 submit 中途被杀时，
     * 该意图记录是判断「远端可能已创建任务并扣费」的唯一依据。
     */
    suspend fun markSubmitting(shotId: String)
    /** submit 成功后立即同步落盘 video_id（防重复付费的生死线）。实现必须同步落盘（Room + 事务） */
    suspend fun markSubmitted(shotId: String, providerTaskId: String)
    /**
     * P0-1：响应已计费但 video_id 解析失败/响应体异常时调用——
     * 标记「待对账」而非静默失败。恢复时须先与服务端对账再决定是否重提。
     */
    suspend fun markReconcile(shotId: String, reason: String)
    /** 校验 size>0 才置 COMPLETED */
    suspend fun markCompleted(shotId: String, localFileUri: String, fileSize: Long)
    suspend fun markFailed(shotId: String, reason: String)
    /** 恢复判定：SUBMITTED 态优先 re-poll 已知 video_id，绝不重新 submit */
    suspend fun pendingRepoll(episodeId: String): List<com.dramafactory.core.model.CheckpointEntry>
    /** 读取整集checkpoint（渲染队列内部用） */
    suspend fun getEpisode(episodeId: String): com.dramafactory.core.model.EpisodeCheckpoint?
    /** 遍历全部 episodeId（recoverOnBoot 扫描用，P1-6） */
    suspend fun allEpisodeIds(): List<String>
}

// ============ 预算闸门 ============
interface BudgetGuard {
    /** MVP 条数型（决议Q2）：返回 false 表示将超上限，队列暂停等待用户确认 */
    fun canSubmit(projectId: String): Boolean
    fun consumeSubmitted(projectId: String)
    /** 已用/上限/牌价金额估算 */
    val usage: StateFlow<com.dramafactory.core.model.BudgetUsage>
}

// ============ 编排器 ============
interface PipelineOrchestrator {
    /** S1..S7 + 各Gate通过位图 */
    val stage: StateFlow<com.dramafactory.core.model.PipelineStage>
    /** 判定当前阶段 Gate 是否放行（评审全过/校验通过/Key有效/预算未超） */
    suspend fun evaluateGates(projectId: String): com.dramafactory.core.model.GateReport
    suspend fun advanceTo(stage: com.dramafactory.core.model.PipelineStage)
    /** 进程重启后的恢复总入口：读checkpoint → 先repoll已提交镜 → 续跑队列 */
    suspend fun recoverOnBoot()
}

// ============ Key 安全存储 ============
interface KeyVault {
    suspend fun save(configId: String, providerId: String, plainKey: String)
    /** 仅Provider层可见，永不回显UI */
    suspend fun load(configId: String): String
    /** sk-***abc 展示 */
    fun masked(configId: String): String
    suspend fun delete(configId: String)

    // ---- v1.9.0：同步读写（供非协程上下文使用，如 VideoProviderRouter 解析/激活标记）----
    /** 同步读取；不存在返回空串，不抛异常 */
    fun readSync(configId: String): String = ""
    /** 同步写入（providerId 记空），失败静默 */
    fun writeSync(configId: String, plainValue: String) {}
}
