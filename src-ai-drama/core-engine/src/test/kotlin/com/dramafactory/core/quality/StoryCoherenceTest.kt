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
}
