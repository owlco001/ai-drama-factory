package com.dramafactory.app.ui

import com.dramafactory.core.model.AuditState
import com.dramafactory.core.quality.AssetAuditor
import com.dramafactory.core.quality.AssetInspector
import com.dramafactory.core.quality.StylePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * QualityEngine（App 侧编排，第九轮）：对齐 pavo 质量方法论的端上接入层。
 *
 * 职责：
 * - A. 资产质量闸门：G1 文件级硬校验（纯 Kotlin，不调 API）+ G2 多模态打分（agnes-2.5-flash 带图，
 *   defects 非空直接拒，失败重试 ≤3 次）；结果落库 assets 表 quality_score/audit_state/defects。
 * - B. 角色参考图套装（由 AssetsLogic.buildReferenceSheet 生成子卡）。
 * - C. 时代红线：内置西汉预设（StylePreset.HAN_DEFAULT），negative/suffix 折叠进生成 prompt；
 *   按剧集放行 allowed_cross_era（经 episodes 表落库）。
 * - D/E. 分镜六铁律 + 提交前忠实性校验（纯函数，在 core-engine quality 包；本类提供桥接）。
 * - F. 开场帧场景化重渲染 + 道具主资产 i2i（prompt 由 ShotDirector 构建，实际生成调 ImageProvider）。
 * - G. 成片色彩统一（FfmpegAssembler.gradeBatch）。
 * - H. 中文配音指令（ChineseAudioInjector，既有 Q9 已落地，核对一致性）。
 *
 * 不可移植（GPU 类）明确降级：InsightFace 锁脸 / GPT-SoVITS 音色克隆 / Demucs 人声分离
 * 在手机端不跑，报告标注「需服务端 / 降级为 DNA 参考注入」，不强行在手机跑。
 */
class QualityEngine {

    /**
     * 对单张资产执行 G1+G2 审计。
     * @param imageBytes 图片二进制（G1 解码用）
     * @param imageDataUri 图片 data URI（G2 多模态带图用）
     * @param description 资产 prompt（G2 上下文）
     * @param assetType character/scene/prop
     * @param pose 姿态名（character 人脸占比用）
     * @param estimatedFaceRatio 可选 Bitmap 估算人脸占比
     * @param describer G2 多模态调用（注入 AgnesProvider.chat 适配）
     * @return [AssetAuditOutcome]
     */
    suspend fun auditAsset(
        imageBytes: ByteArray,
        imageDataUri: String,
        description: String,
        assetType: String = "character",
        pose: String = "",
        estimatedFaceRatio: Double? = null,
        describer: AssetAuditor.ImageDescriber,
    ): AssetAuditOutcome {
        val result = AssetAuditor.audit(
            imageBytes = imageBytes,
            imageDataUri = imageDataUri,
            description = description,
            assetType = assetType,
            pose = pose,
            describer = describer,
            estimatedFaceRatio = estimatedFaceRatio,
        )
        val state = if (result.passed) AuditState.APPROVED else AuditState.REJECTED
        return AssetAuditOutcome(
            qualityScore = result.qualityScore,
            auditState = state,
            defects = result.defects,
            rejectReason = result.reason,
            g1ErrorCode = result.inspection?.errorCode,
            faceRatio = result.faceRatio,
            poseRole = pose.ifBlank { null },
        )
    }

    /** G1+G2 审计结果（落库用）。 */
    data class AssetAuditOutcome(
        val qualityScore: Double,
        val auditState: AuditState,
        val defects: List<String>,
        val rejectReason: String?,
        val g1ErrorCode: String?,
        val faceRatio: Double?,
        val poseRole: String?,
    ) {
        fun defectsJson(): String = "[" + defects.joinToString(",") { "\"$it\"" } + "]"
        fun isApproved() = auditState == AuditState.APPROVED
    }

    /**
     * 把 era 红线约束折叠进生成 prompt（C 子模块：negative 折叠进 negative_prompt，
     * positive 折叠进 suffix；按剧集放行剔除禁词）。
     */
    fun withEra(prompt: String, allowedCrossEra: List<String> = emptyList(), preset: StylePreset = StylePreset.HAN_DEFAULT): String =
        preset.withEraConstraints(prompt, allowedCrossEra)

    companion object {
        /** 全局默认西汉预设单例（App 启动即用）。 */
        val HAN_PRESET = StylePreset.HAN_DEFAULT
    }
}
