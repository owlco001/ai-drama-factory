package com.dramafactory.app.ui

import com.dramafactory.app.data.AssetEntity

/**
 * 分镜生成用的「资产目录」单一构造器。
 *
 * v1.7.17：此前 StoryboardViewModel 与 AppGraph 各自手写了一遍
 * `assetsAllOf(projectId).map { AssetSnapshot(...) }`，两处规则不同步，且都把
 * 全部资产不筛不滤地塞给 LLM，导致三个真问题：
 *   1. 参考图子卡（front_bust / side_45_right / profile_side / front_full_body …）跟母卡一起进目录，
 *      LLM 看到 5 张同名"张角"随手引一张侧脸/半身当首帧 → 跨镜长相与构图漂移；
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
     * - 排除子卡：parent_id 非空 或 pose_role 非空（参考图套装只保留母卡）；
     * - 排除无图卡：自身无图 **且** 其参考图子卡也无图（v1.7.21 放宽：母卡没生成图
     *   但参考图套装已出图时，该角色仍应进目录——否则用户在资产页点了「参考图」
     *   却没单独生成母卡图，这个角色就彻底从目录里消失、一镜都不会引用它）；
     * - 排除判了重生成的卡：review_state == "regen"（用户已否决这张图）；
     * - kind 归一化：仅保留 character / scene / prop / local，未知值当 local。
     */
    fun build(rows: List<AssetEntity>): List<com.dramafactory.core.quality.AiStoryboardDirector.AssetSnapshot> {
        // v1.7.21：「参考图已出图」的母卡——这些母卡即使自身无图也该进目录
        val mothersBackedByRefs = rows.asSequence()
            .filter { !it.parent_id.isNullOrBlank() && it.review_state != "regen" && uriOf(it) != null }
            .mapNotNull { it.parent_id }
            .toSet()
        return rows.asSequence()
            .filter { it.asset_id.isNotBlank() }
            .filter { it.parent_id.isNullOrBlank() && it.pose_role.isNullOrBlank() }
            .filter { uriOf(it) != null || it.asset_id in mothersBackedByRefs }
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
    }

    /**
     * 参考图注入优先级（v1.7.21）。
     *
     * `front_bust` 是锁脸基准（人脸占画面约 ⅓），必须排第一；`side_45_right` 解决转头崩脸。
     * 正侧面与全身用于补充体型与侧对镜头，优先级靠后。
     */
    private val REFERENCE_PRIORITY = listOf(
        "front_bust", "side_45_right", "profile_side", "front_full_body")

    /** 单个角色最多注入的参考图张数——多角色项目里要给场景卡留位置。 */
    private const val MAX_REF_PER_CHARACTER = 2

    /** 取某张卡片可用的图片 URI（远端优先，本地兜底）；无图返回 null。 */
    private fun uriOf(row: AssetEntity): String? =
        row.remote_url?.takeIf { it.isNotBlank() } ?: row.image_uri?.takeIf { it.isNotBlank() }

    /**
     * v1.7.21：把「本镜引用的资产 id」解析成实际要注入的参考图 URI 列表。
     *
     * 旧实现（内联在 ViewModels.setAssetImageResolver 里）只做 `rows.filter { asset_id in refIds }`，
     * 按 id 精确匹配、不展开子卡，于是两个后果：
     *  1. v1.7.20 生成的 4 张参考图**一张都进不了渲染**——花 4 张图的额度做参考图套装，
     *     结果锁脸靠的还是母卡那一张图，参考图完全是死资产；
     *  2. 目录只给母卡（防止 LLM 引到侧脸），所以 refIds 里永远是母卡 id，
     *     渲染永远只取到 1 张图。
     *
     * 本实现：引用母卡时，优先取该母卡的参考图（按锁脸优先级，单个角色最多 2 张），
     * 参考图不足时再补母卡自身的图。场景/道具卡无子卡，直接取自身图。
     *
     * @param refIds 本镜 first_asset_ids（LLM 按目录引用的 id，都是母卡）
     * @param limit 注入上限（视频模型 input_images 配额）
     */
    fun resolveRefUris(
        rows: List<AssetEntity>,
        refIds: List<String>,
        limit: Int = 4,
    ): List<String> {
        if (refIds.isEmpty()) return emptyList()
        // 已被用户判「重生成」的子卡不参与锁脸（那张图已被否决）
        val childrenOf = rows
            .filter { !it.parent_id.isNullOrBlank() && it.review_state != "regen" }
            .groupBy { it.parent_id }
        val out = LinkedHashSet<String>()
        for (id in refIds) {
            if (out.size >= limit) break
            val row = rows.firstOrNull { it.asset_id == id } ?: continue
            var taken = 0
            // 1) 参考图子卡（锁脸优先级顺序）
            for (key in REFERENCE_PRIORITY) {
                if (taken >= MAX_REF_PER_CHARACTER || out.size >= limit) break
                val child = childrenOf[id]?.firstOrNull { it.pose_role == key } ?: continue
                val uri = uriOf(child) ?: continue
                if (out.add(uri)) taken++
            }
            // 2) 卡片自身的图：参考图不足时补位（母卡被判重生成则跳过）
            if (taken < MAX_REF_PER_CHARACTER && out.size < limit && row.review_state != "regen") {
                uriOf(row)?.let { out.add(it) }
            }
        }
        return out.take(limit)
    }

    /**
     * v1.7.21：项目级兜底——**仅在本镜完全没有引用时**启用。
     *
     * 旧实现只要按引用取不到图就回退「项目级 character/scene 前 4 张」，
     * 在多角色项目里等于把**别的角色**的脸当参考注入进来，直接串脸——
     * 比不注入更糟。故多角色项目一律不兜底：宁可该镜不锁脸，也不能锁错人。
     */
    fun fallbackUris(rows: List<AssetEntity>, limit: Int = 4): List<String> {
        val mothers = rows.filter {
            it.parent_id.isNullOrBlank() && it.pose_role.isNullOrBlank() && it.review_state != "regen"
        }
        if (mothers.count { normalizeKind(it.kind) == "character" } > 1) return emptyList()
        return mothers
            .filter { val k = normalizeKind(it.kind); k == "character" || k == "scene" }
            .mapNotNull { uriOf(it) }
            .distinct()
            .take(limit)
    }

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
     * 2. 「张角，正面立姿（front anchor）」→ 逗号前的部分（参考图子卡格式）；
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
