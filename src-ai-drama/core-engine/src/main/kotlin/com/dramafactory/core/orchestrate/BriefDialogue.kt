package com.dramafactory.core.orchestrate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T014 任务2：AI 模式流式对话 Brief。
 *
 * 用户粘完文本，AI 主动问 5~6 轮关键问题（朝代/风格/角色数/情绪/配音/补充要求），
 * 用户可自由补充任何细节，最后展示编辑式 Brief 卡片，用户确认后才进流水线。
 *
 * 状态机（5 个状态）：
 *   IDLE → QUESTIONING → ANSWERED → CONFIRMING → CONFIRMED
 *                                  ↘ CANCELLED
 *
 * 设计原则：
 * 1. Brief 是纯数据类，方便单测和 UI 双向绑定；
 * 2. 状态机独立于 DefaultAiOrchestrator：对话失败/取消不影响主流程
 *    （用户可始终选择"跳过对话，直接一键成片"）；
 * 3. 任意阶段都支持用户自由补充，AI 把补充读进 [customNotes]。
 */
data class Brief(
    val era: String = "",                // 时代朝代（"西汉" / "唐" / "现代" / "架空"）
    val style: String = "",              // 视觉风格（"cinematic" / "noir" / "shōjo" / ...）
    val characterCount: Int = 0,         // 主要角色数（2-6）
    val mood: String = "",               // 情绪基调（"热血" / "悬疑" / "治愈" / "史诗"）
    val withAudio: Boolean = true,       // 是否生成配音
    val customNotes: String = "",        // 用户自由补充（任意长度）
    val rawScript: String = "",          // 原始粘入剧本（BRIEF_DIALOGUE 前置输入）
    val confirmed: Boolean = false,      // 用户已确认
) {
    fun isComplete(): Boolean = era.isNotBlank() && style.isNotBlank() && characterCount > 0
    /** isComplete 但同时确认了配音选项（用于 strict 模式） */
    fun isFullyConfirmed(): Boolean = isComplete() && confirmed

    /** 人类可读的「Brief 摘要」，供 UI 确认卡片展示 */
    fun renderSummary(): String = buildString {
        appendLine("朝代：${era.ifBlank { "未填" }}")
        appendLine("风格：${style.ifBlank { "未填" }}")
        appendLine("主要角色数：${if (characterCount > 0) characterCount else "未填"}")
        appendLine("情绪基调：${mood.ifBlank { "未填" }}")
        appendLine("配音：${if (withAudio) "是" else "否"}")
        if (customNotes.isNotBlank()) {
            appendLine("补充要求：")
            appendLine(customNotes.lines().joinToString("\n") { "  · $it" })
        }
    }.trimEnd()

    /** 折叠进 EXTRACT_ASSETS prompt 的提示词（仅在 brief 已确认时使用） */
    fun toPromptFragment(): String = buildString {
        append("【用户确认的成片 Brief】")
        append("时代=${era}; ")
        append("风格=${style}; ")
        append("主要角色数=${characterCount}; ")
        append("情绪=${mood}; ")
        append("配音=${if (withAudio) "是" else "否"}; ")
        if (customNotes.isNotBlank()) append("补充=${customNotes}; ")
    }
}

/** Brief 对话阶段状态 */
enum class BriefState(val label: String) {
    IDLE("待开始"),
    QUESTIONING("问询中"),
    ANSWERED("已回答"),
    CONFIRMING("待确认"),
    CONFIRMED("已确认"),
    CANCELLED("已取消"),
}

/**
 * Brief 状态机（业务纯 Kotlin，JVM 可单测）。
 *
 * AI 主动问的 5 个核心问题，按用户填的字段跳过已填项；用户可自由补充任何内容
 * （进 [Brief.customNotes]）。任意阶段 userInsert 都能继续。
 */
class BriefDialogue(
    private val maxRounds: Int = 6,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(BriefState.IDLE)
    val state: StateFlow<BriefState> = _state

    private val _brief = MutableStateFlow(Brief())
    val brief: StateFlow<Brief> = _brief

    private val _history = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val history: StateFlow<List<DialogueTurn>> = _history

    private val _nextQuestion = MutableStateFlow<String?>(null)
    val nextQuestion: StateFlow<String?> = _nextQuestion

    /** 对话轮数（用于"6 轮内"判定） */
    val round: Int get() = _history.value.count { it.side == DialogueTurn.Side.AI }

    /** 启动对话：先问第一个未填字段 */
    fun start(rawScript: String) {
        if (_state.value != BriefState.IDLE) return
        _brief.value = Brief(rawScript = rawScript)
        _state.value = BriefState.QUESTIONING
        val q = nextQuestionFor(_brief.value)
        _nextQuestion.value = q
        if (q != null) _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, q)
    }

    /** 用户回答主问（field 由 nextQuestion 文案决定） */
    fun onAnswer(field: BriefField, value: String) {
        if (_state.value != BriefState.QUESTIONING) return
        // 记录用户上一题回答
        _history.value = _history.value + DialogueTurn(DialogueTurn.Side.USER, value)
        _brief.value = when (field) {
            BriefField.ERA -> _brief.value.copy(era = value)
            BriefField.STYLE -> _brief.value.copy(style = value)
            BriefField.CHARACTER_COUNT -> _brief.value.copy(characterCount = value.toIntOrNull() ?: 0)
            BriefField.MOOD -> _brief.value.copy(mood = value)
            BriefField.WITH_AUDIO -> _brief.value.copy(withAudio = value.lowercase() in listOf("是","y","yes","true","1","要","开"))
        }
        val nextQ = nextQuestionFor(_brief.value)
        if (nextQ == null || round >= maxRounds) {
            _state.value = BriefState.ANSWERED
            _nextQuestion.value = null
        } else {
            _nextQuestion.value = nextQ
            _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, nextQ)
        }
    }

    /** 用户自由补充（任意时刻都可调） */
    fun onUserNote(note: String) {
        if (note.isBlank()) return
        val existing = _brief.value.customNotes
        val merged = if (existing.isBlank()) note else "$existing\n$note"
        _brief.value = _brief.value.copy(customNotes = merged)
        _history.value = _history.value + DialogueTurn(DialogueTurn.Side.USER, note)
    }

    /** 进入确认阶段 */
    fun requestConfirm() {
        if (_state.value == BriefState.QUESTIONING && !_brief.value.isComplete()) return
        _state.value = BriefState.CONFIRMING
    }

    /** 用户确认 brief（不再改） */
    fun confirm() {
        if (_state.value != BriefState.CONFIRMING) return
        _brief.value = _brief.value.copy(confirmed = true)
        _state.value = BriefState.CONFIRMED
    }

    /** 取消对话（用户随时可调） */
    fun cancel() {
        _state.value = BriefState.CANCELLED
        _nextQuestion.value = null
    }

    /** 直接编辑某个字段（确认阶段用户可改） */
    fun editField(field: BriefField, value: String) {
        if (_state.value != BriefState.CONFIRMING) return
        _brief.value = when (field) {
            BriefField.ERA -> _brief.value.copy(era = value)
            BriefField.STYLE -> _brief.value.copy(style = value)
            BriefField.CHARACTER_COUNT -> _brief.value.copy(characterCount = value.toIntOrNull() ?: 0)
            BriefField.MOOD -> _brief.value.copy(mood = value)
            BriefField.WITH_AUDIO -> _brief.value.copy(withAudio = value.lowercase() in listOf("是","y","yes","true","1","要","开"))
        }
    }

    /** 给当前 brief 选下一个问题（按未填顺序；最多问 maxRounds 轮） */
    private fun nextQuestionFor(b: Brief): String? {
        if (round >= maxRounds) return null
        return when {
            b.era.isBlank() -> "本剧时代是？（西汉 / 唐 / 明清 / 民国 / 现代 / 架空）"
            b.style.isBlank() -> "视觉风格偏好？（cinema 写实 / 古风水墨 / 黑白默片 / 动漫番剧 / 自定义）"
            b.characterCount <= 0 -> "主要角色数？（2-6 个，太多分镜会散）"
            b.mood.isBlank() -> "情绪基调？（热血 / 悬疑 / 治愈 / 史诗 / 日常 / 自定义）"
            else -> null
        }
    }

    /**
     * 是否需要问"是否配音"——其实默认 true 可推断；这里只用作 [isComplete] 的子判定。
     * 真正交互中我们允许用户跳过这题。
     */
    private fun Brief.withAudioKnown(): Boolean = rawScript.isNotEmpty()
}

/** 字段枚举（避免传字符串） */
enum class BriefField { ERA, STYLE, CHARACTER_COUNT, MOOD, WITH_AUDIO }

/** 一轮对话（AI 问题 / 用户回答 / 用户补充） */
data class DialogueTurn(
    val side: Side,
    val content: String,
    val at: Long = 0L,
) {
    enum class Side { AI, USER }
}
