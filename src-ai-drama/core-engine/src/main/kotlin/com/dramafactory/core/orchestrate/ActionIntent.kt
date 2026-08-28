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

/** 从 AI 回复文本里解析所有 [ACT] 指令（容错：坏行跳过不抛） */
fun parseActions(text: String): List<ActionIntent> {
    val out = mutableListOf<ActionIntent>()
    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (!line.startsWith(ActionIntent.MARK)) continue
        val body = line.removePrefix(ActionIntent.MARK).trim()
        if (body.isBlank()) continue
        val parts = body.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        val verb = parts[0]
        if (verb.isBlank()) continue
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
    return out
}

/** 已知的动作 verb（用于系统 prompt 提示 LLM，覆盖全部可调控功能） */
val KNOWN_ACTIONS: List<String> = listOf(
    "new_project", "open_project", "set_script",
    "extract_assets", "generate", "stop_generate", "remove_asset", "edit_asset",
    "review_pass", "review_all_pass", "build_pose_pack", "set_cross_era", "list_assets",
    "gen_shots", "render", "compose_film", "run_pipeline", "goto",
)
