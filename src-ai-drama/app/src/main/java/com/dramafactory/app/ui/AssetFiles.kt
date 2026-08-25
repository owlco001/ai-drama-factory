package com.dramafactory.app.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 第八轮：本地上传 URI 落盘到 app 内部目录 filesDir/uploads/。
 *
 * 背景：相册 GetContent 返回的 content:// URI 只有临时读权限（回调内有效，进程重启即失效）；
 * 拍摄输出在 cacheDir/capture/ 随时可能被系统清理。统一在回调内拷贝为内部 file:// 后，
 * 资产卡预览（Coil）、图生图 input_images、图生视频 keyframes 引用都长期稳定。
 */
object AssetFiles {
    const val UPLOAD_DIR = "uploads"

    /**
     * 将任意可读 URI（content:// / file://）拷贝到 filesDir/uploads/ 并返回 file:// URI 字符串。
     * @param isVideo 视频 true / 图片 false（决定MIME回退扩展名与文件名前缀）
     * @return 内部 file:// URI 字符串；读取失败/空文件返回 null
     */
    suspend fun copyToInternal(ctx: Context, uri: Uri, isVideo: Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.filesDir, UPLOAD_DIR).apply { mkdirs() }
                val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
                val ext = AssetsLogic.AssetFileNames.extFromMime(
                    mime, fallback = if (isVideo) "mp4" else "jpg")
                val target = File(
                    dir,
                    AssetsLogic.AssetFileNames.internalFileName(
                        type = if (isVideo) "video" else "image", ext = ext))
                val ok = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                    target.length() > 0L   // 空文件视为失败（取消拍摄可能留下0字节文件）
                } ?: false
                if (ok) Uri.fromFile(target).toString() else null
            }.getOrNull()
        }
}
