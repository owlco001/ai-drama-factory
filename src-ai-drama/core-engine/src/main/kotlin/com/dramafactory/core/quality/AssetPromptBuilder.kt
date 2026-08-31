package com.dramafactory.core.quality

/**
 * 资产生成 prompt 的组装器（core 层，app / desktop 共用）。
 *
 * v1.7.17 前，同样的组装逻辑在 AssetsViewModel、AppGraph 两处、DesktopAppGraph 里
 * 各写了一遍，规则互相漂移：
 * - AppGraph 与 DesktopAppGraph 仍给图像接口传 negativePrompt，而 Agnes 图像端
 *   不支持该字段（400），两处被 runCatching 吞掉异常后静默失败；
 * - 只有 AssetsViewModel 一处把禁词并入正向，其余三处的时代红线形同虚设。
 *
 * 这里收敛成一处，规则如下：
 * 1. 正向顺序即权重：主体描述 → era 红线 → 类型专用 suffix → 类型专用背景指令
 *    → Do NOT include 禁词 → 质量负向（模型对尾部权重最高，故背景指令压在最后）；
 * 2. 禁词一律并入正向（图像端无 negative_prompt 字段），只取 ASCII 项（英文抑制强于中文）。
 *
 * v1.7.19：三类资产各走一套约束。此前 scene / prop 都只走 era 红线，
 * 于是场景被模型自行塞入人物、道具被套上 cinematic 等环境化语义而背景脏：
 * - character：主体版 era（剥离建筑/场景语义）+ 棚拍 suffix + 纯色棚拍底，禁环境杂物；
 * - scene：完整 era（场景本就该有建筑陈设）+ cinematic + 空场无人指令，禁人物；
 * - prop：主体版 era + 道具 suffix（去 cinematic / 9:16）+ 纯色底孤立单品，禁环境人物。
 */
object AssetPromptBuilder {

    const val KIND_CHARACTER = "character"
    const val KIND_SCENE = "scene"
    const val KIND_PROP = "prop"

    /** 固定追加的英文质量负向模板（并入正向，因为图像端不接受 negative_prompt 字段） */
    const val QUALITY_NEGATIVE = "blurry, lowres, bad anatomy, deformed hands, extra fingers, " +
        "mutated, disfigured, ugly, watermark, signature, text, logo, oversaturated, distorted face"

    /**
     * 只带正向约束（era 红线 / 棚拍）的版本，不含禁词与质量负向段落。
     * 适合当 G2 多模态审计的「图像描述」——把几百字禁词表喂给审计模型只会干扰打分。
     */
    fun constrained(
        preset: StylePreset,
        kind: String,
        basePrompt: String,
        allowed: List<String> = emptyList(),
    ): String = when (kindOf(kind)) {
        KIND_CHARACTER -> preset.withCharacterStudioConstraints(basePrompt, allowed)
        KIND_SCENE -> preset.withSceneConstraints(basePrompt, allowed)
        KIND_PROP -> preset.withPropConstraints(basePrompt, allowed)
        else -> preset.withEraConstraints(basePrompt, allowed)
    }

    /**
     * 完整正向 prompt（正向约束 + 禁词并入 + 质量负向）。
     *
     * @param extraNegative 额外禁词表（如参考图的通用禁忌），叠加在该类型默认禁词之后。
     */
    fun finalPrompt(
        preset: StylePreset,
        kind: String,
        basePrompt: String,
        allowed: List<String> = emptyList(),
        extraNegative: List<String> = emptyList(),
    ): String {
        val head = constrained(preset, kind, basePrompt, allowed)
        // v1.7.21：图像端只喂「精简禁词」（20 余项），不再塞完整 era 红线（150+ 项）。
        // 实测旧版三类卡禁词均 100+ 项，真正管用的 scene background / environment / people
        // 被淹没在 gun / train / refrigerator / skateboard 这类无关噪声里，等于没说。
        val negItems = preset.coreForbiddenFor(kind) + extraNegative
        val enForbidden = negItems
            .filter { it.isNotBlank() && it.all { c -> c.code < 128 } }
            .distinct()
        val redline = if (enForbidden.isNotEmpty()) " Do NOT include: ${enForbidden.joinToString(", ")}." else ""
        // v1.7.21：尾部强指令压在最末（模型对 prompt 尾部权重最高）。
        // 旧版把「纯色背景」「空场无人」放在中部、后面还拖着 100+ 禁词，指令被稀释失效。
        val anchor = preset.tailAnchorFor(kind)
        val tail = if (anchor.isNotBlank()) " $anchor" else ""
        return "$head.$redline Negative prompt (soft): $QUALITY_NEGATIVE.$tail"
    }

    /**
     * 角色参考图专用完整 prompt（v1.7.20）。
     *
     * 复用角色资产的「纯色棚拍底 + 主体版 era」链路（即 [finalPrompt] 的 character 分支），
     * 额外注入该参考图的角度构图指令与通用硬性规范。
     *
     * 顺序即权重：角色描述 → 角度构图指令（中英双语）→ 通用规范 →（内部）era 主体红线
     * → 棚拍 suffix → 纯色背景指令 → Do NOT include 禁词 → 质量负向。
     *
     * 每张参考图独立成图：[shot] 只描述一个角度，绝不把多个角度塞进同一张画布
     * （视频模型对拼图 / 多小人识别失败，会直接导致锁脸失效）。
     */
    fun finalReferencePrompt(
        preset: StylePreset,
        characterDesc: String,
        shot: StylePreset.ReferenceShotSpec,
        allowed: List<String> = emptyList(),
    ): String {
        val base = buildString {
            append(characterDesc.trim())
            append("。").append(shot.cn)
            append(" ").append(shot.en)
            if (preset.referenceCommonPositive.isNotBlank()) append("。").append(preset.referenceCommonPositive)
        }
        return finalPrompt(preset, KIND_CHARACTER, base, allowed, extraNegative = preset.referenceCoreForbidden)
    }

    /** 参考图专用禁词：角色棚拍禁词 + 参考图通用禁忌（拼图 / 小人脸 / 逆光 / 遮眼 / 水印等）。 */
    fun referenceNegativePromptFor(preset: StylePreset, allowed: List<String> = emptyList()): String =
        (preset.studioNegativePromptFor(allowed).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            + preset.referenceCommonNegative).distinct().joinToString(", ")

    /** 按资产类型取画幅（此前 StylePreset 里定义了这三个尺寸常量却无人使用） */
    fun sizeFor(preset: StylePreset, kind: String): String = when (kindOf(kind)) {
        KIND_CHARACTER -> preset.characterImageSize
        KIND_SCENE -> preset.sceneImageSize
        KIND_PROP -> preset.propImageSize
        else -> preset.characterImageSize
    }

    private fun kindOf(kind: String): String = kind.trim().lowercase()
}
