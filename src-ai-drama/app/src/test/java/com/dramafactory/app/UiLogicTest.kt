package com.dramafactory.app

import com.dramafactory.app.ui.AssetsLogic
import com.dramafactory.app.ui.ProjectsLogic
import com.dramafactory.app.ui.QueueLogic
import com.dramafactory.app.ui.SettingsLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * UI层ViewModel逻辑单测（纯JVM，不依赖Robolectric）。
 * 覆盖：设置页测试/保存语义、项目导入校验、资产评审闸门、队列预算确认与RECONCILE处置。
 */
class UiLogicTest {

    // ---------- SettingsLogic ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 设置页_测试连通成功后允许保存并清空明文() = runTest {
        val vault = FakeVault()
        val logic = SettingsLogic({ FakeVideoProvider(ok = true) }, { "cfg1" }, vault, "cfg1")
        logic.refresh()
        logic.onKeyChanged("sk-abc1234567890")

        // 未测试直接保存 → 拒绝（防坏Key覆盖好Key）
        assertFalse(logic.saveKey())
        assertEquals(0, vault.saved.size)

        // 测试连通成功 → 保存放行，输入框明文清出，掩码可见
        logic.testConnection()
        assertTrue((logic.state.value.testResult as? SettingsLogic.TestResult.Success)?.latencyMs == 42L)
        assertTrue(logic.saveKey())
        assertEquals("sk-abc1234567890", vault.saved["cfg1"])
        assertEquals("", logic.state.value.keyInput)
        assertEquals("sk-***890", logic.state.value.maskedSaved)   // 前3后3掩码，UI永不回显明文
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 设置页_连通失败显示错误且不允许保存() = runTest {
        val logic = SettingsLogic({ FakeVideoProvider(ok = false) }, { "cfg1" }, FakeVault(), "cfg1")
        logic.onKeyChanged("sk-bad")
        logic.testConnection()
        val r = logic.state.value.testResult
        assertTrue(r is SettingsLogic.TestResult.Failure && r.message.contains("401"))
        assertFalse(logic.saveKey())
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 设置页_空Key提示() = runTest {
        val logic = SettingsLogic({ FakeVideoProvider(ok = true) }, { "cfg1" }, FakeVault(), "cfg1")
        logic.testConnection()
        assertEquals("请先输入API Key",
            (logic.state.value.testResult as SettingsLogic.TestResult.Failure).message)
    }

    // ---------- ProjectsLogic ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 项目导入_仅接受txtmd且拒绝空文件() = runTest {
        val logic = ProjectsLogic()
        assertFalse(logic.importNovel("novel.pdf", "内容"))   // 扩展名不符
        assertTrue(logic.state.value.importError!!.contains("TXT"))
        assertFalse(logic.importNovel("novel.txt", "  "))     // 空文件
        assertTrue(logic.state.value.importError!!.contains("为空"))
        assertTrue(logic.importNovel("story.md", "# 第一章"))
        assertNull2(logic.state.value.importError)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 项目新建_空名拒绝_成功返回id并刷新列表() = runTest {
        val logic = ProjectsLogic()
        var persisted: Pair<String, String?>? = null
        logic.persistProject = { name, novel -> persisted = name to novel; "p_new" }
        logic.loadProjects = { listOf(ProjectsLogic.ProjectItem("p_new", persisted!!.first)) }

        logic.onNameChanged("")
        assertEquals(null, logic.createProject())
        logic.onNameChanged("霸总爱上机器人")
        val id = logic.createProject()
        assertEquals("p_new", id)
        assertEquals(1, logic.state.value.projects.size)
    }

    // ---------- AssetsLogic ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 资产评审_全部保留才放行渲染门_重生成触发再生成() = runTest {
        val logic = AssetsLogic()
        val generated = mutableListOf<String>()
        logic.generateHandler = { Result.success("url_${it.assetId}") }
        logic.reviewPersist = { id, st -> generated.add("$id=$st") }

        assertFalse(logic.reviewAllPassed())   // 空资产不放行
        logic.addAsset("a1", AssetsLogic.Kind.CHARACTER, "女主")
        logic.addAsset("a2", AssetsLogic.Kind.SCENE, "街头")
        assertFalse(logic.reviewAllPassed())

        logic.review("a1", keep = true)
        assertFalse(logic.reviewAllPassed())
        logic.review("a2", keep = true)
        assertTrue(logic.reviewAllPassed())    // 全keep → GateReport.reviewPassed=true

        // 重生成路径：勾选取消 → regen落库 + 自动再生成（URL更新）
        logic.review("a2", keep = false)
        assertFalse(logic.reviewAllPassed())
        assertTrue(generated.contains("a2=regen"))
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 资产生成_失败不拖垮状态机_可重试() = runTest {
        val logic = AssetsLogic()
        var fail = true
        logic.generateHandler = { if (fail) Result.failure(RuntimeException("网络错误")) else Result.success("u") }
        logic.addAsset("a1", AssetsLogic.Kind.PROP, "怀表")
        logic.generate("a1")
        assertFalse(logic.assets.value[0].generating)          // 失败后转圈复位
        assertEquals("网络错误", logic.assets.value[0].errorMessage)  // v1.9.8：失败信息可见（不再静默吞错）
        fail = false
        logic.generate("a1")
        assertEquals("u", logic.assets.value[0].remoteUrl)
        assertTrue(logic.assets.value[0].errorMessage == null)         // 成功清空错误提示
    }

    // ---------- QueueLogic ----------

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 队列_预算超限弹确认_确认走confirmedByUser放行位() = runTest {
        val queue = FakeQueue()
        val budget = FakeBudget()
        val logic = QueueLogic(queue, budget)
        logic.startWatching(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))

        // 模拟引擎因预算超限暂停
        queue.snapshot.value = com.dramafactory.core.model.QueueSnapshot(
            episodeId = "e1", totalShots = 4, completedShots = 2,
            running = true, pausedReason = "budget_exceeded")
        kotlinx.coroutines.delay(2200)   // 等watch轮询捕获

        assertTrue(logic.state.value.showBudgetConfirm)   // 弹窗可见
        logic.dismissBudgetConfirm()
        assertFalse(logic.state.value.showBudgetConfirm)

        // 用户点「继续渲染」→ resume(confirmedByUser=true)，对齐P1-5一次性放行语义
        logic.confirmBudget()
        assertEquals(Pair(1, true), queue.lastResume)

        // 正常恢复走confirmedByUser=false
        logic.resume()
        assertEquals(Pair(2, false), queue.lastResume)
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    @Test
    fun 队列_RECONCILE处置_重试置回PENDING_放弃置BLOCKED() = runTest {
        val queue = FakeQueue()
        val resolved = mutableListOf<Pair<String, Boolean>>()
        val logic = QueueLogic(queue, FakeBudget())
        logic.onReconcileResolve = { shotId, retry -> resolved.add(shotId to retry) }

        logic.openReconcileDialog("s3", "提交结果未知")
        assertEquals("s3", logic.state.value.reconcileShot?.first)
        logic.resolveReconcile(retry = true)
        assertEquals(listOf("s3" to true), resolved)
        assertEquals(null, logic.state.value.reconcileShot)
        // 重试后自动续跑（确认放行）
        assertEquals(Pair(1, true), queue.lastResume)
    }

    private fun assertNull2(x: Any?) { check(x == null) }
}
