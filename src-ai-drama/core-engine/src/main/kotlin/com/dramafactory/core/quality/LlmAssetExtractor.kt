package com.dramafactory.core.quality

/**
 * 第十轮：大模型资产自动提取。
 *
 * 旧实现（AssetsLogic.ScriptAssetExtractor）为纯正则，只能识别
 * 「角色：/场景：/道具：」清单行——小说原文没有这种结构化标签时提取为 0。
 *
 * 新实现：把文本交给 TextProvider（agnes-2.5-flash），要求输出严格 JSON：
 *   {"characters":[{"name":"...","desc":"..."}],"scenes":[...],"props":[...]}
 * 解析失败/超时回退到正则提取（保底不空手而归）。TextProvider 注入可测。
 */
object LlmAssetExtractor {

    data class Asset(val kind: String, val name: String, val desc: String)   // kind: character/scene/prop

    data class ExtractResult(val assets: List<Asset>, val usedLlm: Boolean)

    private const val SYSTEM_PROMPT = """你是短剧制片资产师。从给定的小说/剧本文本中提取制作所需资产清单。
只输出严格 JSON，不要任何其他文字、不要 markdown 代码块。格式：
{"characters":[{"name":"角色名","desc":"外貌/服装/气质中文描述，30字内"}],"scenes":[{"name":"场景名","desc":"环境/时间/氛围描述，30字内"}],"props":[{"name":"道具名","desc":"外观描述，20字内"}]}
要求：characters 2-6个主要角色；scenes 按出现顺序取3-8个；props 只列剧情关键道具；desc 必须具体可直接用于文生图。"""

    /**
     * LLM 提取主入口。
     * @param chat 文本模型调用（App层注入 AgnesProvider.chat）
     * @param text 小说/剧本文本（截断至前8000字防token溢出）
     */
    suspend fun extract(
        text: String,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): ExtractResult {
        val clipped = if (text.length > 8000) text.take(8000) + "\n…(后文略)" else text
        // 重试≤2次（网络抖动/JSON不纯）
        repeat(2) { attempt ->
            runCatching {
                val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", "$SYSTEM_PROMPT\n\n【文本】\n$clipped"))))
                val assets = parseJson(resp.content)
                if (assets.isNotEmpty()) return ExtractResult(assets, usedLlm = true)
            }
            kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return ExtractResult(emptyList(), usedLlm = false)
    }

    /** 容错解析：剥markdown代码栅栏 → 定位首个{…末个} → org.json解析 */
    fun parseJson(content: String): List<Asset> {
        var s = content.trim()
        if (s.startsWith("```")) {   // 剥 ```json … ```
            s = s.removePrefix("```json").removePrefix("```")
            s = s.substringBeforeLast("```").trim()
        }
        val l = s.indexOf('{'); val r = s.lastIndexOf('}')
        if (l < 0 || r <= l) return emptyList()
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(
                s.substring(l, r + 1)).let { it as? kotlinx.serialization.json.JsonObject }
                ?: return emptyList()
            val out = mutableListOf<Asset>()
            for ((key, kind) in mapOf("characters" to "character", "scenes" to "scene", "props" to "prop")) {
                val arr = obj[key] as? kotlinx.serialization.json.JsonArray ?: continue
                for (e in arr) {
                    val o = e as? kotlinx.serialization.json.JsonObject ?: continue
                    fun str(k: String): String =
                        (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim() ?: ""
                    val name = str("name")
                    if (name.isEmpty()) continue
                    out += Asset(kind, name, str("desc"))
                }
            }
            out
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * 合并策略：LLM结果与正则结果按 kind+小写name 去重合并（LLM优先带desc，正则补漏），
     * 上限各 kind 12 条防爆量。
     */
    fun merge(llmAssets: List<Asset>, regexNames: List<Triple<String, String, Unit>>): List<Asset> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Asset>()
        for (a in llmAssets) {
            val k = "${a.kind}:${a.name.lowercase()}"
            if (k in seen) continue
            seen += k; out += a
        }
        return out.groupBy { it.kind }.flatMap { (_, list) -> list.take(12) }
    }
}
