package com.dramafactory.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 资产库页ViewModel逻辑——与Android解耦，JVM可单测。
 *
 * 职责：角色/场景/道具分组卡片流 + 资产生成（调Text/ImageProvider）+ 人工评审勾选（保留/重生成，F04）。
 * 评审语义：keep→review_state='keep'；regen→'regen'并重新触发生成。全部资产keep后评审门（GateReport.reviewPassed）才可放行。
 */
class AssetsLogic {

    enum class Kind(val label: String) { CHARACTER("角色"), SCENE("场景"), PROP("道具") }

    /** 资产卡片数据 */
    data class AssetCard(
        val assetId: String,
        val kind: Kind,
        val prompt: String,
        val remoteUrl: String? = null,     // 生成结果URL/data uri
        val reviewState: String = "none",  // none/keep/regen
        val generating: Boolean = false,   // 生成中转圈
    )

    private val _assets = MutableStateFlow<List<AssetCard>>(emptyList())
    val assets: StateFlow<List<AssetCard>> get() = _assets

    /** 生成回调：App层注入真实ImageProvider调用；返回(url或null)与错误信息 */
    var generateHandler: suspend (card: AssetCard) -> Result<String> = { Result.failure(IllegalStateException("未接线")) }
    /** 评审落库回调：App层注入Room UPDATE assets SET review_state */
    var reviewPersist: suspend (assetId: String, state: String) -> Unit = { _, _ -> }

    fun setAssets(list: List<AssetCard>) { _assets.value = list }

    /** 新增资产（输入prompt后点「添加」） */
    fun addAsset(assetId: String, kind: Kind, prompt: String) {
        if (prompt.isBlank()) return
        _assets.value += AssetCard(assetId = assetId, kind = kind, prompt = prompt.trim())
    }

    /** 删除资产卡片 */
    fun removeAsset(assetId: String) {
        _assets.value = _assets.value.filterNot { it.assetId == assetId }
    }

    /**
     * 生成单资产：置generating转圈 → 调handler → 成功存URL / 失败还原并保留错误提示位。
     * 单卡失败不拖垮其他卡片（PRD崩溃率约束的UI面）。
     */
    suspend fun generate(assetId: String) {
        update(assetId) { it.copy(generating = true) }
        val card = _assets.value.firstOrNull { it.assetId == assetId } ?: return
        val result = runCatching { generateHandler(card) }.getOrElse { Result.failure(it) }
        update(assetId) {
            when {
                result.isSuccess -> it.copy(remoteUrl = result.getOrThrow(), generating = false)
                else -> it.copy(generating = false)   // 失败：URL保持旧值，UI按!success显示重试
            }
        }
    }

    /** 评审勾选：keep=保留 / regen=重生成（重生成同时触发再生成） */
    suspend fun review(assetId: String, keep: Boolean) {
        val newState = if (keep) "keep" else "regen"
        update(assetId) { it.copy(reviewState = newState) }
        reviewPersist(assetId, newState)
        if (!keep) generate(assetId)
    }

    /** 评审门判定：全部资产均已「保留」→ GateReport.reviewPassed=true 放行渲染 */
    fun reviewAllPassed(): Boolean =
        _assets.value.isNotEmpty() && _assets.value.all { it.reviewState == "keep" }

    private fun update(assetId: String, transform: (AssetCard) -> AssetCard) {
        _assets.value = _assets.value.map { if (it.assetId == assetId) transform(it) else it }
    }
}
