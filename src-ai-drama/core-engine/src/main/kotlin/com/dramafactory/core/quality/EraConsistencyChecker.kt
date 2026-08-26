package com.dramafactory.core.quality

/**
 * 时代红线闸口（对齐 pavo consistency_checker._check_era_consistency）。
 *
 * 扫描每个镜头视觉字段（action / frame_start / frame_end）与台词/旁白字段，
 * 命中 era 禁词即报：
 * - 视觉字段命中 → error（意味着错乱会真的渲染进视频）；
 * - 台词/旁白命中 → warn（角色可「讨论」现代概念而不上镜）。
 *
 * 按场景 / 剧集放行（v0.6.0）：本镜放行权从禁词表剔除后再扫描（[StylePreset.effectiveForbidden]）。
 * 匹配按完整禁用词（如「钟表」「手机」「塑料」），不按松散子串，故「钟声」等符合时代词不误伤。
 * ASCII 词条词边界匹配（避免 car⊂card / gun⊂begun 误报）。
 */
object EraConsistencyChecker {

    data class Issue(val severity: String, val code: String, val shotId: String?, val message: String)

    data class Report(val ok: Boolean, val issues: List<Issue>) {
        val errors get() = issues.filter { it.severity == "error" }
        val warnings get() = issues.filter { it.severity == "warn" }
    }

    /**
     * @param shots 镜头列表（map 形状，含 action/frame_start/frame_end/dialogue/narration/shot_direction/scene_id）
     * @param forbidden 禁词表（已按本镜放行剔除后的实际禁词）
     * @param shotAllowed 本镜放行权（直接从未剔除的禁词表里去掉）
     */
    fun check(
        shots: List<Map<String, Any?>>,
        forbidden: List<String>,
        shotAllowed: List<String> = emptyList(),
    ): Report {
        if (forbidden.isEmpty()) return Report(true, emptyList())
        val effective = forbidden.filter { it !in shotAllowed.toSet() }
        if (effective.isEmpty()) return Report(true, emptyList())

        val visualFields = mapOf("action" to "error", "frame_start" to "error", "frame_end" to "error")
        val spokenFields = mapOf("shot_direction" to "warn", "narration" to "warn")
        val issues = mutableListOf<Issue>()

        for (shot in shots) {
            val sid = shot["shot_id"]?.toString()
            val probes = mutableListOf<Pair<String, String>>()
            for ((fld, sev) in visualFields + spokenFields) {
                val v = shot[fld]
                if (v is String && v.isNotBlank()) probes.add(v to sev)
            }
            // dialogue
            val dlg = shot["dialogue"]
            val dlgLines = if (dlg is List<*>) dlg else (if (dlg != null) listOf(dlg) else emptyList())
            for (d in dlgLines) {
                val txt = when (d) {
                    is Map<*, *> -> d["text"]?.toString() ?: d["line"]?.toString() ?: ""
                    is String -> d
                    else -> ""
                }
                if (txt.isNotBlank()) probes.add(txt to "warn")
            }
            for ((text, sev) in probes) {
                val hit = effective.filter { StoryboardGate.termInText(it, text) }
                if (hit.isNotEmpty()) {
                    issues.add(Issue(sev, "era_anachronism", sid,
                        "疑似跨时代/现代元素 $hit 命中镜头文本，违反时代红线" +
                            if (shotAllowed.isNotEmpty()) "（本镜已放行：$shotAllowed）" else ""))
                }
            }
        }
        return Report(ok = issues.none { it.severity == "error" }, issues = issues)
    }
}
