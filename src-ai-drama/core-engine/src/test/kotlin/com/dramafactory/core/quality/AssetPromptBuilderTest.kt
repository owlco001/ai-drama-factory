package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * v1.7.17 回归单测：资产出图 prompt 组装。
 *
 * 背景：角色卡一直有背景干扰，根因是角色资产也套了 globalPromptSuffix
 * （cinematic / shallow depth of field / vertical 9:16 framing），
 * 这些场景化语义逼模型补环境，与「纯色无干扰背景」正面对冲。
 */
class AssetPromptBuilderTest {

    private val preset = StylePreset()

    @Test fun `角色 prompt 丢弃场景化 suffix`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角，灰袍道长")
        assertFalse(p.contains("vertical 9:16 framing"), "竖屏构图会逼模型补环境，角色卡不该要")
        assertFalse(p.contains("cinematic"), "cinematic 打光与纯色棚拍底冲突")
        assertFalse(p.contains("shallow depth of field"), "景深虚化与干净底冲突")
    }

    @Test fun `角色 prompt 带棚拍 suffix 与纯色背景指令`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(p.contains(preset.characterStudioSuffix))
        assertTrue(p.contains("plain solid color background"))
        assertTrue(p.contains("isolated subject"))
    }

    @Test fun `纯色背景指令排在禁词段落之前`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        val backdrop = p.indexOf("plain solid color background")
        val redline = p.indexOf("Do NOT include")
        val quality = p.indexOf("Negative prompt (soft)")
        assertTrue(backdrop > 0, "必须有背景指令")
        assertTrue(redline > backdrop, "背景指令应比禁词段更靠后（模型对尾部权重最高）")
        assertTrue(quality > redline, "质量负向应在最末")
    }

    @Test fun `角色 prompt 带主体版 era 且不诱导环境`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(p.contains(preset.eraPositiveSubjectOnly), "时代红线不能因为去背景而丢失")
        assertFalse(p.contains(preset.era.positive), "角色卡应收窄为主体版红线")
        assertFalse(p.contains("木构与夯土建筑"), "完整 era 的建筑诱导词不该出现在角色卡")
    }

    @Test fun `场景与道具不受棚拍约束影响`() {
        val scene = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(scene.contains("cinematic"), "场景本就该有电影感")
        assertFalse(scene.contains(preset.studioBackdropPositive), "场景不该被要求纯色底")

        val prop = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertFalse(prop.contains(preset.studioBackdropPositive))
    }

    @Test fun `场景卡禁人物，避免模型往空景塞人`() {
        val scene = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(scene.contains("no people"), "场景必须显式要求无人")
        assertTrue(scene.contains("empty location"), "要求空场")
        val redline = scene.substringAfter("Do NOT include", "")
        assertTrue(redline.contains("people"), "人物禁词须并入 Do NOT include（图像端无 negative 字段）")
        assertTrue(redline.contains("person"))
        assertTrue(redline.contains("crowd"))
    }

    @Test fun `道具卡纯色底且去掉环境化 suffix`() {
        val prop = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertTrue(prop.contains("plain solid color background"), "道具要纯色底")
        assertTrue(prop.contains("isolated single object"), "道具要孤立单品，不带环境")
        assertFalse(prop.contains("cinematic"), "cinematic 打光会逼模型补环境")
        assertFalse(prop.contains("vertical 9:16 framing"), "竖屏构图与产品图式冲突")
        assertFalse(prop.contains("木构与夯土建筑"), "道具不该被要求描绘建筑")
        val redline = prop.substringAfter("Do NOT include", "")
        assertTrue(redline.contains("environment"), "环境禁词须并入正向")
        assertTrue(redline.contains("person"), "道具图不该出现人")
    }

    @Test fun `禁词只并英文，中文禁词不进正向`() {
        val p = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(p.contains("Do NOT include"), "英文禁词应并入正向（图像端无 negative_prompt 字段）")
        assertFalse(p.contains("手机"), "中文禁词并入正向只会稀释权重")
        assertTrue(p.contains("mobile phone"), "应有对应的英文禁词")
    }

    @Test fun `constrained 版本不含禁词与质量负向`() {
        val c = AssetPromptBuilder.constrained(preset, "character", "张角")
        assertFalse(c.contains("Do NOT include"))
        assertFalse(c.contains("Negative prompt (soft)"))
        assertTrue(c.contains("张角"))
    }

    @Test fun `sizeFor 按类型给画幅`() {
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "character"))
        assertEquals("1024x768", AssetPromptBuilder.sizeFor(preset, "scene"))
        assertEquals("768x768", AssetPromptBuilder.sizeFor(preset, "prop"))
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "local"))
        assertEquals("1024x1024", AssetPromptBuilder.sizeFor(preset, "CHARACTER"), "大小写不敏感")
    }
}
