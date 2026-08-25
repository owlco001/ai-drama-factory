package com.dramafactory.app

import com.dramafactory.app.ui.AssetsLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.4 bugfix回归：剧本模式导入后资产生成入口缺失。
 * - ScriptAssetExtractor纯函数提取（角色/场景/道具清单 + 场次标题）
 * - stage_flags.script_mode识别
 * - extractFromScript判重与一键建卡入口语义
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptAssetExtractionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun extractsListedCharactersScenesProps() {
        val script = """
            角色：林晚、陈默、老周
            场景：雨夜街头、废弃工厂
            道具：古铜怀表、黑色手提箱
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(7, out.size)   // 3角色 + 2场景 + 2道具
        assertEquals(3, out.count { it.kind == AssetsLogic.Kind.CHARACTER })
        assertEquals(2, out.count { it.kind == AssetsLogic.Kind.SCENE })
        assertEquals(2, out.count { it.kind == AssetsLogic.Kind.PROP })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.CHARACTER && it.name == "林晚" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.SCENE && it.name == "雨夜街头" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.PROP && it.name == "古铜怀表" })
    }

    @Test
    fun extractsSceneHeadingLines() {
        val script = """
            第1场 内景·咖啡馆·夜
            林晚推门而入。
            INT. WAREHOUSE - DAY
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(2, out.size)
        assertTrue(out.all { it.kind == AssetsLogic.Kind.SCENE })
    }

    @Test
    fun dedupesAndPreservesOrder() {
        val script = "角色：林晚、林晚\n第1场 内景·夜\n第1场 内景·夜"
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(2, out.size)
    }

    @Test
    fun emptyScriptYieldsEmptyList() {
        assertTrue(AssetsLogic.ScriptAssetExtractor.extract("这是一段没有任何标记的散文。").isEmpty())
    }

    @Test
    fun scriptModeFlagParsing() {
        assertTrue(AssetsLogic.ScriptAssetExtractor.isScriptMode("""{"script_mode":true,"scene_hint":3}"""))
        assertFalse(AssetsLogic.ScriptAssetExtractor.isScriptMode("{}"))
        assertFalse(AssetsLogic.ScriptAssetExtractor.isScriptMode(null))
    }

    @Test
    fun extractFromScriptAddsCardsAndSkipsDuplicates() = runTest {
        val logic = AssetsLogic()
        var n = 0
        val added1 = logic.extractFromScript("角色：林晚、陈默", idGen = { "id_${n++}" })
        assertEquals(2, added1)
        // 再次提取同一剧本：全部判重跳过，不重复建卡
        val added2 = logic.extractFromScript("角色：林晚、陈默", idGen = { "id_${n++}" })
        assertEquals(0, added2)
        assertEquals(2, logic.assets.value.size)
    }
}
