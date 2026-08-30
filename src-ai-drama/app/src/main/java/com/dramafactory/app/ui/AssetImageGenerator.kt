package com.dramafactory.app.ui

import com.dramafactory.core.model.ImageGenRequest
import com.dramafactory.core.model.ProviderError
import com.dramafactory.core.provider.ImageProvider
import com.dramafactory.core.quality.AssetPromptBuilder
import com.dramafactory.core.quality.StylePreset

/**
 * 资产出图的调用封装（画幅 + 调用 + 尺寸回退）。
 *
 * prompt 组装本身在 core 的 [AssetPromptBuilder]，app 与 desktop 共用同一套规则；
 * 这里只负责：按类型取画幅（旧实现永远走默认的 512x512，StylePreset 里定义的
 * 1024x1024 / 1024x768 / 768x768 从未被使用过），以及服务端拒收画幅时回退重试一次。
 */
object AssetImageGenerator {

    const val KIND_CHARACTER = AssetPromptBuilder.KIND_CHARACTER
    const val KIND_SCENE = AssetPromptBuilder.KIND_SCENE
    const val KIND_PROP = AssetPromptBuilder.KIND_PROP

    /** 只带正向约束的 prompt（供 G2 审计当图像描述） */
    fun buildConstrained(
        kind: String,
        basePrompt: String,
        preset: StylePreset,
        allowed: List<String> = emptyList(),
    ): String = AssetPromptBuilder.constrained(preset, kind, basePrompt, allowed)

    fun buildPrompt(
        kind: String,
        basePrompt: String,
        preset: StylePreset,
        allowed: List<String> = emptyList(),
    ): String = AssetPromptBuilder.finalPrompt(preset, kind, basePrompt, allowed)

    fun imageSizeFor(kind: String, preset: StylePreset): String = AssetPromptBuilder.sizeFor(preset, kind)

    /**
     * 生成资产图。尺寸被服务端拒收（400）时用默认画幅重试一次；
     * 其余错误（网络/鉴权/配额）原样上抛，由调用方决定重试或放弃。
     */
    suspend fun generate(
        provider: ImageProvider,
        kind: String,
        basePrompt: String,
        preset: StylePreset,
        allowed: List<String> = emptyList(),
        inputImages: List<String> = emptyList(),
    ): String {
        val prompt = buildPrompt(kind, basePrompt, preset, allowed)
        val size = imageSizeFor(kind, preset)
        return try {
            provider.generateImage(ImageGenRequest(
                prompt = prompt, size = size,
                negativePrompt = null,      // ★图像端不支持，传了必 400
                inputImages = inputImages))
        } catch (e: ProviderError.ValidationError) {
            provider.generateImage(ImageGenRequest(
                prompt = prompt,
                negativePrompt = null,
                inputImages = inputImages))
        }
    }
}
