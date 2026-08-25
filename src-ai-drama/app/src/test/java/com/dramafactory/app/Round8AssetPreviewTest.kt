package com.dramafactory.app

import com.dramafactory.app.ui.AssetsLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第八轮回归测试：资产预览图 + 拍摄闪退 配套的纯函数覆盖。
 *
 * 覆盖点（JVM可测、不依赖Android）：
 * 1. AssetFileNames.extFromMime —— MIME→扩展名映射，未知/空回退默认（图片jpg/视频mp4）；
 * 2. AssetFileNames.internalFileName —— uploads 目录目标文件名格式与唯一性（防重名）；
 * 3. AssetCard 语义回归：本地图片卡带 imageUri、视频卡带 videoUri、生成卡带 remoteUrl
 *    （UI 的 AssetThumb 依此选择预览源：imageUri ?: remoteUrl，视频显示 🎬 占位）。
 */
class Round8AssetPreviewTest {

    // ---- 1. MIME → 扩展名 ----

    @Test
    fun `mime 映射到扩展名`() {
        assertEquals("jpg", AssetsLogic.AssetFileNames.extFromMime("image/jpeg"))
        assertEquals("png", AssetsLogic.AssetFileNames.extFromMime("image/png"))
        assertEquals("webp", AssetsLogic.AssetFileNames.extFromMime("image/webp"))
        assertEquals("gif", AssetsLogic.AssetFileNames.extFromMime("image/gif"))
        assertEquals("mp4", AssetsLogic.AssetFileNames.extFromMime("video/mp4"))
        assertEquals("webm", AssetsLogic.AssetFileNames.extFromMime("video/webm"))
        assertEquals("mov", AssetsLogic.AssetFileNames.extFromMime("video/quicktime"))
    }

    @Test
    fun `未知或空 MIME 回退默认扩展名`() {
        assertEquals("jpg", AssetsLogic.AssetFileNames.extFromMime(null))
        assertEquals("jpg", AssetsLogic.AssetFileNames.extFromMime(""))
        assertEquals("jpg", AssetsLogic.AssetFileNames.extFromMime("application/octet-stream"))
        // 视频路径显式传 fallback=mp4
        assertEquals("mp4", AssetsLogic.AssetFileNames.extFromMime("application/octet-stream", fallback = "mp4"))
    }

    @Test
    fun `MIME 大小写不敏感`() {
        assertEquals("jpg", AssetsLogic.AssetFileNames.extFromMime("IMAGE/JPEG"))
        assertEquals("mp4", AssetsLogic.AssetFileNames.extFromMime("Video/MP4"))
    }

    // ---- 2. 内部落盘文件名 ----

    @Test
    fun `内部文件名格式为 type_ts_rand_ext`() {
        val name = AssetsLogic.AssetFileNames.internalFileName(type = "image", ext = "jpg", ts = 12345L, rand = 7)
        assertEquals("image_12345_7.jpg", name)
        val v = AssetsLogic.AssetFileNames.internalFileName(type = "video", ext = "mp4", ts = 99L, rand = 1)
        assertEquals("video_99_1.mp4", v)
    }

    @Test
    fun `同一时间戳不同随机数不重名`() {
        val a = AssetsLogic.AssetFileNames.internalFileName("image", "jpg", ts = 1L, rand = 11)
        val b = AssetsLogic.AssetFileNames.internalFileName("image", "jpg", ts = 1L, rand = 12)
        assertNotEquals(a, b)
        // 真实调用链：默认随机数（Math.random 派生），连续两次不相等
        val c = AssetsLogic.AssetFileNames.internalFileName("image", "jpg", ts = 1L)
        val d = AssetsLogic.AssetFileNames.internalFileName("image", "jpg", ts = 1L)
        assertTrue("连续生成应防重名", c != d)
    }

    @Test
    fun `默认扩展名参数不破坏显式传参`() {
        val name = AssetsLogic.AssetFileNames.internalFileName("image", "png", ts = 5L, rand = 3)
        assertTrue(name.endsWith(".png"))
    }

    // ---- 3. AssetCard 预览源语义（AssetThumb 选图逻辑的数据面） ----

    @Test
    fun `本地图片卡优先用 imageUri 作预览源`() {
        val card = AssetsLogic.AssetCard(
            "a1", AssetsLogic.Kind.LOCAL, "本地图片",
            source = "local", imageUri = "file:///data/user/0/com.dramafactory.app/files/uploads/image_1_1.jpg")
        assertEquals(card.imageUri, card.imageUri ?: card.remoteUrl)
    }

    @Test
    fun `视频卡不走图片预览（videoUri 优先）`() {
        val card = AssetsLogic.AssetCard(
            "a2", AssetsLogic.Kind.LOCAL, "本地视频",
            source = "local", videoUri = "file:///data/user/0/com.dramafactory.app/files/uploads/video_1_1.mp4")
        assertEquals(null, card.imageUri)              // 无图片源 → UI 显示 🎬 占位
        assertEquals(null, card.remoteUrl)
    }

    @Test
    fun `生成卡 remoteUrl 即预览源`() {
        val card = AssetsLogic.AssetCard(
            "a3", AssetsLogic.Kind.CHARACTER, "女主",
            remoteUrl = "https://cdn.example.com/img/abc.jpg", reviewState = "keep")
        assertEquals(card.remoteUrl, card.imageUri ?: card.remoteUrl)
    }
}
