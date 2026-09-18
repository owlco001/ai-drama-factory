package com.dramafactory.core.orchestrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionIntentTest {
    @Test fun `解析单条 set_cross_era`() {
        val text = "好的，我已为你放开现代器物限制。\n[ACT] set_cross_era | allowed=手机,眼镜,手表"
        val acts = parseActions(text)
        assertEquals(1, acts.size)
        assertEquals("set_cross_era", acts[0].verb)
        assertEquals(listOf("手机", "眼镜", "手表"), acts[0].paramList("allowed"))
    }

    @Test fun `解析多条且忽略无标记行`() {
        val text = """
            我来帮你调整。
            [ACT] remove_asset | assetId=char_002
            [ACT] edit_asset | assetId=char_003 | prompt=穿红衣的少女
            已处理。
        """.trimIndent()
        val acts = parseActions(text)
        assertEquals(2, acts.size)
        assertEquals("remove_asset", acts[0].verb)
        assertEquals("char_002", acts[0].param("assetId"))
        assertEquals("edit_asset", acts[1].verb)
        assertEquals("char_003", acts[1].param("assetId"))
        assertEquals("穿红衣的少女", acts[1].param("prompt"))
    }

    @Test fun `无 ACT 标记返回空`() {
        assertTrue(parseActions("随便聊聊，今天天气不错").isEmpty())
    }

    @Test fun `空行与无 verb 跳过，纯 verb 保留`() {
        val text = "[ACT] \n[ACT] badline\n[ACT] generate | assetId=x"
        val acts = parseActions(text)
        assertEquals(2, acts.size)  // [ACT] 空行跳过；badline 作为无参 verb 保留
        assertEquals("badline", acts[0].verb)
        assertEquals("generate", acts[1].verb)
    }

    @Test fun `list_assets 无参数`() {
        val acts = parseActions("[ACT] list_assets")
        assertEquals(1, acts.size)
        assertEquals("list_assets", acts[0].verb)
        assertTrue(acts[0].params.isEmpty())
    }

    @Test fun `test_drama 多行剧本不截断`() {
        val text = """好的，我来生成一部测试短剧。
[ACT] test_drama | name=雪夜镖局 | script=大雪夜，镖师张三疯护着一口描金秘匣赶路。
途经荒岭古庙，庙门「吱呀」一开，蹿出三五个黑衣劫匪，当先一人持刀拦住去路。
张三疯把秘匣往腰后一掖，沉声喝道：好狗不挡道。
劫匪头目冷笑：匣子留下，饶你一命。
二人对峙间，山道尽头火把渐亮，一队巡夜差役正打此处经过。"""
        val acts = parseActions(text)
        assertEquals(1, acts.size)
        assertEquals("test_drama", acts[0].verb)
        val script = acts[0].param("script")
        assertTrue(script != null && script.length >= 100, "跨行剧本被截断")
        assertTrue(script != null && script.contains("巡夜差役正打此处经过"), "应保留换行")
    }

    @Test fun `多条指令仅剧本类拼延续，叙述行不混入`() {
        val text = """[ACT] remove_asset | assetId=char_002
已移除该资产。
[ACT] test_drama | script=第一幕雪夜。镖师赶路。劫匪拦路。对峙良久差役赶到解围。
第二幕庙内对峙。"""
        val acts = parseActions(text)
        // remove_asset 不跨行；test_drama 跨行收集到"第二幕…"
        assertEquals(2, acts.size)
        assertEquals("char_002", acts[0].param("assetId"))
        assertTrue(!(acts[0].params.values.any { it.contains("已移除") }), "叙述行不得混入普通指令")
        val s = acts[1].param("script")
        assertTrue(s != null && s.contains("第二幕庙内对峙"), "剧本类跨行未生效")
    }
}
