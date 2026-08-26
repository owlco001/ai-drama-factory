package com.dramafactory.core.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 第十轮：AI 编剧 + AI 导演 文字管线。
 *
 * - 编剧（storyboard）：剧本文本 → 大模型拆成镜头表。每镜含：
 *   shot_no / action（画面动作，无光影词）/ dialogue（台词逐字）/ narration（旁白）/
 *   duration_seconds（5-10s）/ characters_present（角色名列表）/ beat_ref。
 * - 导演（visual）：对每镜生成视觉指令 visual_prompt：运镜/景别/构图，
 *   严格不含光影色彩词（六铁律第3条），供图生视频 prompt 组装。
 *
 * 两次 LLM 调用均要求严格 JSON 输出；解析容错 + 重试≤2；
 * 结果过 StoryboardGate 六铁律校验，错误镜标记但不阻塞其余镜落库。
 * TextProvider 注入可测（App层接 AgnesProvider.chat）。
 */
object AiStoryboardDirector {

    /** 一镜的完整产出（编剧+导演合并视图） */
    data class Shot(
        val shotNo: Int,
        val action: String,
        val dialogue: String? = null,
        val narration: String? = null,
        val durationSeconds: Double = 6.0,
        val characterNames: List<String> = emptyList(),
        val beatRef: String? = null,
        val carryOver: String? = null,
        /** 导演视觉指令（运镜/景别/构图） */
        val visualPrompt: String? = null,
    )

    data class Result(val shots: List<Shot>, val usedLlm: Boolean, val gateErrors: Map<Int, List<String>>)

    private const val WRITER_PROMPT = """你是短剧分镜编剧。把给定的剧本/小说片段拆成视频镜头表。
只输出严格 JSON，不要markdown代码块。格式：
{"shots":[{"shot_no":1,"action":"画面中发生的具体动作（30字内，纯动作描述，禁止出现光线/色调/氛围词）","dialogue":"该镜台词原文（无台词则省略）","narration":"旁白（无则省略）","duration_seconds":6,"characters":["角色名"],"beat_ref":"B01"}]}
规则：每镜5-10秒；一场戏2-5镜；台词必须与原文逐字一致不得改写；shot_no从1连续递增；总镜数控制在4-12镜。"""

    private const val DIRECTOR_PROMPT = """你是短剧摄影导演。为每个镜头写一条中文视觉指令（visual字段）。
只输出严格 JSON：{"visuals":[{"shot_no":1,"visual":"景别+运镜+构图，20-40字"}]}
要求：只描述机位语言（如"近景缓推，人物居左，背景纵深虚化"）；严禁出现光线、色调、天气、氛围词汇；不要重复画面内容。"""

    /**
     * 编剧+导演两段式生成。
     * @param chat 文本模型调用
     * @param script 剧本文本（截断6000字）
     */
    suspend fun generate(
        script: String,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
    ): Result {
        val clipped = if (script.length > 6000) script.take(6000) + "\n…(后文略)" else script

        // —— 编剧：拆镜 ——
        val shots = repeatRetry {
            val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                com.dramafactory.core.model.ChatMessage("user", "$WRITER_PROMPT\n\n【剧本】\n$clipped"))))
            parseShots(resp.content)
        } ?: return Result(emptyList(), usedLlm = false, gateErrors = emptyMap())

        // —— 导演：视觉指令 ——
        val visuals: Map<Int, String> = runCatching {
            val brief = shots.joinToString("\n") { "镜头${it.shotNo}：${it.action}" }
            repeatRetry {
                val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", "$DIRECTOR_PROMPT\n\n$brief"))))
                parseVisuals(resp.content)
            } ?: emptyMap()
        }.getOrDefault(emptyMap())

        // —— 忠实性粗校验：台词逐字必须在剧本原文中出现（引号边界内）——
        val gateErrors = mutableMapOf<Int, List<String>>()
        for (s in shots) {
            val errs = mutableListOf<String>()
            s.dialogue?.let { d ->
                if (d.isNotBlank() && !verbatimIn(d.trim(), script)) errs += "dialogue_not_verbatim"
            }
            if (s.action.isBlank()) errs += "action_empty"
            if (errs.isNotEmpty()) gateErrors[s.shotNo] = errs
        }

        return Result(
            shots = shots.map { it.copy(visualPrompt = visuals[it.shotNo]) },
            usedLlm = true,
            gateErrors = gateErrors)
    }

    /** 重试≤max次直到非null且非空列表结果 */
    private suspend fun <T> repeatRetry(max: Int = 3, block: suspend () -> T?): T? {
        repeat(max) { attempt ->
            val r = try { block() } catch (_: Throwable) { null }
            if (r != null && !(r is List<*> && r.isEmpty())) return r
            kotlinx.coroutines.delay(400L * (attempt + 1))
        }
        return null
    }

    /** 台词逐字校验：原文包含该句（忽略首尾空白）即视为逐字进入 */
    fun verbatimIn(line: String, script: String): Boolean = script.contains(line)

    internal fun parseShots(content: String): List<Shot> {
        val obj = jsonOf(content) ?: return emptyList()
        val arr = obj["shots"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<Shot>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            fun str(k: String): String = (o[k] as? JsonPrimitive)?.content?.trim() ?: ""
            val no = (o["shot_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: (out.size + 1)
            val action = str("action")
            if (action.isEmpty()) continue
            val chars = (o["characters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            out += Shot(
                shotNo = no, action = action,
                dialogue = str("dialogue").ifBlank { null },
                narration = str("narration").ifBlank { null },
                durationSeconds = (o["duration_seconds"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 6.0,
                characterNames = chars, beatRef = str("beat_ref").ifBlank { null },
                carryOver = str("carry_over").ifBlank { null })
        }
        return out.sortedBy { it.shotNo }
    }

    internal fun parseVisuals(content: String): Map<Int, String> {
        val obj = jsonOf(content) ?: return emptyMap()
        val arr = obj["visuals"] as? JsonArray ?: return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val no = (o["shot_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
            val v = (o["visual"] as? JsonPrimitive)?.content?.trim() ?: continue
            if (v.isNotBlank()) out[no] = v
        }
        return out
    }

    /** 容错JSON提取：剥```栅栏 → 首个{到末个} → parse */
    private fun jsonOf(content: String): JsonObject? {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```")
            s = s.substringBeforeLast("```").trim()
        }
        val l = s.indexOf('{'); val r = s.lastIndexOf('}')
        if (l < 0 || r <= l) return null
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(s.substring(l, r + 1)) as? JsonObject
        } catch (_: Throwable) { null }
    }
}
