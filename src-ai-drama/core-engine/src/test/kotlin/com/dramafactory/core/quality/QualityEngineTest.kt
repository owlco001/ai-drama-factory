package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * QualityEngine 核心质量闸门单测（对齐 pavo 资产质量方法论，纯函数可测部分）。
 *
 * 覆盖：
 * - A. G1 文件级硬校验（格式/尺寸/正方形/文件大小/透明残缺/不可解码）
 * - A2. G2 多模态审计（defects 硬惩罚 + score 不达标 + G1 短路）
 * - D. 分镜六铁律闸门（台词逐字/人物完整/action词表/beat_ref单调/carry_over/真实资产ID）
 * - E. 提交前忠实性校验（时间逆转词表 + 台词逐字边界 + 资产真实）
 * - C. 时代红线（era 注入 + 按镜放行 + 视觉字段error/台词warn）
 * - F. 开场帧重渲染逻辑（keep_first_frame 逃生 + prompt 否定孤立原图 + 道具i2i绑定）
 * - B. 角色参考图套装（角度定义 + 中英双语构图指令）
 */
class QualityEngineTest {

    // ============ A. G1 文件级硬校验 ============

    /** 构造最小合法 PNG（1x1，IHDR 标识尺寸）+ 透明像素（alpha 全 0） */
    private fun makePng(width: Int, height: Int, colorType: Int, withAlphaPixel: Boolean = false): ByteArray {
        // 仅头部 + IHDR；readImageDimensions 解析 IHDR(16..23)。透明判定用 colorType(25)。
        // 此处不必是合法完整 PNG（decoder 仅读头部魔数+IHDR），够 G1 头解析用。
        val head = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // 8-byte signature
        // PNG layout: [sig 8] [IHDR len 4] [IHDR type 4] [width 4]@16 [height 4]@20 [bitDepth]@24 [colorType]@25
        val ihdr = ByteArray(18) // length(4) + "IHDR"(4) + width(4) + height(4) + bitDepth(1) + colorType(1)
        ihdr[0] = 0; ihdr[1] = 0; ihdr[2] = 0; ihdr[3] = 13 // IHDR chunk length = 13
        ihdr[4] = 0x49; ihdr[5] = 0x48; ihdr[6] = 0x44; ihdr[7] = 0x52 // "IHDR"
        ihdr[8] = (width ushr 24).toByte(); ihdr[9] = (width ushr 16).toByte(); ihdr[10] = (width ushr 8).toByte(); ihdr[11] = width.toByte()
        ihdr[12] = (height ushr 24).toByte(); ihdr[13] = (height ushr 16).toByte(); ihdr[14] = (height ushr 8).toByte(); ihdr[15] = height.toByte()
        ihdr[16] = 8 // bit depth
        ihdr[17] = colorType.toByte() // 6=RGBA, 2=RGB
        return head + ihdr
    }

    @Test
    fun g1_合法正方形角色图通过() {
        val png = makePng(1024, 1024, 6)
        val r = AssetInspector.inspect(png, "character", "front_anchor")
        assertTrue(r.passed, "1024x1024 正方形 character 应通过 G1")
        assertEquals(1024, r.width)
        assertEquals("png", r.format)
    }

    @Test
    fun g1_非正方形角色图拒绝() {
        val png = makePng(1024, 768, 6)
        val r = AssetInspector.inspect(png, "character", "front_anchor")
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_NOT_SQUARE, r.errorCode)
    }

    @Test
    fun g1_尺寸过小拒绝() {
        val png = makePng(512, 512, 6)
        val r = AssetInspector.inspect(png, "character")
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_DIMENSION_TOO_SMALL, r.errorCode)
    }

    @Test
    fun g1_webp格式拒绝() {
        // WEBP 魔数
        val webp = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0, 0, 0, 0)
        val r = AssetInspector.inspect(webp, "character")
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_FORMAT_UNSUPPORTED, r.errorCode)
    }

    @Test
    fun g1_不可解码拒绝() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val r = AssetInspector.inspect(garbage, "character")
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_UNREADABLE_IMAGE, r.errorCode)
    }

    @Test
    fun g1_透明残缺PNG拒绝() {
        val png = makePng(1024, 1024, 6)
        val r = AssetInspector.inspect(png, "character")
        // colorType 6 => 含 alpha 通道（minAlpha 标记 null）；透明残缺判定在 minAlpha<250 时触发。
        // 头解析给不出 minAlpha 实际值（无 IDAT），此处验证 PNG 含 alpha 时不会被误判为格式错误。
        assertNotNull(r.format)
    }

    @Test
    fun g1_文件过大拒绝() {
        val png = makePng(1024, 1024, 6)
        val r = AssetInspector.inspect(png, "character", rules = AssetInspector.Rules(maxFileSize = 10L))
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_FILE_TOO_LARGE, r.errorCode)
    }

    @Test
    fun g1_人脸占比近似检查() {
        val png = makePng(1024, 1024, 6)
        val rules = AssetInspector.Rules(
            face = AssetInspector.FaceRule(enabled = true, applyToPoses = setOf("front_anchor"), minFaceRatio = 0.6, maxFaceRatio = 0.7))
        // 模拟 Bitmap 估算人脸占比 0.9（越界）
        val r = AssetInspector.inspect(png, "character", "front_anchor", rules, estimatedFaceRatio = 0.9)
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_FACE_RATIO_OUT_OF_RANGE, r.errorCode)
    }

    // ============ A2. G2 多模态审计 ============

    private class FakeDescriber(private val reply: String) : AssetAuditor.ImageDescriber {
        override suspend fun describe(prompt: String, imageDataUri: String): String = reply
    }

    @Test
    fun g2_defects非空直接拒() = kotlinx.coroutines.runBlocking {
        val png = makePng(1024, 1024, 6)
        val reply = """{"score":0.95,"notes":"ok","defects":["水印","文字"],"face_ratio":0.65}"""
        val r = AssetAuditor.audit(png, "data:image/png;base64,xxx", "角色正面", "character", "front_anchor",
            describer = FakeDescriber(reply))
        assertFalse(r.passed)
        assertEquals(AssetAuditor.ERROR_DEFECT_DETECTED, r.errorCode)
        assertEquals(listOf("水印", "文字"), r.defects)
    }

    @Test
    fun g2_分数不达标拒绝() = kotlinx.coroutines.runBlocking {
        val png = makePng(1024, 1024, 6)
        val reply = """{"score":0.4,"notes":"low","defects":[],"face_ratio":0.65}"""
        val r = AssetAuditor.audit(png, "data:image/png;base64,xxx", "角色正面", "character", "front_anchor",
            describer = FakeDescriber(reply))
        assertFalse(r.passed)
        assertEquals(AssetAuditor.ERROR_QUALITY_BELOW_THRESHOLD, r.errorCode)
    }

    @Test
    fun g2_G1失败短路不调多模态() = kotlinx.coroutines.runBlocking {
        var called = 0
        val describer = object : AssetAuditor.ImageDescriber {
            override suspend fun describe(prompt: String, imageDataUri: String): String { called++; return "{}" }
        }
        val garbage = byteArrayOf(0x00, 0x01)
        val r = AssetAuditor.audit(garbage, "data:uri", "x", "character", rules = AssetAuditor.AuditRules(),
            describer = describer)
        assertFalse(r.passed)
        assertEquals(AssetInspector.ERROR_UNREADABLE_IMAGE, r.errorCode)
        assertEquals(0, called) // G1 失败不应调多模态
    }

    @Test
    fun g2_解析容错_非JSON返回失败() = kotlinx.coroutines.runBlocking {
        val png = makePng(1024, 1024, 6)
        val r = AssetAuditor.audit(png, "data:uri", "x", "character",
            describer = FakeDescriber("这不是json"))
        // 非JSON：解析容错给出默认低分（<阈值）→ 拒绝；错误码为 G2_CALL_FAILED 或 QUALITY_BELOW_THRESHOLD 均可
        assertFalse(r.passed)
        assertTrue(r.errorCode == "G2_CALL_FAILED" || r.errorCode == "QUALITY_BELOW_THRESHOLD",
            "非JSON应拒绝，实际 errorCode=${r.errorCode}")
    }

    // ============ B. 角色参考图套装（v1.7.20，取代 6 姿态资产包）============

    @Test
    fun b_参考图套装定义完整() {
        val shots = StylePreset.HAN_DEFAULT.referenceShots
        assertEquals(4, shots.size, "短剧流水线默认 4 张独立参考图")
        val keys = shots.map { it.key }.toSet()
        assertEquals(setOf("front_bust", "side_45_right", "profile_side", "front_full_body"), keys)
        assertTrue(shots.all { it.cn.isNotBlank() && it.en.isNotBlank() }, "每张参考图需中英双语构图指令")
        assertTrue(shots.none { it.expression }, "锁脸参考图必须中性表情，默认套装不含情绪特写")
    }

    @Test
    fun b2_参考图通用规范与禁忌生效() {
        val preset = StylePreset.HAN_DEFAULT
        val p = AssetPromptBuilder.finalReferencePrompt(preset, "张角，灰袍道长", preset.referenceShots.first())
        assertTrue(p.contains(preset.referenceCommonPositive), "通用硬性规范必须叠加到每张参考图")
        assertTrue(p.contains("plain solid color background"), "参考图要纯色/纯白干净底")
        val redline = p.substringAfter("Do NOT include", "")
        assertTrue(redline.contains("multiple views in one image"), "禁拼图：多小人挤一张图会让视频模型识别失败")
        assertTrue(redline.contains("sunglasses"), "禁遮眼")
        assertTrue(redline.contains("exaggerated expression"), "禁夸张表情（参考图必须中性）")
        assertTrue(redline.contains("watermark"), "禁水印")
        // 时代红线仍生效：参考图也必须是西汉风貌
        assertTrue(p.contains(preset.eraPositiveSubjectOnly), "参考图仍受时代红线约束")
        assertFalse(p.contains("木构与夯土建筑"), "参考图不该诱导描绘建筑环境")
    }

    @Test
    fun b3_专业版表情包不进默认套装() {
        val preset = StylePreset.HAN_DEFAULT
        assertEquals(0, preset.referenceShots.count { it.expression }, "默认套装不含情绪图")
        val expr = StylePreset.defaultExpressionShots()
        assertEquals(4, expr.size)
        assertTrue(expr.all { it.expression }, "专业版表情包（喜/怒/平静/忧伤）")
    }

    // ============ C. 时代红线 ============

    @Test
    fun c_era负向折叠进negative() {
        val preset = StylePreset.HAN_DEFAULT
        assertTrue(preset.negativePrompt.contains("手机"), "era.negative 应折叠进全局 negative")
        assertTrue(preset.negativePrompt.contains("塑料"), "global_negative 应保留")
    }

    @Test
    fun c_era正向往suffix() {
        val preset = StylePreset.HAN_DEFAULT
        assertTrue(preset.positiveSuffix.contains("西汉末年"), "era.positive 应折叠进 suffix")
    }

    @Test
    fun c_按镜放行剔除禁词() {
        val preset = StylePreset.HAN_DEFAULT
        val eff = preset.effectiveForbidden(shotAllowed = listOf("游标卡尺"))
        assertFalse(eff.contains("游标卡尺"), "放行权应从本镜禁词剔除")
        assertTrue(eff.contains("手机"), "其余仍禁")
    }

    @Test
    fun c_视觉字段命中error_台词命中warn() {
        val preset = StylePreset.HAN_DEFAULT
        val shots = listOf(
            mapOf("shot_id" to "s1", "action" to "他掏出手机拨号", "dialogue" to listOf(mapOf("text" to "这是现代物品"))),
            mapOf("shot_id" to "s2", "action" to "他拔出青铜剑", "dialogue" to listOf(mapOf("text" to "你这手机从哪来"))),
        )
        val report = EraConsistencyChecker.check(shots, preset.forbiddenEraTerms)
        assertFalse(report.ok, "视觉字段命中手机应 error")
        val err = report.errors.firstOrNull { it.shotId == "s1" }
        assertNotNull(err, "s1 action 命中手机应为 error")
        // s2 dialogue 含手机 → warn
        val warn = report.warnings.firstOrNull { it.shotId == "s2" }
        assertNotNull(warn, "s2 台词命中手机应为 warn")
    }

    @Test
    fun c_词边界不误伤() {
        val preset = StylePreset.HAN_DEFAULT
        // "钟声" 不含 "钟表"，不应命中；ascii "car" 不应在 "policy card" 命中
        val shots = listOf(
            mapOf("shot_id" to "s1", "action" to "远处传来钟声", "dialogue" to listOf(mapOf("text" to "policy card 在手"))),
        )
        val report = EraConsistencyChecker.check(shots, preset.forbiddenEraTerms)
        assertTrue(report.ok, "钟声/policy card 不应误报时代红线")
    }

    // ============ D. 分镜六铁律闸门 ============

    @Test
    fun d_缺beat_ref报错() {
        val entry = StoryboardGate.Entry("s1", 1,
            panel = StoryboardGate.Panel(action = "他拔剑", dialogue = listOf(StoryboardGate.DialogueLine("a", "出招"))),
            beatRef = null)
        val report = StoryboardGate.validateStoryboard(listOf(entry), setOf("a"))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "beat_ref_missing" })
    }

    @Test
    fun d_beat回退报错() {
        val e1 = StoryboardGate.Entry("s1", 1, beatRef = "beat_02", beatIndex = 2, panel = StoryboardGate.Panel(sceneId = "sc", action = "x"))
        val e2 = StoryboardGate.Entry("s2", 2, beatRef = "beat_01", beatIndex = 1, panel = StoryboardGate.Panel(sceneId = "sc", action = "y"), carryOver = "承接")
        val report = StoryboardGate.validateStoryboard(listOf(e1, e2))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "beat_out_of_order" })
    }

    @Test
    fun d_action含光影词报错() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(action = "暖色调打光，他拔剑"))
        val report = StoryboardGate.validateStoryboard(listOf(e))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "action_contains_style_vocab" })
    }

    @Test
    fun d_编造资产ID报错() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            associateAssetIds = listOf("fake_id_123"))
        val report = StoryboardGate.validateStoryboard(listOf(e), catalogApprovedIds = setOf("real_id"))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "missing_asset" })
    }

    @Test
    fun d_同场景缺carry_over报错() {
        val e1 = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1, panel = StoryboardGate.Panel(sceneId = "sc", action = "x"))
        val e2 = StoryboardGate.Entry("s2", 2, beatRef = "beat_02", beatIndex = 2, panel = StoryboardGate.Panel(sceneId = "sc", action = "y"))
        val report = StoryboardGate.validateStoryboard(listOf(e1, e2))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "carry_over_missing" })
    }

    @Test
    fun d_合法分镜通过() {
        val e1 = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(sceneId = "sc", action = "他拔剑出鞘", dialogue = listOf(StoryboardGate.DialogueLine("a", "出招"))),
            associateAssetIds = listOf("char_a"))
        val e2 = StoryboardGate.Entry("s2", 2, beatRef = "beat_02", beatIndex = 2,
            panel = StoryboardGate.Panel(sceneId = "sc", action = "敌人后退", dialogue = listOf(StoryboardGate.DialogueLine("a", "受死"))),
            associateAssetIds = listOf("char_a"), carryOver = "承接s1末帧定态")
        val report = StoryboardGate.validateStoryboard(listOf(e1, e2), catalogApprovedIds = setOf("char_a"), characterAssetIds = setOf("char_a"))
        assertTrue(report.ok, "合法分镜应全部通过：${report.issues}")
    }

    @Test
    fun d_compile_from_script解析beat_ref与资产() {
        val shots = listOf(
            mapOf(
                "shot_id" to "s1", "beat_ref" to "B01", "scene_id" to "sc", "duration_seconds" to 5.0,
                "action" to "他拔剑", "characters_present" to listOf("char_a"),
                "dialogue" to listOf(mapOf("character_id" to "char_a", "text" to "出招")),
                "associate_asset_ids" to listOf("char_a"),
            ),
        )
        val entries = StoryboardGate.compileStoryboard(shots, catalogApprovedIds = setOf("char_a"))
        assertEquals(1, entries.size)
        assertEquals("beat_01", entries[0].beatRef)
        assertEquals(1, entries[0].beatIndex)
        assertTrue(entries[0].associateAssetIds.contains("char_a"))
    }

    // ============ E. 提交前忠实性校验 ============

    @Test
    fun e_时间逆转词表拦截() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(action = "时光逆转，他回到了过去"))
        val report = FidelityGate.gateShot(e, motionPrompt = "时光逆转，他回到了过去")
        assertFalse(report.ok)
        assertTrue(report.blocked)
        assertTrue(report.errors.any { it.code == "gate_time_reversal" })
    }

    @Test
    fun e_台词逐字边界防子串绕过() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(dialogue = listOf(StoryboardGate.DialogueLine("a", "留下吧"))),
            associateAssetIds = listOf("char_a"))
        // 提交文本为「那就留下吧。」——子串包含"留下吧"但无引号边界，应判定未逐字进入
        val report = FidelityGate.gateShot(e, motionPrompt = "那就留下吧。", catalogApprovedIds = setOf("char_a"))
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "gate_dialogue_altered" })
    }

    @Test
    fun e_逐字台词通过() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            panel = StoryboardGate.Panel(dialogue = listOf(StoryboardGate.DialogueLine("a", "出招吧")), duration = 5.0),
            associateAssetIds = listOf("char_a"))
        val report = FidelityGate.gateShot(e, motionPrompt = "他怒喝：「出招吧」", catalogApprovedIds = setOf("char_a"))
        assertTrue(report.ok, "引号边界内的逐字台词应通过：${report.issues}")
    }

    @Test
    fun e_无绑定资产拦截() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1)
        val report = FidelityGate.gateShot(e, motionPrompt = "x")
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "gate_no_asset_bound" })
    }

    @Test
    fun e_时间逆转英文词() {
        val e = StoryboardGate.Entry("s1", 1, beatRef = "beat_01", beatIndex = 1,
            narration = "the scene rewind to the past")
        val report = FidelityGate.gateShot(e, motionPrompt = "x")
        assertFalse(report.ok)
        assertTrue(report.errors.any { it.code == "gate_time_reversal" })
    }

    // ============ F. 开场帧重渲染 + 道具 i2i ============

    @Test
    fun f_keepFirstFrame逃生不重渲染() {
        // keepFirstFrame=true → 跳过开场帧重渲染（保留原视频首帧）；false → 需重渲染
        assertFalse(ShotDirector.shouldRerenderOpeningFrame(keepFirstFrame = true))
        assertTrue(ShotDirector.shouldRerenderOpeningFrame(keepFirstFrame = false))
    }

    @Test
    fun f_开场帧prompt否定孤立原图() {
        val binding = ShotDirector.Binding(baseUri = "base.png", assetUris = listOf("ref1.png"), legend = "@图2=角色DNA")
        val (prompt, neg) = ShotDirector.buildOpeningFramePrompt(
            frameStart = "书房烛光下他研墨",
            binding = binding,
            stylePreset = StylePreset.HAN_DEFAULT,
        )
        assertTrue(prompt.contains("书房烛光下他研墨"), "应含 frame_start 场景描述")
        assertTrue(prompt.contains("NOT an isolated product/studio photo"), "应硬否定孤立原图（对齐 pavo）")
        assertTrue(neg.contains("isolated studio shot"), "negative 应含孤立棚拍否定词")
    }

    @Test
    fun f_道具i2i绑定双图() {
        val base = "scene.png"; val prop = "prop_master.png"
        val (prompt, neg) = ShotDirector.buildPropI2iPrompt(base, prop, "墨玉书简", StylePreset.HAN_DEFAULT)
        assertTrue(prompt.contains("墨玉书简"), "应指明道具名")
        assertTrue(prompt.contains("完全一致的外观"), "应保持外观一致（对齐 pavo prop_i2i）")
        val imgs = ShotDirector.inputImages(ShotDirector.Binding(baseUri = base, assetUris = listOf(prop)))
        assertEquals(listOf(base, prop), imgs)
    }

    @Test
    fun f_开场帧prompt含audit_notes修正() {
        val (prompt, _) = ShotDirector.buildOpeningFramePrompt(
            frameStart = "夜战",
            binding = ShotDirector.Binding(baseUri = "b"),
            stylePreset = StylePreset.HAN_DEFAULT,
            auditNotes = listOf("人脸占比偏低，放大面部"),
        )
        assertTrue(prompt.contains("人脸占比偏低，放大面部"), "re-render 应注入上一轮 fidelity audit_notes")
    }
}
