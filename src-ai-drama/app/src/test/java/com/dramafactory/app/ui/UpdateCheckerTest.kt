package com.dramafactory.app.ui

import com.dramafactory.app.update.UpdateChecker
import com.dramafactory.app.update.UpdateResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondBadRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UpdateChecker JVM 单测：用 Ktor MockEngine 模拟 Gitee releases/latest 响应，
 * 验证 tag_name 解析 + versionCode 比对逻辑（不触网）。
 */
class UpdateCheckerTest {

    private fun jsonClient(handlerStatusCode: Int, body: String): HttpClient {
        return HttpClient(MockEngine { _ ->
            if (handlerStatusCode != 200) respondBadRequest()
            else respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    // Gitee releases/latest 的精简字段（assets[0].browser_download_url 为 APK）
    private val releaseJsonV194 = """
        {
          "tag_name": "v1.9.4",
          "body": "## 更新\n- 修复导出崩溃\n- 提升锁脸一致性",
          "assets": [
            { "browser_download_url": "https://gitee.com/owlco001/ai-drama-factory/releases/download/v1.9.4/ai-drama-factory-v1.9.4-debug.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun `有更新 远程版本号大于当前 返回 UpdateAvailable`() = runTest {
        val client = jsonClient(200, releaseJsonV194)
        val checker = UpdateChecker(client, currentVersionName = "1.9.3")
        val r = checker.check()
        assertTrue(r is UpdateResult.UpdateAvailable, "应识别为新版本，实际=$r")
        r as UpdateResult.UpdateAvailable
        assertEquals("1.9.4", r.versionName)
        assertEquals(10904, r.versionCode)
        assertEquals("https://gitee.com/owlco001/ai-drama-factory/releases/download/v1.9.4/ai-drama-factory-v1.9.4-debug.apk", r.downloadUrl)
        assertTrue(r.notes?.contains("提升锁脸一致性") == true)
    }

    @Test
    fun `已是最新 远程版本号等于当前 返回 Latest`() = runTest {
        val client = jsonClient(200, releaseJsonV194)
        val checker = UpdateChecker(client, currentVersionName = "1.9.4")
        val r = checker.check()
        assertTrue(r is UpdateResult.Latest, "应识别为最新，实际=$r")
        assertEquals("1.9.4", (r as UpdateResult.Latest).currentVersionName)
    }

    @Test
    fun `远程版本号小于当前 也视为已最新`() = runTest {
        val client = jsonClient(200, releaseJsonV194)
        val checker = UpdateChecker(client, currentVersionName = "2.0.0")
        val r = checker.check()
        assertTrue(r is UpdateResult.Latest)
    }

    @Test
    fun `无 assets 时回退到 release tag 页面链接`() = runTest {
        val body = """{ "tag_name": "v1.9.4", "body": "notes", "assets": [] }"""
        val client = jsonClient(200, body)
        val checker = UpdateChecker(client, currentVersionName = "1.9.3")
        val r = checker.check()
        assertTrue(r is UpdateResult.UpdateAvailable)
        assertEquals("https://gitee.com/owlco001/ai-drama-factory/releases/tag/v1.9.4", (r as UpdateResult.UpdateAvailable).downloadUrl)
    }

    @Test
    fun `非法 tag_name 无法解析版本号 返回 Error`() = runTest {
        val body = """{ "tag_name": "notaversion", "body": "x" }"""
        val client = jsonClient(200, body)
        val checker = UpdateChecker(client, currentVersionName = "1.9.3")
        val r = checker.check()
        assertTrue(r is UpdateResult.Error, "无法解析版本号应返回 Error，实际=$r")
    }

    @Test
    fun `网络错误 非200 返回 Error`() = runTest {
        val client = jsonClient(400, "")
        val checker = UpdateChecker(client, currentVersionName = "1.9.3")
        val r = checker.check()
        assertTrue(r is UpdateResult.Error)
    }

    // Gitee releases/latest 现实结构：除 APK 外还会附带自动生成的源码归档（.zip / .tar.gz），
    // 且顺序不保证——本例刻意把源码归档排在 APK 之前，验证下载 URL 仍能锁定 .apk 附件。
    private val releaseJsonWithSourceArchiveFirst = """
        {
          "tag_name": "v1.9.4",
          "body": "notes",
          "assets": [
            { "name": "v1.9.4.zip", "browser_download_url": "https://gitee.com/owlco001/ai-drama-factory/archive/refs/tags/v1.9.4.zip" },
            { "name": "v1.9.4.tar.gz", "browser_download_url": "https://gitee.com/owlco001/ai-drama-factory/archive/refs/tags/v1.9.4.tar.gz" },
            { "name": "ai-drama-factory-v1.9.4-debug.apk", "browser_download_url": "https://gitee.com/owlco001/ai-drama-factory/releases/download/v1.9.4/ai-drama-factory-v1.9.4-debug.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun `下载URL锁定apk附件 即使源码归档排在前面`() = runTest {
        val client = jsonClient(200, releaseJsonWithSourceArchiveFirst)
        val checker = UpdateChecker(client, currentVersionName = "1.9.3")
        val r = checker.check()
        assertTrue(r is UpdateResult.UpdateAvailable, "应识别为新版本，实际=$r")
        r as UpdateResult.UpdateAvailable
        assertEquals(
            "https://gitee.com/owlco001/ai-drama-factory/releases/download/v1.9.4/ai-drama-factory-v1.9.4-debug.apk",
            r.downloadUrl,
            "下载 URL 必须指向 .apk 附件，而非源码归档"
        )
        assertTrue(r.downloadUrl.endsWith(".apk"), "下载 URL 应以 .apk 结尾，实际=${r.downloadUrl}")
    }

    @Test
    fun `parseVersionCodeFromTag 多种格式`() {
        val checker = UpdateChecker(HttpClient(MockEngine { respond("{}") }), currentVersionName = "0.0.0")
        assertEquals(10904, checker.parseVersionCodeFromTag("v1.9.4"))
        assertEquals(10904, checker.parseVersionCodeFromTag("1.9.4"))
        assertEquals(10904, checker.parseVersionCodeFromTag("V1.9.4"))
        assertEquals(10900, checker.parseVersionCodeFromTag("1.9"))
        assertEquals(null, checker.parseVersionCodeFromTag("bad"))
    }
}
