package com.dramafactory.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals

class LlmAssetExtractorTest {
    @Test
    fun `无人机从误分类角色纠正为道具`() {
        val assets = LlmAssetExtractor.parseJson(
            """{"characters":[{"name":"无人机","desc":"银色四旋翼无人机，腹部装有橙色外卖盒"}],"scenes":[],"props":[]}"""
        )

        assertEquals(1, assets.size)
        assertEquals("prop", assets.single().kind)
        assertEquals("无人机", assets.single().name)
    }

    @Test
    fun `有明确人格的无人机角色不被误改`() {
        val assets = LlmAssetExtractor.parseJson(
            """{"characters":[{"name":"无人机小七","desc":"会说话、拥有独立人格的飞行员角色"}],"scenes":[],"props":[]}"""
        )

        assertEquals("character", assets.single().kind)
    }
}
