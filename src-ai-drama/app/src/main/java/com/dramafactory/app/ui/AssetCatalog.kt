package com.dramafactory.app.ui

import com.dramafactory.app.data.AssetEntity

/**
 * 分镜生成用的「资产目录」单一构造器。
 *
 * v1.7.17：此前 StoryboardViewModel 与 AppGraph 各自手写了一遍
 * `assetsAllOf(projectId).map { AssetSnapshot(...) }`，两处规则不同步，且都把
 * 全部资产不筛不滤地塞给 LLM，导致三个真问题：
 *   1. 6 姿态子卡（front_anchor / side_45 / expression_angry …）跟母卡一起进目录，
 *      LLM 看到 7 张同名"张角"随手引一张侧脸/怒容当首帧 → 跨镜长相与姿态漂移；
 *   2. 还没生图的资产（remote_url 与 image_uri 都空）也进目录，LLM 照引不误，
 *      渲染时 mapNotNull 把无图资产静默丢掉 → 该镜参考图为空、完全不锁脸，用户无感知；
 *   3. name 直接 substringBefore("：")，prompt 里没冒号时 name = 整段描述，
 *      目录里 name 与 desc 重复且超长，LLM 难以在 action 里稳定引用。
 *
 * 这里把构造规则收敛成一处，两边共用。
 */
object AssetCatalog {

    /** 目录项显示名最大长度（超出截断，避免把整段描述当名字） */
    private const val NAME_MAX = 12

    /**
     * 构造给 LLM 的资产目录。
     *
     * 过滤规则（按顺序）：
     * - 排除子卡：parent_id 非空 或 pose_role 非空（6 姿态包只保留母卡）；
     * - 排除无图卡：remote_url 与 image_uri 均为空（还没生图，引用了也拿不到参考图）；
     * - 排除判了重生成的卡：review_state == "regen"（用户已否决这张图）；
     * - kind 归一化：仅保留 character / scene / prop / local，未知值当 local。
     */
    fun build(rows: List<AssetEntity>): List<com.dramafactory.core.quality.AiStoryboardDirector.AssetSnapshot> =
        rows.asSequence()
            .filter { it.asset_id.isNotBlank() }
            .filter { it.parent_id.isNullOrBlank() && it.pose_role.isNullOrBlank() }
            .filter { !it.remote_url.isNullOrBlank() || !it.image_uri.isNullOrBlank() }
            .filter { it.review_state != "regen" }
            .map { row ->
                com.dramafactory.core.quality.AiStoryboardDirector.AssetSnapshot(
                    id = row.asset_id,
                    kind = normalizeKind(row.kind),
                    name = displayName(row.prompt),
                    description = row.prompt,
                )
            }
            .toList()

    /** kind 归一化为 character / scene / prop / local，未知值按 local 处理。 */
    fun normalizeKind(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "character" -> "character"
        "scene" -> "scene"
        "prop" -> "prop"
        else -> "local"
    }

    /**
     * 把镜头引用的资产 id 列表序列化进 shots.first_asset_ids。
     *
     * v1.7.17：旧实现是 `joinToString(",", "[", "]")`，产出 `[a_1,a_2]` —— 不带引号，
     * 不是合法 JSON，任何用 JSON 库读这个字段的地方（导出/调试/后续 web 端）都会炸。
     * 现按标准 JSON 数组写出；[parseRefIds] 仍兼容无引号的历史数据。
     */
    fun encodeRefIds(ids: List<String>): String =
        ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .joinToString(",", "[", "]") { "\"${it.replace("\"", "")}\"" }

    /**
     * 解析 shots.first_asset_ids。
     * 容错三件事：带引号的标准 JSON、历史无引号数据、以及外层不是数组的裸字符串。
     */
    fun parseRefIds(raw: String?): List<String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "[]") return emptyList()
        val body = if (s.startsWith("[")) s.removePrefix("[").removeSuffix("]") else s
        return body.split(",", "，")
            .map { it.trim().trim('"').trim('\'') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * 从 prompt 里提取用于展示与引用的短名字。
     *
     * 依次尝试：
     * 1. 「名字：描述」/「名字: 描述」→ 冒号前的部分（清单行落库格式）；
     * 2. 「张角，正面立姿（front anchor）」→ 逗号前的部分（姿态子卡格式）；
     * 3. 都没有 → 整段截断到 NAME_MAX 字。
     * 任何一步结果为空或过长都继续降级，保证 name 永不为空。
     */
    fun displayName(prompt: String?): String {
        val raw = prompt?.trim().orEmpty()
        if (raw.isEmpty()) return "未命名"
        val byColon = raw.substringBefore("：").substringBefore(":").trim()
        if (byColon.isNotBlank() && byColon.length <= NAME_MAX) return byColon
        val byComma = raw.substringBefore("，").substringBefore(",").trim()
        if (byComma.isNotBlank() && byComma.length <= NAME_MAX) return byComma
        return if (byColon.isNotBlank()) byColon.take(NAME_MAX) else raw.take(NAME_MAX)
    }
}
