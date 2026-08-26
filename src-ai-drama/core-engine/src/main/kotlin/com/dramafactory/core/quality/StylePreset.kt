package com.dramafactory.core.quality

/**
 * 时代红线 Style Preset（对齐 pavo style_cinema.json 的 era 块 + global_negative_prompt）。
 *
 * 单一事实源 = [StylePreset.era]，提供：
 * 1. [negativePrompt] 折叠进所有生成 prompt 的 negative_prompt；
 * 2. [positiveSuffix] 折叠进所有生成 prompt 的 suffix；
 * 3. [forbiddenEraTerms] 供 consistency-checker 做时代闸口扫描（视觉字段 error / 台词 warn）；
 * 4. 按场景 / 剧集放行 [allowedCrossEra]（MVP 先存字段 + 校验透传）。
 *
 * 默认内置「西汉末年至新莽时期（约公元1世纪—公元23年）」红线（对齐 style_cinema.json）。
 * 若要其他朝代/架空设定，仅替换 era 块即可，代码零改动。
 */
data class StylePreset(
    /** 预设名，如 "cinema" */
    val name: String = "cinema",
    /** 全局 suffix（cinematic 等） */
    val globalPromptSuffix: String = DEFAULT_GLOBAL_SUFFIX,
    /** 全局 negative（杂斑/水印/文字等） */
    val globalNegativePrompt: List<String> = DEFAULT_GLOBAL_NEGATIVE,
    /** era 块：红线核心 */
    val era: EraSpec = EraSpec(),
    /** 角色 6 姿态定义（B 子模块用） */
    val characterPoses: List<PoseSpec> = defaultCharacterPoses(),
) {

    /** 角色资产生成尺寸（1024x1024 正方形，对齐 pavo v0.9.8） */
    val characterImageSize: String = "1024x1024"
    /** 场景尺寸 */
    val sceneImageSize: String = "1024x768"
    /** 道具母图尺寸 */
    val propImageSize: String = "768x768"

    /** 折叠后的 negative prompt（global + era.negative） */
    val negativePrompt: String get() = (globalNegativePrompt + era.negative).distinct().joinToString(", ")

    /** 折叠后的 positive suffix（global + era.positive） */
    val positiveSuffix: String get() = listOf(globalPromptSuffix, era.positive).filter { it.isNotBlank() }.joinToString(" ")

    /** 时代红线禁词（供闸口扫描） */
    val forbiddenEraTerms: List<String> get() = era.negative

    /** 该预设默认放行权为空（全禁）；按场景/剧集独立声明。 */
    fun resolveAllowed(shotAllowed: List<String> = emptyList(), sceneAllowed: List<String> = emptyList(), episodeAllowed: List<String> = emptyList()): Set<String> =
        (shotAllowed + sceneAllowed + episodeAllowed).toSet()

    /** 把某镜放行权从禁词表剔除，返回该镜实际禁词（供一致性闸口 + 生成负向约束）。 */
    fun effectiveForbidden(shotAllowed: List<String> = emptyList(), sceneAllowed: List<String> = emptyList(), episodeAllowed: List<String> = emptyList()): List<String> {
        val allowed = resolveAllowed(shotAllowed, sceneAllowed, episodeAllowed)
        return if (allowed.isEmpty()) forbiddenEraTerms else forbiddenEraTerms.filter { it !in allowed }
    }

    /**
     * 生成 prompt 时挂上 era 约束（仅正向内容）。
     * ★第十一轮修复（角色生成负面词全当正面用的根因）：旧实现把 [negative] 禁词表
     * 拼进返回的正面 prompt 尾部，图像模型将"手机、塑料、现代招牌"等全部当成了要画的主体，
     * 导致时代红线全面踩线。现返回值只含正向描述；禁词一律经 [ImageGenRequest.negativePrompt]
     * 走独立的 negative_prompt API 通道。
     */
    fun withEraConstraints(basePrompt: String, allowed: List<String> = emptyList()): String {
        val pos = positiveSuffix
        val allowedNote = if (allowed.isNotEmpty())
            "。本镜依剧本设定允许出现跨时代器物：${allowed.joinToString("、")}；其余仍须符合西汉风貌" else ""
        return buildString {
            append(basePrompt)
            if (pos.isNotBlank()) append("。$pos")
            append(allowedNote)
        }
    }

    /**
     * 某次生成应使用的完整 negative_prompt（global + era禁词 − 放行项）。
     * 与 [withEraConstraints] 配对使用：正负分离，各走各的API字段。
     */
    fun negativePromptFor(allowed: List<String> = emptyList()): String =
        (globalNegativePrompt + effectiveForbidden(allowed)).distinct().joinToString(", ")

    /** 默认「西汉末年至新莽」红线（对齐 style_cinema.json era 块）。 */
    data class EraSpec(
        val label: String = "西汉末年至新莽时期（约公元1世纪—公元23年）",
        val positive: String = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），所有人物、服饰、建筑、器物、场景必须严格符合该时代风貌：汉代衣冠（深衣、曲裾、直裾、冠巾）、木构与夯土建筑、简牍竹简、青铜/漆木/陶器、自然光与火烛照明；无电力、无工业、无现代器物。",
        val negative: List<String> = DEFAULT_ERA_NEGATIVE,
    )

    /** 姿态定义（B 子模块：角色 DNA 6 姿态资产包）。 */
    data class PoseSpec(
        val key: String,
        /** 中文构图指令（注入 prompt） */
        val cn: String,
        /** 英文构图指令（注入 prompt） */
        val en: String,
        /** 是否情绪特写（expression_*） */
        val expression: Boolean = false,
    )

    companion object {
        const val DEFAULT_GLOBAL_SUFFIX = "cinematic, shallow depth of field, naturalistic lighting, 24fps motion cadence, professional color grading, vertical 9:16 framing"
        val DEFAULT_GLOBAL_NEGATIVE = listOf(
            "deformed", "mismatched identity", "different actor", "costume change",
            "modern watermark", "text overlay", "low quality", "motion blur on subject",
            "color bleeding", "signature", "speckles", "noise", "lens flare", "color blotches",
            "watermark", "text", "sticker overlay",
            "彩点", "噪点", "光斑", "彩色杂斑", "水印", "文字", "表情包贴纸",
        )
        // 对齐 style_cinema.json era.negative（西汉红线完整禁词表）
        val DEFAULT_ERA_NEGATIVE = listOf(
            "现代物品", "当代物品", "modern object", "contemporary item",
            "塑料", "塑料制品", "plastic", "plastic product",
            "玻璃幕墙", "钢化玻璃高楼", "glass curtain wall",
            "眼镜", "太阳镜", "glasses", "sunglasses",
            "手表", "钟表", "watch", "clock",
            "手机", "电话", "智能手机", "mobile phone", "telephone", "smartphone",
            "电线杆", "电缆", "输电线路", "电塔", "utility pole", "power line", "transmission tower",
            "电灯", "路灯", "灯泡", "electric light", "street lamp", "light bulb",
            "机动车", "汽车", "摩托车", "motor vehicle", "car", "automobile", "motorcycle",
            "霓虹灯", "霓虹招牌", "neon", "neon sign",
            "现代招牌", "广告牌", "高楼大厦", "摩天楼", "modern signage", "billboard", "skyscraper",
            "拉丁字母", "阿拉伯数字", "英文标识", "Latin letters", "Arabic numerals", "English text",
            "空调", "空调外机", "air conditioner",
            "监控摄像头", "摄像头", "security camera", "surveillance camera",
            "现代服装", "西装", "高跟鞋", "运动鞋", "现代制服", "modern clothing", "suit", "high heels", "sneakers",
            "二维码", "条形码", "屏幕", "显示器", "电视", "QR code", "barcode", "screen", "monitor", "television",
            "不锈钢家电", "瓷砖", "水泥", "水泥路", "不锈钢", "stainless steel appliance", "ceramic tile", "cement", "asphalt",
            "modern",
            "shorts", "short skirt", "短裤", "短裙",
            "vernier caliper", "游标卡尺",
            "jeans", "牛仔裤",
            "T-shirt", "T恤",
            "相机",
            "headphones", "耳机",
            "laptop", "电脑", "computer",
            "gun", "firearm", "枪械", "手枪", "步枪", "冲锋枪", "机关枪",
            "train", "火车",
            "airplane", "飞机",
            "refrigerator", "冰箱",
            "microwave", "微波炉",
            "radio", "收音机",
            "无人机",
            "tie", "领带",
            "skateboard", "滑板",
            "electric fan", "电扇",
            "battery", "电池",
            "gasoline", "汽油",
            "concrete", "混凝土",
            "microphone", "麦克风",
            "speaker", "音箱",
        )

        /** 6 姿态定义（对齐 style_cinema.json per_type_overrides.character.poses + pose_templates）。 */
        fun defaultCharacterPoses(): List<PoseSpec> = listOf(
            PoseSpec("front_anchor",
                "正面标准大头照，人脸占画面60-70%，正面平视，光线均匀（主锚点资产）",
                "front-facing close-up portrait, face as the focal anchor, face occupying 60-70% of frame, eye-level, even lighting"),
            PoseSpec("side_45",
                "45度侧脸胸像，五官轮廓清晰，人脸占画面50-70%，光线均匀",
                "45-degree three-quarter side face bust portrait, clear facial features and jawline, face occupying 50-70% of frame, even lighting"),
            PoseSpec("full_body_riding",
                "全身骑马立绘，完整盔甲服饰，固定马匹/装备样式，面部可见，光线均匀",
                "full-body riding portrait on horse, complete armor and costume, fixed horse and equipment design, face visible, even lighting"),
            PoseSpec("expression_serious", "情绪特写-严肃：眉头微蹙，目光沉凝，嘴唇紧抿",
                "face close-up, expression: serious, furrowed brows, steady gaze, pressed lips", expression = true),
            PoseSpec("expression_angry", "情绪特写-发怒：眉头紧锁，怒目圆睁，咬肌紧绷，嘴角下压",
                "face close-up, expression: angry, knitted brows, glaring eyes, clenched jaw, downturned mouth", expression = true),
            PoseSpec("expression_calm", "情绪特写-平静：神情平和，目光沉稳，嘴角自然微合",
                "face close-up, expression: calm, composed expression, steady eyes, relaxed mouth", expression = true),
        )

        /** 默认西汉预设单例。 */
        val HAN_DEFAULT = StylePreset()

        /** 从 style_cinema.json 兼容 dict 构造（MVP 仅取 era 块，其余用默认值）。 */
        fun fromJson(json: Map<String, Any?>): StylePreset {
            val eraMap = json["era"] as? Map<String, Any?> ?: emptyMap<String, Any?>()
            val neg = (eraMap["negative"] as? List<*>)?.mapNotNull { it?.toString() } ?: DEFAULT_ERA_NEGATIVE
            val era = EraSpec(
                label = eraMap["label"]?.toString() ?: "西汉末年至新莽时期（约公元1世纪—公元23年）",
                positive = eraMap["positive"]?.toString() ?: DEFAULT_ERA_NEGATIVE.let { "" },
                negative = neg,
            )
            val gNeg = (json["global_negative_prompt"] as? List<*>)?.mapNotNull { it?.toString() } ?: DEFAULT_GLOBAL_NEGATIVE
            val gSuf = json["global_prompt_suffix"]?.toString() ?: DEFAULT_GLOBAL_SUFFIX
            return StylePreset(globalPromptSuffix = gSuf, globalNegativePrompt = gNeg, era = era)
        }
    }
}
