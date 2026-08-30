package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

/** 第十轮：AI编剧+导演管线单测（纯解析/校验逻辑，不调网络） */
class AiStoryboardDirectorTest {

    @Test
    fun parseShots_严格JSON() {
        val json = """{"shots":[
            {"shot_no":1,"action":"他拔剑出鞘","dialogue":"出招吧","duration_seconds":6,"characters":["林晚"],"beat_ref":"B01"},
            {"shot_no":2,"action":"她侧身避开","narration":"风声骤起"}
        ]}"""
        val shots = AiStoryboardDirector.parseShots(json)
        assertEquals(2, shots.size)
        assertEquals("出招吧", shots[0].dialogue)
        assertEquals("林晚", shots[0].characterNames.first())
        assertEquals(6.0, shots[0].durationSeconds)
        assertNull(shots[1].dialogue)
        assertEquals("风声骤起", shots[1].narration)
    }

    @Test
    fun parseShots_markdown栅栏容错() {
        val wrapped = "```json\n{\"shots\":[{\"shot_no\":1,\"action\":\"转身\"}]}\n```"
        val shots = AiStoryboardDirector.parseShots(wrapped)
        assertEquals(1, shots.size)
        assertEquals("转身", shots[0].action)
    }

    @Test
    fun parseShots_缺字段默认值() {
        val shots = AiStoryboardDirector.parseShots("""{"shots":[{"action":"x"}]}""")
        assertEquals(1, shots.size)
        assertEquals(6.0, shots[0].durationSeconds)   // 默认时长
        assertEquals(1, shots[0].shotNo)              // 缺号自动补
    }

    @Test
    fun parseShots_非JSON返回空() {
        assertTrue(AiStoryboardDirector.parseShots("这不是json").isEmpty())
        assertTrue(AiStoryboardDirector.parseShots("""{"shots":"not-array"}""").isEmpty())
    }

    @Test
    fun parseVisuals_映射镜号() {
        val m = AiStoryboardDirector.parseVisuals(
            """{"visuals":[{"shot_no":1,"visual":"近景缓推"},{"shot_no":2,"visual":"全景横移"}]}""")
        assertEquals(2, m.size)
        assertEquals("近景缓推", m[1])
        assertEquals("全景横移", m[2])
    }

    @Test
    fun 台词逐字校验() {
        val script = "林晚冷声道：「留下吧。」陈默停住了脚步。"
        assertTrue(AiStoryboardDirector.verbatimIn("留下吧", script))
        assertTrue(!AiStoryboardDirector.verbatimIn("留下来", script), "改写台词应判不逐字")
    }

    // 第十五轮：catalog 注入 + asset_ids 校验
    @Test
    fun parseShots_assetIds仅保留catalog内的() {
        val catalog = listOf(
            AiStoryboardDirector.AssetSnapshot("a_1", "character", "张角", "灰袍道长左脸有疤"),
            AiStoryboardDirector.AssetSnapshot("a_2", "scene", "破庙", "残破木结构"),
        )
        val json = """{"shots":[
            {"shot_no":1,"action":"张角走入破庙","asset_ids":["a_1","a_2","a_999"]}
        ]}"""
        val shots = AiStoryboardDirector.parseShots(json, catalog)
        assertEquals(1, shots.size)
        assertEquals(listOf("a_1","a_2"), shots[0].assetIds, "非catalog的a_999应被过滤")
    }

    @Test
    fun parseShots_无catalog时assetIds一律空() {
        val json = """{"shots":[{"shot_no":1,"action":"x","asset_ids":["a_1"]}]}"""
        val shots = AiStoryboardDirector.parseShots(json, emptyList())
        assertEquals(1, shots.size)
        assertTrue(shots[0].assetIds.isEmpty(), "无catalog注入时不接收任何asset_id")
    }
}
