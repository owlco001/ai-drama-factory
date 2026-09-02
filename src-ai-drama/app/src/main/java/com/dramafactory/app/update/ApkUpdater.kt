package com.dramafactory.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在线更新（方案B）：应用内下载 APK 并调起系统安装器。
 *
 * 流程：
 *   1. 从 Gitee Release 附件 URL 下载（Ktor 流式，避免整包进内存）；
 *   2. Gitee 浏览器/直链下载常把文件名自动追加 `.zip` 后缀（如
 *      `ai-drama-factory-v1.9.4-debug.apk.zip`），下载完成后若不是 `.apk` 则改名为 `.apk`
 *      —— 否则 PackageInstaller 会因 MIME/扩展名拒绝；
 *   3. 经 FileProvider 暴露内部文件，构造 ACTION_INSTALL_PACKAGE Intent 调起系统安装器，
 *      用户点「安装」确认（Android 不允许非系统 App 静默安装，必须用户确认）。
 *
 * 设计要点（便于 JVM 单测）：
 * - `httpClient` / `context` / `dirProvider` 注入；单测用 MockEngine 提供响应流，context 用假实现；
 * - `download()` 返回进度 Flow（字节数），UI 可显示进度条；出错抛异常由调用方兜底。
 */
class ApkUpdater(
    private val httpClient: HttpClient,
    /** 安装器：给定 APK 文件，调起系统安装器。为 null 时走基于 appContext 的默认实现；单测可注入假实现。 */
    private val installer: ((File) -> Boolean)? = null,
    private val appContext: Context? = null,
    private val authority: String = "com.dramafactory.app.fileprovider",
    private val targetDir: () -> File = { File((appContext?.cacheDir ?: File(System.getProperty("java.io.tmpdir")!!)), "updates") },
) {

    /** 默认安装实现：经 FileProvider 暴露 APK 并调起系统安装器 */
    private fun defaultInstall(file: File): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, authority, file)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.isSuccess
    }
    sealed interface DownloadEvent {
        data class Progress(val bytes: Long, val total: Long? = null) : DownloadEvent
        data class Done(val apkFile: File) : DownloadEvent
        data class Error(val message: String) : DownloadEvent
    }

    /** 把 URL 末段文件名规范化为 .apk（去除 Gitee 自动追加的 .zip 等） */
    fun normalizeApkName(rawName: String): String {
        var base = rawName.substringAfterLast('/').substringBefore('?').substringBefore('#')
        // 先剥掉所有非 .apk 的后缀（如 .zip / .apk.zip），再统一加 .apk
        while (base.length > 4 && !base.endsWith(".apk", ignoreCase = true)) {
            base = base.substringBeforeLast('.')
        }
        return if (base.endsWith(".apk", ignoreCase = true)) base else "$base.apk"
    }

    /**
     * 下载指定 URL 到内部缓存，流式写文件，发射进度。完成后校正扩展名为 .apk。
     */
    fun download(url: String): Flow<DownloadEvent> = callbackFlow {
        val dir = targetDir().also { if (!it.exists()) it.mkdirs() }
        val name = normalizeApkName(url)
        val tmp = File(dir, "$name.tmp")
        tmp.delete()
        var total: Long? = null
        try {
            httpClient.prepareGet(url).execute { resp ->
                total = resp.headers["Content-Length"]?.toLongOrNull()
                val input = resp.bodyAsChannel().toInputStream()
                tmp.outputStream().use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        sum += read
                        trySend(DownloadEvent.Progress(sum, total))
                    }
                }
            }
            val finalFile = File(dir, name)
            tmp.renameTo(finalFile)
            trySend(DownloadEvent.Done(finalFile))
        } catch (e: Throwable) {
            tmp.delete()
            trySend(DownloadEvent.Error(e.message ?: "下载失败"))
        }
        close()   // 发射完毕后关闭 channel，使 flow 正常结束（toList 收集可用）
    }.catch { e ->
        // callbackFlow 外部异常兜底
        emit(DownloadEvent.Error(e.message ?: "下载失败"))
    }

    /**
     * 调起系统安装器安装指定 APK（需 Manifest 声明 REQUEST_INSTALL_PACKAGES）。
     * 实际行为委托给构造时注入的 installer（默认用 FileProvider + ACTION_INSTALL_PACKAGE）。
     * @return true=已发出安装 Intent；false=文件不存在或安装器调用失败
     */
    fun install(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return (installer ?: ::defaultInstall)(apkFile)
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
    }
}
