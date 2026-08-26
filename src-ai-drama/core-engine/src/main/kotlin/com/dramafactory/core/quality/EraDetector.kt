package com.dramafactory.core.quality

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * 第十三轮：时代红线按剧本自动推断。
 *
 * 旧实现写死西汉预设——现代剧/清宫剧也会被禁手机、被要求穿深衣曲裾。
 * 新流程：
 *   1. [detect] 用 LLM 判断剧本朝代（agnes-2.5-flash，输出严格JSON），失败回退规则关键词匹配
 *   2. [presetFor] 从内置多朝代预设表取对应 StylePreset
 *   3. 生成链路用该 preset 组装 positive/negative
 *
 * 内置朝代：汉/唐/宋/明/清/民国/现代/架空。架空=只保留画质负向，不约束时代。
 */
object EraDetector {

    data class Detection(val eraKey: String, val label: String, val usedLlm: Boolean)

    private const val PROMPT = """判断这个剧本/小说的故事时代背景。只输出严格 JSON，不要markdown：
{"era":"han|tang|song|ming|qing|roc|modern|fantasy","label":"一句话时代描述"}
判定标准：以主体剧情所处时代为准（穿越题材按主角穿越后主体时代）；完全架空的虚构世界用 fantasy。"""

    /**
     * LLM 检测 + 规则兜底。
     * @param chat 文本模型调用；llmReady=false 时直接走规则
     */
    suspend fun detect(
        script: String,
        llmReady: Boolean,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): Detection {
        val clipped = if (script.length > 3000) script.take(3000) else script
        if (llmReady) {
            repeat(2) {
                runCatching {
                    val resp = chat(com.dramafactory.core.model.ChatRequest(
                        messages = listOf(com.dramafactory.core.model.ChatMessage("user", "$PROMPT\n\n【文本】\n$clipped")),
                        temperature = 0.0, enableThinking = false, maxTokens = 128))
                    parse(resp.content)?.let { return it }
                }
            }
        }
        return ruleBased(script)
    }

    internal fun parse(content: String): Detection? {
        var s = content.trim()
        if (s.startsWith("```")) { s = s.removePrefix("```json").removePrefix("```"); s = s.substringBeforeLast("```").trim() }
        val l = s.indexOf('{'); val r = s.lastIndexOf('}')
        if (l < 0 || r <= l) return null
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(s.substring(l, r + 1)) as? JsonObject ?: return null
            val era = (obj["era"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return null
            if (era !in PRESETS.keys) return null
            val label = (obj["label"] as? JsonPrimitive)?.content?.trim().orEmpty()
            Detection(era, label.ifBlank { PRESETS[era]!!.era.label }, usedLlm = true)
        } catch (_: Throwable) { null }
    }

    /** 规则兜底：朝代特征词命中计数，取最高；全零回退汉（保守历史向）。 */
    internal fun ruleBased(script: String): Detection {
        val signals = mapOf(
            "han" to listOf("匈奴", "长安未央", "新莽", "西域都护", "丝绸之路", "大汉", "汉军", "单于", "和亲"),
            "tang" to listOf("大唐", "长安城", "贞观", "开元", "科举", "突厥", "唐诗", "大明宫"),
            "song" to listOf("大宋", "汴京", "临安", "清明上河", "岳飞", "瓦舍勾栏", "交子"),
            "ming" to listOf("大明", "锦衣卫", "东厂", "郑和", "应天府", "内阁"),
            "qing" to listOf("大清", "紫禁城", "皇上万福", "格格", "贝勒", "军机处", "辫子", "康熙", "乾隆", "太后老佛爷"),
            "roc" to listOf("民国", "租界", "黄包车", "旗袍舞女", "上海滩", "军阀", "留声机", "大洋"),
            "modern" to listOf("手机", "地铁", "电脑", "微信", "公司", "汽车", "写字楼", "视频通话", "外卖"),
            "fantasy" to listOf("修炼", "灵气", "法术", "修仙", "斗气", "魔法", "异世界", "系统", "穿越"),
        )
        var best = "han"; var bestHits = 0
        for ((k, words) in signals) {
            val hits = words.count { it in script }
            if (hits > bestHits) { best = k; bestHits = hits }
        }
        return Detection(best, PRESETS[best]!!.era.label, usedLlm = false)
    }

    // ---------------- 多朝代预设表 ----------------

    /** 通用历史负向（各古代朝代共享的「禁现代物」基底） */
    private val ANCIENT_NEGATIVE = StylePreset.DEFAULT_ERA_NEGATIVE

    private fun ancientEra(label: String, positive: String) = StylePreset.EraSpec(label = label, positive = positive, negative = ANCIENT_NEGATIVE)

    /** 各朝代预设：key → StylePreset（仅 era 块不同，姿态/全局负向共用） */
    val PRESETS: Map<String, StylePreset> = mapOf(
        "han" to StylePreset(),   // 西汉末—新莽默认
        "tang" to StylePreset(name = "tang", era = ancientEra("盛唐时期（约公元8世纪）",
            "【严格历史时代约束】本剧设定为唐代，人物服饰为唐制圆领袍、齐胸襦裙、幞头；建筑为斗拱木构、里坊制；器物含唐三彩、金银器、卷轴；无电力、无工业、无现代器物。")),
        "song" to StylePreset(name = "song", era = ancientEra("宋代（北宋/南宋）",
            "【严格历史时代约束】本剧设定为宋代，服饰为宋制褙子、直领对襟衫、东坡巾；市井瓦舍勾栏、交子纸币、点茶焚香；建筑木构歇山顶；无电力、无工业、无现代器物。")),
        "ming" to StylePreset(name = "ming", era = ancientEra("明代",
            "【严格历史时代约束】本剧设定为明代，服饰为明制道袍、袄裙、乌纱帽、网巾；家具为明式硬木；青花瓷、线装书；无电力、无工业、无现代器物。")),
        "qing" to StylePreset(name = "qing", era = ancientEra("清代",
            "【严格历史时代约束】本剧设定为清代，男子剃发留辫、长袍马褂；女子旗装、旗头；宫廷礼制严格；无电力、无工业、无现代器物（清末洋务元素除外，依剧本为准）。")),
        "roc" to StylePreset(name = "roc", era = ancientEra("民国时期（1912-1949）",
            "【时代约束】本剧设定为民国时期：男着长衫马褂或西装、女着旗袍；黄包车、留声机、煤气灯、石库门与租界街景；有电灯电话但无当代数码产品。")),
        "modern" to StylePreset(name = "modern", era = StylePreset.EraSpec(
            label = "当代（21世纪）",
            positive = "【时代约束】本剧设定为当代中国：都市/乡镇现代生活场景，服装为当代日常服饰，器物为现代家电与数码产品。",
            negative = emptyList())),   // 现代剧不禁任何现代物
        "fantasy" to StylePreset(name = "fantasy", era = StylePreset.EraSpec(
            label = "架空世界",
            positive = "【世界观】本剧为架空幻想世界：服饰与场景可融合多朝代东方元素，以剧本设定为准；保持东方美学统一性。",
            negative = listOf("watermark", "text", "low quality", "speckles", "水印", "文字", "低质"))),  // 架空不限时代
    )

    /** 按检测key取预设；未知key回退西汉默认 */
    fun presetFor(eraKey: String): StylePreset = PRESETS[eraKey.lowercase()] ?: StylePreset.HAN_DEFAULT
}
