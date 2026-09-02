package com.dramafactory.app.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 更新通道：拉取远程最新版本并与当前版本号比对。
 *
 * 数据源：Gitee Release API（`repos/{owner}/{repo}/releases/latest`），
 * 与现有发布流程一致——发布即打 tag 并挂载 APK 附件，无需自建后端。
 *
 * 设计要点（便于 JVM 单测）：
 * - `httpClient` 注入，单测用 Ktor MockEngine 替换，不触网；
 * - `currentVersionName` 注入（如 "1.9.3"），与远程 tag 用同一套语义化规则比较，量纲一致；
 * - 远程版本号取自 release 的 `tag_name`（约定 vX.Y.Z）。
 *
 * 语义化版本号：major*10000 + minor*100 + patch（如 v1.9.4 → 10904，v1.9.3 → 10903），
 * 远程 code > 当前 code → 有更新；否则已是最新。
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val currentVersionName: String,
    private val owner: String = "owlco001",
    private val repo: String = "ai-drama-factory",
    private val releaseUrl: String = "https://gitee.com/owlco001/ai-drama-factory/releases/tag",
) {
    /**
     * 把版本名/ tag 转成语义化整数，便于比较：
     * "1.9.4"/"v1.9.4" → 1*10000 + 9*100 + 4 = 10904。
     * 段不足三位时缺段按 0 处理（"1.9" → 10900）。无法解析返回 null。
     */
    fun parseVersionCodeFromTag(tag: String): Int? {
        val cleaned = tag.trim().trimStart('v', 'V')
        val parts = cleaned.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10000 + minor * 100 + patch
    }

    /**
     * 拉取最新 release 并比对。
     * @return UpdateResult（Latest / UpdateAvailable / Error）
     */
    suspend fun check(): UpdateResult {
        val currentCode = parseVersionCodeFromTag(currentVersionName)
            ?: return UpdateResult.Error("当前版本号无法解析：$currentVersionName")
        return runCatching { httpClient.get("https://gitee.com/api/v5/repos/$owner/$repo/releases/latest") }
            .fold(
                onSuccess = { resp -> parseAndCompare(resp, currentCode) },
                onFailure = { UpdateResult.Error(it.message ?: "网络请求失败") },
            )
    }

    private suspend fun parseAndCompare(resp: HttpResponse, currentCode: Int): UpdateResult {
        val body = runCatching { resp.bodyAsText() }.getOrNull().orEmpty()
        if (body.isBlank()) return UpdateResult.Error("响应为空")
        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@runCatching UpdateResult.Error("缺少 tag_name")
            val versionName = tag.trim().trimStart('v', 'V')
            val notes = root["body"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            val downloadUrl = (root["assets"]?.jsonArray
                ?.firstOrNull()
                ?.jsonObject?.get("browser_download_url")
                ?.jsonPrimitive?.content)
                ?: "$releaseUrl/$tag"
            val remoteCode = parseVersionCodeFromTag(tag)
                ?: return@runCatching UpdateResult.Error("无法解析版本号：$tag")
            if (remoteCode > currentCode) {
                UpdateResult.UpdateAvailable(
                    versionName = versionName,
                    versionCode = remoteCode,
                    downloadUrl = downloadUrl,
                    notes = notes,
                )
            } else {
                UpdateResult.Latest(currentVersionName = versionName)
            }
        }.getOrElse { UpdateResult.Error(it.message ?: "解析响应失败") }
    }
}

sealed interface UpdateResult {
    /** 已是最新 */
    data class Latest(val currentVersionName: String) : UpdateResult
    /** 发现新版本 */
    data class UpdateAvailable(
        val versionName: String,
        val versionCode: Int,
        val downloadUrl: String,
        val notes: String? = null,
    ) : UpdateResult
    /** 检查失败（网络/解析） */
    data class Error(val message: String) : UpdateResult
}
