package com.dramafactory.app.ui

import com.dramafactory.app.update.ApkUpdater
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * ApkUpdater JVM 单测：download 流程用 MockEngine 提供字节流（不触网、不依赖 Android 运行时），
 * 验证：文件名 .zip→.apk 校正、流式写出、进度/完成事件发射；install 委托给注入式 installer。
 */
class ApkUpdaterTest {

    private val sampleApk = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00, 0x00, 0x00) // "PK.." 伪 APK 头

    private fun makeUpdater(url: String, body: ByteArray, installer: (File) -> Boolean): Pair<ApkUpdater, File> {
        val dir = File.createTempFile("apktest", "").also { it.delete(); it.mkdirs() }
        val client = HttpClient(MockEngine { _ ->
            respond(body, headers = headersOf(HttpHeaders.ContentLength, body.size.toString()))
        })
        val updater = ApkUpdater(
            httpClient = client,
            installer = installer,
            appContext = null,
            targetDir = { dir },
        )
        return updater to dir
    }

    @Test
    fun `normalizeApkName 去除 Gitee 追加的 zip 后缀`() {
        val u = ApkUpdater(HttpClient(MockEngine { respond("{}") }), installer = { true })
        assertEquals("ai-drama-factory-v1.9.4-debug.apk",
            u.normalizeApkName("https://gitee.com/.../ai-drama-factory-v1.9.4-debug.apk.zip"))
        assertEquals("app.apk", u.normalizeApkName("app.apk"))
        assertEquals("app.apk", u.normalizeApkName("app.apk?token=abc"))
        assertEquals("app.apk", u.normalizeApkName("https://x/app.apk#frag"))
    }

    @Test
    fun `download 写出 apk 并发射进度与完成事件`() = runTest {
        val (updater, dir) = makeUpdater("u", sampleApk) { true }
        val events = updater.download("https://gitee.com/repo/releases/download/v1.9.4/ai-drama-factory-v1.9.4-debug.apk.zip").toList()
        // 最后一个应为 Done，且文件名已校正为 .apk
        val done = events.filterIsInstance<ApkUpdater.DownloadEvent.Done>().single()
        assertTrue(done.apkFile.name.endsWith(".apk"), "文件名应校正为 .apk，实际=${done.apkFile.name}")
        assertFalse(done.apkFile.name.endsWith(".zip"), "不应保留 .zip 后缀")
        assertTrue(done.apkFile.exists(), "APK 文件应已写出")
        assertEquals(sampleApk.size.toLong(), done.apkFile.length())
        // 进度事件中应含总字节数
        val progress = events.filterIsInstance<ApkUpdater.DownloadEvent.Progress>().first()
        assertEquals(sampleApk.size.toLong(), progress.total)
    }

    @Test
    fun `download 完成后 installer 收到校正后的 apk 文件`() = runTest {
        var installed: File? = null
        val (updater, _) = makeUpdater("u", sampleApk) { f -> installed = f; true }
        val events = updater.download("https://gitee.com/repo/x.apk.zip").toList()
        val done = events.filterIsInstance<ApkUpdater.DownloadEvent.Done>().single()
        // 模拟 VM：下载完成后调用 install（委托给注入的 installer）
        updater.install(done.apkFile)
        assertEquals("x.apk", installed?.name, "installer 收到的文件应为 .apk 校正后名称")
    }

    @Test
    fun `install 文件不存在返回 false`() {
        val updater = ApkUpdater(HttpClient(MockEngine { respond("{}") }), installer = { true })
        assertFalse(updater.install(File("/nonexistent/file.apk")))
    }

    @Test
    fun `install 委托 installer 返回值`() {
        val ok = ApkUpdater(HttpClient(MockEngine { respond("{}") }), installer = { true })
        val fail = ApkUpdater(HttpClient(MockEngine { respond("{}") }), installer = { false })
        val tmp = File.createTempFile("apk", ".apk").also { it.writeBytes(sampleApk) }
        assertTrue(ok.install(tmp))
        assertFalse(fail.install(tmp))
    }
}
