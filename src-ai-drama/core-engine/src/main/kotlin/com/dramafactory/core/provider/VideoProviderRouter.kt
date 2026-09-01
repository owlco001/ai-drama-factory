package com.dramafactory.core.provider

import com.dramafactory.core.provider.video.JimengProvider
import com.dramafactory.core.provider.video.KlingProvider
import com.dramafactory.core.provider.video.LumaProvider
import com.dramafactory.core.provider.video.PikaProvider
import com.dramafactory.core.provider.video.RunwayProvider

/**
 * 视频通道供应商路由（v1.9.0 新增）—— 对应文本通道的 TextModelRouter。
 *
 * 此前 AppGraph.video 硬指向 Agnes；现按「激活的视频供应商」动态构造对应适配器：
 * - agnes / custom → 复用已构建的 Agnes 实例（custom 携带 OpenAI 兼容 override）
 * - kling / jimeng / runway / luma / pika → 各自专属 VideoProvider 适配器
 *
 * 激活 id 持久化在 KeyVault（PREF_ACTIVE），默认 agnes。每个供应商 Key 独立分池
 * （configId = "${id}-video"，agnes 走 region 分池），不跨池回退。
 */
object VideoProviderRouter {

    const val PREF_ACTIVE = "video-active-provider"
    private const val CONFIG_VIDEO = "agnes-video"

    lateinit var keyVault: KeyVault
    /** 当前 Agnes 服务站点（影响 agnes 通道与 Key 池） */
    lateinit var regionProvider: () -> AgnesRegion
    /** 返回已构建的 Agnes 实例（含自定义模型 override），供 agnes/custom 路由复用 */
    lateinit var agnesProviderProvider: () -> VideoProvider

    @Volatile private var activeId: String = "agnes"

    fun init(
        keyVault: KeyVault,
        regionProvider: () -> AgnesRegion,
        agnesProviderProvider: () -> VideoProvider,
    ) {
        this.keyVault = keyVault
        this.regionProvider = regionProvider
        this.agnesProviderProvider = agnesProviderProvider
        // 同步通道读取（init 在非协程上下文调用，不可 await）
        runCatching { keyVault.readSync(PREF_ACTIVE) }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { activeId = it }
    }

    /** 设置激活的视频供应商（保存 Key 或显式切换时调用） */
    fun setActive(id: String) {
        activeId = id
        runCatching { keyVault.writeSync(PREF_ACTIVE, id) }
    }

    fun activeVideoProviderId(): String = activeId

    /** 供应商 → KeyVault configId（与设置页保存键一致） */
    fun configIdFor(providerId: String, region: AgnesRegion): String = when (providerId) {
        "agnes" -> agnesScopedConfigId(CONFIG_VIDEO, region)
        "custom" -> "custom-video"
        else -> "$providerId-video"
    }

    private fun keyFor(providerId: String, region: AgnesRegion): String =
        runCatching { keyVault.readSync(configIdFor(providerId, region)) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: ""

    /** 解析当前激活供应商的 VideoProvider 实例 */
    fun resolve(): VideoProvider = resolveFor(activeId)

    /** 按指定供应商 id 解析（设置页测试连通/保存时用候选 Key） */
    fun resolveFor(providerId: String, overrideKey: String? = null): VideoProvider {
        val region = regionProvider()
        val key = overrideKey ?: keyFor(providerId, region)
        return build(providerId, key, region)
    }

    private fun build(id: String, key: String, region: AgnesRegion): VideoProvider = when (id) {
        "agnes", "custom" -> agnesProviderProvider()
        "kling" -> KlingProvider(apiKeyProvider = { key })
        "jimeng" -> JimengProvider(apiKeyProvider = { key })
        "runway" -> RunwayProvider(apiKeyProvider = { key })
        "luma" -> LumaProvider(apiKeyProvider = { key })
        "pika" -> PikaProvider(apiKeyProvider = { key })
        else -> agnesProviderProvider()
    }

    /** 当前激活供应商 Key 是否就绪（视频通道就绪闸门） */
    fun activeKeyReady(): Boolean {
        if (!::keyVault.isInitialized) return false
        val region = regionProvider()
        return keyFor(activeId, region).isNotBlank()
    }
}
