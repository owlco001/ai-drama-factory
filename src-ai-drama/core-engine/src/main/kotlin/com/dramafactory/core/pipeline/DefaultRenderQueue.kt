package com.dramafactory.core.pipeline

import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.VideoProvider
import com.dramafactory.core.provider.ProviderError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 渲染队列 —— 单消费者协程（架构§2）。
 *
 * 每镜流程：BudgetGuard判定 → submit（过限速门）→ video_id到手【立即】markSubmitted落库
 * → 自适应轮询(30s→60s) → 下载clip(size>0校验) → COMPLETED。
 * 401/预算超限/断网 → 项目级PAUSED；单镜失败不拖垮队列。
 *
 * clip下载器可注入：生产为Ktor流式下载，测试为假实现。
 */
class DefaultRenderQueue(
    private val scope: CoroutineScope,
    private val videoProvider: VideoProvider,
    private val checkpointStore: CheckpointStore,
    private val budgetGuard: BudgetGuard,
    /** 下载videoUrl到本地文件并返回(uri,size)；size必须>0才算completed */
    private val downloader: suspend (videoUrl: String, shotId: String) -> Pair<String, Long>,
    /** 轮询间隔策略：默认自适应30s→60s；测试可注入0 */
    var pollIntervalMs: suspend (submittedAt: Long) -> Long = { submittedAt ->
        if (System.currentTimeMillis() - submittedAt < 10 * 60_000L) 30_000L else 60_000L
    },
    /** 提交prompt组装：shotId → (dialogue,narration,action)，由分镜层提供 */
    var shotPromptResolver: suspend (shotId: String) -> Triple<String, String, String> =
        { _ -> Triple("", "", "") },
    /** 首尾帧解析：shotId → (firstUri,lastUri) */
    var shotKeyframeResolver: suspend (shotId: String) -> Pair<String?, String?> = { _ -> null to null },
    private val projectIdOf: (episodeId: String) -> String = { "" },
) {
    private val _state = MutableStateFlow(QueueSnapshot())
    override-free val snapshot get() = _state
    val state: StateFlow<QueueSnapshot> get() = _state

    @Volatile private var paused = false
    @Volatile private var pausedReason: String? = null
    private val cancelledShots = mutableSetOf<String>()
    private var worker: Job? = null

    suspend fun enqueueEpisode(episodeId: String, shots: List<ShotMeta>) {
        // 入队前过load-or-merge：复用checkpoint权威态
        checkpointStore.loadOrMerge(episodeId, shots)
        paused = false; pausedReason = null
        worker?.cancel()
        worker = scope.launch {
            // 先re-poll已提交镜（恢复路径），再按序处理PENDING——绝不重新submit已提交任务
            for (entry in checkpointStore.pendingRepoll(episodeId)) {
                if (!paused) repoll(entry.episodeEntryShot(), entry.providerTaskId!!)
            }
            val cp = checkpointStore.getEpisode(episodeId)
            for (entry in cp?.shots.orEmpty().toList()) {
                while (paused) kotlinx.coroutines.delay(500)
                if (entry.shotId in cancelledShots || entry.state == ShotState.COMPLETED) continue
                when (entry.state) {
                    ShotState.SUBMITTED -> entry.providerTaskId?.let { repoll(entry.shotId, it) }
                    ShotState.PENDING -> processShot(episodeId, entry.shotId)
                    else -> {} // FAILED/BLOCKED保持权威态，不因恢复翻转
                }
            }
            update(episodeId) { it.copy(running = false) }
        }
    }

    private fun CheckpointEntry.episodeEntryShot() = shotId

    private suspend fun update(episodeId: String, f: (QueueSnapshot) -> QueueSnapshot) {
        val cp = checkpointStore.getEpisode(episodeId)
        _state.value = f(
            QueueSnapshot(
                episodeId = episodeId,
                totalShots = cp?.shots?.size ?: 0,
                completedShots = cp?.completedCount ?: 0,
                running = true,
                pausedReason = pausedReason,
            )
        )
    }

    /** 单镜：提交→落库→轮询→下载。任何单镜异常不得拖垮整个队列（PRD §6.1崩溃率约束） */
    private suspend fun processShot(episodeId: String, shotId: String) {
        try {
            // 预算闸门：将超上限 → 队列暂停等待用户确认
            val projectId = projectIdOf(episodeId)
            if (!budgetGuard.canSubmit(projectId)) {
                pause("budget_exceeded")
                return
            }
            val (dialogue, narration, action) = shotPromptResolver(shotId)
            val prompt = com.dramafactory.core.provider.ChineseAudioInjector.buildShotPrompt(dialogue, narration, action)
            val (first, last) = shotKeyframeResolver(shotId)
            val taskId = videoProvider.submitVideo(
                com.dramafactory.core.model.VideoSubmitRequest(
                    shotId = shotId, prompt = prompt,
                    firstImageUri = first, lastImageUri = last,
                )
            )
            // ★HTTP 2xx一返回就同步落盘submitted态——防重复付费生死线（架构§4.3）
            checkpointStore.markSubmitted(shotId, taskId)
            budgetGuard.consumeSubmitted(projectId)
            repoll(shotId, taskId, episodeId)
        } catch (e: ProviderError.AuthError) {
            // 401全局语义：队列自动pause+横幅引导设置页，不烧重试（架构§4.8）
            pause("auth_401")
        } catch (e: Exception) {
            runCatching { checkpointStore.markFailed(shotId, e.message ?: "unknown") }
        }
    }

    /** 轮询已知video_id至终态（恢复与正常路径共用，绝不重新submit） */
    private suspend fun repoll(shotId: String, taskId: String, episodeId: String? = null) {
        val ep = episodeId ?: findEpisodeOf(shotId)
        while (!paused && shotId !in cancelledShots) {
            when (val r = videoProvider.pollResult(taskId)) {
                is PollResult.Completed -> {
                    val (uri, size) = downloader(r.videoUrl, shotId)
                    checkpointStore.markCompleted(shotId, uri, size)
                    return
                }
                is PollResult.Failed -> {
                    checkpointStore.markFailed(shotId, r.reason); return
                }
                is PollResult.InProgress -> kotlinx.coroutines.delay(pollIntervalMs(System.currentTimeMillis()))
            }
        }
    }

    private fun findEpisodeOf(shotId: String): String? =
        _state.value.episodeId

    override fun cancelShot(shotId: String) { cancelledShots += shotId }

    suspend fun pause(reason: String = "manual") {
        paused = true; pausedReason = reason
    }

    suspend fun resume(confirmedByUser: Boolean) {
        // 预算超限需用户确认弹窗才放行（US6）；其余原因自动恢复
        if (pausedReason == "budget_exceeded" && !confirmedByUser) return
        paused = false; pausedReason = null
    }
}

private fun <T> MutableStateFlow<T>.free() {}
