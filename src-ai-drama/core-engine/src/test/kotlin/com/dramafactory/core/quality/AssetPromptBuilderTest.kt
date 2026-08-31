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

    /**
     * v1.7.21 修正：旧断言写反了（要求 redline > backdrop，等于把背景指令埋在 100+ 禁词之前）。
     * 图像模型对 prompt **尾部**权重最高，最关键的一条约束必须在最末尾。
     */
    @Test fun `尾部强指令排在最末尾，高于禁词与质量负向`() {
        for (kind in listOf("character", "scene", "prop")) {
            val p = AssetPromptBuilder.finalPrompt(preset, kind, "测试")
            val redline = p.indexOf("Do NOT include")
            val quality = p.indexOf("Negative prompt (soft)")
            val anchor = p.indexOf("【画面要求·最高优先级】")
            assertTrue(redline > 0, "$kind 应有禁词段")
            assertTrue(quality > redline, "$kind 质量负向应在禁词之后")
            assertTrue(anchor > quality, "$kind 尾部强指令必须在质量负向之后（模型尾部权重最高）")
            assertTrue(anchor > p.length - 400, "$kind 尾部强指令应紧贴 prompt 末尾")
        }
    }

    @Test fun `尾部强指令内容按类型区分`() {
        val c = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertTrue(c.contains("BACKGROUND REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(c.contains("solid white background"), "角色尾部要锁死纯色底")

        val s = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertTrue(s.contains("EMPTY SCENE REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(s.contains("no people"), "场景尾部要锁死无人")

        val p = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertTrue(p.contains("PROP SHOT REQUIREMENT (HIGHEST PRIORITY)"))
        assertTrue(p.contains("no hands"), "道具尾部要锁死无手")
    }

    /**
     * v1.7.21：禁词膨胀回归闸。
     * 旧版三类卡均塞了 100+ 项（含 gun / train / refrigerator / skateboard 等无关项），
     * 真正管用的 scene background / environment / people 被稀释成路人，约束等于失效。
     */
    @Test fun `禁词精简，不把完整时代红线塞进图像 prompt`() {
        for (kind in listOf("character", "scene", "prop")) {
            val p = AssetPromptBuilder.finalPrompt(preset, kind, "测试")
            val notInclude = p.substringAfter("Do NOT include:", "").substringBefore("Negative prompt (soft):")
            val items = notInclude.trim().trimEnd('.').split(",").map { it.trim() }.filter { it.isNotEmpty() }
            assertTrue(items.size <= 45, "$kind 禁词应精简（实际 ${items.size} 项）：$items")
            assertFalse(p.contains("refrigerator"), "$kind 不该出现与资产外观无关的时代禁词")
            assertFalse(p.contains("skateboard"))
            assertFalse(p.contains("airplane"))
        }
        // 完整红线仍完整保留在视频端 negative 通道，未被裁掉
        assertTrue(preset.sceneNegativePromptFor().contains("refrigerator"), "视频端仍用完整红线")
        assertTrue(preset.studioNegativePromptFor().contains("refrigerator"))
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
        assertTrue(p.contains("smartphone"), "应有对应的英文禁词")
    }

    /**
     * v1.7.21：era 正向按资产类型分家。
     *
     * 旧版三类共用一份（场景用完整版），导致：
     * - 场景 era 首句「所有**人物**、服饰、建筑…」——正向在喊「画人」，no people 压不住；
     * - 道具 era 含「汉代衣冠」——等于请模型画个穿衣的人；
     * - 角色 era 含「简牍竹简、青铜/漆木/陶器、火烛照明」——诱导模型在人物周围补器物与火光。
     */
    @Test fun `era 正向按类型分家，互不串味`() {
        val c = AssetPromptBuilder.finalPrompt(preset, "character", "张角")
        assertFalse(c.contains("简牍竹简"), "角色卡提器物会诱导模型在人物周围补道具")
        assertFalse(c.contains("火烛照明"), "火光是环境元素，角色卡不该提")
        assertTrue(c.contains("深衣"), "服饰红线必须保留")

        val s = AssetPromptBuilder.finalPrompt(preset, "scene", "破庙内景")
        assertFalse(s.contains(preset.era.positive), "场景不该再用完整 era（首句就在喊「人物」）")
        assertTrue(s.contains("木构与夯土建筑"), "建筑红线是场景的本分")
        assertFalse(s.substringBefore("Do NOT include").contains("所有人物"), "场景正向不得出现「人物」二字")

        val p = AssetPromptBuilder.finalPrompt(preset, "prop", "墨玉书简")
        assertFalse(p.contains("深衣"), "道具卡提衣冠等于请模型画个穿衣的人")
        assertFalse(p.contains("火烛照明"), "道具卡不该提环境照明")
        assertTrue(p.contains("简牍竹简"), "器物形制红线是道具的本分")
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
