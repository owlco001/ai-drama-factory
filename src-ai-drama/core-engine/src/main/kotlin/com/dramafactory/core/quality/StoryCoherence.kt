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
            if (previous != null && previous.sceneContext?.isNotBlank() == true && shot.sceneContext.isNullOrBlank())
                issues += Issue("scene_context_missing", shot.shotNo, "跨镜缺少时间/天气/空间/光线上下文")
            if (previous != null && environmentChangedWithoutTransition(previous, shot))
                issues += Issue("scene_context_conflict", shot.shotNo, "跨镜时间/天气/明暗发生变化但缺少转场依据")
            if (approvedAssetIds.isNotEmpty() && shot.assetIds.any { it !in approvedAssetIds })
                issues += Issue("asset_unbound", shot.shotNo, "镜头包含未批准资产")
            if (shot.durationSeconds !in 5.0..10.0)
                issues += Issue("rhythm_duration", shot.shotNo, "单镜时长应为5-10秒")
            previous = shot
        }
        return issues
    }

    private fun environmentChangedWithoutTransition(
        previous: AiStoryboardDirector.Shot,
        current: AiStoryboardDirector.Shot,
    ): Boolean {
        val before = previous.sceneContext?.trim().orEmpty()
        val after = current.sceneContext?.trim().orEmpty()
        if (before.isBlank() || after.isBlank() || before == after) return false
        val transition = "进入|离开|推门|开门|关门|转场|切换|来到|走进|走出|穿过|回到|转身"
        val timeShift = "过了|随后数小时|天亮|次日|翌日|第二天|时间推移|多年后"
        val weatherOrLight = "雨|晴|雪|雾|夜|昼|白天|阳光|明亮|昏暗|阴天|闪电|室外|室内"
        val weatherChanged = (Regex("雨|雪|雾|晴|阴天").containsMatchIn(before) &&
            Regex("雨|雪|雾|晴|阴天").containsMatchIn(after) &&
            Regex("雨|雪|雾|晴|阴天").findAll(before).map { it.value }.toSet() !=
            Regex("雨|雪|雾|晴|阴天").findAll(after).map { it.value }.toSet())
        val timeChanged = (Regex("夜|昼|白天|清晨|黎明|深夜").containsMatchIn(before) &&
            Regex("夜|昼|白天|清晨|黎明|深夜").containsMatchIn(after) &&
            Regex("夜|昼|白天|清晨|黎明|深夜").findAll(before).map { it.value }.toSet() !=
            Regex("夜|昼|白天|清晨|黎明|深夜").findAll(after).map { it.value }.toSet())
        val lightChanged = (Regex("阳光|明亮|昏暗|阴暗|灯光|自然光").containsMatchIn(before) &&
            Regex("阳光|明亮|昏暗|阴暗|灯光|自然光").containsMatchIn(after) &&
            Regex("阳光|明亮|昏暗|阴暗|灯光|自然光").findAll(before).map { it.value }.toSet() !=
            Regex("阳光|明亮|昏暗|阴暗|灯光|自然光").findAll(after).map { it.value }.toSet())
        return Regex(weatherOrLight).containsMatchIn(before) &&
            Regex(weatherOrLight).containsMatchIn(after) &&
            (weatherChanged || timeChanged || lightChanged) &&
            !Regex(transition).containsMatchIn(current.carryOver.orEmpty()) &&
            !Regex(timeShift).containsMatchIn(current.carryOver.orEmpty())
    }
}
