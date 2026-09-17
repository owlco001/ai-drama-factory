package com.dramafactory.core.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

object TextModelRouterFactory {
    fun create(
        keyVault: KeyVault,
        initialStore: TextModelStore? = null
    ): TextModelRouter {
        val store = initialStore ?: InMemoryTextModelStore(keyVault = keyVault)
        runBlocking { store.hydrateActive() }
        return DefaultTextModelRouterImpl(store)
    }

    fun create(
        keyVault: KeyVault,
        scope: CoroutineScope
    ): TextModelRouter = create(keyVault)
}
