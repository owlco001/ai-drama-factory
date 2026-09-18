package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v1.9.35 音效/拟声词不得成为角色台词（真机反馈：叮咚被角色朗读） */
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
}
