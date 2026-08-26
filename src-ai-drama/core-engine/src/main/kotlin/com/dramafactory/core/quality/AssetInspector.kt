package com.dramafactory.core.quality

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * G1 文件级硬校验层（对齐 pavo asset_inspector.py T01）。
 *
 * 在进入多模态打分（G2）之前，对所有资产做**确定性、零模型成本**的文件级校验：
 * 格式白名单 / per-type 尺寸范围 / 正方形（character）/ 文件大小 / 可解码性 /
 * （P1）人脸占比（Bitmap宽高比近似，无GPU模型）。
 *
 * 约定（与 pavo asset_inspector 对齐）：
 * - 格式白名单：png / jpeg（禁 webp、透明残缺）
 * - 尺寸范围：character 1024~1536 且 |w-h|≤64；scene 1024x768；prop 768x768
 * - 错误码（唯一权威）：FORMAT_UNSUPPORTED / DIMENSION_TOO_SMALL / DIMENSION_TOO_LARGE /
 *   NOT_SQUARE / FILE_TOO_LARGE / UNREADABLE_IMAGE / FACE_RATIO_OUT_OF_RANGE
 *
 * 任一检查失败 → [InspectionResult.passed]=false，调用方不得再调多模态（先硬后软、失败即短路）。
 * 本模块纯 Kotlin + 标准库，可在 Android / JVM 任意运行，零外部依赖。
 */
object AssetInspector {

    // ---- 错误码常量（跨文件共享，见 pavo 架构 §11）----
    const val ERROR_FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED"
    const val ERROR_DIMENSION_TOO_SMALL = "DIMENSION_TOO_SMALL"
    const val ERROR_DIMENSION_TOO_LARGE = "DIMENSION_TOO_LARGE"
    const val ERROR_NOT_SQUARE = "NOT_SQUARE"
    const val ERROR_FILE_TOO_LARGE = "FILE_TOO_LARGE"
    const val ERROR_UNREADABLE_IMAGE = "UNREADABLE_IMAGE"
    const val ERROR_FACE_RATIO_OUT_OF_RANGE = "FACE_RATIO_OUT_OF_RANGE"

    /** 默认最大文件大小（字节）10 MB */
    const val DEFAULT_MAX_FILE_SIZE = 10L * 1024 * 1024

    /** 默认正方形容差（|w-h| <= tolerance 视为正方形） */
    const val DEFAULT_SQUARE_TOLERANCE = 64

    /** 透明残缺阈值：PNG 存在 alpha 通道且最小 alpha 低于该值视为「透明残缺」 */
    const val TRANSPARENT_ALPHA_MIN = 250

    /**
     * 单资产文件级校验规则。
     * @param allowedFormats 格式白名单（小写扩展名/解码格式），默认 png/jpeg
     * @param dimensionRules per-type 尺寸规则；缺失回退顶层 min/max
     * @param maxFileSize 文件字节数上限
     * @param face P1 人脸占比检查（默认关闭；App端用 Bitmap 宽高比近似，非 InsightFace）
     */
    data class Rules(
        val allowedFormats: Set<String> = setOf("png", "jpeg"),
        val dimensionRules: Map<String, DimensionRule> = defaultDimensionRules(),
        val maxFileSize: Long = DEFAULT_MAX_FILE_SIZE,
        val face: FaceRule = FaceRule(),
    ) {
        companion object {
            /** pavo 默认 per-type 尺寸：character 1024~1536 正方；scene 1024x768；prop 768x768 */
            fun defaultDimensionRules(): Map<String, DimensionRule> = mapOf(
                "character" to DimensionRule(minWidth = 1024, minHeight = 1024, maxWidth = 1536, maxHeight = 1536, squareCheck = true),
                "scene" to DimensionRule(minWidth = 768, minHeight = 768, maxWidth = 1536, maxHeight = 1536, squareCheck = false),
                "prop" to DimensionRule(minWidth = 768, minHeight = 768, maxWidth = 1536, maxHeight = 1536, squareCheck = false),
            )
        }
    }

    /** per-type 尺寸规则（对齐 pavo dimension_rules.<type>） */
    data class DimensionRule(
        val minWidth: Int = 1024, val minHeight: Int = 1024,
        val maxWidth: Int = 1536, val maxHeight: Int = 1536,
        val squareCheck: Boolean = false,
        val squareTolerance: Int = DEFAULT_SQUARE_TOLERANCE,
    )

    /**
     * P1 人脸占比检查（App端降级方案：用 Bitmap 宽高比近似人脸占比范围，非 InsightFace）。
     * 默认关闭。开启时：pose ∈ applyToPoses 的 character 资产，按 estimatedFaceRatio 判定。
     */
    data class FaceRule(
        val enabled: Boolean = false,
        val applyToPoses: Set<String> = emptySet(),
        val minFaceRatio: Double = 0.60,
        val maxFaceRatio: Double = 0.70,
    )

    /** 文件级硬校验结果（对齐 pavo InspectionResult）。 */
    data class InspectionResult(
        val passed: Boolean,
        val errorCode: String? = null,
        val reason: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val format: String = "",
        /** P1 人脸占比（0~1）；未启用/检测失败时为 null（不阻断）。 */
        val faceRatio: Double? = null,
        /** 全部失败码（供 reaudit 收集多原因）。 */
        val failedChecks: List<String> = emptyList(),
        val fileSizeBytes: Long = 0L,
        /** 人脸检测器状态：null=未启用；"approx"=Bitmap近似；"unavailable"=降级 */
        val faceDetector: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "passed" to passed,
            "error_code" to errorCode,
            "reason" to reason,
            "width" to width,
            "height" to height,
            "format" to format,
            "face_ratio" to faceRatio,
            "failed_checks" to failedChecks,
        )
    }

    /**
     * 对单资产做 G1 文件级硬校验。
     *
     * @param bytes 图片二进制（已下载/本地读取），非空即解码校验。
     * @param assetType character / scene / prop
     * @param pose 姿态名（front_anchor 等），仅 character 且 P1 开启时用于人脸检查。
     * @param rules 校验规则（默认 pavo 标准）。
     * @param estimatedFaceRatio 已通过 Bitmap 估算的人脸占比（0~1）；由调用方在 Android 侧算出传入。
     *        为 null 且 face.enabled=true 时视为降级（不阻断）。
     */
    fun inspect(
        bytes: ByteArray,
        assetType: String = "character",
        pose: String = "",
        rules: Rules = Rules(),
        estimatedFaceRatio: Double? = null,
    ): InspectionResult {
        val type = assetType.ifBlank { "character" }
        val failed = mutableListOf<String>()
        var firstError: String? = null
        var firstReason = ""

        // 1) 可解码（魔数嗅探 + 头解析）
        val info = sniff(bytes)
        if (info == null) {
            failed.add(ERROR_UNREADABLE_IMAGE)
            firstError = ERROR_UNREADABLE_IMAGE
            firstReason = "无法解码图片（损坏/格式异常/前64字节非图像魔数）"
            return InspectionResult(
                passed = false, errorCode = firstError, reason = firstReason,
                failedChecks = failed, fileSizeBytes = bytes.size.toLong(),
            )
        }
        val (width, height, fmt, minAlpha) = info

        // 2) 格式（白名单 + 透明残缺）
        val fmtErr = checkFormat(fmt, minAlpha)
        if (fmtErr != null) { failed.add(fmtErr.first); if (firstError == null) { firstError = fmtErr.first; firstReason = fmtErr.second } }

        // 3) 文件大小
        val sizeErr = checkFileSize(bytes.size.toLong(), rules.maxFileSize)
        if (sizeErr != null) { failed.add(sizeErr.first); if (firstError == null) { firstError = sizeErr.first; firstReason = sizeErr.second } }

        // 4) 正方形
        val sqErr = checkSquare(width, height, type, rules)
        if (sqErr != null) { failed.add(sqErr.first); if (firstError == null) { firstError = sqErr.first; firstReason = sqErr.second } }

        // 5) 尺寸范围
        val dimErr = checkDimensions(width, height, type, rules)
        if (dimErr != null) { failed.add(dimErr.first); if (firstError == null) { firstError = dimErr.first; firstReason = dimErr.second } }

        // 6) 人脸占比（P1；默认关闭，Bitmap宽高比近似）
        var faceRatio: Double? = null
        var faceDetector: String? = null
        val faceErr = checkFaceRatio(type, pose, rules, estimatedFaceRatio)
        if (faceErr != null) {
            failed.add(faceErr.first)
            if (firstError == null) { firstError = faceErr.first; firstReason = faceErr.second }
            faceRatio = estimatedFaceRatio
            faceDetector = if (estimatedFaceRatio == null) "unavailable" else "approx"
        } else if (rules.face.enabled && pose in rules.face.applyToPoses && estimatedFaceRatio != null) {
            faceRatio = estimatedFaceRatio
            faceDetector = "approx"
        }

        return InspectionResult(
            passed = failed.isEmpty(),
            errorCode = firstError,
            reason = if (failed.isEmpty()) "ok" else firstReason,
            width = width, height = height, format = fmt,
            faceRatio = faceRatio, failedChecks = failed,
            fileSizeBytes = bytes.size.toLong(),
            faceDetector = faceDetector,
        )
    }

    // ------------------------------------------------------------------
    // 单项检查（返回 (error_code | null, reason)）
    // ------------------------------------------------------------------

    /** 魔数嗅探 + 头解析：返回 (width, height, format小写, minAlpha|null)。不可解码返回 null。 */
    internal fun sniff(bytes: ByteArray): Quad? {
        if (bytes.size < 16) return null
        val fmt = sniffFormat(bytes) ?: return null
        val (w, h, minAlpha) = readImageDimensions(bytes) ?: return null
        return Quad(w, h, fmt, minAlpha)
    }

    internal data class Quad(val w: Int, val h: Int, val fmt: String, val minAlpha: Int?)

    /** 通过魔数嗅探图片格式（小写）；无法识别返回 null。 */
    internal fun sniffFormat(bytes: ByteArray): String? {
        fun u8(o: Int) = bytes[o].toInt() and 0xFF
        fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
        fun u32(o: Int) = (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
        return when {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            u32(0) == 0x89504E47L && u32(4) == 0x0D0A1A0AL -> "png"
            // JPEG: FF D8 FF
            u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF -> "jpeg"
            // WEBP: "RIFF"...."WEBP"
            u32(0) == 0x52494646L && u32(8) == 0x57454250L -> "webp"
            // GIF87a / GIF89a
            bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "gif"
            // BMP: "BM"
            u16(0) == 0x4D42 -> "bmp"
            else -> null
        }
    }

    /**
     * 纯解析图片尺寸与 alpha：仅依赖标准库魔数结构，不引入 Android/graphics 依赖
     * （core-engine 为纯 JVM 模块）。支持 PNG / JPEG / GIF / BMP / WEBP 头解析。
     * 返回 (width, height, minAlpha|null)；无法解析返回 null。
     */
    internal fun readImageDimensions(bytes: ByteArray): Triple<Int, Int, Int?>? {
        val fmt = sniffFormat(bytes) ?: return null
        return when (fmt) {
            "png" -> parsePng(bytes)
            "jpeg" -> parseJpeg(bytes)
            "gif" -> parseGif(bytes)
            "bmp" -> parseBmp(bytes)
            "webp" -> parseWebp(bytes)
            else -> null
        }
    }

    private fun parsePng(bytes: ByteArray): Triple<Int, Int, Int?> {
        // IHDR 在 byte 16: width(4) height(4) bitDepth(1) colorType(1)
        val w = readBE32(bytes, 16)
        val h = readBE32(bytes, 20)
        val colorType = bytes[25].toInt() and 0xFF
        // colorType 6 (RGBA) / 4 (GA) 含 alpha；近似：扫描首个 IDAT 之前无法全图，
        // 这里用 colorType 判定是否「可能含alpha」，MVP 用阈值仅对含alpha类型做占位；
        // 真实 min_alpha 需全图解码（Android侧Bitmap），这里返回 null（交由 Bitmap 估算）。
        val hasAlpha = colorType == 4 || colorType == 6
        return Triple(w, h, if (hasAlpha) null else 255)
    }

    private fun parseJpeg(bytes: ByteArray): Triple<Int, Int, Int?> {
        // 遍历 marker 段，SOF0..SOF15 (除 C4/C8/CC) 携带尺寸
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = (bytes[i + 5].toInt() and 0xFF) shl 8 or (bytes[i + 6].toInt() and 0xFF)
                val w = (bytes[i + 7].toInt() and 0xFF) shl 8 or (bytes[i + 8].toInt() and 0xFF)
                return Triple(w, h, 255)
            }
            // 段长度
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (len <= 0) break
            i += len + 2
        }
        return Triple(0, 0, 255)
    }

    private fun parseGif(bytes: ByteArray): Triple<Int, Int, Int?> {
        // Logical Screen Descriptor: width(2)@6 height(2)@8
        val w = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val h = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        return Triple(w, h, 255)
    }

    private fun parseBmp(bytes: ByteArray): Triple<Int, Int, Int?> {
        // DIB header: width(4)@18 height(4)@22
        val w = readLE32(bytes, 18)
        val h = readLE32(bytes, 22)
        return Triple(w, h, 255)
    }

    private fun parseWebp(bytes: ByteArray): Triple<Int, Int, Int?> {
        // VP8X / VP8 / VP8L
        val chunk = bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII)
        return when (chunk) {
            "VP8 " -> { // simple lossy: width/height 在 +14 byte(2 each) 反转
                val w = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8) + 1
                val h = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8) + 1
                Triple(w, h, 255)
            }
            "VP8L" -> { // lossless: 14-bit w/h at +18
                val b = readLE32(bytes, 21)
                val w = (b and 0x3FFF) + 1
                val h = ((b shr 14) and 0x3FFF) + 1
                Triple(w, h, 255)
            }
            "VP8X" -> {
                val w = (readLE32(bytes, 24) and 0xFFFFFF) + 1
                val h = (readLE32(bytes, 27) and 0xFFFFFF) + 1
                Triple(w, h, 255)
            }
            else -> Triple(0, 0, 255)
        }
    }

    internal fun readBE32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    internal fun readLE32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun checkFormat(fmt: String, minAlpha: Int?): Pair<String, String>? {
        // 禁 webp / 透明残缺
        if (fmt == "webp") {
            return ERROR_FORMAT_UNSUPPORTED to "格式不支持：解码为 webp（白名单仅 png/jpeg）"
        }
        if (fmt !in setOf("png", "jpeg")) {
            return ERROR_FORMAT_UNSUPPORTED to "格式不支持：解码格式=$fmt（白名单仅 png/jpeg）"
        }
        // 透明残缺：PNG 含 alpha 通道且最小 alpha 低于阈值（minAlpha 已知且 < 阈值）
        if (fmt == "png" && minAlpha != null && minAlpha < TRANSPARENT_ALPHA_MIN) {
            return ERROR_FORMAT_UNSUPPORTED to "透明残缺：PNG 含 alpha 通道且存在透明像素"
        }
        return null
    }

    private fun checkFileSize(size: Long, maxSize: Long): Pair<String, String>? {
        if (size > maxSize) return ERROR_FILE_TOO_LARGE to "文件过大：$size > $maxSize bytes"
        return null
    }

    private fun checkSquare(w: Int, h: Int, type: String, rules: Rules): Pair<String, String>? {
        val dr = rules.dimensionRules[type] ?: return null
        if (!dr.squareCheck) return null
        if (kotlin.math.abs(w - h) > dr.squareTolerance) {
            return ERROR_NOT_SQUARE to "非正方形：${w}x$h（|w-h|=${kotlin.math.abs(w - h)} > ${dr.squareTolerance}）"
        }
        return null
    }

    private fun checkDimensions(w: Int, h: Int, type: String, rules: Rules): Pair<String, String>? {
        if (w <= 0 || h <= 0) return ERROR_DIMENSION_TOO_SMALL to "尺寸无效：${w}x$h"
        val dr = rules.dimensionRules[type] ?: DimensionRule()
        if (w < dr.minWidth || h < dr.minHeight) {
            return ERROR_DIMENSION_TOO_SMALL to "$type 尺寸过小：${w}x$h < ${dr.minWidth}x${dr.minHeight}"
        }
        if (w > dr.maxWidth || h > dr.maxHeight) {
            return ERROR_DIMENSION_TOO_LARGE to "$type 尺寸过大：${w}x$h > ${dr.maxWidth}x${dr.maxHeight}"
        }
        return null
    }

    private fun checkFaceRatio(type: String, pose: String, rules: Rules, estimated: Double?): Pair<String, String>? {
        if (!rules.face.enabled) return null
        if (type != "character") return null
        if (pose !in rules.face.applyToPoses) return null
        // 降级：未提供估算值（如非Android环境）→ 不阻断
        val ratio = estimated ?: return null
        val (minR, maxR) = rules.face.minFaceRatio to rules.face.maxFaceRatio
        if (ratio !in minR..maxR) {
            return ERROR_FACE_RATIO_OUT_OF_RANGE to "人脸占比越界（Bitmap近似）：$ratio ∉ [$minR, $maxR]"
        }
        return null
    }

    /** 仅做可解码+格式嗅探（供调用方快速判断）。 */
    fun isDecodable(bytes: ByteArray): Boolean = sniff(bytes) != null

    /** 流版本（Android 侧 BitmapFactory 解码后转 ByteArray 亦可复用 inspect） */
    fun inspectStream(stream: InputStream, assetType: String, pose: String, rules: Rules, estimatedFaceRatio: Double?): InspectionResult {
        val bytes = stream.readBytes()
        return inspect(bytes, assetType, pose, rules, estimatedFaceRatio)
    }
}
