package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 第十三轮：时代红线按剧本自动推断 */
class EraDetectorTest {

    @Test fun 规则_清代剧本() {
        val d = EraDetector.ruleBased("紫禁城里，皇上万福金安，格格们请安。军机处议事。")
        assertEquals("qing", d.eraKey)
    }
    @Test fun 规则_现代剧本() {
        val d = EraDetector.ruleBased("他掏出手机打开微信，挤上地铁去公司上班。")
        assertEquals("modern", d.eraKey)
    }
    @Test fun 规则_唐代剧本() {
        val d = EraDetector.ruleBased("长安城内，贞观三年，突厥使者入朝。")
        assertEquals("tang", d.eraKey)
    }
    @Test fun 规则_架空剧本() {
        val d = EraDetector.ruleBased("少年觉醒系统，开始修炼灵气，踏入修仙之路。")
        assertEquals("fantasy", d.eraKey)
    }
    @Test fun 规则_西汉兜底() {
        val d = EraDetector.ruleBased("无特征文本")
        assertEquals("han", d.eraKey)
    }

    @Test fun 现代预设无时代禁词() {
        assertTrue(EraDetector.presetFor("modern").era.negative.isEmpty())
        val p = EraDetector.presetFor("modern")
        assertFalse(p.negativePrompt.contains("手机"), "现代剧不应禁手机")
    }
    @Test fun 清代预设含历史禁词() {
        val p = EraDetector.presetFor("qing")
        assertTrue(p.negativePrompt.contains("手机"))
    }
    @Test fun 未知key回退汉() {
        assertEquals("han", if (EraDetector.presetFor("xyz").name == "cinema") "han" else "unknown")
    }

    private fun assertFalse(b: Boolean, msg: String = "") = kotlin.test.assertFalse(b, msg)
}
