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
    /**
     * 角色参考图套装（v1.7.20）。
     *
     * 取代旧的「6 姿态资产包」（正面锚点 / 45 度侧脸 / 全身骑马 / 三种情绪特写）——
     * 那是「单张图里摆造型」的思路，而图生视频（Kling / 即梦 / Pika）真正需要的是
     * **4 张彼此独立、纯白底、平视、中性表情的多角度参考图**，且严禁拼在一张画布上
     * （多数视频模型对拼图 / 多小人识别失败，会直接导致锁脸失效）。
     *
     * 正规工程三视图（正交：正 / 侧 / 背全身的 CRS 设定板）只在数字人、LoRA 训练、
     * 高精度长镜头才需要，短剧流水线不启用，避免算力浪费与画风漂移。
     */
    val referenceShots: List<ReferenceShotSpec> = defaultReferenceSheet(),

    /**
     * 参考图通用硬性规范（v1.7.20）——每张参考图都必须叠加，与具体角度无关。
     * 对应《图生视频·角色参考图标准》的「必须遵守」清单。
     */
    val referenceCommonPositive: String = DEFAULT_REFERENCE_COMMON_POSITIVE,
    /** 参考图通用禁忌（v1.7.20）——「踩坑禁忌」清单：拼图、小人脸、逆光、遮眼、夸张表情、水印等。 */
    val referenceCommonNegative: List<String> = DEFAULT_REFERENCE_COMMON_NEGATIVE,
    /** 参考图精简禁词（v1.7.21）——进图像 prompt 的那一份，见 [DEFAULT_REFERENCE_CORE_FORBIDDEN]。 */
    val referenceCoreForbidden: List<String> = DEFAULT_REFERENCE_CORE_FORBIDDEN,
    /**
     * 角色棚拍无干扰背景约束（T014 任务1：用户要求角色生成用纯色/无背景模式，
     * 与场景/道具解耦，便于后续跨镜合成与绿幕抠图）。
     * 正向：纯色背景、影棚布景、主体孤立、无环境。
     * 负向：场景/环境/家具/杂物等干扰元素。
     */
    val studioBackdropPositive: String = "plain solid color background, seamless studio backdrop, isolated subject, no environment, no scenery, no props around, character reference sheet, centered subject, even soft studio lighting, sharp focus on the character",
    val studioBackdropNegative: List<String> = listOf(
        "scene background", "environment", "landscape", "furniture", "props around",
        "complex background", "outdoor", "interior setting", "decorated room",
        "background scenery", "detailed background", "busy background", "gradient background",
        "room interior", "crowd", "bokeh background", "pedestal", "tabletop", "multiple objects",
        "场景背景", "环境", "风景", "家具", "杂物", "复杂背景", "室外", "室内陈设",
    ),
    /**
     * v1.7.17：角色资产专用正向 suffix，替代 [globalPromptSuffix]。
     *
     * 原实现角色卡也套 globalPromptSuffix（cinematic / shallow depth of field /
     * naturalistic lighting / vertical 9:16 framing），这些全是场景化、环境化语义，
     * 与「纯色无干扰背景」正面对冲——模型为了满足 cinematic 打光与 9:16 构图，
     * 会自动补出环境与陈设，角色卡背景始终清不干净。
     * 角色资产本就是给 i2i 锁脸用的参考图，要的是干净底 + 主体清晰，不是电影感画面。
     */
    val characterStudioSuffix: String = DEFAULT_CHARACTER_STUDIO_SUFFIX,
    /**
     * v1.7.19：主体版 era 正向（角色 / 道具专用）。
     *
     * [EraSpec.positive] 原文要求「人物、服饰、建筑、器物、场景必须严格符合…木构与夯土建筑、
     * 简牍竹简、青铜/漆木/陶器」——这些建筑与环境语义会强迫模型补出背景与陈设，
     * 与角色棚拍底、道具纯色底正面对冲，这才是角色卡与道具卡背景清不干净的真正根因
     * （v1.7.17 只调了 suffix 顺序，红线正文仍在喊「画建筑画场景」）。
     * 本版只约束主体自身（服饰 / 器物形制与材质 / 照明），并显式声明不描绘建筑与环境。
     */
    val eraPositiveSubjectOnly: String = DEFAULT_ERA_POSITIVE_CHARACTER,
    /**
     * v1.7.21：角色专用 era 正向（[eraPositiveSubjectOnly] 的实质实现）。
     *
     * v1.7.19 版虽已剥离「建筑、场景」，但仍保留了「简牍竹简、青铜/漆木/陶器、
     * 自然光与火烛照明」——器物与火光会诱导模型在人物周围补道具与光影，
     * 这是角色卡背景清不干净的最后一环。本版只约束人物自身的服饰形制与材质。
     */
    val eraPositiveCharacter: String = DEFAULT_ERA_POSITIVE_CHARACTER,
    /**
     * v1.7.21：场景专用 era 正向。
     *
     * 关键修复：此前场景走完整 [EraSpec.positive]，其首句为「所有**人物**、服饰、
     * 建筑、器物、场景必须严格符合该时代风貌」——**正向在喊「画人物」**，
     * 与末尾的空场无人指令正面对冲，而正向语义权重更高，模型照画不误。
     * 本版剥离「人物、服饰」，只约束建筑、陈设与照明，并显式声明画面中不得有人。
     */
    val eraPositiveScene: String = DEFAULT_ERA_POSITIVE_SCENE,
    /**
     * v1.7.21：道具专用 era 正向。
     *
     * 此前道具与角色共用主体版红线，里面写着「汉代衣冠（深衣、曲裾、直裾、冠巾）」——
     * 道具卡在喊「画衣服」，等于邀请模型画一个穿衣的人物出来。
     * 本版只约束器物自身的形制、材质与工艺。
     */
    val eraPositiveProp: String = DEFAULT_ERA_POSITIVE_PROP,
    /**
     * v1.7.21：尾部强指令（tail anchor）。
     *
     * 图像模型对 prompt **尾部**权重最高，而 v1.7.19 把「纯色背景」「空场无人」
     * 这些最关键的约束放在了 prompt 中部，后面还拖着 100+ 条 Do NOT include 禁词
     * 和质量负向——指令被彻底淹没（实测角色卡禁词 106 项、场景 100 项、道具 105 项）。
     *
     * 本版把每类资产最不可妥协的一条约束抽出来，中英双语、显式标注最高优先级，
     * 压在 prompt 最末尾。
     */
    val characterTailAnchor: String = DEFAULT_CHARACTER_TAIL_ANCHOR,
    val sceneTailAnchor: String = DEFAULT_SCENE_TAIL_ANCHOR,
    val propTailAnchor: String = DEFAULT_PROP_TAIL_ANCHOR,
    /**
     * v1.7.21：资产图专用「精简禁词」——只进正向 Do NOT include 的那一份。
     *
     * 此前把 150+ 条完整时代红线全塞进图像 prompt，其中 gun / train / airplane /
     * refrigerator / skateboard / battery 之类与资产卡外观毫无关系，
     * 却在 100+ 项的噪声里把真正管用的「scene background / environment / people」
     * 稀释成了路人。实测三类卡禁词均 100+ 项，模型无法分辨优先级。
     *
     * 精简原则：只留「该类型最可能翻车」的 20 余项 = 少量高频时代词 + 该类型专用干扰词。
     * 完整 era 红线仍走视频端 negative_prompt 与一致性闸口（那两处支持长列表）。
     */
    val characterCoreForbidden: List<String> = DEFAULT_CHARACTER_CORE_FORBIDDEN,
    val sceneCoreForbidden: List<String> = DEFAULT_SCENE_CORE_FORBIDDEN,
    val propCoreForbidden: List<String> = DEFAULT_PROP_CORE_FORBIDDEN,
    /**
     * v1.7.19：场景资产专用 —— 空场、无人物。
     * 用户反馈场景卡被模型自行塞入人物：此前场景只走 [withEraConstraints]，
     * 禁词表里没有任何「人」相关项，模型默认往空景里加人。
     */
    val sceneEmptyPositive: String = DEFAULT_SCENE_EMPTY_POSITIVE,
    val sceneEmptyNegative: List<String> = DEFAULT_SCENE_EMPTY_NEGATIVE,
    /**
     * v1.7.19：道具资产专用 —— 纯色底、孤立单品、产品图式。
     * 此前道具沿用 [globalPromptSuffix]（cinematic / naturalistic lighting / vertical 9:16 framing），
     * 全是环境化语义，逼模型补场景；且道具没有任何纯色底约束，故背景干扰。
     */
    val propStudioSuffix: String = DEFAULT_PROP_STUDIO_SUFFIX,
    val propStudioPositive: String = DEFAULT_PROP_STUDIO_POSITIVE,
    val propStudioNegative: List<String> = DEFAULT_PROP_STUDIO_NEGATIVE,
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
    fun withEraConstraints(
        basePrompt: String,
        allowed: List<String> = emptyList(),
        suffixOverride: String? = null,
        eraPositiveOverride: String? = null,
    ): String {
        // suffixOverride 非空时（角色棚拍 / 道具），丢弃 globalPromptSuffix 的场景化语义，
        // 只保留 era 正向红线 + 调用方给的专用 suffix。
        // eraPositiveOverride：角色 / 道具改用主体版红线，避免红线正文里的
        // 「建筑、场景」语义诱导模型补背景（v1.7.19）。
        val eraPos = eraPositiveOverride ?: era.positive
        val pos = if (suffixOverride.isNullOrBlank()) listOf(globalPromptSuffix, eraPos).filter { it.isNotBlank() }.joinToString(" ")
                  else listOf(eraPos, suffixOverride).filter { it.isNotBlank() }.joinToString(" ")
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

    /**
     * 角色棚拍专用约束（T014 任务1）：在 era 正向约束之外，额外追加纯色无干扰背景指令，
     * 使角色资产与场景/环境解耦。仅用于 [AssetsLogic.Kind.CHARACTER] 类资产。
     */
    fun withCharacterStudioConstraints(basePrompt: String, allowed: List<String> = emptyList()): String {
        // 顺序即权重：era 红线 → 棚拍 suffix → 纯色背景指令（放在最末，模型对尾部权重最高）
        val eraConstrained = withEraConstraints(
            basePrompt, allowed,
            suffixOverride = characterStudioSuffix,
            eraPositiveOverride = eraPositiveCharacter,
        )
        return buildString {
            append(eraConstrained.trimEnd('。', '.', ' ', '　'))
            if (studioBackdropPositive.isNotBlank()) append("。${studioBackdropPositive}")
        }
    }

    /**
     * 场景专用约束（v1.7.19）：保留完整 era 正向（场景本就该有建筑与陈设）与
     * globalPromptSuffix 的 cinematic 氛围，末尾追加「空场无人」指令——
     * 模型对 prompt 尾部权重最高，把无人要求放最后才能压住它往空景里塞人的惯性。
     */
    fun withSceneConstraints(basePrompt: String, allowed: List<String> = emptyList()): String {
        val eraConstrained = withEraConstraints(
            basePrompt, allowed, eraPositiveOverride = eraPositiveScene)
        return buildString {
            // v1.7.21：era 正文以「。」结尾，直接再拼「。empty location…」会出双句号
            append(eraConstrained.trimEnd('。', '.', ' ', '　'))
            if (sceneEmptyPositive.isNotBlank()) append("。${sceneEmptyPositive}")
        }
    }

    /** 场景专用 negative：era 禁词之外叠加人物禁词。 */
    fun sceneNegativePromptFor(allowed: List<String> = emptyList()): String =
        (globalNegativePrompt + effectiveForbidden(allowed) + sceneEmptyNegative).distinct().joinToString(", ")

    /**
     * 道具专用约束（v1.7.19）：主体版 era（剥离建筑/场景）+ 道具 suffix（去 cinematic / 9:16）
     * + 纯色底与孤立单品指令（末尾，权重最高）。
     */
    fun withPropConstraints(basePrompt: String, allowed: List<String> = emptyList()): String {
        val eraConstrained = withEraConstraints(
            basePrompt, allowed,
            suffixOverride = propStudioSuffix,
            eraPositiveOverride = eraPositiveProp,
        )
        return buildString {
            append(eraConstrained.trimEnd('。', '.', ' ', '　'))
            if (propStudioPositive.isNotBlank()) append("。${propStudioPositive}")
        }
    }

    /** 道具专用 negative：era 禁词之外叠加环境 / 人物禁词。 */
    fun propNegativePromptFor(allowed: List<String> = emptyList()): String =
        (globalNegativePrompt + effectiveForbidden(allowed) + propStudioNegative).distinct().joinToString(", ")

    /**
     * 角色棚拍专用 negative（T014 任务1）：在 era 禁词之外，叠加棚拍负向干扰词。
     */
    fun studioNegativePromptFor(allowed: List<String> = emptyList()): String =
        (globalNegativePrompt + effectiveForbidden(allowed) + studioBackdropNegative).distinct().joinToString(", ")

    /**
     * v1.7.21：取该资产类型的「尾部强指令」——压在 prompt 最末尾的那一条，
     * 是该类型最不可妥协的约束（角色=纯色底只有一人 / 场景=绝对无人 / 道具=纯色底只有一物）。
     */
    fun tailAnchorFor(kind: String): String = when (kind.trim().lowercase()) {
        AssetPromptBuilder.KIND_CHARACTER -> characterTailAnchor
        AssetPromptBuilder.KIND_SCENE -> sceneTailAnchor
        AssetPromptBuilder.KIND_PROP -> propTailAnchor
        else -> ""
    }

    /**
     * v1.7.21：取该资产类型的「精简禁词」——只进图像 prompt 的 Do NOT include 段。
     *
     * 与 [studioNegativePromptFor] / [sceneNegativePromptFor] / [propNegativePromptFor]
     * 的区别：那三个是给**视频端 negative_prompt** 用的完整版（支持长列表，150+ 项无妨）；
     * 本方法是给**图像端正向**用的精简版（20 余项），避免指令稀释。
     */
    fun coreForbiddenFor(kind: String): List<String> = when (kind.trim().lowercase()) {
        AssetPromptBuilder.KIND_CHARACTER -> characterCoreForbidden
        AssetPromptBuilder.KIND_SCENE -> sceneCoreForbidden
        AssetPromptBuilder.KIND_PROP -> propCoreForbidden
        else -> DEFAULT_ERA_NEGATIVE_ASSET
    }

    /** 默认「西汉末年至新莽」红线（对齐 style_cinema.json era 块）。 */
    data class EraSpec(
        val label: String = "西汉末年至新莽时期（约公元1世纪—公元23年）",
        val positive: String = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），所有人物、服饰、建筑、器物、场景必须严格符合该时代风貌：汉代衣冠（深衣、曲裾、直裾、冠巾）、木构与夯土建筑、简牍竹简、青铜/漆木/陶器、自然光与火烛照明；无电力、无工业、无现代器物。",
        val negative: List<String> = DEFAULT_ERA_NEGATIVE,
    )

    /**
     * 参考图规格（v1.7.20）。
     *
     * 每张 spec 对应**一张独立图片**，绝不拼在一张画布上——视频模型对多小人拼图识别极差。
     *
     * @param key 落库到 assets.pose_role 的角度标识
     * @param cn 中文构图指令
     * @param en 英文构图指令（中英双语双保险，英文抑制力更强）
     * @param expression 是否情绪特写。短剧流水线默认套装全部为 false（参考图必须中性表情）；
     *                   仅专业完整版表情包使用，见 [defaultExpressionShots]。
     */
    data class ReferenceShotSpec(
        val key: String,
        /** 中文构图指令（注入 prompt） */
        val cn: String,
        /** 英文构图指令（注入 prompt） */
        val en: String,
        /** 是否情绪特写（专业版表情包用，默认套装为 false） */
        val expression: Boolean = false,
    )

    companion object {
        const val DEFAULT_GLOBAL_SUFFIX = "cinematic, shallow depth of field, naturalistic lighting, 24fps motion cadence, professional color grading, vertical 9:16 framing"
        /**
         * 角色资产专用正向 suffix（见 [StylePreset.characterStudioSuffix]）。
         * 刻意不含 cinematic / 9:16 framing / naturalistic lighting —— 那些是给视频与场景用的，
         * 用在角色卡上会逼模型补环境，与纯色棚拍底直接冲突。
         */
        const val DEFAULT_CHARACTER_STUDIO_SUFFIX = "character reference sheet style, plain neutral backdrop, even soft studio lighting, centered subject, sharp focus, subject fully visible in frame, high detail face and costume"

        /**
         * 主体版 era 正向（见 [StylePreset.eraPositiveSubjectOnly]）。
         * 保留服饰 / 器物形制 / 材质 / 照明红线，剥离「建筑、环境、场景」等会诱导模型补背景的语义，
         * 并显式声明只刻画主体本身。
         */
        @Deprecated("v1.7.21 起按资产类型分家，改用 DEFAULT_ERA_POSITIVE_CHARACTER / _SCENE / _PROP")
        const val DEFAULT_ERA_POSITIVE_SUBJECT_ONLY = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），图中主体（人物服饰、器物形制与材质）必须严格符合该时代风貌：汉代衣冠（深衣、曲裾、直裾、冠巾）、简牍竹简、青铜/漆木/陶器、自然光与火烛照明；无电力、无工业、无现代器物。仅刻画主体本身，不得描绘建筑、环境或场景。"

        /**
         * v1.7.21 角色专用 era 正向：只约束人物自身的服饰形制与材质。
         *
         * 相较 v1.7.19 版移除了「简牍竹简、青铜/漆木/陶器、自然光与火烛照明」——
         * 在角色卡里提器物与火光，模型就会在人物周围把它们画出来。
         */
        const val DEFAULT_ERA_POSITIVE_CHARACTER = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），图中人物的服饰形制、发式冠巾、面料质地必须严格符合汉代风貌（深衣、曲裾、直裾、冠巾，麻葛与丝帛）；无电力、无工业、无现代器物。只描绘这一个人本身：不得描绘任何背景、环境、建筑、陈设、器物、火光或其他角色。"

        /**
         * v1.7.21 场景专用 era 正向：只约束建筑、陈设与照明，**不再出现「人物」二字**。
         *
         * 这是「场景图里有人」的直接根因——旧版完整 era 首句写着「所有人物、服饰、
         * 建筑、器物、场景必须严格符合该时代风貌」，正向在要求画人，
         * 末尾的 no people 根本压不住。
         */
        const val DEFAULT_ERA_POSITIVE_SCENE = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），画面中的建筑形制、空间陈设与道具器物必须严格符合汉代风貌：木构与夯土建筑、简牍竹简、青铜/漆木/陶器、自然光与火烛照明；无电力、无工业、无现代器物。这是空场空镜，画面中不得出现任何人物。"

        /**
         * v1.7.21 道具专用 era 正向：只约束器物自身的形制、材质与工艺。
         *
         * 相较共用版移除了「汉代衣冠（深衣、曲裾、直裾、冠巾）」——
         * 道具卡里提衣冠，等于请模型画一个穿着汉服的人出来。
         */
        const val DEFAULT_ERA_POSITIVE_PROP = "【严格历史时代约束】本剧设定为西汉末年至新莽时期（约公元1世纪），该器物的形制、材质与工艺必须严格符合汉代风貌（简牍竹简、青铜、漆木、陶器、麻葛与丝帛）；无电力、无工业、无现代器物。只描绘这一件器物本身：不得描绘任何人物、服饰、背景、环境、建筑或其他物件。"

        // ---- v1.7.21：尾部强指令（压在 prompt 最末尾，模型尾部权重最高）----

        /** 角色：纯色底 + 画面里只有这一个人。 */
        const val DEFAULT_CHARACTER_TAIL_ANCHOR = "【画面要求·最高优先级】背景必须是纯白或纯灰的单一纯色，无任何渐变与纹理；画面中只有这一个人，没有地面、墙面、家具、器物、植物、火光或任何环境元素；人物完整居中，头顶与脚下留白。 / BACKGROUND REQUIREMENT (HIGHEST PRIORITY): single flat solid white background, absolutely no environment, no scenery, no floor, no wall, no furniture, no objects, no plants, no firelight, no light patches, no gradient, no texture; only this one character centered in frame with clean margins."

        /** 场景：绝对空场无人（正向在喊人，必须用最高优先级压回去）。 */
        const val DEFAULT_SCENE_TAIL_ANCHOR = "【画面要求·最高优先级】这是一个完全空无一人的空镜：画面中绝对不能出现任何人物、人影、剪影、面孔、手部或任何活体，只有建筑与陈设；出现任何人物即视为失败。 / EMPTY SCENE REQUIREMENT (HIGHEST PRIORITY): absolutely no people, no person, no human figure, no silhouette, no face, no hands, no living being anywhere in frame; only architecture and set dressing; the presence of any human is a failure."

        /** 道具：纯色底 + 画面里只有这一件器物，没有人手。 */
        const val DEFAULT_PROP_TAIL_ANCHOR = "【画面要求·最高优先级】背景必须是纯白或纯灰的单一纯色；画面中只有这一件器物居中且完整呈现，没有人物、手、地面、桌面、衬布、其他物件或任何环境元素。 / PROP SHOT REQUIREMENT (HIGHEST PRIORITY): single flat solid white background, only this one object centered and fully visible, no people, no hands, no floor, no table, no cloth, no other objects, no environment."

        /**
         * v1.7.21：资产图专用「高频时代禁词」——完整 era 红线里与资产外观直接相关的那部分。
         * 其余（机动车 / 火车 / 冰箱 / 滑板 / 枪械…）对资产卡外观没有意义，只对实景镜头有意义，
         * 不进图像 prompt，仍保留在视频端 negative 与一致性闸口。
         */
        val DEFAULT_ERA_NEGATIVE_ASSET = listOf(
            "modern object", "contemporary item", "modern clothing", "suit", "tie",
            "high heels", "sneakers", "jeans", "T-shirt", "shorts", "short skirt",
            "glasses", "sunglasses", "watch", "smartphone", "plastic", "neon sign",
            "modern signage", "Latin letters", "Arabic numerals",
        )

        /** 场景空场指令（见 [StylePreset.sceneEmptyPositive]）：空镜，供后续跨镜合成用。 */
        const val DEFAULT_SCENE_EMPTY_POSITIVE = "empty location, no people, no human figures, no characters, no silhouettes, no crowd, only architecture and set dressing, wide establishing shot of an empty place, no living subject in frame"
        val DEFAULT_SCENE_EMPTY_NEGATIVE = listOf(
            "people", "person", "human", "human figure", "man", "woman", "child",
            "character", "crowd", "silhouette", "pedestrian", "portrait", "face",
            "人物", "人影", "人群", "行人", "角色", "人脸",
        )
        /** 场景图精简禁词（v1.7.21）：高频时代词 + 空场无人干扰词，控制在 25 项内。 */
        val DEFAULT_SCENE_CORE_FORBIDDEN = DEFAULT_ERA_NEGATIVE_ASSET + listOf(
            "people", "person", "human", "human figure", "man", "woman", "child",
            "character", "crowd", "silhouette", "pedestrian", "portrait", "face",
        )

        /** 道具专用 suffix（见 [StylePreset.propStudioSuffix]）：去 cinematic / 9:16 framing 等环境化语义。 */
        const val DEFAULT_PROP_STUDIO_SUFFIX = "object reference sheet style, plain neutral backdrop, even soft studio lighting, centered single object, sharp focus, object fully visible in frame, high detail material and texture"
        /** 道具纯色底与孤立单品指令（见 [StylePreset.propStudioPositive]）。 */
        const val DEFAULT_PROP_STUDIO_POSITIVE = "plain solid color background, seamless neutral backdrop, isolated single object, product shot, centered object, no environment, no scenery, even soft studio lighting, sharp focus on the object, object fully visible in frame"
        val DEFAULT_PROP_STUDIO_NEGATIVE = listOf(
            "people", "person", "human", "hand holding", "model", "character", "face",
            "scene background", "environment", "landscape", "furniture", "room interior",
            "complex background", "detailed background", "outdoor", "tabletop",
            "multiple objects", "pedestal",
            "人物", "人", "手", "环境", "场景", "背景", "室内", "家具",
        )
        /** 道具图精简禁词（v1.7.21）：高频时代词 + 纯色底/孤立单品干扰词。 */
        val DEFAULT_PROP_CORE_FORBIDDEN = DEFAULT_ERA_NEGATIVE_ASSET + listOf(
            "people", "person", "human", "hand holding", "model", "character", "face",
            "scene background", "environment", "landscape", "furniture", "room interior",
            "complex background", "detailed background", "outdoor", "tabletop",
            "multiple objects", "pedestal",
        )
        /** 角色图精简禁词（v1.7.21）：高频时代词 + 棚拍纯色底干扰词。 */
        val DEFAULT_CHARACTER_CORE_FORBIDDEN = DEFAULT_ERA_NEGATIVE_ASSET + listOf(
            "scene background", "environment", "landscape", "furniture", "props around",
            "complex background", "outdoor", "interior setting", "decorated room",
            "background scenery", "detailed background", "busy background",
            "gradient background", "room interior", "crowd", "bokeh background",
            "pedestal", "tabletop", "multiple objects", "second character",
        )
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

        /**
         * 参考图通用硬性规范（见 [StylePreset.referenceCommonPositive]）——每张参考图都叠加，
         * 与角度无关：平视 / 纯白底 / 中性表情 / 五官清晰无遮挡 / 无文字水印 / 同款服装妆造。
         */
        const val DEFAULT_REFERENCE_COMMON_POSITIVE = "平视镜头，中性平静表情，五官清晰完整无遮挡，纯白干净背景，均匀柔和棚拍柔光，构图居中，照片质感，无夸张透视，无文字水印，与同套其他参考图完全一致的服装发型妆容，用于AI视频角色参考图 / eye-level camera, neutral calm expression, clear complete unobstructed facial features, plain white clean background, even soft studio lighting, centered composition, photorealistic, no exaggerated perspective, no text and no watermark, identical costume hair and makeup as the other reference shots, AI video character reference"
        /**
         * 参考图通用禁忌（见 [StylePreset.referenceCommonNegative]）——踩坑清单：
         * 拼图多小人（视频模型识别失败）、人脸过小（锁脸失败）、逆光阴阳脸、
         * 墨镜厚刘海遮眼、夸张表情、大俯仰拍、文字水印。
         */
        val DEFAULT_REFERENCE_COMMON_NEGATIVE = listOf(
            "multiple views in one image", "contact sheet", "collage", "split panel",
            "grid of poses", "multiple small figures", "two people", "duplicate character",
            "tiny face", "face too small", "distant shot", "small subject in large scene",
            "backlight", "harsh shadow on face", "half face in shadow", "rim light only",
            "sunglasses", "thick bangs covering eyes", "mask", "hat covering face", "face covered",
            "exaggerated expression", "big smile", "grimace", "wide open mouth",
            "looking up", "looking down", "extreme low angle", "extreme high angle", "dutch angle",
            "text", "watermark", "subtitle", "sticker", "logo", "signature",
            "拼图", "多角度拼一张", "九宫格", "多个小人", "两个人", "人脸过小", "远景小人",
            "逆光", "阴阳脸", "半张脸阴影", "墨镜", "厚刘海", "遮眼", "口罩",
            "夸张表情", "大笑", "仰头", "低头", "仰拍", "俯拍", "文字", "水印", "字幕", "贴纸",
        )
        /**
         * v1.7.21：参考图精简禁词（进图像 prompt 的那一份）。
         *
         * 完整禁忌表 40+ 项，叠加到角色精简禁词上会重新逼近 70 项，前功尽弃。
         * 这里只留「一旦踩了就彻底锁不住脸」的 14 项。
         */
        val DEFAULT_REFERENCE_CORE_FORBIDDEN = listOf(
            "multiple views in one image", "collage", "grid of poses", "multiple small figures",
            "two people", "duplicate character",
            "tiny face", "face too small", "distant shot",
            "backlight", "half face in shadow", "sunglasses", "face covered",
            "exaggerated expression", "big smile", "looking up", "looking down",
            "watermark", "text",
        )

        /**
         * 短剧流水线默认参考图套装（v1.7.20）：**4 张彼此独立的图片**。
         *
         * 取代旧的「6 姿态资产包」。选这 4 张的理由：
         * - 基准正面半身：锁脸主锚点，最重要；人脸占画面约 1/3，太小会直接锁脸失败；
         * - 45° 右前半身：解决人物转头时崩脸 / 变脸；
         * - 正侧面：走路、侧对镜头、对话镜头；
         * - 正面全身：锁定身高体型、服装版型、鞋履配饰。
         *
         * 四张必须同服装、同发型、同妆容，全部平视 + 纯白底 + 中性表情 + 五官无遮挡：
         * 背景越花模型越容易改脸，这是踩坑最多的一条。
         */
        fun defaultReferenceSheet(): List<ReferenceShotSpec> = listOf(
            ReferenceShotSpec("front_bust",
                "正面半身（胸以上到大腿中部），平视镜头，脸部完整无遮挡，五官清晰，中性平静表情，纯白干净背景，均匀柔和棚拍柔光，构图居中，人脸占画面约三分之一，无夸张透视，照片质感（基准锁脸图，最重要）",
                "front-facing half-body portrait from chest to mid-thigh, eye-level camera, face fully visible and unobstructed, clear facial features, neutral calm expression, plain white clean background, even soft studio lighting, centered composition, face occupying about one third of frame, no exaggerated perspective, photorealistic (primary face-lock reference)"),
            ReferenceShotSpec("side_45_right",
                "45度右前半身，头部右转45度露出半边侧脸，平视镜头，五官清晰，中性平静表情，纯白干净背景，均匀柔和棚拍柔光，构图居中（用于解决转头时崩脸、变脸）",
                "45-degree right three-quarter half-body, head turned 45 degrees to the right showing one side of the face, eye-level camera, clear features, neutral calm expression, plain white background, even soft studio lighting, centered (for stable identity during head turns)"),
            ReferenceShotSpec("profile_side",
                "正侧面半身，耳朵与鼻梁侧面轮廓清晰完整，平视镜头，中性平静表情，纯白干净背景，均匀柔和棚拍柔光，构图居中（用于走路、侧对镜头、对话镜头）",
                "true side profile half-body, clear and complete ear and nose bridge silhouette, eye-level camera, neutral calm expression, plain white background, even soft studio lighting, centered (for walking shots and side-facing dialogue)"),
            ReferenceShotSpec("front_full_body",
                "正面全身站姿，与前三张完全同款服装、发型、妆容，自然放松站姿，平视镜头，完整全身入镜，纯白干净背景，均匀柔和棚拍柔光（用于锁定身高、体型、服装版型、鞋履配饰）",
                "front-facing full-body standing pose, exactly the same costume hair and makeup as the other reference shots, natural relaxed stance, eye-level camera, full body in frame, plain white background, even soft studio lighting (to lock height build costume silhouette footwear and accessories)"),
        )

        /**
         * 专业完整版可选：表情参考包（喜 / 怒 / 平静 / 忧伤）。
         *
         * 短剧流水线默认**不生成**——图生视频的锁脸参考图必须是中性表情，
         * 夸张表情会让后续视频很难生成正常表情。仅大项目 / 数字人 / LoRA 训练扩展用。
         */
        fun defaultExpressionShots(): List<ReferenceShotSpec> = listOf(
            ReferenceShotSpec("expression_joy", "情绪特写-喜悦：眉眼舒展，嘴角自然上扬，神态明朗",
                "face close-up, expression: joyful, relaxed brows, natural smile, bright demeanor", expression = true),
            ReferenceShotSpec("expression_angry", "情绪特写-愤怒：眉头紧锁，怒目圆睁，咬肌紧绷，嘴角下压",
                "face close-up, expression: angry, knitted brows, glaring eyes, clenched jaw, downturned mouth", expression = true),
            ReferenceShotSpec("expression_calm", "情绪特写-平静：神情平和，目光沉稳，嘴角自然微合",
                "face close-up, expression: calm, composed expression, steady eyes, relaxed mouth", expression = true),
            ReferenceShotSpec("expression_sorrow", "情绪特写-忧伤：眉梢微垂，目光低敛，嘴角轻抿，神情黯淡",
                "face close-up, expression: sorrowful, slightly lowered brows, downcast eyes, pressed lips, somber demeanor", expression = true),
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
