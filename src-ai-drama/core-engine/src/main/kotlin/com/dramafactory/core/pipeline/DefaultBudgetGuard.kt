package com.dramafactory.core.pipeline

import com.dramafactory.core.model.BudgetUsage
import com.dramafactory.core.provider.BudgetGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 条数型预算闸门（决议Q2）：MVP只做条数上限；金额按平台牌价仅展示估算。
 * canSubmit=false 表示将超上限，队列暂停等待用户确认后 resume(confirmedByUser=true) 放行。
 */
class DefaultBudgetGuard(
    private val limits: MutableMap<String, Int> = mutableMapOf(),
) : BudgetGuard {

    private val usedMap = mutableMapOf<String, Int>()

    private val _usage = MutableStateFlow(BudgetUsage(0, 50))
    override val usage: StateFlow<BudgetUsage> get() = _usage

    fun setLimit(projectId: String, limit: Int) {
        limits[projectId] = limit
        refresh(projectId)
    }

    fun used(projectId: String): Int = usedMap.getOrDefault(projectId, 0)

    /** 超限判定：已提交条数 ≥ 上限即拦截 */
    override fun canSubmit(projectId: String): Boolean =
        used(projectId) < limits.getOrDefault(projectId, 50)

    override fun consumeSubmitted(projectId: String) {
        usedMap[projectId] = used(projectId) + 1
        refresh(projectId)
    }

    private fun refresh(projectId: String) {
        _usage.value = BudgetUsage(used(projectId), limits.getOrDefault(projectId, 50))
    }
}
