package com.dramafactory.core.quality

/**
 * v1.9.35 音效/拟声词台词守卫。
 *
 * 真机反馈：AI 分镜把环境音（叮咚、门铃声等）写进 dialogue，
 * 视频端 generate_audio 会把它当台词朗读——角色开口念"叮咚"。
 * 编剧 Prompt 已禁止该行为，但 Prompt 不保证被遵循，这里做解析层硬拦截：
 * 纯音效文本从 dialogue/narration 剥离（由调用方降级进 action）。
 */
object SoundEffectFilter {

    /** 常见中文拟声词/音效词（小写匹配用原文即可，中文无大小写） */
    private val SFX_WORDS = setOf(
        "叮", "咚", "叮咚", "叮当", "叮叮", "咚咚", "叮叮咚", "叮咚声",
        "砰", "嘭", "噗", "噗嗤", "噗通", "扑通", "哐", "哐当", "哐啷",
        "啪", "啪嗒", "噼啪", "噼里啪啦",
        "哗", "哗啦", "哗哗", "哗啦啦",
        "嗡", "嗡嗡", "嗡嗡声",
        "轰", "轰鸣", "轰隆", "隆隆",
        "嘶", "嘶嘶", "咝",
        "呜", "呜呜", "呜咽", "呜哇",
        "哇", "哇哇", "哇啦",
        "呱", "呱呱", "嘎", "嘎嘎", "叽叽", "叽叽喳喳", "喳喳",
        "喵", "咪", "喵喵", "汪", "汪汪", "嗷", "嗷呜", "吼", "嚎",
        "咕", "咕噜", "咕嘟", "咕咚",
        "嘶溜", "吸溜", "呼噜", "呼", "呼呼",
        "窸窣", "悉悉索索", "簌簌", "沙沙",
        "淅沥", "淅淅沥沥",
        "滴", "滴答", "嘀嗒", "嘀嘀", "滴答声",
        "嘶啦", "刺啦", "咔", "咔嚓", "咔哒", "喀",
        "锵", "铮", "铿", "当", "铛", "镗",
        "咣", "咣当", "拨", "铮铮",
        "嗖", "唰", "飕",
        "咳", "咳咳", "哼", "哼哼", "嘿", "嘿嘿", "哈", "哈哈", "哈哈哈",
        "嘻嘻", "呵呵", "嘿嘿嘿", "咯咯", "噗哈哈",
        "嗬", "嚯", "唷", "哎哟", "哎呀",
        "靴", "轱辘", "铮啦",
    )

    /** 纯音效判定后的处理结果 */
    sealed interface Verdict {
        /** 保持原样，正常台词/旁白 */
        data object Keep : Verdict
        /** 纯音效，应剥离出 dialogue/narration（text 为原文，供降级进 action） */
        data class Strip(val text: String) : Verdict
    }

    /**
     * 判定一段 dialogue/narration 是否"纯音效"：
     * 去掉标点/空白后按标点切分，每个 token 都是音效词、或为音效字叠字
     * （如"叮叮叮"、"叮咚叮咚"）即判为音效。含任何非音效汉字视为真实台词保留。
     */
    fun classify(text: String?): Verdict {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Verdict.Keep
        // 拆分：中英文标点、空白、引号都作分隔
        val tokens = raw.split(Regex("[\\s，。！？；：、,.!?;:\"'“”‘’~—…·()（）\\[\\]【】「」『』]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Verdict.Keep
        // 长句必含语法结构，不当音效处理（如"门外传来叮咚声"是叙述）
        if (raw.any { it.code in 0x4E00..0x9FFF } && raw.filter { it.code in 0x4E00..0x9FFF }.length > 8) return Verdict.Keep
        val allSfx = tokens.all { isSfxToken(it) }
        return if (allSfx) Verdict.Strip(raw) else Verdict.Keep
    }

    private fun isSfxToken(token: String): Boolean {
        if (token.isEmpty()) return false
        // 数字（时长/编号混入）不算音效，防误伤
        if (token.all { it.isDigit() }) return false
        if (token in SFX_WORDS) return true
        // 音效字叠字模式：token 完全由少数音效字组成（如 叮叮咚、咚咚咚、叮咚叮咚）
        val sfxChars = SFX_WORDS.filter { it.length == 1 }.map { it[0] }.toSet()
        val han = token.filter { it.code in 0x4E00..0x9FFF }
        if (han.isEmpty()) return false
        if (token.length <= 8 && han.all { it in sfxChars }) return true
        // 音效词循环（叮咚叮咚 = 叮咚重复）
        for (w in SFX_WORDS) {
            if (w.length >= 2 && token.length % w.length == 0) {
                val sb = StringBuilder()
                repeat(token.length / w.length) { sb.append(w) }
                if (token == sb.toString()) return true
            }
        }
        return false
    }
}
