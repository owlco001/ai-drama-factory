package com.dramafactory.app.ui

import com.dramafactory.core.model.BudgetUsage
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.model.ShotMeta
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.RenderQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 渲染队列页ViewModel逻辑——与Android解耦，JVM可单测。
 *
 * 职责：
 * - 展示每集渲染进度（queue.state快照 + 本地镜状态列表轮询刷新）；
 * - 暂停/恢复/取消按钮 → RenderQueue对应方法；
 * - 预算确认弹窗：enqueueEpisode后若队列因budget_exceeded暂停 → 弹确认框，
 *   用户点「继续渲染」→ resume(confirmedByUser=true)，对齐DefaultRenderQueue
 *   的budgetConfirmed一次性放行语义（P1-5）；
 * - RECONCILE镜人工处置：重试（重置为PENDING续跑）/放弃（标BLOCKED终态）。
 */
class QueueLogic(
    private val queue: RenderQueue,
    private val budgetGuard: BudgetGuard,
) {

    /** 队列页状态 */
    data class UiState(
        val snapshot: QueueSnapshot = QueueSnapshot(),
        val usage: BudgetUsage = BudgetUsage(0, 50),
        /** 镜状态机实时刷新：shotId → 状态名 */
        val shotStates: Map<String, String> = emptyMap(),
        /** 待用户确认预算超限（弹窗可见位） */
        val showBudgetConfirm: Boolean = false,
        /** 待人工处置的RECONCILE镜头（对话框数据）：shotId → 原因 */
        val reconcileShot: Pair<String, String>? = null,
        val enqueueError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    private var watchJob: Job? = null
    /** RECONCILE处置回调：retry=true重置PENDING / false标BLOCKED。由App层注入Room写实现 */
    var onReconcileResolve: suspend (shotId: String, retry: Boolean) -> Unit = { _, _ -> }
    /** 镜状态读取器：App层注入Room查询（shotId→状态名），供轮询刷新 */
    var shotStateReader: suspend () -> Map<String, String> = { emptyMap() }

    /** 订阅队列快照+预算用量，并启动镜状态轮询（2s一次，页面离开时stopWatching） */
    fun startWatching(scope: CoroutineScope) {
        stopWatching()
        watchJob = scope.launch {
            while (true) {
                val base = _state.value
                _state.value = base.copy(
                    snapshot = queue.state.value,
                    usage = budgetGuard.usage.value,
                    shotStates = runCatching { shotStateReader() }.getOrDefault(base.shotStates),
                )
                // budget_exceeded暂停且尚未确认 → 触发预算确认弹窗（对齐resume(confirmed)放行位）
                if (queue.state.value.pausedReason == "budget_exceeded" && !_state.value.showBudgetConfirm) {
                    _state.value = _state.value.copy(showBudgetConfirm = true)
                }
                delay(2000)
            }
        }
    }

    fun stopWatching() { watchJob?.cancel(); watchJob = null }

    /** 入队一集。shots由分镜层供给 */
    suspend fun enqueue(episodeId: String, shots: List<ShotMeta>) {
        runCatching { queue.enqueueEpisode(episodeId, shots) }
            .onFailure { _state.value = _state.value.copy(enqueueError = it.message ?: "入队失败") }
    }

    /** 手动暂停 */
    suspend fun pause() = queue.pause()

    /** 普通恢复（非预算原因） */
    suspend fun resume() = queue.resume(confirmedByUser = false)

    /** 取消单镜 */
    fun cancelShot(shotId: String) = queue.cancelShot(shotId)

    /**
     * 第六轮：注入分镜关键帧（图生视频）解析器到渲染队列。
     * shotId → (firstImageUri, lastImageUri)；两者齐备则走 AgnesVideoAdapter keyframes 模式。
     */
    fun setKeyframeResolver(resolver: suspend (shotId: String) -> Pair<String?, String?>) {
        (queue as? com.dramafactory.core.pipeline.DefaultRenderQueue)?.shotKeyframeResolver = resolver
    }

    /**
     * 第六轮：注入视频参考（video reference）解析器到渲染队列。
     * 仅当供应商模型标记 supportsVideoReference=true 时上游才填充此值。
     */
    fun setReferenceVideoResolver(resolver: suspend (shotId: String) -> String?) {
        (queue as? com.dramafactory.core.pipeline.DefaultRenderQueue)?.shotReferenceVideoResolver = resolver
    }

    /** 预算确认弹窗：「继续渲染」（确认放行，越过预算门提交一镜） */
    suspend fun confirmBudget() {
        _state.value = _state.value.copy(showBudgetConfirm = false)
        queue.resume(confirmedByUser = true)
    }

    /** 预算确认弹窗：「暂不渲染」（保持暂停） */
    fun dismissBudgetConfirm() {
        _state.value = _state.value.copy(showBudgetConfirm = false)
    }

    /** 打开某RECONCILE镜的人工处置对话框 */
    fun openReconcileDialog(shotId: String, reason: String) {
        _state.value = _state.value.copy(reconcileShot = shotId to reason)
    }

    /** 处置：retry=true → 重置PENDING续跑；false → 放弃（BLOCKED终态，绝不自动翻转） */
    suspend fun resolveReconcile(retry: Boolean) {
        val shot = _state.value.reconcileShot?.first ?: return
        onReconcileResolve(shot, retry)
        _state.value = _state.value.copy(reconcileShot = null)
        if (retry) queue.resume(confirmedByUser = true)   // 续跑队列剩余镜
    }

    fun dismissReconcileDialog() {
        _state.value = _state.value.copy(reconcileShot = null)
    }

    fun clearEnqueueError() {
        _state.value = _state.value.copy(enqueueError = null)
    }
}
