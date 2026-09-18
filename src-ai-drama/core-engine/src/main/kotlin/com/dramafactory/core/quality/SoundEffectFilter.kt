package com.dramafactory.core.quality

/**
 * v1.9.35 音效/拟声词台词守卫。
 *
 * 真机反馈：AI 分镜把环境音（叮咚、门铃声等）写进 dialogue，
 * 视频端 generate_audio 会把它当台词朗读——角色开口念"叮咚"。
 * 编剧 Prompt 已禁止该行为，但 Prompt 不保证被遵循，这里做解析层硬拦截：
 * 纯音效文本从 dialogue/narration 剥离（由调用方降级进 action）。
 *
 * v1.9.37 守卫v2：扩展到旁白与叠字类
 * - 旁白/台词里的音效标注「（音效：叮咚）」「（叮）」「(哗啦)」→ 整句 Strip；
 * - BGM/音乐提示「（BGM 渐起）」→ StripPartial（剥离进 action，保留残留真实台词）；
 * - 叠字拟声词词库扩充（叮铃/门铃/吱呀/嘎吱/咯吱/笛/唧/哒/嗒 等）；
 * - 整句仅由音效词+语气词/省略号/破折号构成（如「叮咚……」「叮——咚——」）→ Strip；
 * - 长句保护不变：>8 汉字且含非音效叙述成分 → Keep（fail-closed，不误伤正常台词）。
 */
object SoundEffectFilter {

    /** 常见中文拟声词/音效词（v1.9.37 叠字类扩充：叮铃/门铃/吱呀/嘎吱/咯吱/笛/唧/哒/嗒 等） */
    private val SFX_WORDS = setOf(
        "叮", "咚", "叮咚", "叮当", "叮叮", "咚咚", "叮叮咚", "叮咚声",
        "叮铃", "铃", "门铃", "叮铃叮铃", "铃铛", "叮铃响",
        "砰", "嘭", "砰砰", "噗", "噗嗤", "噗通", "扑通", "哐", "哐当", "哐啷",
        "啪", "啪嗒", "啪啪", "噼啪", "噼里啪啦",
        "哗", "哗啦", "哗哗", "哗哗哗", "哗啦啦",
        "嗡", "嗡嗡", "嗡嗡声",
        "轰", "轰鸣", "轰隆", "隆隆",
        "嘶", "嘶嘶", "咝",
        "呜", "呜呜", "呜哇", "呜啦",
        "哇", "哇哇", "哇啦",
        "呱", "呱呱", "嘎", "嘎吱", "嘎嘎", "叽叽", "叽叽喳喳", "喳喳", "唧",
        "喵", "咪", "喵喵", "汪", "汪汪", "嗷", "嗷呜", "吼", "嚎",
        "咕", "咕噜", "咕嘟", "咕咚",
        "呼", "呼呼",
        "窸窣", "悉悉索索", "簌簌", "沙沙", "沙",
        "淅沥", "淅淅沥沥",
        "滴", "滴答", "嘀嗒", "嘀嘀", "滴答声",
        "嘶啦", "刺啦", "咔", "咔嚓", "咔哒", "喀",
        "锵", "铮", "铿", "铛", "铛铛", "镗",
        "咣", "咣当", "铮铮",
        "嗖", "唰", "飕",
        "咳", "咳咳", "哼", "哼哼", "嘿", "嘿嘿", "哈", "哈哈", "哈哈哈",
        "嘻嘻", "呵呵", "嘿嘿嘿", "咯咯", "咯吱", "噗哈哈",
        "嗬", "嚯", "唷", "哎哟", "哎呀",
        "轱辘", "铮啦",
        "吱", "吱呀", "吱吱", "笛",
        "哒", "嗒", "哒哒", "哒哒哒", "咚咚咚",
    )

    /** 括号标注中可出现的音乐类提示词 */
    private val MUSIC_HINTS = setOf("BGM", "bgm", "音乐", "配乐", "鼓点")

    /** 括号标注中可出现的动词/状态词（描述音乐或音效如何出现，非真实台词）
     *  只做「精确 token 全等」匹配，避免 contains 子串把「（来了）」「（停一下）」「（起来开门）」误剥离（P0-1）。 */
    private val MUSIC_LEAD_MODIFIERS = setOf(
        "渐起", "渐弱", "渐强", "渐停", "渐止", "响起", "密集", "骤停", "骤起",
    )

    /** 括号标注中可出现的真·语气/结构助字（不含来/停/中/去/得/着了等日常实词单字，
     *  否则「（来）」「（中了）」「（着了）」会被逐字命中误判为音效标注而误剥离——P1-1） */
    private val NON_SFX_STOPWORDS: String = "的了吗呢吧啊哦唉"

    /** 括号内出现这些实词/实词组合或日常单字实词 → 整段判定为正常台词（fail-closed，不得剥离） */
    private val PAREN_STOP_WORDS = setOf(
        "起来", "来了", "停一下", "停下来", "开门", "坐下", "说话", "走吧",
        "来", "停", "中", "去", "得", "着",
    )

    /** 单字音效字拼接串（v1.9.37：用 String.contains 避免 Set<Char> 推断问题） */
    private val SFX_SINGLE_CHARS: String by lazy {
        SFX_WORDS.filter { it.length == 1 }.joinToString("")
    }

    /** 纯音效判定后的处理结果 */
    sealed interface Verdict {
        /** 保持原样，正常台词/旁白 */
        data object Keep : Verdict
        /** 纯音效，应剥离出 dialogue/narration（text 为原文，供降级进 action） */
        data class Strip(val text: String) : Verdict
        /**
         * v1.9.37：剥离括号内的 BGM/音效标注。
         * stripped = 被剥掉的标注文字（不含括号，供并入 action）；
         * kept = 剥离后残留的真实台词（为空表示整段都是标注，调用方置 null）。
         */
        data class StripPartial(val stripped: String, val kept: String) : Verdict
    }

    /**
     * 判定一段 dialogue/narration 是否"纯音效"：
     * 去掉标点/空白后按标点切分，每个 token 都是音效词、或为音效字叠字
     * （如"叮叮叮"、"叮咚叮咚"）即判为音效。含任何非音效汉字视为真实台词保留。
     *
     * v1.9.37：
     * - 括号包裹的「音效：xxx」「BGM/音乐类提示」→ 剥离（Strip 或 StripPartial）；
     * - 整句仅由音效词+语气词/省略号/破折号构成（「叮咚……」「叮——咚——」）→ Strip；
     * - 长句（>8 汉字）且含非音效叙述成分 → Keep（fail-closed）。
     */
    fun classify(text: String?): Verdict {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Verdict.Keep

        // v1.9.37：先处理括号内的 BGM/音效标注
        val partial = stripMusicAnnotation(raw)
        if (partial != null) return partial

        // 无括号：去掉所有非汉字标点/空白后，若整段汉字数量 <=8 且无实词结构 → 音效词；
        // 但括号内单字/双字短语（如"（来了）""（停一下）"）不是音效，整句 Keep（P0-1 回归）。
        val hanCount = raw.count { it.code in 0x4E00..0x9FFF }
        val hasParen = Regex("[（(]([^()（）]*)[)）]").containsMatchIn(raw)
        // 无标点长句（>8 汉字）必是叙述，Keep
        if (hanCount > 8) return Verdict.Keep
        // 括号短语（去掉括号后的汉字 token）→ 若所有 token 全在 SFX 词库才 Strip，否则 Keep
        val tokens = if (hasParen) {
            raw.replace(Regex("[（(]([^()（）]*)[)）]"), "\\$1")
                .split(Regex("[\\s，。！？；：、,.!?;:\"'“”‘’~—…·]+")).filter { it.isNotEmpty() }
        } else {
            raw.split(Regex("[\\s，。！？；：、,.!?;:\"'“”‘’~—…·]")).filter { it.isNotEmpty() }
        }
        if (tokens.isEmpty()) return Verdict.Keep
        val allSfx = tokens.all { isSfxToken(it) }
        return if (allSfx) Verdict.Strip(raw) else Verdict.Keep
    }

    /**
     * v1.9.37：扫描括号段（半角/全角），剥离其中的 BGM/音效标注。
     * - 所有括号内内容都是标注（音效/BGM/拟声词/音乐动词，无实词）时生效；
     * - 剥离后无残留 → Verdict.Strip(原文)；有残留 → Verdict.StripPartial(标注文字, 残留台词)；
     * - 任一括号内出现实词 → 返回 null（fail-closed，走正常判定）。
     */
    private fun stripMusicAnnotation(raw: String): Verdict? {
        val parenRegex = Regex("\\(([^()]*?)\\)|（([^（）]*?)）")
        val matches = parenRegex.findAll(raw).toList()
        if (matches.isEmpty()) return null
        if (matches.any { m -> !isAnnotationContent(m.groupValues[2].ifEmpty { m.groupValues[1] }) }) {
            return null // 括号内含实词 → 可能是正常台词，不剥离
        }
        val annoTexts = matches.map { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }.filter { it.isNotBlank() }
        if (annoTexts.isEmpty()) return null // 只有空括号，不是音效标注
        val kept = matches
            .sortedByDescending { it.range.first }
            .fold(raw) { acc, m ->
                val span = m.range
                acc.substring(0, span.first) + acc.substring(span.last + 1)
            }
            .trim()
        return if (kept.isEmpty()) Verdict.Strip(raw)
        else Verdict.StripPartial(annoTexts.joinToString("、"), kept)
    }

    /**
     * 判断括号内文字是否为"音效/BGM 标注"（而非角色台词）：
     * 按「音效：」前缀与标点切 token；每个 token 命中 音效/BGM/音乐词/音乐动词/音效词，
     * 或仅由音效汉字+停用字组成 → 标注；含实词汉字 → false（fail-closed）。
     */
    private fun isAnnotationContent(inner: String): Boolean {
        val body = inner.trim().removePrefix("音效：").removePrefix("音效:").trim()
        if (body.isEmpty()) return true
        // 先按标点切 token，再对每个 token 做 fail-closed 实词检查
        val tokens = body.split(
            Regex("[\\s，。！？；：、,.!?;:\"'“”‘’~—…·]+")
        ).filter { it.isNotEmpty() }
        for (t in tokens) {
            for (w in PAREN_STOP_WORDS) if (t.contains(w)) return false
        }
        return tokens.all { isAnnoToken(it) }
    }

    /** 单个 token 是否为标注成分（音乐词/音乐动词/音效词/全停用字组合） */
    private fun isAnnoToken(token: String): Boolean {
        if (MUSIC_HINTS.any { h -> token.equals(h, ignoreCase = true) }) return true
        // 复合音乐动词：允许 token 与词组合（"音乐" + "渐起" 可连写为 "音乐渐起"）
        if (token in MUSIC_LEAD_MODIFIERS) return true
        if (MUSIC_LEAD_MODIFIERS.any { m -> token.endsWith(m) }) return true
        if (isSfxToken(token)) return true
        // 仅由汉字构成，且每个汉字都是音效字或停用字 → 视为标注（防"叮当"类混入）
        if (token.all { ch -> ch.code in 0x4E00..0x9FFF }) {
            for (c in token) {
                val ok = SFX_SINGLE_CHARS.contains(c) || NON_SFX_STOPWORDS.contains(c)
                if (!ok) return false
            }
            return true
        }
        return false // 非汉字成分（数字等）出现 → fail-closed 不判定为标注
    }

    private fun isSfxToken(token: String): Boolean {
        if (token.isEmpty()) return false
        // 数字（时长/编号混入）不算音效，防误伤
        if (token.all { it.isDigit() }) return false
        if (token in SFX_WORDS) return true
        // 音效字叠字模式：token 完全由少数音效字组成（如 叮叮咚、咚咚咚、叮咚叮咚、哗哗哗）
        val han = token.filter { it.code in 0x4E00..0x9FFF }
        if (han.isEmpty()) return false
        var allSfxChars = true
        for (c in token) {
            if (!SFX_SINGLE_CHARS.contains(c)) { allSfxChars = false; break }
        }
        if (token.length <= 8 && allSfxChars) return true
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
