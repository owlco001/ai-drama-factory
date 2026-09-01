package com.dramafactory.app.ui

/**
 * 供应商注册表 —— 设置页多模型选择数据源（第四轮新增）。
 *
 * v1.9.0：Agnes(PavoAPI) + 五家专属适配器（可灵/即梦/Runway/Luma/Pika）全部可用，
 * 各家 API 规格差异（鉴权头、关键帧字段名、图像格式 data URI/URL）由各自适配器封装；
 * 选中并保存 Key 后即写入视频路由（VideoProviderRouter），渲染时按激活供应商出图。
 * 自定义模型走 OpenAI 兼容格式。
 */
object ProviderRegistry {

    enum class Status { AVAILABLE, COMING_SOON }

    data class ProviderInfo(
        val id: String,
        val label: String,
        val status: Status,
        val note: String = "",
    )

    val ALL = listOf(
        ProviderInfo("agnes", "Agnes（PavoAPI）", Status.AVAILABLE, "MVP全功能接入"),
        ProviderInfo("kling", "可灵 Kling", Status.AVAILABLE, "专属适配器：image/image_tail 关键帧 · sound 音频"),
        ProviderInfo("jimeng", "即梦 / 豆包 Seedance", Status.AVAILABLE, "专属适配器：多模态首/尾帧+参考图 · generate_audio"),
        ProviderInfo("runway", "Runway", Status.AVAILABLE, "专属适配器：单 promptImage i2v · 模型自带音轨"),
        ProviderInfo("luma", "Luma", Status.AVAILABLE, "专属适配器：keyframes 首/尾帧（需 URL）"),
        ProviderInfo("pika", "Pika", Status.AVAILABLE, "专属适配器：image-to-video（需 URL）"),
        ProviderInfo("custom", "自定义模型（OpenAI兼容）", Status.AVAILABLE, "填 base_url + model_id + api_key"),
    )

    fun byId(id: String): ProviderInfo? = ALL.firstOrNull { it.id == id }

    /** 自定义模型配置（OpenAI兼容协议，参考pavo agnes_client.py POST /videos模板） */
    data class CustomModelConfig(
        val baseUrl: String,      // 如 https://api.example.com/v1
        val modelId: String,      // 如 my-video-model-1
        val apiKey: String,
        val submitNote: String = "",   // 提交方式说明（用户备注）
    ) {
        fun isValid(): Boolean =
            baseUrl.startsWith("http") && modelId.isNotBlank() && apiKey.isNotBlank()

        companion object {
            const val MAX_URL_CHARS = 500
            const val MAX_NOTE_CHARS = 2000

            /** 防OOM截断后构造；非法输入返回null */
            fun create(baseUrl: String?, modelId: String?, apiKey: String?, note: String?): CustomModelConfig? {
                if (baseUrl == null || modelId == null || apiKey == null) return null
                val cfg = CustomModelConfig(
                    baseUrl = baseUrl.trim().take(MAX_URL_CHARS),
                    modelId = modelId.trim().take(200),
                    apiKey = apiKey.trim().take(4096),
                    submitNote = (note ?: "").trim().take(MAX_NOTE_CHARS),
                )
                return cfg.takeIf { it.isValid() }
            }
        }
    }
}
