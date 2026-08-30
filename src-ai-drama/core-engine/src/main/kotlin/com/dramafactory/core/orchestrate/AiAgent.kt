package com.dramafactory.core.orchestrate

import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatResponse
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
    /** 本地动作执行器（挂起）：收到 ActionIntent 与进度回调，返回一句话执行结果（用于回显）；返回 null 表示无法执行 */
    private val actionHandler: suspend (ActionIntent, (String) -> Unit) -> String? = { _, _ -> null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 当前项目 id 提示（进入不同项目自动切换上下文时由 VM 注入），让 AI 知道自己作用于哪个项目 */
    private val currentProjectHint: String? = null,
    /** 消息滑动窗口保留的最大对话轮数（一轮=user+assistant），默认 10，防止长对话超出 LLM context */
    private val maxTurns: Int = 10,
    /** LLM 采样温度，默认 0.7（创意写作适中），原硬编码 0.0 */
    private val temperature: Double = 0.7,
    /** 日志输出函数，默认 println（core-engine 不含 Android Log） */
    private val logger: (String) -> Unit = { println(it) },
) {
    private val systemPrompt = buildString {
        append("你是「AI短剧工厂」这个手机App的操控助手，风格像资深短剧编剧导演+产品搭档。\n")
        append("你不是只会聊天的机器人——你能真正操控这个App的全部功能，帮用户把小说/剧本变成短剧资产、分镜、视频和成片。\n\n")
        append("【对话方式】用户用大白话跟你说话（例如「给这个项目提取资产」「把主角改成红衣」「生成分镜」「跑完整流程出成片」「打开资产页看看」），\n")
        append("你用中文、口语化、像真人搭档一样回话，不要列点式官腔。需要动手时在回复正文之后另起一行附机器指令（用户看不到这行，是给App执行的）。\n\n")
        append("【你能操控的功能】\n")
        append("· 项目：new_project(建项目) / open_project(打开项目) / set_script(写入剧本文本)\n")
        append("· 资产：extract_assets(从剧本提取资产卡) / generate(重生成某资产图) / stop_generate(中止生成) / remove_asset(删除) / edit_asset(改描述) / review_pass(过审) / review_all_pass / build_pose_pack(角色姿态包) / set_cross_era(放开跨时代器物) / list_assets(列出当前资产)\n")
        append("· 分镜与成片：gen_shots(生成分镜) / render(入渲染队) / compose_film(合成成片) / run_pipeline(跑完整流程：提取→图→分镜→渲染)\n")
        append("· 渲染控制与状态：render_status(当前渲染进度) / render_pause(暂停队列) / render_resume(恢复队列)\n")
        append("· 模型配置：model_status(文本/视频/图像三通道 Key 状态)；缺配置就主动发 goto|page=settings 引导用户补 Key\n")
        append("· 导航：goto(切换到某标签，page=projects/assets/storyboard/queue/library/settings)\n\n")
        append("【机器指令格式】在回复正文之后另起一行：\n")
        append("  [ACT] <动作> | 参数=值 | 参数=值\n")
        append("例：\n")
        append("  建项目 → [ACT] new_project | name=雪夜镖局\n")
        append("  写剧本 → [ACT] set_script | text=大雪夜，镖师护送秘匣…（≥100字）\n")
        append("  提取资产 → [ACT] extract_assets\n")
        append("  改主角 → [ACT] edit_asset | assetId=a_主角 | prompt=穿红衣的少女\n")
        append("  生成分镜 → [ACT] gen_shots\n")
        append("  跑完整流程 → [ACT] run_pipeline\n")
        append("  打开资产页 → [ACT] goto | page=assets\n\n")
        append("【重要规矩】\n")
        append("1) 操作资产前先 [ACT] list_assets 拿到真实 assetId，不要编造 id；拿不准就问用户要资产名。\n")
        append("2) 用户还没建项目/没传剧本时，先引导建项目+传剧本，或自己 [ACT] new_project 并请用户给剧本。\n")
        append("3) set_script 的 text 必须≥100字才够生成；不够就先跟用户聊补齐。\n")
        append("4) 用户说「开工/生成整部/跑流程」就发 [ACT] run_pipeline（前提是已有剧本）。\n")
        append("5) 每次回复控制在一两段内，口语自然，像真人搭档而不是客服；动手的事用 [ACT] 表达，别在正文里写机器指令。\n")
        append("6) 用户问「进度/到哪了/好了没」→ 先发 [ACT] render_status 拿真实进度再回答，不要凭感觉编。\n")
        append("7) 渲染卡住/失败时，先 [ACT] render_status 看状态，再决定 pause/resume 或请用户去「渲染」标签页处理。\n")
        if (currentProjectHint != null) {
            append("\n【当前项目上下文】你现在操作的项目 id 是：$currentProjectHint。用户说的「这个项目/本项目」都指它。")
        }
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
    suspend fun say(userText: String, onNotice: (String) -> Unit = {}): String {
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

        // v1.7.16：滑动窗口裁剪——发送前确保消息数不超过上限
        trimMessages()

        // v1.6.3 修对话闪退：textProvider.chat 是 Ktor/OkHttp，可能在某些 ROM 上抛 native
        // 异常逃过 Kotlin try-catch（红旗/澎湃OS 偶发）。这里包 try-catch 转成空回复，
        // 不让异常冒泡到 sendUserMessage 的 runCatching 之外的 native 路径。
        val resp: ChatResponse = try {
            textProvider.chat(
                ChatRequest(messages = _messages.toList(), model = modelId, temperature = temperature),
            )
        } catch (t: Throwable) {
            logger("AiAgent chat failed: ${t.message}")
            ChatResponse(content = "（AI 调用失败：${t.javaClass.simpleName}：${t.message?.take(80)}）", raw = "")
        }
        val raw = resp.content.ifBlank { "（AI 没有回复，请重试）" }
        lastAiText = raw

        // 抽取 [ACT] 指令，剥离展示文本，逐条执行并回显
        val actions = parseActions(raw)
        val displayText = stripActions(raw)
        val execNotes = mutableListOf<String>()
        for (act in actions) {
            runCatching { actionHandler(act, onNotice) }
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

    /**
     * 滑动窗口裁剪：保留 system prompt + 最近 maxTurns 轮完整对话。
     * 按完整对话对（user+assistant）删除，避免出现孤立消息。
     * 在每次发送用户消息后、调用 LLM 前执行。
     */
    private fun trimMessages() {
        val maxMessages = 1 + maxTurns * 2  // system + N轮(user+assistant)
        while (_messages.size > maxMessages) {
            // 找到第一条非 system 消息（应为 user），连同下一条（assistant）一起删除
            val firstIdx = _messages.indexOfFirst { it.role != "system" }
            if (firstIdx < 0) break
            _messages.removeAt(firstIdx)
            // 删除配对的 assistant 消息（如果存在且不是 system）
            if (firstIdx < _messages.size && _messages[firstIdx].role != "system") {
                _messages.removeAt(firstIdx)
            }
        }
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
