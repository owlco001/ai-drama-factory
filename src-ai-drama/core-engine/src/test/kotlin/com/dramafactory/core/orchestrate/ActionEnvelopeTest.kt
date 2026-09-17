package com.dramafactory.core.orchestrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 动作信封（P0 动作可靠性）回归测试。
 * 覆盖：跨 chunk 解析、未知动作、非法参数、协议版本不支持、上下文不匹配、幂等重放。
 */
class ActionEnvelopeTest {

    // ---------- 跨 chunk 解析 ----------

    @Test
    fun `跨 chunk 的 ACT 标记能完整解析并解码为信封`() {
        // 模拟流式文本把一条 [ACT] 拆进多个 chunk，最终整段拼起来仍可解析
        val streamChunks = listOf("[ACT] gener", "ate | asset", "Id=char_001")
        val full = streamChunks.joinToString("")
        val act = parseActions(full)
        assertEquals(1, act.size)
        val decoded = decodeEnvelope(act.single(), ActionContext(projectId = "p_1"))
        val ok = assertIs<DecodedAction.Ok>(decoded)
        assertEquals("generate", ok.envelope.verb)
        assertEquals("char_001", ok.envelope.args["assetId"])
        assertEquals("p_1", ok.envelope.projectId)
        assertEquals(ACTION_PROTOCOL_VERSION, ok.envelope.protocolVersion)
    }

    // ---------- 未知动作 ----------

    @Test
    fun `未知动作 fail-closed 返回 BLOCKED`() {
        val act = ActionIntent("fly_to_moon", mapOf())
        val decoded = decodeEnvelope(act, ActionContext(projectId = "p_1"))
        val rejected = assertIs<DecodedAction.Rejected>(decoded)
        assertEquals(ActionStatus.FAILED, rejected.result.status)
        assertEquals("UNKNOWN_ACTION", rejected.result.errorCode)
    }

    @Test
    fun `协议版本不支持返回 BLOCKED`() {
        val act = ActionIntent("generate", mapOf("protocolVersion" to "99", "assetId" to "a1"))
        val decoded = decodeEnvelope(act, ActionContext(projectId = "p_1"))
        val rejected = assertIs<DecodedAction.Rejected>(decoded)
        assertEquals("UNSUPPORTED_PROTOCOL", rejected.result.errorCode)
    }

    @Test
    fun `非法协议版本不会回落到 v1`() {
        val rejected = assertIs<DecodedAction.Rejected>(decodeEnvelope(
            ActionIntent("generate", mapOf("protocolVersion" to "abc", "assetId" to "a1")),
            ActionContext(projectId = "p_1")))
        assertEquals("INVALID_PROTOCOL", rejected.result.errorCode)
    }

    @Test
    fun `失败动作的幂等键不登记并允许重试`() {
        val ctx = ActionContext(projectId = "p_1")
        val intent = ActionIntent("new_project", mapOf("name" to "A", "idempotencyKey" to "retry-1"))
        val first = assertIs<DecodedAction.Ok>(decodeEnvelope(intent, ctx))
        ctx.markSucceeded(first.envelope, ActionResult(first.envelope.actionId, ActionStatus.FAILED, "failed"))
        assertIs<DecodedAction.Ok>(decodeEnvelope(intent, ctx))
    }

    // ---------- 非法参数 ----------

    @Test
    fun `缺必要参数返回 FAILED`() {
        val act = ActionIntent("generate", mapOf()) // 缺 assetId
        val decoded = decodeEnvelope(act, ActionContext(projectId = "p_1"))
        val rejected = assertIs<DecodedAction.Rejected>(decoded)
        assertEquals("INVALID_ARGUMENTS", rejected.result.errorCode)
        assertTrue(rejected.result.message.contains("assetId"))
    }

    @Test
    fun `set_script 兼容 text 或 script 别名`() {
        val viaText = decodeEnvelope(ActionIntent("set_script", mapOf("text" to "剧本")), ActionContext(projectId = "p_1"))
        val viaScript = decodeEnvelope(ActionIntent("set_script", mapOf("script" to "剧本")), ActionContext(projectId = "p_1"))
        assertIs<DecodedAction.Ok>(viaText)
        assertIs<DecodedAction.Ok>(viaScript)
    }

    // ---------- 上下文不匹配 ----------

    @Test
    fun `需要项目上下文但缺失时 fail-closed`() {
        val act = ActionIntent("extract_assets", mapOf())
        val decoded = decodeEnvelope(act, ActionContext()) // 无 projectId
        val rejected = assertIs<DecodedAction.Rejected>(decoded)
        assertEquals("INVALID_ARGUMENTS", rejected.result.errorCode)
        assertTrue(rejected.result.message.contains("projectId"))
    }

    @Test
    fun `需要剧集上下文的动作缺失 episodeId 时 fail-closed`() {
        val act = ActionIntent("render_status", mapOf())
        val decoded = decodeEnvelope(act, ActionContext(projectId = "p_1")) // 无 episodeId
        val rejected = assertIs<DecodedAction.Rejected>(decoded)
        assertEquals("INVALID_ARGUMENTS", rejected.result.errorCode)
        assertTrue(rejected.result.message.contains("episodeId"))
    }

    // ---------- 幂等重放 ----------

    @Test
    fun `相同幂等键重放被 BLOCKED 不二次执行`() {
        val ctx = ActionContext(projectId = "p_1")
        val intent = ActionIntent("new_project", mapOf("name" to "雪夜镖局", "idempotencyKey" to "idem-1"))
        val first = assertIs<DecodedAction.Ok>(decodeEnvelope(intent, ctx))
        ctx.markSucceeded(first.envelope, ActionResult(first.envelope.actionId, ActionStatus.SUCCEEDED, "ok"))
        // 同一 context（含执行成功后登记的幂等键）再次解码同一指令 → 拒绝
        val replay = decodeEnvelope(intent, ctx)
        val rejected = assertIs<DecodedAction.Rejected>(replay)
        assertEquals("DUPLICATE_IDEMPOTENCY", rejected.result.errorCode)
    }

    @Test
    fun `不同幂等键可各自执行`() {
        val ctx = ActionContext(projectId = "p_1")
        val a = decodeEnvelope(ActionIntent("new_project", mapOf("name" to "A", "idempotencyKey" to "k-1")), ctx)
        val b = decodeEnvelope(ActionIntent("new_project", mapOf("name" to "B", "idempotencyKey" to "k-2")), ctx)
        assertIs<DecodedAction.Ok>(a)
        assertIs<DecodedAction.Ok>(b)
    }

    @Test
    fun `缺少 actionId 时按 verb 与幂等键确定性生成`() {
        val ctx = ActionContext(projectId = "p_1")
        val ok = assertIs<DecodedAction.Ok>(
            decodeEnvelope(ActionIntent("new_project", mapOf("name" to "A", "idempotencyKey" to "k-1")), ctx))
        assertTrue(ok.envelope.actionId.startsWith("act_new_project_"))
        // 同幂等键同动词 → 相同 actionId（可复现、便于对账）
        val ok2 = assertIs<DecodedAction.Ok>(
            decodeEnvelope(ActionIntent("new_project", mapOf("name" to "B", "idempotencyKey" to "k-1")),
                ActionContext(projectId = "p_1")))
        assertEquals(ok.envelope.actionId, ok2.envelope.actionId)
    }

    // ---------- 旧 [ACT] 兼容：不携带 actionId/idempotencyKey/protocolVersion ----------

    @Test
    fun `旧式 ACT 无信封元数据也能解码（默认版本号 1）`() {
        val act = parseActions("[ACT] edit_asset | assetId=char_003 | prompt=穿红衣的少女").single()
        val decoded = decodeEnvelope(act, ActionContext(projectId = "p_1"))
        val ok = assertIs<DecodedAction.Ok>(decoded)
        assertEquals(1, ok.envelope.protocolVersion)
        assertEquals("char_003", ok.envelope.args["assetId"])
        assertEquals("穿红衣的少女", ok.envelope.args["prompt"])
        assertEquals("p_1", ok.envelope.projectId)
    }

    @Test
    fun `信封元数据参数不进入 args`() {
        val act = parseActions(
            "[ACT] generate | assetId=a1 | actionId=act-x | idempotencyKey=idem-9 | protocolVersion=1").single()
        val ok = assertIs<DecodedAction.Ok>(
            decodeEnvelope(act, ActionContext(projectId = "p_1")))
        assertEquals("act-x", ok.envelope.actionId)
        assertEquals("idem-9", ok.envelope.idempotencyKey)
        assertEquals(setOf("assetId"), ok.envelope.args.keys)
    }

    // ---------- 结构化结果 ----------

    @Test
    fun `ActionResult 携带状态实体引用与错误码`() {
        val r = ActionResult("act-1", ActionStatus.FAILED, "写入失败", listOf("asset:a1"), "WRITE_FAILED")
        assertEquals("act-1", r.actionId)
        assertEquals(ActionStatus.FAILED, r.status)
        assertEquals("WRITE_FAILED", r.errorCode)
        assertEquals(listOf("asset:a1"), r.entityRefs)
    }
}