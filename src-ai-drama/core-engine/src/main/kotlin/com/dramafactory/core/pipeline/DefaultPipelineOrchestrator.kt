package com.dramafactory.core.pipeline

import com.dramafactory.core.model.GateReport
import com.dramafactory.core.model.PipelineStage
import com.dramafactory.core.provider.CheckpointStore
import com.dramafactory.core.provider.PipelineOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 管线编排器 —— 七阶段状态机 + Gate判定 + 恢复总入口（架构§2/§3）。
 * 恢复语义：读checkpoint → SUBMITTED有video_id的镜全部先进re-poll队头（零重复付费）
 * → COMPLETED文件缺失重置 → 续跑队列。
 */
class DefaultPipelineOrchestrator(
    private val checkpointStore: CheckpointStore,
    private val queue: DefaultRenderQueue?,
    /** 可选：按集取队列（队列实例为单episode单worker，多集并行恢复时由App层提供各自队列） */
    private val queueFor: ((episodeId: String) -> DefaultRenderQueue?)? = null,
) : PipelineOrchestrator {

    private val _stage = MutableStateFlow(PipelineStage.S1_PROJECT)
    override val stage: StateFlow<PipelineStage> get() = _stage

    override suspend fun evaluateGates(projectId: String): GateReport {
        // MVP桩：四闸门由UI/分镜层回填真实结果；此处按当前阶段给出结构
        return GateReport(stage = _stage.value, budgetOk = true, keyValid = true,
            reviewPassed = true, storyboardPassed = true)
    }

    override suspend fun advanceTo(stage: PipelineStage) { _stage.value = stage }

    /**
     * P1-6：进程重启恢复总入口。遍历checkpointStore全部episodeId逐个enqueueEpisode——
     * 队列恢复路径（pendingRepoll队头 + SUBMITTING→RECONCILE）保证已付费镜绝不重提。
     * shots 由 checkpoint 权威态还原（shotId即键，prompt可后补）。
     */
    override suspend fun recoverOnBoot() {
        val fallback = queue
        // P1-6修复：queue为null但提供了queueFor时（多集并行恢复）不得提前返回——
        // 恢复入口必须覆盖App层按集取队列的形态；两者皆无才无事可做。
        if (fallback == null && queueFor == null) return
        val ids = checkpointStore.allEpisodeIds()
        for (episodeId in ids) {
            val cp = checkpointStore.getEpisode(episodeId) ?: continue
            val q = queueFor?.invoke(episodeId) ?: fallback ?: continue
            q.enqueueEpisode(episodeId, cp.shots.map {
                com.dramafactory.core.model.ShotMeta(it.shotId, episodeId, "")
            })
        }
    }
}
