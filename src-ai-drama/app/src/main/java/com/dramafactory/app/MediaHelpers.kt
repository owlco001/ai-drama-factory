package com.dramafactory.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.dramafactory.core.model.ProviderError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * TD-4：从 AppGraph 上帝对象抽出的媒体工具（原 AppGraph 内的 private 方法 + extractFirstFrameAsDataUri）。
 * 纯函数/挂起函数，无 AppGraph 状态依赖，便于单独单测。
 */
object MediaHelpers {

    /**
     * video URL → 首帧 PNG data URI（供图像通道退化路径用）。
     * 退化路径仅在「激活非 Agnes 视频供应商且无 Agnes Key」时触发，截帧属兜底能力，
     * 失败不致命（由调用方按图像生成失败提示用户）。
     */
    suspend fun extractFirstFrameAsDataUri(url: String): String = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url)
            val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: throw ProviderError.TransientError("取首帧失败：$url")
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 下载图像为字节数组（data:image 直接 base64 解码；http(s) 走 java.net）。 */
    fun fetchImageBytes(url: String): ByteArray? = runCatching {
        if (url.startsWith("data:image")) {
            val b64 = url.substringAfter(",")
            Base64.decode(b64, Base64.DEFAULT)
        } else {
            java.net.URL(url).openStream().use { it.readBytes() }
        }
    }.getOrNull()

    /** 降采样到 512px 内 JPEG 并返回 data URI（配合 G2 多模态审计，防 base64 爆上下文）。 */
    fun downscaleToDataUri(bytes: ByteArray): String? = runCatching {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val scale = 512.0 / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
        val w = (bmp.width * scale).toInt().coerceIn(1, 512)
        val h = (bmp.height * scale).toInt().coerceIn(1, 512)
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()
}
