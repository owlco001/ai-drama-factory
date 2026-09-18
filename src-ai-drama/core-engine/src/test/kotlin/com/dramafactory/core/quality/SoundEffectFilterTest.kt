package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.9.35 音效/拟声词不得成为角色台词（真机反馈：叮咚被角色朗读）。
 * v1.9.37 守卫v2：扩展到旁白与叠字类（音效标注剥离 / 叠字词库 / BGM 提示）。
 */
class SoundEffectFilterTest {

    @Test
    fun `纯音效dialogue判定Strip`() {
        assertTrue(SoundEffectFilter.classify("叮咚") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("叮！咚！") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("叮咚叮咚") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("咚咚咚") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("哗啦——") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `真实台词保持Keep`() {
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("你怎么现在才来"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("叮个屁，开门！"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify(null))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify(""))
        // 门外传来叮咚声 = 叙述句，长句保留
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("只听得门外传来一阵清脆的叮咚声，江雪缓缓抬头看向门口"))
    }

    @Test
    fun `parseShots把音效从dialogue剥离并入action`() {
        val json = """{"shots":[{"shot_no":1,"action":"江雪坐在桌前写字","dialogue":"叮咚","narration":"叮","duration_seconds":6,"characters":["江雪"],"asset_ids":[],"beat_ref":"B01","scene_context":"夜晚室内烛光"}]}"""
        val (shots, _) = AiStoryboardDirector.parseShots(json)
        assertEquals(1, shots.size)
        val s = shots[0]
        assertNull(s.dialogue)
        assertNull(s.narration)
        assertTrue(s.action.contains("音效：叮咚") && s.action.contains("音效：叮"))
    }

    @Test
    fun `parseShots正常台词不回归`() {
        val json = """{"shots":[{"shot_no":1,"action":"江雪抬头","dialogue":"谁？","narration":"夜色渐深","duration_seconds":6,"characters":["江雪"],"asset_ids":[],"beat_ref":"B01","scene_context":"夜晚室内烛光"}]}"""
        val (shots, _) = AiStoryboardDirector.parseShots(json)
        assertEquals("谁？", shots[0].dialogue)
        assertEquals("夜色渐深", shots[0].narration)
    }

    @Test
    fun `数字编号不误伤`() {
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("110"))
    }

    // ================= v1.9.37 守卫v2 新增用例 =================

    @Test
    fun `旁白音效标注整句Strip`() {
        assertTrue(SoundEffectFilter.classify("（音效：叮咚）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("(叮)") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（咚咚）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("(哗啦)") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `破折号叠字拟声整句Strip`() {
        // 整句仅由音效词+破折号构成
        assertTrue(SoundEffectFilter.classify("叮——咚——") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("叮咚……") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `长句含叙述成分Keep`() {
        // 长句（>8 汉字）且含非音效叙述成分 → 防误伤正常描写
        assertEquals(SoundEffectFilter.Verdict.Keep,
            SoundEffectFilter.classify("叮当作响的铜器碰撞声，她停下脚步"))
    }

    @Test
    fun `叠字拟声词扩充词库Strip`() {
        assertTrue(SoundEffectFilter.classify("哗哗哗") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("咚咚咚") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("叮铃叮铃") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("叮铃") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("吱呀") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("咯吱") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("砰砰") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("哒哒") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `带实词台词不回归Keep`() {
        // 「叮个屁，开门！」含实词「屁/开/门」→ 必须 Keep（不许回归）
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("叮个屁，开门！"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("你怎么现在才来"))
    }

    @Test
    fun `BGM音乐提示整段剥离`() {
        // 整段都是 BGM 标注 → Strip
        assertTrue(SoundEffectFilter.classify("（BGM 渐起）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（音乐渐强）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（鼓点密集）") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `BGM提示剥离保留残留台词StripPartial`() {
        // 「（BGM 渐起）快跑！」→ 剥离标注，保留「快跑！」
        val v = SoundEffectFilter.classify("（BGM 渐起）快跑！")
        assertTrue(v is SoundEffectFilter.Verdict.StripPartial, "应判定为 StripPartial: $v")
        val sp = v as SoundEffectFilter.Verdict.StripPartial
        assertEquals("快跑！", sp.kept)
        assertTrue(sp.stripped.contains("BGM"), "stripped 应含 BGM: ${sp.stripped}")
    }

    @Test
    fun `音乐动词子串误剥离回归Keep`() {
        // P0-1 回归：日常括号成分不得被误判为音乐标注
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（起来开门）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（来了）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（停一下）"))
        // 复合音乐提示仍要剥离（正向用例防修过头）
        assertTrue(SoundEffectFilter.classify("（BGM 渐起）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（音乐渐弱）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（鼓点密集）") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `括号内日常实词单字误剥离回归Keep`() {
        // P1-1 回归：NON_SFX_STOPWORDS 不得含日常实词单字，否则「（来）」「（中了）」「（着了）」被误剥离
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（来）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（停了）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（中了）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（去了）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（得了）"))
        assertEquals(SoundEffectFilter.Verdict.Keep, SoundEffectFilter.classify("（着了）"))
        // 正常拟声词括号仍要剥离
        assertTrue(SoundEffectFilter.classify("（叮咚）") is SoundEffectFilter.Verdict.Strip)
        assertTrue(SoundEffectFilter.classify("（哗啦）") is SoundEffectFilter.Verdict.Strip)
    }

    @Test
    fun `parseShots旁白剥离后残留台词保留`() {
        // narration「（BGM 渐起）夜深了」→ StripPartial：dialogue 留「夜深了」，BGM 进 action
        val json = """{"shots":[{"shot_no":1,"action":"夜景","narration":"（BGM 渐起）夜深了","duration_seconds":6,"characters":[],"asset_ids":[],"beat_ref":"B01","scene_context":"夜晚"}]}"""
        val (shots, _) = AiStoryboardDirector.parseShots(json)
        val s = shots[0]
        assertEquals("夜深了", s.narration)
        assertTrue(s.action.contains("BGM"), "BGM 标注应并入 action: ${s.action}")
    }

    @Test
    fun `parseShots剥离BGM提示并入action`() {
        val json = """{"shots":[{"shot_no":1,"action":"江雪奔跑","dialogue":"（BGM 渐起）快跑！","duration_seconds":6,"characters":["江雪"],"asset_ids":[],"beat_ref":"B01","scene_context":"夜晚街道"}]}"""
        val (shots, _) = AiStoryboardDirector.parseShots(json)
        assertEquals(1, shots.size)
        val s = shots[0]
        assertEquals("快跑！", s.dialogue)
        assertTrue(s.action.contains("音效"), "BGM 标注应并入 action: ${s.action}")
    }
}
