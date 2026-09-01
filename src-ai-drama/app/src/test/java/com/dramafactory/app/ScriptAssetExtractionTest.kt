package com.dramafactory.app

import com.dramafactory.app.ui.AssetsLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 *
 * 第七轮：短剧剧本格式兼容——Markdown加粗场次标题（**场1 漠北草原·日·外**）、
 * 加粗清单行（**角色：王莽**）、对白标签（蒲奴（OS）：）提取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptAssetExtractionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // v1.9.0：AssetsViewModel 构造即取 AppGraph.text（=agnes）组装 describer，
        // 纯 JVM 测试没有 Application.init 流程 → lateinit 崩溃（历史 flaky 根因）。
        // 这里补一个离线实例，不发任何网络请求。
        try {
            com.dramafactory.app.AppGraph.agnes
        } catch (t: Throwable) {
            com.dramafactory.app.AppGraph.agnes =
                com.dramafactory.core.provider.AgnesProvider(apiKeyProvider = { "sk-offline-test" })
        }
    }
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
        // 第七轮：场景卡prompt取去除场号后的环境描述
        assertTrue(out.any { it.name == "内景·咖啡馆·夜" })
        assertTrue(out.any { it.name == "INT. WAREHOUSE - DAY" })
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

    // ---------------------------------------------------------------
    // 第七轮：短剧剧本格式兼容（《莽途》EP01-03 对白级剧本）
    // ---------------------------------------------------------------

    @Test
    fun markdownBoldSceneHeadingsExtractSceneCards() {
        val script = """
            **场1 漠北草原·日·外**
            **第2场 长安西市·日·外**
            **第1场**
            **场景1**
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(4, out.size)
        assertTrue(out.all { it.kind == AssetsLogic.Kind.SCENE })
        // prompt 取去除场号后的环境描述；无描述时用场次标题本身
        assertTrue(out.any { it.name == "漠北草原·日·外" })
        assertTrue(out.any { it.name == "长安西市·日·外" })
        assertTrue(out.any { it.name == "第1场" })
        assertTrue(out.any { it.name == "场景1" })
    }

    @Test
    fun markdownBoldListLinesExtractCards() {
        val script = """
            **角色：王莽、刘歆**
            **场景：草原**
            **道具：弯刀**
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(4, out.size)
        assertEquals(2, out.count { it.kind == AssetsLogic.Kind.CHARACTER })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.CHARACTER && it.name == "王莽" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.CHARACTER && it.name == "刘歆" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.SCENE && it.name == "草原" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.PROP && it.name == "弯刀" })
    }

    @Test
    fun plainFormatsStillExtract() {
        val script = """
            角色：林晚
            第3场 雨夜
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(2, out.size)
        assertTrue(out.any { it.kind == AssetsLogic.Kind.CHARACTER && it.name == "林晚" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.SCENE && it.name == "雨夜" })
    }

    @Test
    fun dialogueLabelsExtractCharacters() {
        val script = """
            蒲奴（OS）：单于已死，草原当有新的主人。
            张二（嫌弃地擦灰，忽然眼睛亮了）：这怀表是前朝旧物！
            追兵声（画外嘶喊）：报——
            林晚推门而入。
            王莽道：住手！
        """.trimIndent()
        val out = AssetsLogic.ScriptAssetExtractor.extract(script)
        assertEquals(3, out.size)
        assertTrue(out.all { it.kind == AssetsLogic.Kind.CHARACTER })
        assertTrue(out.any { it.name == "蒲奴" })
        assertTrue(out.any { it.name == "张二" })
        assertTrue(out.any { it.name == "追兵声" })   // 误判可接受
    }

    /** 《莽途》EP01-03 对白级剧本片段（短剧Markdown格式） */
    private val EP0103_SNIPPET = """
        **场1 漠北草原·日·外**
        王莽（OS）：单于已死，草原当有新的主人。
        蒲奴（画外嘶喊）：报——匈奴大军压境！
        **场2 王帐·夜·内**
        角色：王莽、刘歆、张二
        **场3 长安西市·日·外**
        张二（嫌弃地擦灰，忽然眼睛亮了）：这怀表……是前朝旧物！
        道具：古铜怀表、黑色手提箱
        **场4 太庙·日·内**
        刘歆（低声）：新朝初立，民心未附。
    """.trimIndent()

    @Test
    fun ep0103MangtuScriptExtractsAtLeastTwoSceneCards() {
        val out = AssetsLogic.ScriptAssetExtractor.extract(EP0103_SNIPPET)
        val scenes = out.filter { it.kind == AssetsLogic.Kind.SCENE }
        assertTrue("EP01-03至少提取2张场景卡，实际${scenes.size}", scenes.size >= 2)
        assertTrue(scenes.any { it.name == "漠北草原·日·外" })
        assertTrue(scenes.any { it.name == "长安西市·日·外" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.CHARACTER && it.name == "王莽" })
        assertTrue(out.any { it.kind == AssetsLogic.Kind.PROP && it.name == "古铜怀表" })
    }

    @Test
    fun extractFromScriptExtractsBoldSceneScript() = runTest {
        val logic = AssetsLogic()
        var n = 0
        val added = logic.extractFromScript(EP0103_SNIPPET, idGen = { "id_${n++}" })
        assertTrue("应提取出多张资产卡，实际$added", added >= 6)
        assertTrue(logic.assets.value.any {
            it.kind == AssetsLogic.Kind.SCENE && it.prompt == "漠北草原·日·外"
        })
    }

    /** 提取结果为0时提示文案（第七轮：指明支持的剧本格式） */
    @Test
    fun extractZeroResultShowsFormatGuidanceMessage() = kotlinx.coroutines.runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())   // init协程立即执行，避免调度器不驱动
        val dao = Round6LocalUploadI2iTest.MemDao()
        dao.episode = com.dramafactory.app.data.EpisodeEntity(
            episode_id = "p9_ep1", project_id = "p9", ep_no = 1,
            script_json = "这是一段没有任何资产标记的散文。",
            stage_flags = """{"script_mode":true}""")
        com.dramafactory.app.AppGraph.dao = dao
        val vm = com.dramafactory.app.ui.AssetsViewModel("p9")
        // init 协程 withContext(Dispatchers.IO) 在真实线程执行，等待其完成
        kotlinx.coroutines.withTimeout(5000) {
            while (!vm.scriptMode.value) kotlinx.coroutines.delay(10)
        }
        vm.extractFromScript()
        kotlinx.coroutines.withTimeout(5000) {
            while (vm.extractMessage.value == null) kotlinx.coroutines.delay(10)
        }
        assertEquals(0, vm.assets.value.size)
        // 第十轮：LLM优先、正则兜底，两者皆空时提示统一
        assertEquals("未能从文本提取到资产（LLM与规则均未命中）", vm.extractMessage.value)
    }
}
