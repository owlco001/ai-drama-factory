package com.dramafactory.core.pipeline

import com.dramafactory.core.model.CheckpointEntry
import com.dramafactory.core.model.PollResult
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.model.ShotState
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.RenderQueue
import com.dramafactory.core.provider.VideoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 渲染队列 —— 单消费者协程（架构§2）。
 *
 * 每镜流程：BudgetGuard判定 → 【先落SUBMITTING意图】→ submit（过限速门）
 * → video_id到手【立即同步】markSubmitted落库 → 自适应轮询(30s→60s)
 * → 下载clip(size>0校验，失败仅重试取回不重提) → COMPLETED。
 * 401/预算超限 → 项目级PAUSED；单镜失败不拖垮队列。
 *
 * P0-1 防重复付费不变量：
 * - submitVideo 之前 SUBMITTING 意图已同步持久化；
 * - video_id 到手后第一件事是同步 markSubmitted，之后才做任何其他事；
 * - 已发出HTTP但结果不明（瞬断/解析失败/429歧义）→ RECONCILE 待对账，绝不盲目重提。
 * P0-2 单消费者不变量：enqueueEpisode 重入时旧 worker cancel 后必须 join 退出才启动新 worker。
 */
class DefaultRenderQueue(
    private val scope: CoroutineScope,
    private val videoProvider: VideoProvider,
    private val checkpointStore: CheckpointStore,
    private val budgetGuard: BudgetGuard,
    /** 下载videoUrl到本地文件并返回(uri,size)；size必须>0才算completed */
    private val downloader: suspend (videoUrl: String, shotId: String) -> Pair<String, Long>,
    /** 轮询间隔策略：默认自适应30s→60s；测试可注入0 */
    var pollIntervalMs: suspend (submittedAt: Long) -> Long = { _ ->
        30_000L
    },
    /** 提交prompt组装：shotId → (dialogue,narration,action)，由分镜层提供 */
    var shotPromptResolver: suspend (shotId: String) -> Triple<String, String, String> =
        { _ -> Triple("", "", "") },
    /** 首尾帧解析：shotId → (firstUri,lastUri) */
    var shotKeyframeResolver: suspend (shotId: String) -> Pair<String?, String?> = { _ -> null to null },
    // v1.7.2：角色/场景资产参考图解析器（套用 pavo 锁脸逻辑）。给定 shotId 返回该镜
    // 应注入视频生成的资产参考图 URI 列表（角色主锚图为主），使角色长相跨镜一致。
    var shotAssetImageResolver: suspend (shotId: String) -> List<String> = { _ -> emptyList() },
    /** 第六轮：视频参考解析：shotId → referenceVideoUri（仅当模型标记支持时由上游填充） */
    var shotReferenceVideoResolver: suspend (shotId: String) -> String? = { _ -> null },
    private val projectIdOf: (episodeId: String) -> String = { "" },
) : RenderQueue {

    private val _state = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> get() = _state

    @Volatile private var paused = false
    @Volatile private var pausedReason: String? = null
    /** P1-5：用户对budget_exceeded确认放行后，允许越过预算门提交（一次性，提交后即复位） */
    @Volatile private var budgetConfirmed = false
    private val cancelledShots = mutableSetOf<String>()
    private val submittedAtMap = mutableMapOf<String, Long>()
    private var worker: Job? = null

    /** P1-4：取回失败重试退避上限（指数退避封顶） */
    companion object {
        const val FETCH_RETRY_BASE_MS = 2_000L
        const val FETCH_RETRY_CAP_MS = 60_000L
        /** ★F5 修复：取回失败重试次数上限。达到上限后保持 SUBMITTED 退出本 repoll，
         *  避免已付费镜头因缓存目录不可写等原因「永久空转」（下次 recoverOnBoot 经 pendingRepoll 重试一次）。 */
        const val FETCH_RETRY_MAX = 8
    }

    override suspend fun enqueueEpisode(episodeId: String, shots: List<ShotMeta>) {
        // 入队前过load-or-merge：复用checkpoint权威态
        checkpointStore.loadOrMerge(episodeId, shots)
        // P1-5：budget_exceeded 暂停必须等用户确认，enqueue不得自动续跑烧钱
        if (pausedReason != "budget_exceeded") {
            paused = false; pausedReason = null
        }
        // P0-2：旧worker必须真正退出（cancel是异步的）才能启动新worker，
        // 否则两个worker短暂并行会同时选中同一PENDING镜重复提交。
        worker?.cancel()
        worker?.join()
        worker = scope.launch {
            // 主循环：反复扫描checkpoint直至全部终态。
            // 暂停（预算/401/弱网）时不退出——delay等待用户resume后自动续跑剩余PENDING镜
            while (true) {
                if (paused) { delay(100); continue }
                updateSnapshot(episodeId)
                // 先re-poll已提交镜（恢复路径），绝不重新submit已提交任务
                val repolls = checkpointStore.pendingRepoll(episodeId)
                if (repolls.isNotEmpty()) {
                    for (entry in repolls) {
                        if (paused) break
                        repoll(entry.shotId, entry.providerTaskId!!)
                    }
                    continue
                }
                val cp = checkpointStore.getEpisode(episodeId)
                val next = cp?.shots?.firstOrNull {
                    it.shotId !in cancelledShots && it.state == ShotState.PENDING
                }
                if (next == null) break   // 无待处理镜：队列跑完
                processShot(episodeId, next.shotId)
                updateSnapshot(episodeId)
            }
            updateSnapshot(episodeId)
            _state.value = _state.value.copy(running = false)
        }
    }

    private suspend fun updateSnapshot(episodeId: String) {
        val cp = checkpointStore.getEpisode(episodeId)
        _state.value = QueueSnapshot(
            episodeId = episodeId,
            totalShots = cp?.shots?.size ?: 0,
            completedShots = cp?.completedCount ?: 0,
            running = true,
            pausedReason = pausedReason,
        )
    }

    /** 单镜：意图落库→提交→落库→轮询→下载。任何单镜异常不得拖垮整个队列（PRD §6.1崩溃率约束） */
    private suspend fun processShot(episodeId: String, shotId: String) {
        val projectId = projectIdOf(episodeId)
        try {
            // 预算闸门：将超上限 → 队列暂停等待用户确认
            // P1-5：用户已对budget_exceeded显式确认 → 放行本次提交（确认后复位，防无限越权）
            if (!budgetGuard.canSubmit(projectId) && !budgetConfirmed) {
                pause("budget_exceeded")
                return
            }
            budgetConfirmed = false
            // ★P0-1生死线第一步：submit之前先把「即将付费」的意图同步落盘。
            // 此后进程无论在哪一行被杀，恢复时都能看到该镜处于SUBMITTING→对账，绝不静默重提。
            checkpointStore.markSubmitting(shotId)

            val (dialogue, narration, action) = shotPromptResolver(shotId)
            val prompt = com.dramafactory.core.provider.ChineseAudioInjector.buildShotPrompt(dialogue, narration, action)
            val (first, last) = shotKeyframeResolver(shotId)
            val referenceVideo = shotReferenceVideoResolver(shotId)
            // v1.7.2：套用 pavo 锁脸——每镜注入角色/场景资产参考图（i2i），保证跨镜长相一致
            val assetImages = shotAssetImageResolver(shotId)
            val taskId = videoProvider.submitVideo(
                com.dramafactory.core.model.VideoSubmitRequest(
                    shotId = shotId, prompt = prompt,
                    firstImageUri = first, lastImageUri = last,
                    referenceVideoUri = referenceVideo,
                    inputImages = assetImages,
                )
            )
            // ★P0-1生死线第二步：HTTP 2xx/video_id一返回就【同步落库】，且这是拿到id后的第一个动作
            checkpointStore.markSubmitted(shotId, taskId)
            submittedAtMap[shotId] = System.currentTimeMillis()
            budgetGuard.consumeSubmitted(projectId)
            repoll(shotId, taskId)
        } catch (e: CancellationException) {
            // P1-3：用户取消≠业务失败。原样上抛保持结构化并发语义，绝不污染checkpoint
            throw e
        } catch (e: ProviderError.AuthError) {
            // 401全局语义：队列自动pause+横幅引导设置页，不烧重试（架构§4.8）。
            // 意图已落库：恢复后走RECONCILE/SUBMITTED路径对账或重提前先核实
            pause("auth_401")
        } catch (e: ProviderError.ValidationError) {
            // 本地参数校验/明确400/422：远端未创建任务、未计费，可安全标FAILED
            runCatching { checkpointStore.markFailed(shotId, e.message ?: "validation") }
        } catch (e: ProviderError.ReconcileRequired) {
            // P0-1：响应已计费但video_id缺失/响应体异常——落库原始响应待对账，绝不静默重提
            runCatching { checkpointStore.markReconcile(shotId, "${e.message} | raw=${e.rawBody.take(400)}") }
        } catch (e: Exception) {
            // 其余（网络瞬断/5xx耗尽/429耗尽）：HTTP请求可能已到达服务端，
            // 计费状态未知 → 标RECONCILE而非FAILED，防止恢复后重复付费
            runCatching { checkpointStore.markReconcile(shotId, e.message ?: "unknown") }
        }
    }

    /**
     * 轮询已知video_id至终态（恢复与正常路径共用，绝不重新submit）。
     * P1-4：pollResult瞬断在轮内退避重试；下载失败仅重试取回（镜保持SUBMITTED），
     * 与「生成失败」严格区分——已付费镜头永不因取回问题脱离re-pool通道。
     */
    private suspend fun repoll(shotId: String, taskId: String) {
        var fetchBackoff = FETCH_RETRY_BASE_MS
        var fetchFails = 0
        while (!paused && shotId !in cancelledShots) {
            val r = try {
                videoProvider.pollResult(taskId)
            } catch (e: CancellationException) {
                throw e   // P1-3：取消原样传播
            } catch (e: ProviderError.AuthError) {
                pause("auth_401"); return
            } catch (e: Exception) {
                delay(fetchBackoff); fetchBackoff = minOf(fetchBackoff * 2, FETCH_RETRY_CAP_MS)
                continue  // 瞬断：退避后继续轮询同一video_id
            }
            when (r) {
                is PollResult.Completed -> {
                    try {
                        val (uri, size) = downloader(r.videoUrl, shotId)
                        checkpointStore.markCompleted(shotId, uri, size)
                        return
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 取回失败≠生成失败：保持SUBMITTED，退避后重新下载
                        fetchFails++
                        if (fetchFails >= FETCH_RETRY_MAX) {
                            // ★F5：取回失败达到上限，保持 SUBMITTED 退出本 repoll，避免永久空转；
                            // 恢复路径（pendingRepoll）会在下次 recoverOnBoot 时重试一次。
                            return
                        }
                        delay(fetchBackoff); fetchBackoff = minOf(fetchBackoff * 2, FETCH_RETRY_CAP_MS)
                    }
                }
                is PollResult.Failed -> {
                    checkpointStore.markFailed(shotId, r.reason); return
                }
                is PollResult.InProgress -> {
                    fetchBackoff = FETCH_RETRY_BASE_MS   // 正常轮询，重置取回退避
                    delay(pollIntervalMs(submittedAtMap[shotId] ?: 0L))
                }
            }
        }
    }

    override fun cancelShot(shotId: String) { cancelledShots += shotId }

    override suspend fun pause() { paused = true; pausedReason = "manual" }

    suspend fun pause(reason: String) { paused = true; pausedReason = reason }

    override suspend fun resume(confirmedByUser: Boolean) {
        // 预算超限需用户确认弹窗才放行（US6）；其余原因自动恢复
        if (pausedReason == "budget_exceeded" && !confirmedByUser) return
        // P1-5：确认放行 → 允许越过预算门提交（一次性），并清暂停让worker续跑
        if (pausedReason == "budget_exceeded" && confirmedByUser) budgetConfirmed = true
        paused = false; pausedReason = null
    }
}
