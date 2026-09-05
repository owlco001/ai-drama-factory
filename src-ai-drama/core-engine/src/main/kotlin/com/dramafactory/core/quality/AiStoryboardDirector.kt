package com.dramafactory.core.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 第十轮：AI 编剧 + AI 导演 文字管线。
 * 第十五轮：注入项目已审批资产清单(asset snapshot)，要求 LLM 用 asset_id 引用而非自由发挥，
 *          解决「分镜与资产图严重不符」——LLM 拿不到角色/场景/道具的 prompt 描述就凭空编。
 *
 * - 编剧（storyboard）：剧本文本 + 资产目录 → 大模型拆成镜头表。每镜含：
 *   shot_no / action / dialogue（逐字）/ narration / duration_seconds / characterNames /
 *   assetIds（引用的真实 asset_id 列表）/ beatRef。
 * - 导演（visual）：基于编剧产物 + 资产名 → 生成 visual_prompt。
 *
 * 两次 LLM 调用均要求严格 JSON 输出；解析容错 + 重试≤3；
 * 结果过 StoryboardGate 六铁律校验，错误镜标记但不阻塞其余镜落库。
 * TextProvider 注入可测（App层接 AgnesProvider.chat）。
 */
object AiStoryboardDirector {

    /** 注入给 LLM 的资产快照（仅含必要字段，不泄漏整张表） */
    data class AssetSnapshot(
        val id: String,
        val kind: String,         // character / scene / prop
        val name: String,         // 角色/场景/道具名（中文）
        val description: String,  // 详细描述（用于 LLM 理解但不会原样写入分镜）
    )

    /** 一镜的完整产出（编剧+导演合并视图） */
    data class Shot(
        val shotNo: Int,
        val action: String,
        val dialogue: String? = null,
        val narration: String? = null,
        val durationSeconds: Double = 6.0,
        val characterNames: List<String> = emptyList(),
        /** 第十五轮：本镜引用的真实 asset_id 列表（来自 catalog 注入），渲染时据此拉图入锁脸 */
        val assetIds: List<String> = emptyList(),
        val beatRef: String? = null,
        val carryOver: String? = null,
        /** 导演视觉指令（运镜/景别/构图） */
        val visualPrompt: String? = null,
    )

    /**
     * v1.9.17：资产引用统计——诊断「分镜没引用资产」到底断在哪一环。
     * - catalogSize=0：目录本身为空（资产没生图等）→ LLM 无从引用；
     * - catalogSize>0 且 rawRefs=0：LLM 压根没输出 asset_ids（指令未跟随）；
     * - rawRefs>0 且 keptRefs=0：LLM 引用了但 id 都不在目录内（幻觉）→ 被静默丢弃。
     */
    data class RefStats(
        val catalogSize: Int,
        val rawRefs: Int,
        val keptRefs: Int,
    ) {
        val droppedRefs: Int get() = (rawRefs - keptRefs).coerceAtLeast(0)
    }

    data class Result(
        val shots: List<Shot>,
        val usedLlm: Boolean,
        val gateErrors: Map<Int, List<String>>,
        val refStats: RefStats = RefStats(0, 0, 0),
    )

    private const val WRITER_PROMPT = """你是短剧分镜编剧。把给定的剧本/小说片段拆成视频镜头表。
只输出严格 JSON，不要markdown代码块。格式：
{"shots":[{"shot_no":1,"action":"画面中发生的具体动作（30字内，纯动作描述，禁止出现光线/色调/氛围词）","dialogue":"该镜台词原文（无台词则省略）","narration":"旁白（无则省略）","duration_seconds":6,"characters":["角色名"],"asset_ids":["a_xxx","a_yyy"],"beat_ref":"B01"}]}
规则：
- 每镜5-10秒；一场戏2-5镜；台词必须与原文逐字一致不得改写；shot_no从1连续递增；总镜数控制在4-12镜。
- 资产引用：剧本中出现的每个角色/场景/道具，必须且只能从下方【资产目录】的 asset_id 中挑选并写入 asset_ids；不要自己造新名。若该镜无明显角色/场景/道具，asset_ids 可为 []。
- action 中引用角色时使用资产目录中的"名字"（中文），便于人工对账。"""

    private const val DIRECTOR_PROMPT = """你是短剧摄影导演。为每个镜头写一条中文视觉指令（visual字段）。
只输出严格 JSON：{"visuals":[{"shot_no":1,"visual":"景别+运镜+构图，20-40字"}]}
要求：只描述机位语言（如"近景缓推，人物居左，背景纵深虚化"）；严禁出现光线、色调、天气、氛围词汇；不要重复画面内容。
asset_ids 已锁定：写 visual 时必须考虑该镜引用的资产（角色长相/场景/道具），visual_prompt 描述应与资产描述一致（如"近景缓推张角道长"而非"近景缓推一古装男子"）。"""

    /**
     * 编剧+导演两段式生成。
     * @param chat 文本模型调用
     * @param script 剧本文本（截断6000字）
     * @param assets 项目已审批的资产目录（可空，空时回退到原行为，asset_ids 全空）
     */
    suspend fun generate(
        script: String,
        chat: suspend (com.dramafactory.core.model.ChatRequest) -> com.dramafactory.core.model.ChatResponse,
        assets: List<AssetSnapshot> = emptyList(),
    ): Result {
        val clipped = if (script.length > 6000) script.take(6000) + "\n…(后文略)" else script
        val catalogBlock = renderCatalog(assets)

        // —— 编剧：拆镜 ——
        // v1.9.17：parseShots 额外返回引用统计；空列表仍触发重试（对齐原 repeatRetry 语义）
        val parsed: Pair<List<Shot>, RefStats>? = repeatRetry {
            val writerMsg = if (catalogBlock.isNotBlank()) {
                "$WRITER_PROMPT\n\n【资产目录】\n$catalogBlock\n\n【剧本】\n$clipped"
            } else {
                "$WRITER_PROMPT\n\n【剧本】\n$clipped"
            }
            val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                com.dramafactory.core.model.ChatMessage("user", writerMsg))))
            val p = parseShots(resp.content, assets)
            if (p.first.isEmpty()) null else p
        }
        val shots = parsed?.first ?: return Result(emptyList(), usedLlm = false, gateErrors = emptyMap(),
            refStats = RefStats(assets.size, 0, 0))
        val refStats = parsed.second

        // —— 导演：视觉指令 ——
        val visuals: Map<Int, String> = runCatching {
            val brief = shots.joinToString("\n") { s ->
                val assetNames = s.assetIds.mapNotNull { id -> assets.firstOrNull { it.id == id }?.let { "${it.kind}:${it.name}" } }
                val tail = if (assetNames.isNotEmpty()) " [资产：${assetNames.joinToString("、")}]" else ""
                "镜头${s.shotNo}：${s.action}$tail"
            }
            val directorMsg = if (catalogBlock.isNotBlank()) {
                "$DIRECTOR_PROMPT\n\n【资产目录】\n$catalogBlock\n\n$brief"
            } else {
                "$DIRECTOR_PROMPT\n\n$brief"
            }
            repeatRetry {
                val resp = chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                    com.dramafactory.core.model.ChatMessage("user", directorMsg))))
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
            gateErrors = gateErrors,
            refStats = refStats)
    }

    /** 把资产目录渲染成 LLM 友好的文本块 */
    private fun renderCatalog(assets: List<AssetSnapshot>): String {
        if (assets.isEmpty()) return ""
        return assets.joinToString("\n") { a ->
            // 只给 LLM 必要信息：id / 种类 / 名 / 描述。description 限制 200 字内避免超长
            val desc = if (a.description.length > 200) a.description.take(200) + "…" else a.description
            "- id=${a.id} | kind=${a.kind} | name=${a.name} | desc=${desc}"
        }
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

    /**
     * 第十五轮：解析 shots；asset_ids 限定为 catalog 里的 id（防止 LLM 幻觉乱写），
     * 非 catalog 的 id 丢弃（不入 shot.assetIds 也不报错）。
     */
    internal fun parseShots(content: String, assets: List<AssetSnapshot> = emptyList()): Pair<List<Shot>, RefStats> {
        val empty = emptyList<Shot>()
        val obj = jsonOf(content) ?: return empty to RefStats(assets.size, 0, 0)
        val arr = obj["shots"] as? JsonArray ?: return empty to RefStats(assets.size, 0, 0)
        val validIds = assets.map { it.id }.toSet()
        val out = mutableListOf<Shot>()
        var rawRefs = 0
        var keptRefs = 0
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            fun str(k: String): String = (o[k] as? JsonPrimitive)?.content?.trim() ?: ""
            val no = (o["shot_no"] as? JsonPrimitive)?.content?.toIntOrNull() ?: (out.size + 1)
            val action = str("action")
            if (action.isEmpty()) continue
            val chars = (o["characters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            val rawAssetIds = (o["asset_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            val assetIds = if (validIds.isEmpty()) emptyList()
                else rawAssetIds.filter { it in validIds }.distinct()
            rawRefs += rawAssetIds.size
            keptRefs += assetIds.size
            out += Shot(
                shotNo = no, action = action,
                dialogue = str("dialogue").ifBlank { null },
                narration = str("narration").ifBlank { null },
                durationSeconds = (o["duration_seconds"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 6.0,
                characterNames = chars,
                assetIds = assetIds,
                beatRef = str("beat_ref").ifBlank { null },
                carryOver = str("carry_over").ifBlank { null })
        }
        return out.sortedBy { it.shotNo } to RefStats(assets.size, rawRefs, keptRefs)
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
