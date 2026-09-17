package com.dramafactory.core.orchestrate

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.TextProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** 流式、多轮 AI 助手；旧 AiAgent.say API 保持不变。 */
class StreamingAssistant(
    private val textProvider: TextProvider,
    private val modelId: String,
    private val actionHandler: suspend (ActionIntent) -> String? = { null },
    /**
     * P0 动作可靠性：结构化信封处理器。提供时按 [ActionEnvelope] 路由（解码 + 幂等去重 +
     * fail-closed），执行层以 [ActionResult] 为准；缺省回退 [actionHandler] 兼容旧路径。
     */
    private val envelopeHandler: suspend (ActionEnvelope) -> ActionResult =
        { env -> ActionResult(env.actionId, ActionStatus.SUCCEEDED, "") },
    /** 动作上下文（当前项目/集 + 幂等键去重集合），由 App 层在构建时注入。 */
    private val actionContext: ActionContext = ActionContext(),
    private val idempotencyStore: ActionIdempotencyStore? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val maxTurns: Int = 10,
) {
    private val messages = mutableListOf(ChatMessage("system", SYSTEM_PROMPT))
    private val turns = mutableListOf<DialogueTurn>()
    val history: List<DialogueTurn> get() = turns.toList()

    fun sayStreaming(userText: String): Flow<StreamChunk> = flow {
        val text = userText.trim()
        if (text.isBlank()) { emit(StreamChunk.Done("")); return@flow }
        turns += DialogueTurn(DialogueTurn.Side.USER, text, nowMs())
        messages += ChatMessage("user", text)
        trimMessages()
        val raw = StringBuilder()
        try {
            textProvider.streamChat(ChatRequest(messages = messages.toList(), model = modelId)).collect { part ->
                if (part.isNotEmpty()) { raw.append(part); emit(StreamChunk.TextDelta(part)) }
            }
        } catch (t: Throwable) {
            val msg = when (t) {
                is ProviderError.AuthError -> "API Key 无效或已过期，请去设置页检查文本模型 Key。"
                is ProviderError.QuotaError -> "API 调用配额已用完，请稍后再试。"
                else -> "AI 暂时没有响应，请稍后再试。"
            }
            raw.append("⚠️ ").append(msg)
            emit(StreamChunk.TextDelta("⚠️ $msg"))
        }
        val full = raw.toString().ifBlank { "（AI 没有回复，请重试）" }
        val display = full.lines().filterNot { it.trim().startsWith(ActionIntent.MARK) }.joinToString("\n").trim()
        // P0：把整段流式文本（已跨 chunk 缓冲）逐条解析为 [ACT] 指令，再解码为 [ActionEnvelope]，
        // 结构化执行；缺省回退旧 [actionHandler] 兼容路径。
        val ctx = ActionContext(actionContext.projectId, actionContext.episodeId, actionContext.knownIdempotencyKeys)
        val notes = mutableListOf<String>()
        var succeeded = 0
        var blocked = 0
        for (action in parseActions(full)) {
            val result: ActionResult
            when (val decoded = decodeEnvelope(action, ctx)) {
                is DecodedAction.Ok -> {
                    val key = decoded.envelope.idempotencyKey
                    if (key != null && idempotencyStore != null && !idempotencyStore.reserve(key)) {
                        result = ActionResult(decoded.envelope.actionId, ActionStatus.BLOCKED,
                            "幂等键已处理过：$key，禁止重复执行", errorCode = "DUPLICATE_IDEMPOTENCY")
                        blocked++
                    } else {
                        result = try { envelopeHandler(decoded.envelope) } catch (t: Throwable) {
                            if (key != null) idempotencyStore?.release(key)
                            throw t
                        }
                        if (key != null && idempotencyStore != null) {
                            if (result.status == ActionStatus.SUCCEEDED) idempotencyStore.complete(key)
                            else idempotencyStore.release(key)
                        }
                        actionContext.markSucceeded(decoded.envelope, result)
                    }
                }
                is DecodedAction.Rejected -> {
                    result = decoded.result
                    blocked++
                }
            }
            notes += result.message.ifBlank { action.verb }
            emit(StreamChunk.ActionComplete(action.verb, result.message))
        }
        val incomplete = if (blocked > 0) "；$blocked 项未完成" else ""
        val finalText = if (notes.isEmpty()) display else display + "\n（已为你执行：${notes.joinToString("；")}$incomplete）"
        turns += DialogueTurn(DialogueTurn.Side.AI, finalText, nowMs())
        messages += ChatMessage("assistant", full)
        emit(StreamChunk.Done(finalText))
    }

    private fun trimMessages() {
        while (messages.size > 1 + maxTurns * 2) {
            val i = messages.indexOfFirst { it.role != "system" }
            if (i < 0) return
            messages.removeAt(i)
            if (i < messages.size && messages[i].role != "system") messages.removeAt(i)
        }
    }

    companion object {
        private const val SYSTEM_PROMPT = """你是「AI短剧工厂」手机App里的操控助手，也是用户的编剧导演搭档。请用自然、简洁、口语化的中文回应，不要机械复述或写客服式清单。你不只是聊天：用户明确要求操作时，必须真正调用App模块。

【机器指令协议】需要操作App时，在回复正文末尾另起一行输出一条或多条：
[ACT] <动作> | 参数=值 | 参数=值
机器指令不要放进正文，不要编造执行结果。

【可用动作】
- new_project：建项目，例 [ACT] new_project | name=雪夜镖局
- set_script：把剧本写入当前项目，例 [ACT] set_script | text=剧本文本
- open_project：打开项目，例 [ACT] open_project | id=p_xxx
- test_drama：创建并保存测试项目和剧本，例 [ACT] test_drama | name=测试短剧 | script=剧本文本
- list_assets / extract_assets / generate / edit_asset / remove_asset / review_pass / review_all_pass
- gen_shots / render / render_status / render_pause / render_resume / compose_film / run_pipeline
- goto：切换页面，例 [ACT] goto | page=projects

【硬规则】
1. 用户说“建项目/创建项目/新建项目”时，必须输出 new_project，不得只口头答应。
2. 用户同时给出剧本时，先输出 new_project，再输出 set_script；不要假称已保存。
3. 用户说“测试短剧/试做”时，必须输出 test_drama，它会创建并保存项目与第1集剧本后再跑流程。
4. 操作资产前先 list_assets，禁止编造 assetId。
5. 缺少必要信息时只追问缺的那一项；动作执行结果由App回显，不能自行杜撰。"""
    }
}
