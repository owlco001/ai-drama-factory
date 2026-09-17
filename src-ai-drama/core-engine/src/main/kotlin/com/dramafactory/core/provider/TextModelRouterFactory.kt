package com.dramafactory.core.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

object TextModelRouterFactory {
    fun create(
        keyVault: KeyVault,
        initialStore: TextModelStore? = null
    ): TextModelRouter {
        val store = initialStore ?: InMemoryTextModelStore(keyVault = keyVault)
        val router = DefaultTextModelRouterImpl(store)
        runBlocking {
            store.hydrateActive()
            // v1.8.8：把持久化的 Agnes region 同步进 router 实例，保证冷启动 region 正确
            val region = runCatching { keyVault.load(PREF_AGNES_REGION) }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { AgnesRegion.valueOf(it) }.getOrNull() }
                ?: AgnesRegion.INTERNATIONAL
            router.setAgnesRegion(region)
        }
        return router
    }

    fun create(
        keyVault: KeyVault,
        scope: CoroutineScope
    ): TextModelRouter = create(keyVault)
}
