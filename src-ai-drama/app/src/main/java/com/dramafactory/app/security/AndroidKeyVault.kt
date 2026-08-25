package com.dramafactory.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dramafactory.core.provider.KeyVault

/**
 * KeyVault生产实现 —— EncryptedSharedPreferences封装（架构§6）。
 *
 * MasterKey AES256-GCM托管于Android Keystore（StrongBox可用则用），
 * 明文Key仅在本进程内存中解密一次；App数据目录被拷走也无法解密。
 * UI层永不调用load()，只见masked()。
 */
class AndroidKeyVault(context: Context) : KeyVault {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)   // StrongBox可用则用，不可用时自动降级TEE
            .build()
        EncryptedSharedPreferences.create(
            context, "drama_key_vault", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun save(configId: String, providerId: String, plainKey: String) {
        prefs.edit()
            .putString("$configId.key", plainKey)
            .putString("$configId.provider", providerId)
            .apply()
    }

    /** 仅Provider层可见，永不回显UI */
    override suspend fun load(configId: String): String =
        prefs.getString("$configId.key", null)
            ?: throw NoSuchElementException("no key for config $configId")

    /** sk-***abc 掩码展示（pavo _mask_key语义：前3后3） */
    override fun masked(configId: String): String {
        val k = prefs.getString("$configId.key", "") ?: ""
        return when {
            k.isEmpty() -> "<empty>"
            k.length <= 8 -> "***"
            else -> "${k.take(3)}***${k.takeLast(3)}"
        }
    }

    override suspend fun delete(configId: String) {
        prefs.edit().remove("$configId.key").remove("$configId.provider").apply()
    }
}
