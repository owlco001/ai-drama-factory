package com.dramafactory.core.quality

/**
 * 开场帧场景化重渲染 + 道具主资产 i2i 合成（对齐 pavo shot_director.py）。
 *
 * - **F. 开场帧重渲染**：I2V 首帧条件化是模型设计特性，直接把原始资产图当首关键帧提交，
 *   视频开场必为原图。对齐 pavo `_render_opening_frame`：把首关键帧改为「按 frame_start
 *   场景化重渲染的电影开场帧」（i2i from 资产图，融入场景/光线/景深/胶片颗粒，硬否定
 *   isolated studio shot / product photo）——第 0 帧从机制上不再可能是孤立资产原图。
 *   逃生开关 [keepFirstFrame]=true（资产图本身就是理想开场帧时用）。
 *
 * - **F. 道具主资产 i2i**：道具先生成一张主资产图；每镜以双图 i2i 把主资产合成进场景
 *   静帧（input_images=[base静帧, 主资产URI]，prompt 要求「保持角色/场景不变、仅以完全
 *   一致外观渲染道具」），全集体道具一致；角色/场景镜（无命中道具）不碰。
 *
 * 纯函数构建 prompt（不依赖 Android/GPU）；实际图像生成调 ImageProvider。多模态视觉验收
 * 默认关闭（需网络）。
 */
object ShotDirector {

    /** i2i 绑定：把 bound asset URIs 作为 input_images，拼 @图N 图例进 prompt。 */
    data class Binding(
        val baseUri: String,                       // 场景/角色基础静帧
        val assetUris: List<String> = emptyList(), // 绑定的角色/道具参考图（含主资产）
        val legend: String = "",                   // @图N 对应说明
    )

    /**
     * 构建开场帧重渲染 prompt（对齐 pavo _render_opening_frame）。
     * @param frameStart 本镜 frame_start 场景描述（空则用 action）
     * @param binding 本镜 i2i 绑定（基础帧 + 角色/道具参考图）
     * @param stylePreset 样式预设（取 global_prompt_suffix + negative）
     * @param keepFirstFrame true=不重渲染（逃生开关），直接返回原 baseUri 作为首帧
     * @param auditNotes 上一轮 fidelity gate 的 audit_notes（用于 re-render 修正）
     */
    fun buildOpeningFramePrompt(
        frameStart: String,
        binding: Binding,
        stylePreset: StylePreset,
        auditNotes: List<String> = emptyList(),
    ): Pair<String, String> {
        val opening = frameStart.ifBlank { "the opening scene" }
        val bits = mutableListOf<String>().apply {
            add("Cinematic opening film still of this shot: $opening.")
            if (binding.legend.isNotBlank()) {
                add(
                    "参考图对应关系：@图1=本镜基础画面；${binding.legend}。" +
                        "其中每一件道具/角色都必须清晰、完整、未被遮挡地出现在画面中，外观与参考图保持一致。"
                )
            }
            add(
                "Subject fully integrated into the scene with environment, lighting " +
                    "and atmosphere; live-action movie frame, shallow depth of field, " +
                    "subtle film grain. NOT an isolated product/studio photo, NOT a " +
                    "catalog image."
            )
            for (note in auditNotes) add("修正要求：$note")
            if (stylePreset.globalPromptSuffix.isNotBlank()) add(stylePreset.globalPromptSuffix)
        }
        val prompt = bits.filter { it.isNotBlank() }.joinToString(" ")
        val negative = (stylePreset.globalNegativePrompt + "isolated studio shot, plain background, product photo")
            .distinct().joinToString(", ")
        return prompt to negative
    }

    /**
     * 构建道具 i2i 合成 prompt（对齐 pavo _composite_props）：双图 i2i 把主资产合成进场景。
     * @param baseSceneUri 场景静帧 URI（第一张 i2i 输入）
     * @param propMasterUri 道具主资产 URI（第二张 i2i 输入）
     * @param propName 道具名（用于明示「保持外观一致」）
     * @param stylePreset 样式预设
     */
    fun buildPropI2iPrompt(
        baseSceneUri: String,
        propMasterUri: String,
        propName: String,
        stylePreset: StylePreset,
    ): Pair<String, List<String>> {
        val prompt = buildString {
            append("将道具「$propName」以完全一致的外观合成进当前场景静帧：")
            append("保持角色与场景不变，仅渲染该道具，道具外观严格对齐参考图；")
            append("清晰、完整、无遮挡地呈现于画面合理位置。")
            if (stylePreset.globalPromptSuffix.isNotBlank()) append(" ${stylePreset.globalPromptSuffix}")
        }
        val negative = stylePreset.globalNegativePrompt
        return prompt to negative
    }

    /** 与 [buildPropI2iPrompt] 同语义，返回 (positive, negative_list) 便于 Kotlin 调用方展开。 */
    fun buildPropI2iPromptPair(
        baseUri: String,
        propAssetUri: String,
        propName: String,
        stylePreset: StylePreset,
    ): Pair<String, List<String>> {
        val (positive, negative) = buildPropI2iPrompt(baseUri, propAssetUri, propName, stylePreset)
        return positive to negative
    }

    /** 从 binding 组装 i2i input_images（base + 各参考图）。 */
    fun inputImages(binding: Binding): List<String> =
        listOf(binding.baseUri) + binding.assetUris

    /**
     * 判定是否需要对某镜重渲染开场帧：非 keep_first_frame 即重渲染。
     * 对齐 pavo：shot.get("keep_first_frame") 时跳过 _render_opening_frame。
     */
    fun shouldRerenderOpeningFrame(keepFirstFrame: Boolean): Boolean = !keepFirstFrame

    /**
     * 判定某镜是否命中道具 i2i：props_present 非空即需把道具主资产合成进场景。
     * @param propMasterUris 命中的道具主资产 URI（空=无命中，跳过）
     */
    fun needsPropI2i(propMasterUris: List<String>): Boolean = propMasterUris.isNotEmpty()
}
