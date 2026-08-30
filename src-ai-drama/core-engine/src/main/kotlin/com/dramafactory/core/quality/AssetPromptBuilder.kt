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
 * 1. 正向顺序即权重：主体描述 → era 红线 →（角色）棚拍 suffix →（角色）纯色背景指令
 *    → Do NOT include 禁词 → 质量负向；
 * 2. 角色类丢弃 globalPromptSuffix 的 cinematic / 9:16 framing / naturalistic lighting，
 *    那些场景化语义会逼模型补环境，与纯色棚拍底正面对冲；
 * 3. 禁词一律并入正向（图像端无 negative_prompt 字段），只取 ASCII 项（英文抑制强于中文）。
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
    ): String = if (isCharacter(kind)) {
        preset.withCharacterStudioConstraints(basePrompt, allowed)
    } else {
        preset.withEraConstraints(basePrompt, allowed)
    }

    /** 完整正向 prompt（正向约束 + 禁词并入 + 质量负向）。 */
    fun finalPrompt(
        preset: StylePreset,
        kind: String,
        basePrompt: String,
        allowed: List<String> = emptyList(),
    ): String {
        val head = constrained(preset, kind, basePrompt, allowed)
        val neg = if (isCharacter(kind)) preset.studioNegativePromptFor(allowed) else preset.negativePromptFor(allowed)
        val enForbidden = neg.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.code < 128 } }
            .distinct()
        val redline = if (enForbidden.isNotEmpty()) " Do NOT include: ${enForbidden.joinToString(", ")}." else ""
        return "$head.$redline Negative prompt (soft): $QUALITY_NEGATIVE"
    }

    /** 按资产类型取画幅（此前 StylePreset 里定义了这三个尺寸常量却无人使用） */
    fun sizeFor(preset: StylePreset, kind: String): String = when (kind.trim().lowercase()) {
        KIND_CHARACTER -> preset.characterImageSize
        KIND_SCENE -> preset.sceneImageSize
        KIND_PROP -> preset.propImageSize
        else -> preset.characterImageSize
    }

    private fun isCharacter(kind: String): Boolean = kind.trim().lowercase() == KIND_CHARACTER
}
