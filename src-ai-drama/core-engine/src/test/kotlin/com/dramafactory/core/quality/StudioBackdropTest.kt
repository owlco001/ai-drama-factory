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

    // ---------- v1.9.11：角色「手持道具」修复 ----------

    @Test fun `角色 era 正向显式要求双手空置`() {
        val out = preset.withCharacterStudioConstraints("一位将军")
        assertTrue(out.contains("双手自然垂于身侧且完全空置"),
            "角色 era 正向应显式禁止持握器物，实际: $out")
        assertTrue(out.contains("不持握任何器物"), "应列举具体禁用手持物")
    }

    @Test fun `角色尾部强指令含空手中英双语且优先级最高`() {
        val anchor = preset.tailAnchorFor(AssetPromptBuilder.KIND_CHARACTER)
        assertTrue(anchor.contains("双手完全空置"), "中文空手指令")
        assertTrue(anchor.contains("BOTH HANDS MUST BE COMPLETELY EMPTY"), "英文空手指令")
        assertTrue(anchor.contains("no weapon") && anchor.contains("no scroll"), "应点名兵器/简牍")
        // 尾部强指令压在 prompt 最末尾（模型尾部权重最高）
        val full = AssetPromptBuilder.finalPrompt(preset, AssetPromptBuilder.KIND_CHARACTER, "一位将军")
        assertTrue(full.trimEnd().endsWith(anchor.trimEnd()), "空手指令应位于 prompt 最末尾")
    }

    @Test fun `角色精简禁词含持握类并透传进正向`() {
        val forbidden = preset.coreForbiddenFor(AssetPromptBuilder.KIND_CHARACTER)
        assertTrue(forbidden.contains("holding") && forbidden.contains("hand holding"), "应含持握动词")
        assertTrue(forbidden.contains("weapon in hand") && forbidden.contains("sword in hand"), "应含 in-hand 类")
        val full = AssetPromptBuilder.finalPrompt(preset, AssetPromptBuilder.KIND_CHARACTER, "一位将军")
        assertTrue(full.contains("Do NOT include"), "禁词应并入正向 Do NOT include")
        assertTrue(full.contains("holding"), "持握类为 ASCII，应被透传进最终 prompt")
    }

    @Test fun `棚拍负向含持握类`() {
        val neg = preset.studioNegativePromptFor()
        assertTrue(neg.contains("holding") && neg.contains("hand holding"), "棚拍负向应含持握类")
        assertTrue(neg.contains("手持"), "棚拍负向应含中文持握词")
    }

    @Test fun `场景与道具不受角色空手约束污染`() {
        assertFalse(preset.tailAnchorFor(AssetPromptBuilder.KIND_SCENE).contains("BOTH HANDS"),
            "空手指令不应泄漏到场景")
        assertFalse(preset.eraPositiveScene.contains("不持握"), "场景正向不应含空手指令")
    }
}
