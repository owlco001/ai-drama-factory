package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertTrue

class StoryCoherenceTest {
    @Test fun detectsContextAssetAndRhythmBreaks() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "开始", durationSeconds = 6.0, assetIds = listOf("a")),
            AiStoryboardDirector.Shot(3, "跳转", durationSeconds = 12.0, assetIds = listOf("bad")),
        )
        val codes = StoryCoherence.validate(shots, setOf("a")).map { it.code }
        assertTrue("shot_order_gap" in codes)
        assertTrue("carry_over_missing" in codes)
        assertTrue("asset_unbound" in codes)
        assertTrue("rhythm_duration" in codes)
    }

    @Test
    fun 雨夜进入室内不得无依据变成阳光明媚() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "站在门外", durationSeconds = 6.0,
                sceneContext = "雨夜，院门外，昏暗湿滑"),
            AiStoryboardDirector.Shot(2, "站在门厅", durationSeconds = 6.0,
                carryOver = "她推门进入门厅，门外雨声仍在",
                sceneContext = "阳光明媚，室内门厅")
        )
        val codes = StoryCoherence.validate(shots).map { it.code }
        assertTrue("scene_context_conflict" !in codes, "有明确推门承接时允许空间/光线变化")

        val invalid = shots[1].copy(carryOver = "她站在门厅")
        assertTrue("scene_context_conflict" in StoryCoherence.validate(listOf(shots[0], invalid)).map { it.code })
    }

    @Test
    fun 跨镜缺少场景上下文应报错() {
        val shots = listOf(
            AiStoryboardDirector.Shot(1, "站在门外", durationSeconds = 6.0, sceneContext = "雨夜，院门外，昏暗"),
            AiStoryboardDirector.Shot(2, "推门", durationSeconds = 6.0, carryOver = "她推门进入")
        )
        assertTrue("scene_context_missing" in StoryCoherence.validate(shots).map { it.code })
    }
}
