package com.dramafactory.desktop

import com.dramafactory.core.provider.KeyVault
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 桌面端密钥库：PBKDF2 派生密钥 + AES 加密，文件存 ~/.ai-drama-factory/keystore.dat。
 * 简化版（无 AndroidKeyStore 等价物）；Phase B 可升级为 OS 钥匙串。
 */
class FileKeyVault(private val dir: File) : KeyVault {
    private val file = File(dir, "keystore.dat")
    private val deviceSecret: String = buildDeviceSecret()
    private val cipherKey: SecretKeySpec

    init {
        if (!dir.exists()) dir.mkdirs()
        cipherKey = deriveKey(deviceSecret)
    }

    private fun buildDeviceSecret(): String {
        val props = listOf(
            System.getProperty("os.name") ?: "",
            System.getProperty("user.name") ?: "",
            System.getProperty("os.arch") ?: "",
            File(System.getProperty("user.home")).absolutePath,
        ).joinToString("|")
        return "aidrama://" + sha256(props).take(32)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun deriveKey(secret: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(digest.copyOf(16), "AES")
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, cipherKey)
        return Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun dec(cipherText: String): String {
        val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, cipherKey)
        return String(c.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8)
    }

    override suspend fun save(configId: String, providerId: String, plainKey: String) {
        val lines = if (file.exists()) file.readLines(StandardCharsets.UTF_8).toMutableList()
        else mutableListOf()
        val idx = lines.indexOfFirst { it.startsWith("$configId=") }
        val entry = "$configId=${enc(plainKey)}"
        if (idx >= 0) lines[idx] = entry else lines.add(entry)
        file.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
    }

    override suspend fun load(configId: String): String {
        if (!file.exists()) return ""
        val enc = file.readLines(StandardCharsets.UTF_8)
            .firstOrNull { it.startsWith("$configId=") }?.substringAfter("=") ?: return ""
        return runCatching { dec(enc) }.getOrDefault("")
    }

    override fun masked(configId: String): String =
        if (file.exists() && file.readLines(StandardCharsets.UTF_8).any { it.startsWith("$configId=") })
            "****" else ""

    override suspend fun delete(configId: String) {
        if (!file.exists()) return
        val lines = file.readLines(StandardCharsets.UTF_8).filterNot { it.startsWith("$configId=") }
        file.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
    }
}
