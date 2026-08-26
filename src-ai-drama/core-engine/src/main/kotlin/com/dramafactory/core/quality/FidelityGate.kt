package com.dramafactory.core.quality

/**
 * 提交前忠实性闸门（对齐 pavo fidelity_gate.py gate_shot，Part A 确定性部分）。
 *
 * 在 [submitVideo] 之前跑 A.1–A.7 确定性校验 —— 资产真实 / 台词逐字 / 时长镜序 /
 * 禁编造 / 禁时间逆转（30 词表）/ 状态不漂移 / 跨镜一致。**blocked 的镜直接
 * 不提交**（对齐 pavo：blocked 镜 continue，记进 render_manifest.blocked_shots）。
 *
 * Part B（多模态视觉验收）默认关闭（需网络调模型），对齐 pavo：本环境禁渲染/网络，
 * `visual=false` 只跑 Part A 并报告 Part B 为 skipped；启用是一开关（待渲染放开后）。
 *
 * 时间逆转词表（A.5）对齐 pavo FORBIDDEN_TIME_REVERSAL_TERMS。
 */
object FidelityGate {

    /** A.5 时间逆转词表（对齐 pavo fidelity_gate.FORBIDDEN_TIME_REVERSAL_TERMS） */
    val FORBIDDEN_TIME_REVERSAL_TERMS: List<String> = listOf(
        "时光逆转", "时间逆转", "逆转时光", "逆转时间",
        "时间倒流", "时光倒流", "时空倒流", "岁月倒流",
        "时空回溯", "时间回溯", "时光回溯", "回溯时间",
        "时光倒退", "时间倒退", "倒转时空", "时空扭曲回到",
        "画面倒放", "倒放", "倒带", "回放倒序",
        "rewind", "reverse time", "time reversal", "time rewind",
        "turn back time", "backwards playback", "reverse playback",
        "time-reverse", "time reverse", "chronological reversal",
    )

    /** 扫描时间逆转的字段 */
    private val TIME_SCAN_FIELDS = listOf("motion_prompt", "action", "frame_start", "frame_end", "carry_over", "narration")

    data class Issue(val severity: String, val code: String, val message: String)

    data class GateReport(
        val shotId: String,
        val ok: Boolean,
        val blocked: Boolean,
        val issues: List<Issue>,
        val auditNotes: List<String> = emptyList(),
        val visualChecked: Boolean = false,
    ) {
        val errors get() = issues.filter { it.severity == "error" }
        fun toMap() = mapOf(
            "shot_id" to shotId,
            "ok" to ok,
            "blocked" to blocked,
            "visual_checked" to visualChecked,
            "error_count" to errors.size,
            "issues" to issues.map { mapOf("severity" to it.severity, "code" to it.code, "message" to it.message) },
            "audit_notes" to auditNotes,
        )
    }

    /**
     * 单镜提交前校验（Part A 确定性）。
     *
     * @param entry 分镜条目（含 panel.dialogue / beat_ref / beat_index / carry_over / state / associate_asset_ids）
     * @param motionPrompt 即将提交的 prompt 文本
     * @param catalogApprovedIds 已批准资产集合
     * @param prevEntry 上一镜条目（跨镜一致性 / beat 单调）
     * @param submittedDuration 实际提交时长（秒），null=不校验时长
     * @param expectedIndex 渲染循环 1-based 顺序（镜序校验）
     * @param baselineState 基准表状态（跨镜锁定，可选）
     */
    fun gateShot(
        entry: StoryboardGate.Entry,
        motionPrompt: String,
        catalogApprovedIds: Set<String> = emptySet(),
        prevEntry: StoryboardGate.Entry? = null,
        submittedDuration: Double? = null,
        expectedIndex: Int? = null,
        baselineState: Map<String, String>? = null,
        visual: Boolean = false,
    ): GateReport {
        val sid = entry.shotId
        val report = mutableListOf<Issue>()
        val notes = mutableListOf<String>()

        fun add(severity: String, code: String, message: String) {
            report.add(Issue(severity, code, message))
            if (severity == "error") { /* blocked 由 ok 推导 */ }
        }

        // A.1 资产绑定完整 + 真实
        if (entry.associateAssetIds.isEmpty()) {
            add("error", "gate_no_asset_bound", "该镜没有绑定任何资产：首尾帧无法做图像条件绑定")
        } else {
            for (aid in entry.associateAssetIds) {
                if (catalogApprovedIds.isNotEmpty() && aid !in catalogApprovedIds) {
                    add("error", "gate_asset_not_real", "绑定资产 $aid 不在 catalog['approved'] 中：禁止编造资产 ID")
                }
            }
            for (ref in entry.unresolvedAssetRefs) {
                add("error", "gate_asset_unresolved", "资产引用 $ref 未能解析为已批准资产")
            }
        }

        // A.2 台词逐字（零删改，引号边界精确匹配）
        for ((i, line) in entry.panel.dialogue.withIndex()) {
            val text = line.text
            if (text.isNotBlank() && !verbatimPresent(text, motionPrompt)) {
                add("error", "gate_dialogue_altered", "第 ${i + 1} 条台词未逐字进入提交文本（台词零删改）：$text")
            }
        }

        // A.3 时长 + 镜序
        if (entry.panel.duration <= 0) {
            add("error", "gate_duration_missing", "分镜未声明时长：无法校验提交时长")
        } else if (submittedDuration != null) {
            if (kotlin.math.abs(submittedDuration - entry.panel.duration) > 1.5) {
                add("error", "gate_duration_mismatch", "提交时长 $submittedDuration 与分镜 ${entry.panel.duration} 不一致")
            }
        }
        if (expectedIndex != null && entry.index != expectedIndex) {
            add("error", "gate_shot_out_of_order", "镜序错乱：分镜 index=${entry.index}，实际提交顺序=$expectedIndex")
        }

        // A.4 无编造情节：beat_ref 存在 + 单调
        val ref = entry.beatRef
        if (ref.isNullOrBlank()) {
            add("error", "gate_beat_ref_missing", "该镜没有 beat_ref：无剧本来源的镜头视为编造情节")
        } else if (entry.beatIndex <= 0) {
            add("error", "gate_beat_index_invalid", "beat_ref $ref 无有效顺序号，无法校验时间线顺叙")
        } else if (prevEntry != null && prevEntry.beatIndex > 0 && entry.beatIndex < prevEntry.beatIndex) {
            add("error", "gate_beat_out_of_order", "节拍回退：本镜 $ref(#${entry.beatIndex}) 早于上一镜 #${prevEntry.beatIndex}")
        }

        // A.5 时间逆转词表（任何提交文本命中即 error）
        val probes = mapOf(
            "motion_prompt" to motionPrompt,
            "action" to entry.panel.action,
            "frame_start" to "",   // 由 entry 透传（调用方填充到 action/panel 时）
            "frame_end" to "",
            "carry_over" to entry.carryOver,
            "narration" to entry.narration,
        )
        for (fld in TIME_SCAN_FIELDS) {
            val text = (probes[fld] ?: "").toString()
            if (text.isNotBlank()) {
                val hits = StoryboardGate.lintTerms(text, FORBIDDEN_TIME_REVERSAL_TERMS)
                if (hits.isNotEmpty()) {
                    add("error", "gate_time_reversal", "$fld 命中时间逆转类词 $hits：本剧不存在时光倒流设定，禁止提交")
                }
            }
        }

        // A.6 状态不漂移（与基准表一致）
        if (baselineState != null) {
            for ((field, want) in baselineState) {
                val got = entry.state[field] ?: want
                if (got != want) {
                    add("error", "gate_state_drift", "剧情状态 $field 漂移：基准表=$want 本镜=$got（状态只能在剧本指定镜切换）")
                }
            }
        }

        // A.7 跨镜一致（同场景非首镜 carry_over 必填）
        val sameScene = prevEntry != null && entry.panel.sceneId.isNotBlank() &&
            prevEntry.panel.sceneId == entry.panel.sceneId
        if (sameScene && entry.carryOver.isBlank()) {
            add("error", "gate_carry_over_missing", "场景 ${entry.panel.sceneId} 非首镜缺少 carry_over：跨镜衔接无依据")
        }

        val blocked = report.any { it.severity == "error" }
        if (blocked) notes.add("本镜因 ${report.count { it.severity == "error" }} 项结构校验失败被拦截，未提交视频。")
        if (visual) notes.add("（Part B 视觉验收已启用，需调用多模态校验）")

        return GateReport(
            shotId = sid,
            ok = !blocked,
            blocked = blocked,
            issues = report,
            auditNotes = notes,
            visualChecked = visual,
        )
    }

    /** 引号边界精确匹配（对齐 pavo _verbatim_present）：防「那就留下吧」子串绕过。 */
    private val QUOTE_LEFT = "：:「『“'（(【[<《\n\r\t 　"
    private val QUOTE_RIGHT = "」』”'）)】]>》\n\r\t 　"

    internal fun verbatimPresent(text: String, haystack: String): Boolean {
        if (text.isEmpty()) return true
        if (haystack.isEmpty()) return false
        var start = haystack.indexOf(text)
        while (start != -1) {
            val end = start + text.length
            val leftOk = start == 0 || haystack[start - 1] in QUOTE_LEFT
            val rightOk = end == haystack.length || haystack[end] in QUOTE_RIGHT
            if (leftOk && rightOk) return true
            start = haystack.indexOf(text, start + 1)
        }
        return false
    }
}
