package com.dramafactory.core.orchestrate

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.provider.TextProvider

/**
 * T014 任务2（智能体升级）：自由对话式 AI 编剧导演。
 *
 * 与 BriefDialogue（固定问卷）不同，AiAgent 是 LLM 驱动的开放式对话：
 *   - 系统 prompt 定义人设（短剧编剧导演智能体）
 *   - 多轮 messages 维持上下文
 *   - 用户自由输入，AI 自由回应、主动追问、澄清需求
 *   - 用户说"开始/开工/生成"或点 UI 按钮时，把累积的 [scriptDraft] + 对话摘要送编排器
 *
 * 模型无关：TextProvider 由调用方注入（App 走 TextModelRouter 让用户自选）。
 */
class AiAgent(
    private val textProvider: TextProvider,
    private val modelId: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val systemPrompt = buildString {
        append("你是「AI短剧工厂」的编剧导演智能体，风格像资深网文编剧+分镜导演。\n")
        append("用户会给你一段小说/剧本（或零碎想法），你要：\n")
        append("1) 主动澄清关键创作要素：时代背景、视觉风格、主要角色数、情绪基调、是否要配音；\n")
        append("2) 给出专业建议（如分镜节奏、角色一致性），但尊重用户最终决定；\n")
        append("3) 当用户说「开始/开工/生成/成片」或明确表示要动手时，确认要素齐了就回应「好的，这就开工」。\n")
        append("用中文、口语化、像真人搭档聊天，不要列点式官腔。每次回复控制在一两段内。")
    }

    private val _messages = mutableListOf<ChatMessage>(
        ChatMessage(role = "system", content = systemPrompt),
    )
    val messages: List<ChatMessage> get() = _messages.toList()

    /** 用户粘入/提到的剧本文本草稿（长度≥100 视为可开工） */
    var scriptDraft: String = ""
        private set

    private val _history = mutableListOf<DialogueTurn>()
    val history: List<DialogueTurn> get() = _history.toList()

    /** 发送一句话，返回 AI 回复文本（同步加入上下文） */
    suspend fun say(userText: String): String {
        val trimmed = userText.trim()
        if (trimmed.isBlank()) return ""
        // 累积剧本草稿：超过100字且当前草稿为空则记为剧本
        if (scriptDraft.length < 100 && trimmed.length >= 100) {
            scriptDraft = trimmed
        } else if (trimmed.length >= 100) {
            scriptDraft = trimmed // 后续粘入覆盖
        }
        _history.add(DialogueTurn(DialogueTurn.Side.USER, trimmed, nowMs()))
        _messages.add(ChatMessage(role = "user", content = trimmed))
        val resp = textProvider.chat(
            ChatRequest(messages = _messages.toList(), model = modelId, temperature = 0.8),
        )
        val aiText = resp.content.ifBlank { "（AI 没有回复，请重试）" }
        _history.add(DialogueTurn(DialogueTurn.Side.AI, aiText, nowMs()))
        _messages.add(ChatMessage(role = "assistant", content = aiText))
        return aiText
    }

    /** 是否已具备开工条件（有剧本 + 对话非空） */
    fun canGenerate(): Boolean = scriptDraft.length >= 100 && _history.isNotEmpty()

    /** 生成时用的脚本文本：优先 scriptDraft，否则用用户全部发言拼合 */
    fun resolveScript(): String {
        if (scriptDraft.length >= 100) return scriptDraft
        return _history.filter { it.side == DialogueTurn.Side.USER }
            .joinToString("\n\n") { it.content }
            .takeIf { it.length >= 100 } ?: scriptDraft
    }
}
