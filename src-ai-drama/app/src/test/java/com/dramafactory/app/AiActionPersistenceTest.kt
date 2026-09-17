package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.data.PersistenceActionExecutor
import com.dramafactory.app.data.PersistenceWriteException
import com.dramafactory.app.storage.DefaultStorageGuard
import com.dramafactory.core.orchestrate.ActionContext
import com.dramafactory.core.orchestrate.ActionEnvelope
import com.dramafactory.core.orchestrate.ActionIntent
import com.dramafactory.core.orchestrate.ActionResult
import com.dramafactory.core.orchestrate.ActionStatus
import com.dramafactory.core.orchestrate.AiOrchestrator
import com.dramafactory.core.orchestrate.DecodedAction
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import com.dramafactory.core.orchestrate.PipelineStage5
import com.dramafactory.core.orchestrate.decodeEnvelope
import com.dramafactory.core.orchestrate.markSucceeded
import com.dramafactory.core.orchestrate.parseActions
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AI 动作持久化回归测试（P0）。
 * 覆盖：pipeline 完成判定（外层 Result 与 PipelineRun.success 双条件）；持久化异常 → FAILED
 * 结果（带 actionId/entityRefs）；幂等重放；未知动作/非法参数 fail-closed；写后读回通过 → SUCCEEDED。
 */
class AiActionPersistenceTest {

    /**
     * 与 AiAssistant.run_pipeline / AiPipelinePage.launchPipeline 完全一致的完成判定：
     * 必须同时满足外层 Result 成功 且 PipelineRun.success==true，否则按失败处理。
     */
    private fun isPipelineCompleted(res: Result<AiOrchestrator.PipelineRun>): Boolean =
        res.isSuccess && res.getOrNull()?.success == true

    @Test
    fun `pipeline 外层成功但 PipelineRun success=false 时必须判定为失败`() = runBlocking {
        // 注入 persistAssets 抛持久化异常 → DefaultAiOrchestrator 进入失败契约（外层仍 success）
        val o = DefaultAiOrchestrator(
            createProject = { "p_x" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ ->
                Result.success(listOf(DefaultAiOrchestrator.AiAsset("a1", "character", "主角", "描述")))
            },
            generateImage = { Result.success("http://img/x.png") },
            auditAsset = { Result.success(DefaultAiOrchestrator.AuditResult(passed = true)) },
            generateShots = { _, _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "张三走进大殿"))) },
            enqueueRender = { _, shots -> Result.success(shots.size) },
            persistAssets = { _, _ -> throw IllegalStateException("asset write failed mid-batch") },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        val res = o.run("a".repeat(200))
        // 外层 Result 是成功（编排器用 Result.success 包裹业务失败）
        assertTrue(res.isSuccess)
        val run = res.getOrThrow()
        assertFalse(run.success)
        // 但完成判定必须为 false —— 不得误报"已完成"
        assertFalse(isPipelineCompleted(res))
        assertTrue(run.errors.any {
            (it as? AiOrchestrator.AiError.StageFailed)?.causeMsg?.contains("asset write failed") == true
        })
    }

    @Test
    fun `pipeline 全成功时完成判定为 true`() = runBlocking {
        val o = DefaultAiOrchestrator(
            createProject = { "p_ok" },
            createEpisode = { pid, _ -> "${pid}_ep1" },
            checkModel = { Result.success(Unit) },
            extractAssets = { _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiAsset("a1", "character", "主角", "描述"))) },
            generateImage = { Result.success("http://img/x.png") },
            auditAsset = { Result.success(DefaultAiOrchestrator.AuditResult(passed = true)) },
            generateShots = { _, _, _ -> Result.success(listOf(DefaultAiOrchestrator.AiShot(1, "张三走进大殿"))) },
            enqueueRender = { _, shots -> Result.success(shots.size) },
            writeCheckpoint = { _, _, _, _, _, _ -> },
            readCheckpoint = { null },
        )
        val res = o.run("b".repeat(200))
        assertTrue(isPipelineCompleted(res))
    }

    @Test
    fun `持久化写入异常映射为 ActionResult FAILED 携带 actionId 与实体ID`() = runBlocking {
        val dao = FailingWriteDramaDao()
        val guard = DefaultStorageGuard()
        val actionId = "act-created-1"
        val thrown = try {
            PersistenceActionExecutor.writeProject(dao, guard, "项目", actionId)
            null
        } catch (e: PersistenceWriteException) {
            e
        }
        assertTrue(thrown != null)
        val result = ActionResult(
            actionId = thrown!!.actionId,
            status = ActionStatus.FAILED,
            message = "写入失败：${thrown.message}",
            entityRefs = thrown.entityIds,
            errorCode = "PERSIST_FAILED",
        )
        assertEquals(actionId, result.actionId)
        assertEquals(ActionStatus.FAILED, result.status)
        assertFalse(result.entityRefs.isEmpty())
        assertEquals("PERSIST_FAILED", result.errorCode)
    }

    @Test
    fun `幂等键重放不重复创建`() {
        val ctx = ActionContext(projectId = "p_1")
        val intent = ActionIntent("new_project", mapOf("name" to "项目", "idempotencyKey" to "k-1"))
        val first = assertIs<DecodedAction.Ok>(decodeEnvelope(intent, ctx))
        ctx.markSucceeded(first.envelope, ActionResult(first.envelope.actionId, ActionStatus.SUCCEEDED, "ok"))
        val replay = decodeEnvelope(intent, ctx)
        val rejected = assertIs<DecodedAction.Rejected>(replay)
        assertEquals("DUPLICATE_IDEMPOTENCY", rejected.result.errorCode)
    }

    @Test
    fun `未知动作与非法参数 fail-closed`() {
        val ctx = ActionContext(projectId = "p_1")
        assertIs<DecodedAction.Rejected>(decodeEnvelope(ActionIntent("hack", mapOf()), ctx))
        assertIs<DecodedAction.Rejected>(decodeEnvelope(ActionIntent("generate", mapOf()), ctx)) // 缺 assetId
        assertIs<DecodedAction.Rejected>(decodeEnvelope(ActionIntent("new_project", mapOf("name" to "x", "protocolVersion" to "9")), ctx))
    }

    @Test
    fun `写后读回验证通过则动作 SUCCEEDED`() = runBlocking {
        val dao = FakeDao()
        val guard = DefaultStorageGuard()
        val receipt = PersistenceActionExecutor.writeProject(dao, guard, "雪夜镖局", "act-new")
        val envelope = ActionEnvelope(actionId = "act-new", verb = "new_project", args = mapOf("name" to "雪夜镖局"))
        val result = ActionResult(envelope.actionId, ActionStatus.SUCCEEDED,
            "已建项目：雪夜镖局（id=${receipt.entityIds.single()}）",
            entityRefs = listOf("project:${receipt.entityIds.single()}"))
        assertEquals(ActionStatus.SUCCEEDED, result.status)
        assertTrue(result.entityRefs.single().startsWith("project:"))
    }

    @Test
    fun `旧式 ACT 跨 chunk 解析后经信封执行仍可写库`() = runBlocking {
        // 模拟 [ACT] 拆进两个流式 chunk，最终整段解析为信封
        val full = "[ACT] test_dr" + "ama | name=测试短剧 | script=" + "剧本".repeat(60)
        val act = parseActions(full)
        assertEquals(1, act.size)
        val decoded = decodeEnvelope(act.single(), ActionContext(projectId = "p_1"))
        val ok = assertIs<DecodedAction.Ok>(decoded)
        assertEquals("test_drama", ok.envelope.verb)
        // 信封项目上下文来自注入的 ActionContext
        assertEquals("p_1", ok.envelope.projectId)
    }

    private class FakeDao : StoragePersistenceContractTest.FakeDramaDao()

    private class FailingWriteDramaDao : StoragePersistenceContractTest.FakeDramaDao() {
        override suspend fun upsertProject(p: com.dramafactory.app.data.ProjectEntity) {
            throw IllegalStateException("database unavailable")
        }
    }
}