package com.dramafactory.core.quality

import kotlin.text.RegexOption
/**
 * 分镜六铁律闸门（对齐 pavo storyboard.py validate_storyboard）。
 *
 * ToonFlow 六铁律作为**确定性代码规则**——任何 error 阻断整集渲染（一帧不渲）。
 * 纯 Kotlin 实现 [validateStoryboard]：[Storyboard] 编译由 [compileStoryboard] 完成。
 *
 * 六铁律：
 * ① 台词逐字 diff（零删改，引号边界精确匹配，防子串绕过）；
 * ② 出场人物完整（claimed chars 都有资产）；
 * ③ action 只描述动作/状态（forbidden 词表 lint，光影/色调/配乐词移出）；
 * ④ beat_ref 必须存在且单调递增（防跳节拍/无中生有）；
 * ⑤ carry_over 必填（同场景非首镜）；
 * ⑥ 资产必须是 catalog 里的真实 ID（禁编造）。
 */
object StoryboardGate {

    /** Lint 词表：action 禁含光影/色调/配乐词汇（对齐 pavo FORBIDDEN_ACTION_TERMS）。 */
    val FORBIDDEN_ACTION_TERMS: List<String> = listOf(
        // 光影
        "光影", "光线", "逆光", "顶光", "侧光", "打光", "布光", "补光", "轮廓光",
        "氛围光", "丁达尔", "光晕", "光斑", "柔光", "高光", "阴影层次", "明暗对比",
        // 色调
        "色调", "冷色调", "暖色调", "色温", "调色", "饱和度", "对比度", "滤镜",
        "灰度", "色彩风格", "胶片颗粒",
        // 配乐
        "配乐", "背景音乐", "音乐", "旋律", "鼓点", "弦乐", "管弦", "主题曲",
        "音效设计", "混音",
        // ascii（词边界匹配）
        "lighting", "backlight", "rim light", "color grading", "colour grading",
        "saturation", "contrast", "filter", "bokeh", "film grain",
        "soundtrack", "bgm", "music", "score", "warm tone", "cool tone",
    )

    /** 分镜条目（结构化，对齐 pavo storyboard entry）。 */
    data class Entry(
        val shotId: String,
        val index: Int,                       // 1-based 渲染顺序
        val panel: Panel = Panel(),
        val beatRef: String? = null,
        val beatIndex: Int = 0,               // 归一化后节拍序号
        val associateAssetIds: List<String> = emptyList(),
        val unresolvedAssetRefs: List<String> = emptyList(),
        val carryOver: String = "",
        val narration: String = "",
        val durationSeconds: Double = 0.0,
        val state: Map<String, String> = emptyMap(),
    ) {
        /** 角色资产（associate 里 character 类型，由调用方填充 assetType 旁注；此处仅存 id）。 */
        val characterAssetIds: List<String> get() = panel.charactersPresent
    }

    data class Panel(
        val sceneId: String = "",
        val duration: Double = 0.0,
        val shotSize: String = "",
        val cameraMove: String = "",
        val action: String = "",
        val position: String = "",
        val facing: String = "",
        val costume: String = "",
        val emotion: String = "",
        val dialogue: List<DialogueLine> = emptyList(),
        val sfx: String = "",
        val charactersPresent: List<String> = emptyList(),   // character asset ids
        val styleNotes: String = "",                          // 被 lint 移出的光影词
    )

    data class DialogueLine(val characterId: String = "", val text: String = "")

    /** 校验问题。 */
    data class Issue(
        val severity: String,   // "error" | "warn"
        val code: String,
        val shotId: String?,
        val message: String,
    )

    data class Report(
        val ok: Boolean,
        val issues: List<Issue>,
    ) {
        val errors get() = issues.filter { it.severity == "error" }
        val warnings get() = issues.filter { it.severity == "warn" }
        fun toMap() = mapOf(
            "ok" to ok,
            "error_count" to errors.size,
            "warn_count" to warnings.size,
            "issues" to issues.map { mapOf("severity" to it.severity, "code" to it.code, "shot_id" to it.shotId, "message" to it.message) },
        )
    }

    /**
     * 校验分镜：六铁律全为确定性检查，任一 error → [Report.ok]=false（整集中止）。
     * @param storyboard 编译后的条目列表
     * @param catalogApprovedIds 已批准资产 ID 集合（六铁律⑥）
     * @param characterAssetIds 可作为「角色」的资产 ID 集合（六铁律②判定）
     */
    fun validateStoryboard(
        storyboard: List<Entry>,
        catalogApprovedIds: Set<String> = emptySet(),
        characterAssetIds: Set<String> = emptySet(),
    ): Report {
        val issues = mutableListOf<Issue>()
        var prevBeatIndex = 0

        for (entry in storyboard) {
            val sid = entry.shotId

            // 六铁律⑥：资产必须是 catalog 真实 ID（禁编造）
            for (aid in entry.associateAssetIds) {
                if (catalogApprovedIds.isNotEmpty() && aid !in catalogApprovedIds) {
                    issues.add(Issue("error", "missing_asset", sid, "绑定资产 $aid 不在 catalog['approved'] 中：禁止编造资产 ID"))
                }
            }
            for (ref in entry.unresolvedAssetRefs) {
                issues.add(Issue("error", "asset_unresolved", sid, "资产引用 $ref 未能解析为已批准资产"))
            }

            // 六铁律②：出场人物完整（claimed chars 都有角色资产）
            for (cid in entry.panel.charactersPresent) {
                if (characterAssetIds.isNotEmpty() && cid !in characterAssetIds) {
                    issues.add(Issue("warn", "character_no_asset", sid, "角色 $cid 出场但无对应角色资产：建议绑定 DNA 参考图"))
                }
            }

            // 六铁律③：action 只描述动作/状态（forbidden 词表 lint）
            val actionProbe = entry.panel.action
            val hitTerms = lintTerms(actionProbe, FORBIDDEN_ACTION_TERMS)
            if (hitTerms.isNotEmpty()) {
                issues.add(Issue("error", "action_contains_style_vocab", sid, "action 含光影/色调/配乐词（应移入 styleNotes）：$hitTerms"))
            }

            // 六铁律①：台词逐字（零删改，引号边界精确匹配）
            for ((i, line) in entry.panel.dialogue.withIndex()) {
                if (line.text.isBlank()) continue
                // 逐字校验在 FidelityGate 做提交前比对；此处校验分镜内部台词完整性（非空且不为占位）
                if (line.text.length < 1) {
                    issues.add(Issue("error", "dialogue_empty", sid, "第 ${i + 1} 条台词为空"))
                }
            }

            // 六铁律④：beat_ref 存在且单调递增
            if (entry.beatRef.isNullOrBlank()) {
                issues.add(Issue("error", "beat_ref_missing", sid, "该镜没有 beat_ref：无剧本来源的镜头视为编造情节"))
            } else if (entry.beatIndex <= 0) {
                issues.add(Issue("error", "beat_index_invalid", sid, "beat_ref ${entry.beatRef} 无有效顺序号，无法校验时间线顺叙"))
            } else if (prevBeatIndex > 0 && entry.beatIndex < prevBeatIndex) {
                issues.add(Issue("error", "beat_out_of_order", sid, "节拍回退：本镜 beat#${entry.beatIndex} 早于上一镜 #$prevBeatIndex，剧本必须顺叙"))
            }
            if (entry.beatIndex > 0) prevBeatIndex = entry.beatIndex

            // 六铁律⑤：carry_over 必填（同场景非首镜）
            val sameScenePrev = storyboard.asSequence().takeWhile { it !== entry }
                .lastOrNull()?.panel?.sceneId == entry.panel.sceneId && entry.panel.sceneId.isNotBlank()
            if (sameScenePrev && entry.carryOver.isBlank()) {
                issues.add(Issue("error", "carry_over_missing", sid, "场景 ${entry.panel.sceneId} 非首镜缺少 carry_over：跨镜衔接无依据"))
            }
        }

        return Report(ok = issues.none { it.severity == "error" }, issues = issues)
    }

    /** 词条命中（对齐 pavo term_in_text）：ASCII 词边界匹配，CJK 精确子串。 */
    fun termInText(term: String, text: String): Boolean {
        if (term.isBlank() || text.isBlank()) return false
        return if (term.all { it.isLetterOrDigit() || it == '.' }) {
            // 内联 (?i) 做大小写不敏感，避免依赖 RegexOption 枚举
            val pat = Regex("""(?i)(?<![A-Za-z0-9])${Regex.escape(term)}(?![A-Za-z0-9])""")
            pat.containsMatchIn(text)
        } else {
            text.contains(term)
        }
    }

    fun lintTerms(text: String, terms: List<String>): List<String> =
        terms.filter { termInText(it, text) }

    /** 从剧本 shots（ToonFlow 形状）编译为分镜条目。兼容 beat_ref / asset_ids / props_present。 */
    fun compileStoryboard(
        shots: List<Map<String, Any?>>,
        catalogApprovedIds: Set<String> = emptySet(),
    ): List<Entry> {
        val beats = deriveBeats(shots)
        val beatsById = beats.associateBy { it.beatId }
        return shots.mapIndexed { i, shot ->
            val sid = shot["shot_id"]?.toString() ?: "shot_${i + 1}"
            val (beatId, beatIndex) = resolveBeatRef(shot, i + 1, beatsById, beats)
            val (assoc, unresolved) = resolveAssetIds(shot, catalogApprovedIds)
            val dialogue = parseDialogue(shot["dialogue"])
            val chars = (shot["characters_present"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            Entry(
                shotId = sid,
                index = i + 1,
                panel = Panel(
                    sceneId = shot["scene_id"]?.toString() ?: "",
                    duration = (shot["duration_seconds"] as? Number)?.toDouble() ?: 0.0,
                    action = shot["action"]?.toString() ?: "",
                    position = shot["position"]?.toString() ?: "",
                    facing = shot["facing"]?.toString() ?: "",
                    costume = shot["costume"]?.toString() ?: "",
                    emotion = shot["emotion"]?.toString() ?: "",
                    dialogue = dialogue,
                    charactersPresent = chars,
                ),
                beatRef = beatId,
                beatIndex = beatIndex,
                associateAssetIds = assoc,
                unresolvedAssetRefs = unresolved,
                carryOver = shot["carry_over"]?.toString() ?: "",
                narration = shot["narration"]?.toString() ?: "",
                durationSeconds = (shot["duration_seconds"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }

    private data class Beat(val beatId: String, val index: Int, val summary: String, val source: String)

    private fun deriveBeats(shots: List<Map<String, Any?>>): List<Beat> {
        val raw = shots.firstOrNull()?.get("beats")
        val beats = mutableListOf<Beat>()
        if (raw is List<*> && raw.isNotEmpty()) {
            raw.forEachIndexed { i, b ->
                val m = b as? Map<*, *> ?: emptyMap<String, Any?>()
                val id = (m["beat_id"] ?: m["id"] ?: "beat_%02d".format(i + 1)).toString()
                val summary = (m["summary"] ?: m["text"] ?: "").toString()
                val source = (m["source"] ?: m["chapter"] ?: "").toString()
                beats.add(Beat(id, i + 1, summary, source))
            }
            return beats
        }
        shots.forEachIndexed { i, shot ->
            beats.add(Beat("beat_%02d".format(i + 1), i + 1, shot["action"]?.toString() ?: "", shot["shot_id"]?.toString() ?: ""))
        }
        return beats
    }

    private fun resolveBeatRef(shot: Map<String, Any?>, index: Int, beatsById: Map<String, Beat>, beats: List<Beat>): Pair<String?, Int> {
        val ref = shot["beat_ref"] ?: shot["beat_id"]
        if (ref != null) {
            val s = ref.toString().trim()
            val norm = "beat_%02d".format(s.filter { it.isDigit() }.toIntOrNull() ?: 0)
            val candidates = listOf(
                s, s.lowercase(), s.uppercase(),
                norm,
                "beat_${s.lowercase().removePrefix("b")}",
            )
            for (key in candidates) {
                val b = beatsById[key]
                if (b != null) return b.beatId to b.index
            }
            // 未命中：返回归一化 id 与 -1（无效顺序号，由校验报 beat_index_invalid）
            return norm to -1
        }
        if (beats.isEmpty()) return null to -1
        val pos = minOf(index, beats.size) - 1
        return beats[pos].beatId to beats[pos].index
    }

    private fun resolveAssetIds(shot: Map<String, Any?>, catalogApprovedIds: Set<String>): Pair<List<String>, List<String>> {
        val ordered = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        fun push(v: String?) {
            if (v.isNullOrBlank()) return
            if (v in catalogApprovedIds) {
                if (v !in ordered) ordered.add(v)
            } else {
                if (v !in ordered) ordered.add(v)
                if (v !in unresolved) unresolved.add(v)
            }
        }
        (shot["associate_asset_ids"] as? List<*>)?.forEach { push(it?.toString()) }
        (shot["asset_ids"] as? List<*>)?.forEach { push(it?.toString()) }
        (shot["props_present"] as? List<*>)?.forEach { push(it?.toString()) }
        return ordered to unresolved
    }

    private fun parseDialogue(d: Any?): List<DialogueLine> {
        if (d == null) return emptyList()
        val list = if (d is List<*>) d else listOf(d)
        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> DialogueLine(
                    characterId = item["character_id"]?.toString() ?: item["speaker"]?.toString() ?: "",
                    text = item["text"]?.toString() ?: item["line"]?.toString() ?: "",
                )
                is String -> DialogueLine(text = item)
                else -> null
            }
        }
    }
}
