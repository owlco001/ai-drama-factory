package com.dramafactory.core.quality

/**
 * 资产图 prompt 的 LLM 扩写器（core 层，app / desktop 共用）。
 *
 * 剧本提取的资产卡 prompt 只是正则抽出的**裸名词**（如「王莽」「漠北草原·日·外」），
 * 直接喂图像模型出图质量差、与剧本语境脱节、人物/场景/道具只靠名词撑不起来。
 * 本模块在生图前用文本 LLM 把裸名词扩写成一段**符合时代红线、聚焦主体**的视觉描述，
 * 再交给 [AssetPromptBuilder] 套类型约束（纯色底 / 空场无人 / 禁词 / 尾部强指令）。
 *
 * 设计要点：
 * - 扩写只描述**主体本身**（人物外貌服饰 / 场景建筑陈设 / 道具形制材质），
 *   绝不写背景、环境、构图、镜头、光线布置——那些由 [AssetPromptBuilder] 按类型统一追加，
 *   避免和红线约束重复甚至对冲。
 * - 严格禁止现代元素（电、工业、塑料、玻璃幕墙…），并给出少量高频禁词作负向提示。
 * - 输出以英文为主（图像模型对英文理解更强），可夹中文关键术语；纯文本、无 JSON、无前缀。
 * - 与具体文本 Provider 解耦：调用方把「发一条 user 消息并返回模型文本」的函数注入 [enrich]，
 *   便于单测用假实现替换。
 */
object AssetPromptEnricher {

    /** 各类型扩写的侧重点（注入系统指令），只描述主体自身 */
    private fun kindFocus(kind: String): String = when (kind.lowercase()) {
        AssetPromptBuilder.KIND_CHARACTER ->
            "人物：汉代衣冠形制（深衣/曲裾/直裾/冠巾）、发式、面料质地（麻葛/丝帛）、体型年龄神态、单人平视；" +
            "只考虑这一个人本身；双手必须空置、自然下垂，不描述任何手持的器物、兵器、刀剑、简牍、书卷、杯盏、杖或道具"
        AssetPromptBuilder.KIND_SCENE ->
            "场景：木构与夯土建筑、空间陈设、道具器物、时辰天光与氛围；这是空场空镜，绝不出现任何人物"
        AssetPromptBuilder.KIND_PROP ->
            "器物：形制、材质与工艺（简牍竹简/青铜/漆木/陶器/麻葛丝帛）、尺寸比例、孤立单件；绝不出现人物或手持的人手"
        else -> "主体本身"
    }

    /** 构造发给 LLM 的指令文本 */
    fun instruction(
        kind: String,
        eraLabel: String,
        forbidden: List<String>,
    ): String = buildString {
        append("你是为古装短剧图像模型服务的视觉提示词写手。")
        append("当前时代设定：$eraLabel。")
        append("请仅针对「${kindFocus(kind)}」扩写下面给出的资产名称，输出一段聚焦主体本身的视觉描述。\n")
        append("规则：\n")
        append("1. 用 2-4 句以英文为主的描述（可夹中文关键术语），不要 JSON、不要前缀、不要任何解释。\n")
        append("2. 只描述主体自身外观，禁止描写背景、环境、构图、镜头、光线布置（由下游统一追加）。\n")
        append("   ★若主体是人物：双手必须空置，禁止出现 holding / carrying / wielding / in hand 等持握表述，")
        append("也不要写兵器、简牍、书卷、杯盏、杖等任何被拿在手里的物件（实物由道具资产单独生成）。\n")
        append("3. 严禁任何现代元素：无电力、无工业、无塑料、无玻璃幕墙、无现代服装与招牌文字。\n")
        if (forbidden.isNotEmpty()) {
            append("4. 避免出现这些概念：${forbidden.take(12).joinToString("、")}。\n")
        }
    }

    /** 清洗 LLM 原始输出：去代码块/引号/前后空白，压缩多余空白 */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(Regex("^```[a-zA-Z]*\\s*"), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
            .replace(Regex("^[\"“‘『【\\s]+"), "")
            .replace(Regex("[\"”’』】\\s]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * 调用文本 LLM 扩写裸名词。
     * @param chat 发送单条 user 消息并返回模型文本的函数（由 App 层用 TextProvider 包裹）。
     * @param fallback 扩写失败/返回空时回退的文本（通常即裸名词本身）。
     * @return 清洗后的扩写文本；chat 抛异常或返回空时回退 [fallback]。
     */
    suspend fun enrich(
        chat: suspend (String) -> String,
        kind: String,
        name: String,
        eraLabel: String = "西汉末年至新莽时期（约公元1世纪—公元23年）",
        forbidden: List<String> = emptyList(),
        fallback: String = name,
    ): String {
        val userMsg = "${instruction(kind, eraLabel, forbidden)}\n资产名称：$name"
        val raw = runCatching { chat(userMsg) }.getOrNull().orEmpty()
        val cleaned = clean(raw)
        if (cleaned.isBlank()) return fallback
        // v1.9.11：人物扩写若仍出现持握表述，直接丢弃回退裸词——
        // 下游的正向约束救不回已经写进主体描述的「手持某物」，必须从源头掐掉。
        if (kind.lowercase() == AssetPromptBuilder.KIND_CHARACTER && HELD_ITEM_HINT.containsMatchIn(cleaned)) {
            return fallback
        }
        return cleaned
    }

    /**
     * v1.9.11：人物扩写结果里的「持握」痕迹（中英）。命中即判定该次扩写不可用，回退裸词。
     * 与 [instruction] 的软约束互为双保险：软约束管 LLM 不写，这里管写了也进不了生图链路。
     */
    private val HELD_ITEM_HINT = Regex(
        """\b(holding|carrying|wielding|gripping|clutching|in\s+(his|her|their)?\s*hands?)\b""" +
        """|手持|拿在手里|握着|手持着""",
        RegexOption.IGNORE_CASE)
}
