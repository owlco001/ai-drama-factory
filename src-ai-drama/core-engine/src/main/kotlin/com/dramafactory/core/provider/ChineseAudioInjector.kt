package com.dramafactory.core.provider

/**
 * 中文配音指令注入工具 —— 决议Q9产品级默认行为。
 * 继承 pavo-drama 实战方案：中文台词开头主导 + 显式「全程使用中文普通话配音」追加。
 * generate_audio 原生出声轨（人声+环境音+SFX一体），永不做静音+重配（丢环境音是硬伤）。
 */
object ChineseAudioInjector {

    const val MANDARIN_SUFFIX = "全程使用中文普通话配音"

    /**
     * 注入规则：
     * 1. prompt 为空 → 直接返回显式中文指令；
     * 2. prompt 已含显式指令 → 原样返回（不重复叠加）；
     * 3. 中文台词开头主导（台词/旁白在前）+ 末尾追加「全程使用中文普通话配音」。
     */
    fun inject(prompt: String): String {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return MANDARIN_SUFFIX
        if (trimmed.contains(MANDARIN_SUFFIX)) return trimmed
        // 台词/旁白已主导开头，只需末尾追加显式语言锚定指令
        return "$trimmed。$MANDARIN_SUFFIX"
    }

    /** 判定prompt是否以中文主导开头（前N个非空白有效字符中汉字占比）——防英文漂移的启发式 */
    fun chineseLeading(prompt: String, sample: Int = 12): Boolean {
        val chars = prompt.take(sample).filterNot { it.isWhitespace() || it.isDigit() || it in ".,!?，。！？：「」『』" }
        if (chars.isEmpty()) return false
        val han = chars.count { it.code in 0x4E00..0x9FFF }
        return han * 2 >= chars.count()
    }

    /** 组装一镜完整提交prompt：中文台词/旁白主导开头 + 动作描述 + 显式中文指令 */
    fun buildShotPrompt(dialogue: String, narration: String, action: String): String {
        val head = listOf(dialogue.trim(), narration.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val body = action.trim()
        val raw = listOf(head, body).filter { it.isNotEmpty() }.joinToString(if (head.isEmpty()) "" else " ")
        return inject(raw)
    }
}
