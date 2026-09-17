package com.dramafactory.app.data

import com.dramafactory.app.storage.StorageGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 统一持久化执行器（P0 持久化可靠性）。
 *
 * 所有项目/剧本/资产/分镜/生成 URL 写入统一收敛到此通道，固定数据流：
 *   requireStorageReady → DAO write → 精确 ID 读回 → projectId/episodeId/关键字段比对 → WriteReceipt
 * 任一步失败即抛 [PersistenceWriteException]，不得 getOrNull、不得忽略影响数、不得在单项失败后继续计数。
 */
data class WriteReceipt(
    val entityType: String,
    val entityIds: List<String>,
    val verified: Boolean = true,
)

/** 持久化写入失败：带动作 ID、阶段、实体 ID，供上层构造结构化 FAILED 结果。 */
class PersistenceWriteException(
    val actionId: String,
    val stage: String,
    val entityIds: List<String>,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "$message [stage=$stage entity=${entityIds.joinToString(",")} cause=${cause?.let { it.javaClass.simpleName + ":" + (it.message ?: "").replace(Regex("(?i)(key|token|secret|password)\\s*[=:]\\s*\\S+"), "\\$1=[REDACTED]").take(240) } ?: "none"}]",
    cause,
)

object PersistenceActionExecutor {

    fun newProjectId(prefix: String = "p"): String = "${prefix}_${UUID.randomUUID()}"

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun fail(actionId: String, stage: String, entityId: String, msg: String, cause: Throwable? = null): Nothing =
        throw PersistenceWriteException(actionId, stage, listOf(entityId), msg, cause)

    /** 项目写入 + 读回：失败即抛，返回可验证的 [WriteReceipt]。 */
    suspend fun writeProject(dao: DramaDao, guard: StorageGuard, name: String, actionId: String): WriteReceipt {
        guard.requireReady()
        val pid = onIo {
            val id = newProjectId()
            try {
                dao.upsertProject(ProjectEntity(project_id = id, name = name, created_at = System.currentTimeMillis()))
            } catch (e: Throwable) {
                throw PersistenceWriteException(actionId, "project.write", listOf(id), "项目写入异常", e)
            }
            val back = dao.project(id)
                ?: fail(actionId, "project.write", id, "项目写入后按 ID 读回为空（数据库未落库）")
            if (back.name != name) {
                fail(actionId, "project.write", id, "项目字段校验不一致：name=${back.name} != $name")
            }
            id
        }
        return WriteReceipt("project", listOf(pid), true)
    }

    /** 剧本(剧集)写入 + 读回 + script 比对：失败即抛。 */
    suspend fun writeEpisode(
        dao: DramaDao,
        guard: StorageGuard,
        episode: EpisodeEntity,
        actionId: String,
        expectedScript: String? = null,
    ): WriteReceipt {
        guard.requireReady()
        val eid = onIo {
            try {
                dao.upsertEpisode(episode)
            } catch (e: Throwable) {
                throw PersistenceWriteException(actionId, "episode.write", listOf(episode.episode_id), "剧本写入异常", e)
            }
            val back = dao.episode(episode.episode_id)
                ?: fail(actionId, "episode.write", episode.episode_id, "剧本写入后按 ID 读回为空")
            if (back.project_id != episode.project_id || back.ep_no != episode.ep_no) {
                fail(actionId, "episode.write", episode.episode_id,
                    "剧本关键字段不一致：project_id=${back.project_id}/${episode.project_id} ep_no=${back.ep_no}/${episode.ep_no}")
            }
            if (expectedScript != null && back.script_json != expectedScript) {
                fail(actionId, "episode.write", episode.episode_id, "剧本 script_json 与写入不一致")
            }
            episode.episode_id
        }
        return WriteReceipt("episode", listOf(eid), true)
    }

    /** 单个资产写入 + 读回 + project/kind 比对：失败即抛。 */
    suspend fun writeAsset(dao: DramaDao, guard: StorageGuard, asset: AssetEntity, actionId: String): WriteReceipt {
        guard.requireReady()
        val aid = onIo {
            try {
                dao.upsertAsset(asset)
            } catch (e: Throwable) {
                throw PersistenceWriteException(actionId, "asset.write", listOf(asset.asset_id), "资产写入异常", e)
            }
            val back = dao.assetsAllOf(asset.project_id).firstOrNull { it.asset_id == asset.asset_id }
                ?: fail(actionId, "asset.write", asset.asset_id, "资产写入后按 ID 读回为空")
            if (back.project_id != asset.project_id || back.kind != asset.kind ||
                back.prompt != asset.prompt || back.parent_id != asset.parent_id || back.pose_role != asset.pose_role) {
                fail(actionId, "asset.write", asset.asset_id,
                    "资产关键字段校验不一致：project=${back.project_id}/${asset.project_id} " +
                        "kind=${back.kind}/${asset.kind} prompt=${back.prompt}/${asset.prompt}")
            }
            asset.asset_id
        }
        return WriteReceipt("asset", listOf(aid), true)
    }

    /** 资产批量写入（全有或失败）：第 N 项失败立即抛，不继续第 N+1 项。 */
    suspend fun writeAssetsBatch(
        dao: DramaDao,
        guard: StorageGuard,
        projectId: String,
        assets: List<AssetEntity>,
        actionId: String,
    ): WriteReceipt {
        guard.requireReady()
        val ids = onIo {
            assets.forEachIndexed { i, a ->
                try {
                    dao.upsertAsset(a)
                    val back = dao.assetsAllOf(projectId).firstOrNull { it.asset_id == a.asset_id }
                    if (back == null) fail(actionId, "asset.batch[$i]", a.asset_id, "第 ${i + 1} 项资产写入后读回为空")
                    if (back.project_id != projectId || back.kind != a.kind || back.prompt != a.prompt ||
                        back.parent_id != a.parent_id || back.pose_role != a.pose_role) {
                        fail(actionId, "asset.batch[$i]", a.asset_id, "第 ${i + 1} 项资产关键字段读回不一致")
                    }
                } catch (e: PersistenceWriteException) {
                    throw e
                } catch (e: Throwable) {
                    fail(actionId, "asset.batch[$i]", a.asset_id, "第 ${i + 1} 项资产写入异常", e)
                }
            }
            assets.map { it.asset_id }
        }
        return WriteReceipt("asset", ids, true)
    }

    /**
     * 分镜替换写入：先写并校验本批次，再删除旧批次中多余的镜，最后校验新集合。
     * 因此写入失败不会先破坏旧数据；旧镜也不会污染成功结果。
     */
    suspend fun writeShotsBatch(
        dao: DramaDao,
        guard: StorageGuard,
        episodeId: String,
        shots: List<ShotEntity>,
        actionId: String,
        verifyCollection: Boolean = true,
    ): WriteReceipt {
        guard.requireReady()
        val ids = onIo {
            try {
                dao.replaceShotsAtomically(episodeId, shots)
            } catch (e: Throwable) {
                fail(actionId, "shot.batch.replace", episodeId, "分镜替换事务失败", e)
            }
            shots.forEachIndexed { i, s ->
                val back = dao.shotsOf(episodeId).firstOrNull { it.shot_id == s.shot_id }
                if (back == null) fail(actionId, "shot.batch[$i]", s.shot_id, "第 ${i + 1} 镜写入后读回为空")
            }
            if (verifyCollection) {
                val expected = shots.map { it.shot_id }.toSet()
                val persisted = dao.shotsOf(episodeId)
                val byId = persisted.associateBy { it.shot_id }
                val missing = expected - byId.keys
                if (missing.isNotEmpty()) {
                    fail(actionId, "shot.batch.verify", missing.joinToString(","), "本批次分镜读回缺失：$missing")
                }
                shots.forEach { expectedShot ->
                    val actual = byId[expectedShot.shot_id]!!
                    if (actual.episode_id != episodeId || actual.project_id != expectedShot.project_id ||
                        actual.shot_no != expectedShot.shot_no || actual.action != expectedShot.action ||
                        actual.dialogue != expectedShot.dialogue || actual.first_asset_ids != expectedShot.first_asset_ids) {
                        fail(actionId, "shot.batch.verify", expectedShot.shot_id, "分镜关键字段读回不一致")
                    }
                }
                val finalIds = dao.shotsOf(episodeId).map { it.shot_id }.toSet()
                if (finalIds != expected) fail(actionId, "shot.batch.verify", expected.joinToString(","), "分镜替换后集合不一致：persisted=$finalIds expected=$expected")
            }
            shots.map { it.shot_id }
        }
        return WriteReceipt("shot", ids, true)
    }

    /** 生成 URL 回填 + 按 asset_id 读回比对：失败即抛，绝不把"写失败"当成生成成功。 */
    suspend fun setAssetRemoteUrlVerified(
        dao: DramaDao,
        guard: StorageGuard,
        assetId: String,
        url: String,
        actionId: String,
    ): WriteReceipt {
        guard.requireReady()
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
        if (url.isBlank() || parsed == null || parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            fail(actionId, "asset.remote_url", assetId, "URL 必须是非空 http/https 地址")
        }
        onIo {
            try {
                dao.setAssetRemoteUrl(assetId, url, System.currentTimeMillis())
            } catch (e: Throwable) {
                throw PersistenceWriteException(actionId, "asset.remote_url", listOf(assetId), "URL 回填写入异常", e)
            }
            val back = dao.assetRemoteUrl(assetId)
            if (back != url) {
                fail(actionId, "asset.remote_url", assetId,
                    "URL 回填读回不一致：back=${back?.take(40)} expected=${url.take(40)}")
            }
        }
        return WriteReceipt("asset.remote_url", listOf(assetId), true)
    }

    suspend fun deleteAssetsVerified(
        dao: DramaDao, guard: StorageGuard, projectId: String, assetIds: List<String>, actionId: String,
    ): WriteReceipt {
        guard.requireReady()
        val ids = assetIds.distinct()
        onIo {
            ids.forEachIndexed { index, id ->
                try { dao.deleteAsset(id) }
                catch (e: Throwable) {
                    fail(actionId, "asset.delete[$index]", id, "资产删除失败", e)
                }
                if (dao.assetsAllOf(projectId).any { it.asset_id == id }) {
                    fail(actionId, "asset.delete[$index]", id, "资产删除后仍可读回")
                }
            }
        }
        return WriteReceipt("asset.delete", ids, true)
    }

    suspend fun updateAssetPromptVerified(
        dao: DramaDao, guard: StorageGuard, asset: AssetEntity, prompt: String, actionId: String,
    ): WriteReceipt {
        guard.requireReady()
        onIo {
            try {
                dao.updateAssetLocal(asset.asset_id, asset.source, asset.image_uri, asset.video_uri,
                    asset.reference_image_uri, prompt, System.currentTimeMillis())
            } catch (e: Throwable) {
                fail(actionId, "asset.edit", asset.asset_id, "资产编辑失败", e)
            }
            val back = dao.assetsAllOf(asset.project_id).firstOrNull { it.asset_id == asset.asset_id }
            if (back?.prompt != prompt) fail(actionId, "asset.edit", asset.asset_id, "资产编辑后读回不一致")
        }
        return WriteReceipt("asset.edit", listOf(asset.asset_id), true)
    }

    /** 本地资产 URI 更新 + 全字段读回校验。 */
    suspend fun updateAssetLocalVerified(
        dao: DramaDao, guard: StorageGuard, asset: AssetEntity, actionId: String,
        source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String,
    ): WriteReceipt {
        guard.requireReady()
        onIo {
            try {
                dao.updateAssetLocal(asset.asset_id, source, imageUri, videoUri, referenceImageUri,
                    prompt, System.currentTimeMillis())
            } catch (e: Throwable) {
                fail(actionId, "asset.local", asset.asset_id, "本地资产更新失败", e)
            }
            val back = dao.assetsAllOf(asset.project_id).firstOrNull { it.asset_id == asset.asset_id }
            if (back == null || back.project_id != asset.project_id || back.kind != asset.kind ||
                back.source != source || back.image_uri != imageUri || back.video_uri != videoUri ||
                back.reference_image_uri != referenceImageUri || back.prompt != prompt) {
                fail(actionId, "asset.local", asset.asset_id, "本地资产更新后关键字段读回不一致")
            }
        }
        return WriteReceipt("asset.local", listOf(asset.asset_id), true)
    }

    suspend fun setReviewStateVerified(
        dao: DramaDao, guard: StorageGuard, assetId: String, state: String, projectId: String,
        actionId: String,
    ): WriteReceipt {
        guard.requireReady()
        onIo {
            try { dao.setReviewState(assetId, state) }
            catch (e: Throwable) { fail(actionId, "asset.review", assetId, "资产评审写入失败", e) }
            val back = dao.assetsAllOf(projectId).firstOrNull { it.asset_id == assetId }
            if (back?.review_state != state) fail(actionId, "asset.review", assetId, "资产评审后读回不一致")
        }
        return WriteReceipt("asset.review", listOf(assetId), true)
    }
}