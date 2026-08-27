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
 *   - 用户说"开始/开工/生成"时进流水线（见 AiPipelineViewModel）
 *
 * 大脑控制 APP：AI 在回复里可附 [ACT] 标记调用本地能力（如放开时代红线、
 * 删资产、改描述），由 actionHandler 在端侧执行并回显，用户无感。
 *
 * 模型无关：TextProvider 由调用方注入（App 走 TextModelRouter 让用户自选）。
 */
class AiAgent(
    private val textProvider: TextProvider,
    private val modelId: String,
    /** 本地动作执行器（挂起）：收到 ActionIntent 返回一句话执行结果（用于回显）；返回 null 表示无法执行 */
    private val actionHandler: suspend (ActionIntent) -> String? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val systemPrompt = buildString {
        append("你是「AI短剧工厂」的编剧导演智能体，风格像资深网文编剧+分镜导演。\n")
        append("用户会给你一段小说/剧本（或零碎想法），你要：\n")
        append("1) 主动澄清关键创作要素：时代背景、视觉风格、主要角色数、情绪基调、是否要配音；\n")
        append("2) 给出专业建议（如分镜节奏、角色一致性），但尊重用户最终决定；\n")
        append("3) 当用户说「开始/开工/生成/成片」或明确表示要动手时，确认要素齐了就回应「好的，这就开工」。\n")
        append("用中文、口语化、像真人搭档聊天，不要列点式官腔。每次回复控制在一两段内。\n\n")
        append("【控制本软件】需要真正修改项目时（如放开时代红线、列出/删除/编辑资产、停止或重生成某张图、让资产过审、生成角色姿态包），")
        append("在回复正文之后另起一行附机器指令，格式：\n")
        append("  [ACT] <动作> | 参数=值 | 参数=值\n")
        append("已知动作：${KNOWN_ACTIONS.joinToString(", ")}。\n")
        append("操作资产的完整流程：先发 [ACT] list_assets 拿到资产名和 id，再发带 assetId 的指令。\n")
        append("例：\n")
        append("  列出资产 → [ACT] list_assets\n")
        append("  放开跨时代器物 → [ACT] set_cross_era | allowed=手机,眼镜\n")
        append("  删除某角色 → [ACT] remove_asset | assetId=a_主角\n")
        append("  改某资产描述 → [ACT] edit_asset | assetId=a_主角 | prompt=穿红衣的少女\n")
        append("  让某角色过审 → [ACT] review_pass | assetId=a_主角\n")
        append("  为角色生成6姿态包 → [ACT] build_pose_pack | characterId=a_主角\n")
        append("不要编造不存在的 assetId/characterId；拿不准就先 [ACT] list_assets 查，或问用户要资产名。")
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
        val raw = resp.content.ifBlank { "（AI 没有回复，请重试）" }
        lastAiText = raw

        // 抽取 [ACT] 指令，剥离展示文本，逐条执行并回显
        val actions = parseActions(raw)
        val displayText = stripActions(raw)
        val execNotes = mutableListOf<String>()
        for (act in actions) {
            runCatching { actionHandler(act) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { execNotes.add(it) }
        }
        val finalText = if (execNotes.isNotEmpty()) {
            buildString {
                append(displayText)
                if (displayText.isNotBlank()) append("\n")
                append("（已为你执行：${execNotes.joinToString("；")}）")
            }
        } else displayText

        _history.add(DialogueTurn(DialogueTurn.Side.AI, finalText, nowMs()))
        _messages.add(ChatMessage(role = "assistant", content = raw)) // 上下文保留原始（含[ACT]），便于多轮连贯
        return finalText
    }

    /** 从展示文本里剥掉 [ACT] 行 */
    private fun stripActions(text: String): String =
        text.lines().filter { !it.trim().startsWith(ActionIntent.MARK) }
            .joinToString("\n").trimEnd()

    /** 最近一次 AI 原始回复文本（用于意图识别） */
    var lastAiText: String = ""
        private set

    /** AI 是否表达要开工/生成（大脑控制 APP：识别意图自动进流水线） */
    fun lastAiWantsGenerate(): Boolean {
        val t = lastAiText
        if (t.isBlank()) return false
        return t.contains("开工") || t.contains("生成") || t.contains("开始做") ||
            t.contains("这就干") || t.contains("开始生成") || t.contains("我来弄") ||
            t.contains("直接做") || t.contains("开始吧") || t.contains("动手")
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
