package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * T014 任务1 回归单测：角色棚拍无干扰背景约束。
 * 验证角色资产生成时 positive/negative 各自带棚拍约束；场景/道具类不受影响。
 *
 * v1.7.19：角色改走**主体版 era**（见 [StylePreset.eraPositiveSubjectOnly]）——
 * 完整 era 正文里的「建筑、场景」语义会诱导模型补背景，与纯色棚拍底正面对冲，
 * 这是角色卡背景一直清不掉的根因（v1.7.17 只调了 suffix 顺序，没动红线正文）。
 */
class StudioBackdropTest {

    private val preset = StylePreset()

    @Test fun `withCharacterStudioConstraints 追加棚拍正向约束`() {
        val out = preset.withCharacterStudioConstraints("一位将军，身穿汉代铠甲")
        assertTrue(out.contains("一位将军，身穿汉代铠甲"))
        assertTrue(out.contains(preset.studioBackdropPositive))
        assertTrue(out.contains(preset.eraPositiveSubjectOnly), "仍应带 era 正向约束（主体版）")
        assertFalse(out.contains(preset.era.positive), "完整 era 正文含建筑/场景语义，会诱导模型补背景")
    }

    @Test fun `studioNegativePromptFor 含棚拍负向干扰词`() {
        val neg = preset.studioNegativePromptFor()
        assertTrue(neg.contains("scene background"))
        assertTrue(neg.contains("environment"))
        assertTrue(neg.contains("复杂背景"))
        assertTrue(neg.contains("furniture"))
    }

    @Test fun `studioNegativePromptFor 仍含 era 禁词`() {
        val neg = preset.studioNegativePromptFor()
        assertTrue(neg.contains("手机"))
        assertTrue(neg.contains("car"))
        assertTrue(neg.contains("modern"))
    }

    @Test fun `普通 withEraConstraints 不含棚拍约束`() {
        val out = preset.withEraConstraints("场景：大殿内")
        assertFalse(out.contains(preset.studioBackdropPositive), "非角色资产不应带棚拍正向")
        val neg = preset.negativePromptFor()
        assertFalse(neg.contains("复杂背景"), "非角色负向不应含棚拍词")
        assertFalse(neg.contains("环境"), "非角色负向不应含棚拍词")
    }

    @Test fun `棚拍负向字段默认非空`() {
        assertTrue(preset.studioBackdropPositive.isNotBlank())
        assertTrue(preset.studioBackdropNegative.isNotEmpty())
    }

    @Test fun `棚拍正向字段语义正确`() {
        val pos = preset.studioBackdropPositive
        assertTrue(pos.contains("plain solid color"))
        assertTrue(pos.contains("studio backdrop"))
        assertTrue(pos.contains("no environment"))
        assertTrue(pos.contains("isolated subject"))
    }
}
