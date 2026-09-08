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
        val notes = mutableListOf<String>()
        for (action in parseActions(full)) {
            val result = runCatching { actionHandler(action) }.getOrNull() ?: action.verb
            notes += result
            emit(StreamChunk.ActionComplete(action.verb, result))
        }
        val finalText = if (notes.isEmpty()) display else display + "\n（已为你执行：${notes.joinToString("；")}）"
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
        private const val SYSTEM_PROMPT = """你是AI短剧工厂的智能助手，也是用户的编剧导演搭档。请用自然、简洁、口语化的中文回应，结合前文理解用户，不要机械复述，不要无意义列清单。你能协助提取资产、生成图片、生成分镜、渲染和合成成片；用户表达明确意图时主动推进，缺少必要信息时只追问关键问题。需要调用App模块时，在回复末尾单独输出一行：[ACT] 动作 | 参数=值。不要编造资产ID或执行结果。"""
    }
}
