package com.dramafactory.app

import com.dramafactory.app.ui.ProviderRegistry
import com.dramafactory.app.ui.ProjectsLogic
import com.dramafactory.app.ui.SettingsLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 第四轮真机反馈修复回归测试：
 * 多供应商选择、自定义模型保存、剧本导入（文件/粘贴+场次解析）。
 */
class Round4FeedbackTest {

    // ---------- 供应商注册表 ----------

    @Test
    fun 供应商列表_含全部7项且Agnes可用() {
        assertEquals(7, ProviderRegistry.ALL.size)
        val ids = ProviderRegistry.ALL.map { it.id }
        assertTrue(ids.containsAll(listOf("agnes", "kling", "jimeng", "runway", "luma", "pika", "custom")))
        assertEquals(ProviderRegistry.Status.AVAILABLE, ProviderRegistry.byId("agnes")!!.status)
        for (id in listOf("kling", "jimeng", "runway", "luma", "pika")) {
            assertEquals(ProviderRegistry.Status.COMING_SOON, ProviderRegistry.byId(id)!!.status, id)
        }
    }

    // ---------- 设置页：供应商选择 + 自定义模型 ----------

    @Test
    fun 设置页_选择待接入供应商仅标记不可渲染() = runTest {
        val logic = SettingsLogic(FakeVideoProvider(ok = true), FakeVault(), "cfg1")
        assertFalse(logic.selectProvider("kling"))                       // 返回false=暂不可渲染
        assertEquals("kling", logic.state.value.selectedProviderId)
        assertTrue(logic.state.value.providerLabel.contains("待接入"))
        assertTrue(logic.selectProvider("agnes"))
        assertTrue(logic.state.value.providerLabel.contains("可用"))
    }

    @Test
    fun 自定义模型_合法配置保存后明文清出并落库() = runTest {
        val vault = FakeVault()
        val logic = SettingsLogic(FakeVideoProvider(ok = true), vault, "cfg1")
        var persisted: ProviderRegistry.CustomModelConfig? = null
        logic.persistCustomConfig = { persisted = it }

        logic.onCustomFieldChanged("baseUrl", "https://api.example.com/v1")
        logic.onCustomFieldChanged("modelId", "my-video-1")
        logic.onCustomFieldChanged("apiKey", "sk-custom-1234567890")
        logic.onCustomFieldChanged("note", "POST /videos 提交")

        assertTrue(logic.saveCustomModel())
        assertEquals("sk-custom-1234567890", vault.saved["custom-video"])   // Key入加密存储
        assertEquals("my-video-1", persisted?.modelId)                      // 配置落供应商表
        assertEquals("", logic.state.value.customApiKey)                    // 明文立即清出
        assertTrue(logic.state.value.customSaved)
    }

    @Test
    fun 自定义模型_非法配置拒绝保存() = runTest {
        val logic = SettingsLogic(FakeVideoProvider(ok = true), FakeVault(), "cfg1")
        logic.onCustomFieldChanged("baseUrl", "ftp://not-http")
        logic.onCustomFieldChanged("modelId", "m1")
        logic.onCustomFieldChanged("apiKey", "sk-x")
        assertFalse(logic.saveCustomModel())
        assertFalse(logic.state.value.customSaved)

        assertNull(ProviderRegistry.CustomModelConfig.create(null, "m", "k", null))
        assertNull(ProviderRegistry.CustomModelConfig.create("https://a.com", "", "k", null))
    }

    // ---------- 剧本导入 ----------

    private fun newProjectsLogic() = ProjectsLogic()

    @Test
    fun 剧本导入_文件方式_场次解析与模式标记() = runTest {
        val logic = newProjectsLogic()
        val script = "第一场 内景 客房 夜\n阿伟：你好\n第二场 外景 天台 日\n小雨：再见\n第三场 内景 车库 夜"
        assertTrue(logic.importDocument(ProjectsLogic.ImportMode.SCRIPT, "script.txt", script, pasted = false))
        val st = logic.state.value
        assertEquals(ProjectsLogic.ImportMode.SCRIPT, st.importMode)
        assertFalse(st.importedPasted)
        assertEquals(3, st.sceneHint)
        assertTrue(script in (st.importedNovel ?: ""))
    }

    @Test
    fun 剧本导入_粘贴方式_免扩展名校验() = runTest {
        val logic = newProjectsLogic()
        assertTrue(logic.importDocument(
            ProjectsLogic.ImportMode.SCRIPT, "pasted_script.txt", "场景 1：办公室\nINT. LAB - NIGHT", pasted = true))
        assertTrue(logic.state.value.importedPasted)
        assertTrue(logic.state.value.sceneHint >= 2)
        // 空白粘贴内容仍拒绝
        assertFalse(logic.importDocument(ProjectsLogic.ImportMode.SCRIPT, "p.txt", "   ", pasted = true))
        assertTrue(logic.state.value.importError!!.contains("为空"))
    }

    @Test
    fun 小说导入_原路径不受影响且无场次提示() = runTest {
        val logic = newProjectsLogic()
        assertTrue(logic.importNovel("novel.txt", "很久很久以前……"))
        assertEquals(ProjectsLogic.ImportMode.NOVEL, logic.state.value.importMode)
        assertEquals(0, logic.state.value.sceneHint)
    }

    @Test
    fun 场次解析_中英文格式与启发式判断() {
        val text = """
            第一场 内景 客厅 夜
            对话……
            EXT. STREET - DAY
            场景 3：仓库
            SCENE 4: ROOFTOP
        """.trimIndent()
        assertEquals(4, ProjectsLogic.SceneParser.countScenes(text))   // 第X场/EXT./场景N/SCENE N
        assertEquals(ProjectsLogic.ImportMode.SCRIPT, ProjectsLogic.SceneParser.guessMode(text))
        assertEquals(ProjectsLogic.ImportMode.NOVEL, ProjectsLogic.SceneParser.guessMode("他走在路上，想起多年前的那个夏天。"))
    }
}
