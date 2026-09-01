package com.dramafactory.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dramafactory.core.provider.KeyVault

/**
 * KeyVault生产实现 —— EncryptedSharedPreferences封装（架构§6）+ 三级降级链。
 *
 * 真机闪退修复（第四轮）：StrongBox / Keystore 在部分机型与系统版本会抛
 * ProviderException / KeyStoreException / EncryptedSharedPreferences初始化异常，
 * 直接导致 Application.onCreate 崩溃。降级策略：
 *   L1: MasterKey(AES256_GCM) + StrongBox请求（可用则用）
 *   L2: MasterKey 不带 StrongBox（TEE/软件Keystore）
 *   L3: 普通SharedPreferences明文存储 + degraded=true（UI提示安全等级降低）
 *   L4: 内存实现（极端环境，进程死即失；仅保App不崩）
 * 任一级初始化失败静默降到下一级，永不向上抛。
 */
class AndroidKeyVault private constructor(
    context: Context,
    private val plainPrefs: SharedPreferences?,
    private val memoryFallback: MutableMap<String, String>?,
) : KeyVault {

    companion object {
        @Volatile var lastInitError: String? = null; private set

        /** 工厂方法：逐级尝试，保证不抛异常 */
        fun create(context: Context): AndroidKeyVault {
            val app = context.applicationContext
            // L1/L2: EncryptedSharedPreferences（先带StrongBox请求，再不带）
            for (strongBox in booleanArrayOf(true, false)) {
                try {
                    val builder = MasterKey.Builder(app)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    if (strongBox) builder.setRequestStrongBoxBacked(true)
                    val prefs = EncryptedSharedPreferences.create(
                        app, "drama_key_vault", builder.build(),
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    )
                    return AndroidKeyVault(app, plainPrefs = prefs, memoryFallback = null)
                } catch (t: Throwable) {
                    lastInitError = "L${if (strongBox) 1 else 2}: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
            // L3: 明文 SharedPreferences（数据目录沙箱内，仍优于闪退）
            try {
                val prefs = app.getSharedPreferences("drama_key_vault_plain", Context.MODE_PRIVATE)
                return AndroidKeyVault(app, plainPrefs = prefs, memoryFallback = null)
            } catch (t: Throwable) {
                lastInitError = "L3: ${t.javaClass.simpleName}: ${t.message}"
            }
            // L4: 纯内存兜底
            lastInitError = "L4: memory-only fallback"
            return AndroidKeyVault(app, plainPrefs = null, memoryFallback = mutableMapOf())
        }

        /** 当前是否处于降级态（UI据此提示） */
        fun isDegraded(): Boolean = lastInitError != null
        fun degradationNotice(): String? = lastInitError?.let {
            "Key存储已降级（$it），建议在系统设置中清除数据后重试以恢复加密保护"
        }
    }

    private fun get(key: String): String? =
        plainPrefs?.getString(key, null) ?: memoryFallback?.get(key)

    private fun put(key: String, value: String?) {
        if (plainPrefs != null) {
            plainPrefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        } else {
            if (value == null) memoryFallback!!.remove(key) else memoryFallback!![key] = value
        }
    }

    override suspend fun save(configId: String, providerId: String, plainKey: String) {
        put("$configId.key", plainKey)
        put("$configId.provider", providerId)
    }

    /** 仅Provider层可见，永不回显UI */
    override suspend fun load(configId: String): String =
        get("$configId.key") ?: throw NoSuchElementException("no key for config $configId")

    /** sk-***abc 掩码展示（pavo _mask_key语义：前3后3） */
    override fun masked(configId: String): String {
        val k = get("$configId.key") ?: ""
        return when {
            k.isEmpty() -> "<empty>"
            k.length <= 8 -> "***"
            else -> "${k.take(3)}***${k.takeLast(3)}"
        }
    }

    override suspend fun delete(configId: String) {
        put("$configId.key", null)
        put("$configId.provider", null)
    }

    // v1.9.0：底层本就是同步 SharedPreferences，同步通道直接复用 get/put
    override fun readSync(configId: String): String = get("$configId.key") ?: ""
    override fun writeSync(configId: String, plainValue: String) { put("$configId.key", plainValue) }
}
