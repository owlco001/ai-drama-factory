package com.dramafactory.core.quality

import com.dramafactory.core.model.ChatRequest
import com.dramafactory.core.model.ChatMessage
import com.dramafactory.core.provider.TextProvider

/**
 * G2 多模态审计（对齐 pavo asset_auditor.py，Stage 1.5 G2 闸门）。
 *
 * 先硬后软：G1 [AssetInspector] 失败直接 rejected（零模型成本）；
 * 通过后再调 agnes-2.5-flash（带图）打分，返回 {score, defects, face_ratio}；
 * **defects 非空即 DEFECT_DETECTED 硬惩罚直接拒**（不被总分稀释）；失败重试 ≤3 次。
 *
 * 纯逻辑 + 注入 [ImageDescriber]（即 AgnesProvider.chat 适配），便于 JVM 单测用假实现；
 * 不引入 Android / GPU 依赖。MVP 在手机端调用 agnes-2.5-flash 多模态理解（云端，非端侧模型）。
 */
object AssetAuditor {

    const val ERROR_QUALITY_BELOW_THRESHOLD = "QUALITY_BELOW_THRESHOLD"
    const val ERROR_DEFECT_DETECTED = "DEFECT_DETECTED"
    const val ERROR_FACE_RATIO_OUT_OF_RANGE = "FACE_RATIO_OUT_OF_RANGE"
    /** 第十轮：审计输入超上下文安全上限（熔断） */
    const val ERROR_CONTEXT_OVERLOAD = "CONTEXT_OVERLOAD"

    const val DEFAULT_MAX_ATTEMPTS = 3

    /** 多模态打分通道返回（对齐 pavo _parse_score）。 */
    data class ChannelResult(
        val score: Double = 0.0,
        val notes: String = "",
        val defects: List<String> = emptyList(),
        val faceRatio: Double? = null,
    )

    /** 单资产审计规则。 */
    data class AuditRules(
        val minQualityScore: Double = 0.7,
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        /** 缺陷中英双语词库（命中即 DEFECT_DETECTED 直接拒）。 */
        val defectLexicon: List<String> = DEFAULT_DEFECT_LEXICON,
        /** 质量打分 prompt（带图，{description} 占位）。 */
        val qualityPrompt: String = DEFAULT_QUALITY_PROMPT,
    )

    /** 单资产审计结果。 */
    data class AuditResult(
        val qualityScore: Double,
        val defects: List<String>,
        val faceRatio: Double?,
        val passed: Boolean,
        val errorCode: String?,
        val reason: String,
        val attempts: Int,
        val inspection: AssetInspector.InspectionResult? = null,
    )

    /** 图像描述调用适配（注入 AgnesProvider.chat 实现多模态带图打分）。 */
    fun interface ImageDescriber {
        /**
         * @param prompt 文本 prompt
         * @param imageDataUri 图片 data URI（data:image/png;base64,...）
         * @return 模型返回文本（应为 JSON）
         */
        suspend fun describe(prompt: String, imageDataUri: String): String
    }

    /**
     * 先硬后软审计单资产。
     * @param imageBytes 图片二进制（G1 解码用）
     * @param imageDataUri 图片 data URI（G2 多模态带图用）
     * @param description 资产 prompt（供模型上下文）
     * @param assetType character/scene/prop
     * @param pose 姿态名
     * @param rules 审计规则
     * @param inspectorRules G1 规则
     * @param describer 多模态调用（注入）
     * @param estimatedFaceRatio 可选 Bitmap 估算人脸占比
     */
    suspend fun audit(
        imageBytes: ByteArray,
        imageDataUri: String,
        description: String,
        assetType: String = "character",
        pose: String = "",
        rules: AuditRules = AuditRules(),
        inspectorRules: AssetInspector.Rules = AssetInspector.Rules(),
        describer: ImageDescriber,
        estimatedFaceRatio: Double? = null,
    ): AuditResult {
        // ---- G1 文件级硬校验 ----
        val inspection = AssetInspector.inspect(
            bytes = imageBytes, assetType = assetType, pose = pose,
            rules = inspectorRules, estimatedFaceRatio = estimatedFaceRatio,
        )
        if (!inspection.passed) {
            return AuditResult(
                qualityScore = 0.0, defects = emptyList(), faceRatio = null,
                passed = false, errorCode = inspection.errorCode,
                reason = inspection.reason, attempts = 0, inspection = inspection,
            )
        }

        // ---- G2 审计（重试 ≤3）；★第十轮：先熔断预检，超限直接失败不发API ----
        if (inputOverloaded(description, imageDataUri)) {
            return AuditResult(passed = false, qualityScore = 0.0, defects = emptyList(),
                faceRatio = null, inspection = inspection, attempts = 0,
                errorCode = ERROR_CONTEXT_OVERLOAD,
                reason = "审计输入超过${MAX_INPUT_TOKENS}token上限（多为图像base64过大），请缩小图片后重试")
        }
        // ---- G2 多模态打分（重试 ≤3）----
        var lastRaw = ""
        var lastErr: String? = null
        var channel: ChannelResult? = null
        for (attempt in 1..rules.maxAttempts) {
            try {
                val prompt = rules.qualityPrompt.replace("{description}", description)
                val raw = describer.describe(prompt, imageDataUri)
                lastRaw = raw
                channel = parseScore(raw)
                lastErr = null
                break
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                // 重试：指数退避由调用方/Provider 层处理，这里仅计数
            }
        }
        if (channel == null) {
            return AuditResult(
                qualityScore = 0.0, defects = emptyList(), faceRatio = null,
                passed = false, errorCode = "G2_CALL_FAILED",
                reason = "多模态审计调用失败（${rules.maxAttempts}次）：$lastErr",
                attempts = rules.maxAttempts, inspection = inspection,
            )
        }

        // defects 非空 → 硬惩罚直接拒
        val defects = channel.defects
        val passed: Boolean
        val errorCode: String?
        val reason: String
        if (defects.isNotEmpty()) {
            passed = false
            errorCode = ERROR_DEFECT_DETECTED
            reason = "检测到缺陷词：$defects"
        } else if (channel.score < rules.minQualityScore) {
            passed = false
            errorCode = ERROR_QUALITY_BELOW_THRESHOLD
            reason = "quality ${channel.score} < ${rules.minQualityScore}"
        } else {
            passed = true
            errorCode = null
            reason = "ok"
        }

        return AuditResult(
            qualityScore = channel.score, defects = defects, faceRatio = channel.faceRatio,
            passed = passed, errorCode = errorCode, reason = reason,
            attempts = rules.maxAttempts, inspection = inspection,
        )
    }

    /** 解析模型 JSON 输出：{score, notes, defects, face_ratio}（对齐 pavo _parse_score）。 */
    internal fun parseScore(text: String): ChannelResult {
        val json = extractJsonObject(text) ?: return ChannelResult(notes = "unparseable: ${text.take(120)}")
        val score = (json["score"] as? Number)?.toDouble() ?: 0.0
        val notes = when (val n = json["notes"]) {
            is String -> n
            is Map<*, *> -> n["missing_features"]?.toString() ?: ""
            else -> ""
        }
        val defects = when (val d = json["defects"]) {
            is List<*> -> d.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            else -> emptyList()
        }
        val fr = (json["face_ratio"] as? Number)?.toDouble()
        return ChannelResult(score = score, notes = notes, defects = defects, faceRatio = fr)
    }

    /** 从文本中提取第一个 {...} JSON 对象（容忍模型前后废话）。 */
    internal fun extractJsonObject(text: String): Map<String, Any?>? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (esc) { esc = false; continue }
            when (c) {
                '\\' -> esc = true
                '"' -> inStr = !inStr
                '{' -> if (!inStr) depth++
                '}' -> if (!inStr) { depth--; if (depth == 0) {
                    val sub = text.substring(start, i + 1)
                    return parseJsonToMap(sub)
                } }
            }
        }
        return null
    }

    /** 极简 JSON 对象解析（仅支持 {k:v,...}，值支持 number/string/boolean/array/嵌套对象；够用即可，避免引第三方）。 */
    private fun parseJsonToMap(s: String): Map<String, Any?>? = try {
        val trimmed = s.trim()
        val map = mutableMapOf<String, Any?>()
        // 去掉首尾 {}
        val inner = trimmed.removePrefix("{").removeSuffix("}")
        var i = 0
        while (i < inner.length) {
            // 读 key
            val kStart = inner.indexOf('"', i)
            if (kStart < 0) break
            val kEnd = inner.indexOf('"', kStart + 1)
            if (kEnd < 0) break
            val key = inner.substring(kStart + 1, kEnd)
            val colon = inner.indexOf(':', kEnd)
            if (colon < 0) break
            // 读 value
            val vStart = colon + 1
            val (value, next) = readValue(inner, vStart)
            map[key] = value
            i = next
        }
        map
    } catch (_: Exception) { null }

    private fun readValue(s: String, start: Int): Pair<Any?, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length) return null to s.length
        return when (s[i]) {
            '{' -> {
                val depthEnd = matchBrace(s, i)
                val sub = s.substring(i, depthEnd + 1)
                parseJsonToMap(sub) to depthEnd + 1
            }
            '[' -> {
                val end = matchBracket(s, i)
                val arr = parseJsonArray(s.substring(i + 1, end))
                arr to end + 1
            }
            '"' -> {
                val end = s.indexOf('"', i + 1)
                s.substring(i + 1, end) to end + 1
            }
            't', 'f' -> {
                if (s.startsWith("true", i)) true to i + 4
                else if (s.startsWith("false", i)) false to i + 5
                else null to i + 1
            }
            else -> {
                // number
                var j = i
                while (j < s.length && s[j] != ',' && s[j] != '}') j++
                val numStr = s.substring(i, j).trim()
                val num = numStr.toDoubleOrNull()
                num to j
            }
        }
    }

    private fun parseJsonArray(s: String): List<Any?> {
        val out = mutableListOf<Any?>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= s.length) break
            val (v, next) = readValue(s, i)
            out.add(v)
            i = next
        }
        return out
    }

    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in open until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            when (c) {
                '\\' -> esc = true
                '"' -> inStr = !inStr
                '{' -> if (!inStr) depth++
                '}' -> if (!inStr) { depth--; if (depth == 0) return i }
            }
        }
        return s.length - 1
    }

    private fun matchBracket(s: String, open: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in open until s.length) {
            val c = s[i]
            if (esc) { esc = false; continue }
            when (c) {
                '\\' -> esc = true
                '"' -> inStr = !inStr
                '[' -> if (!inStr) depth++
                ']' -> if (!inStr) { depth--; if (depth == 0) return i }
            }
        }
        return s.length - 1
    }

    /**
     * 适配 AgnesProvider（TextProvider.chat）为 [ImageDescriber]——真·多模态视觉审计。
     * ★第十轮修复（ContextWindowExceededError 196万token根因）：
     * 旧实现把整图 base64 当纯文本拼进消息 → 单次即爆模型上下文。
     * 现按 OpenAI 视觉格式走 image_url（data URI），配合端上512px降采样（几十KB），
     * 并由 AgnesProvider.pickTextModel 按规模自动选型+熔断，三层防护杜绝溢出。
     */
    fun agnesDescriber(provider: TextProvider, model: String = ""): ImageDescriber =
        ImageDescriber { prompt, imageDataUri ->
            // model留空 → Provider 按输入规模自动选择 agnes-2.5-flash / 2.0 / 1.5
            val resp = provider.chat(
                ChatRequest(
                    messages = listOf(
                        ChatMessage("user", DEFAULT_QUALITY_AUDIT_INSTRUCTION + "\n" + prompt,
                            imageUrl = imageDataUri)
                    ),
                    model = model,
                    temperature = 0.0,
                    enableThinking = false,
                    maxTokens = 1024,
                )
            )
            resp.content
        }

    /** 视觉审计附加指令（与 DEFAULT_QUALITY_PROMPT 配合使用） */
    const val DEFAULT_QUALITY_AUDIT_INSTRUCTION =
        "请查看这张生成图，严格按后续要求输出质检JSON。"

    val DEFAULT_DEFECT_LEXICON = listOf(
        "杂斑", "彩色杂斑", "噪点", "光斑", "水印", "文字", "表情包贴纸", "贴纸",
        "speckles", "color blotches", "noise", "lens flare", "watermark", "text", "sticker overlay",
    )

    /**
     * ★第十轮熔断器：发送前估算输入 token，超阈值直接拒绝发起 API 调用。
     * 估算：中文≈1字符1token、ASCII≈4字符1token；安全上限40万（官方上限524288，预留输出）。
     */
    const val MAX_INPUT_TOKENS = 400_000

    fun estimateTokens(text: String): Long {
        var cjk = 0L
        for (c in text) if (c.code > 0x2E80) cjk++
        val ascii = text.length - cjk
        return cjk + ascii / 4
    }

    /** true=输入超安全上限，调用方应放弃请求并提示用户，绝不发API */
    fun inputOverloaded(vararg texts: String): Boolean {
        val total = texts.sumOf { estimateTokens(it) }
        return total > MAX_INPUT_TOKENS
    }

    val DEFAULT_QUALITY_PROMPT = """
        你是一名严格的短剧资产质检员。请审查这张生成图是否符合资产卡要求。
        资产要求描述：{description}
        只回答一个 JSON 对象，不要任何解释：
        {"score": 0.0-1.0, "notes": "问题简述", "defects": ["杂斑/水印/文字/贴纸等缺陷词，无则空数组"], "face_ratio": 0.0-1.0}
        若画面含杂斑/水斑/文字/水印/贴纸等缺陷，务必列入 defects；人脸占比（头像占画面比例）填入 face_ratio，无人物为0。
    """.trimIndent()
}
