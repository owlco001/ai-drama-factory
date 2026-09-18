package com.dramafactory.core.orchestrate

/**
 * T014 智能体指令：AI 大脑控制 APP 的结构化动作。
 *
 * 设计：对话不拘格式（用户/AI 都自由说人话），但 AI 在需要真正操作 App 时，
 * 在回复正文之后附一行机器可读标记（用户无感，UI 解析后执行并回显）：
 *   [ACT] <verb> | key=value | key=value
 * 例：
 *   [ACT] set_cross_era | allowed=手机,眼镜,手表
 *   [ACT] generate | assetId=char_001
 *   [ACT] remove_asset | assetId=char_002
 *   [ACT] edit_asset | assetId=char_003 | prompt=穿红衣的少女
 *   [ACT] stop_generate | assetId=char_001
 *   [ACT] review_pass | assetId=char_001
 *   [ACT] build_pose_pack | characterId=char_001
 *   [ACT] list_assets
 *
 * 解析器 [parseActions] 从一段文本里抽出所有 [ACT] 行，返回 List<ActionIntent>。
 */
data class ActionIntent(
    val verb: String,
    val params: Map<String, String>,
) {
    fun param(key: String): String? = params[key]
    fun paramList(key: String): List<String> =
        param(key)?.split(",")?.map { it.trim() }.orEmpty().filter { it.isNotEmpty() }

    companion object {
        const val MARK = "[ACT]"
    }
}

/** 从 AI 回复文本里解析所有 [ACT] 指令（容错：坏行跳过不抛）。
 * v1.9.35：仅对带长文本参数（script/text）的剧本类指令做跨行延续——
 * `[ACT] test_drama | script=第一幕…` 后的非 [ACT] 行拼进 script 值，
 * 避免 AI 自动生成的多行长剧本被截短；其余指令不拼（防叙述行混入）。 */
fun parseActions(text: String): List<ActionIntent> {
    val out = mutableListOf<ActionIntent>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val rawLine = lines[i]
        val line = rawLine.trim()
        if (!line.startsWith(ActionIntent.MARK)) { i++; continue }
        val body = line.removePrefix(ActionIntent.MARK).trim()
        if (body.isBlank()) { i++; continue }
        val verb = body.substringBefore('|').trim()
        // 剧本类动词：后续非 [ACT] 行作为长值参数延续
        val continuation = if (verb in SCRIPT_VERBS) {
            val buf = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && !lines[j].trim().startsWith(ActionIntent.MARK)) {
                if (lines[j].trim().isNotBlank()) buf += lines[j]
                j++
            }
            i = j - 1  // 循环末尾 i++ 落到下一条 [ACT]
            buf
        } else emptyList()
        val fullBody = if (continuation.isEmpty()) body
            else body + "\n" + continuation.joinToString("\n")
        parseActionLine(fullBody, out)
        i++
    }
    return out
}

/** 带长文本参数（script/text）的剧本类动词：支持跨行延续 */
private val SCRIPT_VERBS = setOf("test_drama", "set_script", "run_pipeline")

/** 解析单条 [ACT] body（verb + key=value），追加进 out。 */
private fun parseActionLine(body: String, out: MutableList<ActionIntent>) {
    val parts = body.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return
    val verb = parts[0]
    if (verb.isBlank()) return
    val params = mutableMapOf<String, String>()
    for (kv in parts.drop(1)) {
        val eq = kv.indexOf('=')
        if (eq <= 0) continue
        val k = kv.substring(0, eq).trim()
        val v = kv.substring(eq + 1).trim()
        if (k.isNotEmpty()) params[k] = v
    }
    out.add(ActionIntent(verb = verb, params = params))
}

/** 已知的动作 verb（用于系统 prompt 提示 LLM，覆盖全部可调控功能）。
 * P0 动作可靠性：未知 verb 在信封解码层 fail-closed（BLOCKED），这里必须是白名单全集。 */
val KNOWN_ACTIONS: List<String> = listOf(
    "new_project", "open_project", "set_script", "test_drama",
    "extract_assets", "generate", "stop_generate", "remove_asset", "edit_asset",
    "review_pass", "review_all_pass", "build_pose_pack", "set_cross_era", "list_assets",
    "gen_shots", "render", "render_status", "render_pause", "render_resume",
    "compose_film", "run_pipeline", "model_status", "goto",
)
