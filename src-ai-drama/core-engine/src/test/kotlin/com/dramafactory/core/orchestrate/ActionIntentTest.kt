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
}
