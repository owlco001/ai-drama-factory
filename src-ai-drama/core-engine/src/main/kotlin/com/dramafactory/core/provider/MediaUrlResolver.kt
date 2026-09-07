package com.dramafactory.core.provider

/**
 * v1.9.28：本地媒体 URI → 公网 URL 解析器。
 *
 * 背景：Agnes Video 2.5 官方要求参考媒体为「公开可访问 URL」。
 * 此前管线直接把 content://（相册选择）、file://（本地上传）、data:image/...（base64）
 * 透传给服务端 → 远端拉不到图，400/422 或静默丢参考。
 *
 * 约定：
 * - http(s) 公网 URL：直接透传，不触碰；
 * - 其余 scheme：由 App/Desktop 层实现上传图床后返回公网 URL；
 * - core 默认实现 = 恒等透传（保持旧行为，单测/无图床环境可用）；
 * - 上传失败抛异常 → 队列转为 ValidationError（FAILED 可读原因），不烧钱提交。
 */
fun interface MediaUrlResolver {
    suspend fun resolve(localUri: String): String

    companion object {
        /** 无需上传：http(s) 公网地址直接可用 */
        fun isPublicHttp(uri: String): Boolean =
            uri.startsWith("http://") || uri.startsWith("https://")

        /** 需要走图床：data:/file:/content:// 及其他本地 scheme */
        fun needsUpload(uri: String): Boolean =
            uri.isNotBlank() && !isPublicHttp(uri)

        /** 默认：恒等透传（无图床环境/单测） */
        val IDENTITY: MediaUrlResolver = MediaUrlResolver { it }
    }
}
