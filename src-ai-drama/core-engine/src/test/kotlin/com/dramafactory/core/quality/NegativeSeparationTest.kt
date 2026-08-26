package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** 第十一轮回归：正负分离——负面提示词绝不混入正面prompt（时代红线踩线根因） */
class NegativeSeparationTest {
    private val preset = StylePreset.HAN_DEFAULT

    @Test
    fun 正面prompt不含任何禁词() {
        val p = preset.withEraConstraints("女主·冷艳·黑长直")
        for (banned in preset.forbiddenEraTerms) {
            assertFalse(p.contains(banned), "正面prompt混入禁词: $banned")
        }
    }

    @Test
    fun negativePromptFor含全部禁词() {
        val neg = preset.negativePromptFor()
        assertTrue(neg.contains("手机"))
        assertTrue(neg.contains("塑料"))
    }

    @Test
    fun 放行项从negative剔除() {
        val neg = preset.negativePromptFor(allowed = listOf("游标卡尺"))
        assertFalse(neg.contains("游标卡尺"), "已放行项不应出现在negative中")
        assertTrue(neg.contains("手机"), "其余仍禁")
    }

    @Test
    fun 正面含时代positive后缀() {
        val p = preset.withEraConstraints("书斋夜读")
        assertTrue(p.contains("西汉"), "era.positive 应折叠进正面")
    }
}
