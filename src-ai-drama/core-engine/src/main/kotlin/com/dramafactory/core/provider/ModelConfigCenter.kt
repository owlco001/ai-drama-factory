package com.dramafactory.core.provider

import com.dramafactory.core.model.VideoParams

data class ModelConfigCenter(
    val textRouter: TextModelRouter,
    var videoProvider: VideoProvider,
    var imageProvider: ImageProvider,
    val customVideoConfig: ProviderRegistryBridge.CustomVideoConfig? = null,
    val videoParams: VideoParams = VideoParams()
) {
    fun activeTextModelId(): String = textRouter.activeTextModelId()
    fun agnesRegion(): AgnesRegion = textRouter.currentRegion()
}

object ProviderRegistryBridge {
    data class CustomVideoConfig(
        val baseUrl: String,
        val modelId: String,
        val submitNote: String = ""
    ) {
        fun isValid(): Boolean = baseUrl.startsWith("http") && modelId.isNotBlank()
    }
}
