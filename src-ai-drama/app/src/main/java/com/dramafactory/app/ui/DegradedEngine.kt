package com.dramafactory.app.ui

import com.dramafactory.core.model.BudgetUsage
import com.dramafactory.core.model.QueueSnapshot
import com.dramafactory.core.provider.BudgetGuard
import com.dramafactory.core.provider.RenderQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ★第五轮加固：引擎降级实现（AppGraph未就绪/初始化失败时QueueViewModel兜底用）。
 * 全部空操作+空闲快照，保证渲染页可打开、显示空状态，绝不闪退。
 */
class DegradedRenderQueue : RenderQueue {
    private val _state = MutableStateFlow(QueueSnapshot())
    override val state: StateFlow<QueueSnapshot> get() = _state
    override suspend fun enqueueEpisode(episodeId: String, shots: List<com.dramafactory.core.model.ShotMeta>) {}
    override suspend fun pause() {}
    override suspend fun resume(confirmedByUser: Boolean) {}
    override fun cancelShot(shotId: String) {}
}

class DegradedBudgetGuard : BudgetGuard {
    private val _usage = MutableStateFlow(BudgetUsage(0, 0))
    override val usage: StateFlow<BudgetUsage> get() = _usage
    override fun canSubmit(projectId: String) = false
    override fun consumeSubmitted(projectId: String) {}
}
