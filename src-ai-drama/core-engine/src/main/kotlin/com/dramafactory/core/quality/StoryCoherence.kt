package com.dramafactory.core.quality

/** 跨镜与故事连贯性确定性校验，保持与旧模型解耦。 */
object StoryCoherence {
    data class Context(val previousShotNo: Int? = null, val previousCarryOver: String? = null,
                       val activeAssetIds: Set<String> = emptySet())
    data class Issue(val code: String, val shotNo: Int, val message: String)

    fun validate(shots: List<AiStoryboardDirector.Shot>, approvedAssetIds: Set<String> = emptySet()): List<Issue> {
        val issues = mutableListOf<Issue>()
        var previous: AiStoryboardDirector.Shot? = null
        for (shot in shots.sortedBy { it.shotNo }) {
            if (previous != null && shot.shotNo != previous!!.shotNo + 1)
                issues += Issue("shot_order_gap", shot.shotNo, "镜号未连续")
            if (previous != null && shot.carryOver.isNullOrBlank())
                issues += Issue("carry_over_missing", shot.shotNo, "跨镜上下文缺少 carry_over")
            if (approvedAssetIds.isNotEmpty() && shot.assetIds.any { it !in approvedAssetIds })
                issues += Issue("asset_unbound", shot.shotNo, "镜头包含未批准资产")
            if (shot.durationSeconds !in 5.0..10.0)
                issues += Issue("rhythm_duration", shot.shotNo, "单镜时长应为5-10秒")
            previous = shot
        }
        return issues
    }
}
