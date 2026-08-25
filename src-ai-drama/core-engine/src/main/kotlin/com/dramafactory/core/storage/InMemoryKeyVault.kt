package com.dramafactory.core.storage

import com.dramafactory.core.provider.KeyVault

/**
 * KeyVault内存假实现 —— 供JVM测试与无Keystore环境。
 * 生产实现为Android EncryptedSharedPreferences封装（app模块），接口签名完全一致。
 */
class InMemoryKeyVault : KeyVault {
    private val keys = mutableMapOf<String, Pair<String, String>>() // configId → (providerId, plainKey)

    override suspend fun save(configId: String, providerId: String, plainKey: String) {
        keys[configId] = providerId to plainKey
    }

    override suspend fun load(configId: String): String =
        keys[configId]?.second ?: throw NoSuchElementException("no key for config $configId")

    override fun masked(configId: String): String {
        val k = keys[configId]?.second ?: return "<empty>"
        return when {
            k.isEmpty() -> "<empty>"
            k.length <= 8 -> "***"
            else -> "${k.take(3)}***${k.takeLast(3)}"   // pavo _mask_key语义：前3后3
        }
    }

    override suspend fun delete(configId: String) { keys.remove(configId) }
}
