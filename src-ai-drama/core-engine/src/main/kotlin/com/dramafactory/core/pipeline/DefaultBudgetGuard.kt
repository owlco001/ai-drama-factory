package com.dramafactory.core.pipeline

import com.dramafactory.core.model.BudgetUsage
import com.dramafactory.core.provider.BudgetGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 条数型预算闸门（决议Q2）：MVP只做条数上限；金额按平台牌价仅展示估算。
 * canSubmit=false 表示将超上限，队列暂停等待用户确认后 resume(confirmedByUser=true) 放行。
 *
 * P1-2：扣减原子化。used 用 AtomicInteger CAS、limits 用 ConcurrentHashMap 只读快照入参，
 * 并发 consumeSubmitted 绝不丢扣减 → 超预算提交不可能发生。
 */
class DefaultBudgetGuard(
    /** 构造时快照为只读；后续变更仅经 setLimit */
    limits: Map<String, Int> = emptyMap(),
) : BudgetGuard {

    private val limitsMap = ConcurrentHashMap(limits)
    private val usedMap = ConcurrentHashMap<String, AtomicInteger>()

    private val _usage = MutableStateFlow(BudgetUsage(0, 50))
    override val usage: StateFlow<BudgetUsage> get() = _usage

    fun setLimit(projectId: String, limit: Int) {
        limitsMap[projectId] = limit
        refresh(projectId)
    }

    fun used(projectId: String): Int = usedMap[projectId]?.get() ?: 0

    /** 超限判定：已提交条数 ≥ 上限即拦截 */
    override fun canSubmit(projectId: String): Boolean =
        used(projectId) < limitOf(projectId)

    /** P1-2：CAS自旋扣减，并发调用恰各计一次 */
    override fun consumeSubmitted(projectId: String) {
        val counter = usedMap.computeIfAbsent(projectId) { AtomicInteger(0) }
        while (true) {
            val cur = counter.get()
            if (counter.compareAndSet(cur, cur + 1)) break
        }
        refresh(projectId)
    }

    private fun limitOf(projectId: String): Int = limitsMap[projectId] ?: DEFAULT_LIMIT

    private fun refresh(projectId: String) {
        _usage.value = BudgetUsage(used(projectId), limitOf(projectId))
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
