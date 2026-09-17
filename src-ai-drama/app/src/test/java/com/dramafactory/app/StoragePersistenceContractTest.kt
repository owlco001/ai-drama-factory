package com.dramafactory.app

import com.dramafactory.app.data.AssetEntity
import com.dramafactory.app.data.AssetQualityRow
import com.dramafactory.app.data.EpisodeEntity
import com.dramafactory.app.data.FinishedFilmEntity
import com.dramafactory.app.data.PersistenceActionExecutor
import com.dramafactory.app.data.PersistenceWriteException
import com.dramafactory.app.data.ProjectEntity
import com.dramafactory.app.data.ProviderConfigEntity
import com.dramafactory.app.data.RenderStateRow
import com.dramafactory.app.data.RenderTaskEntity
import com.dramafactory.app.data.ShotEntity
import com.dramafactory.app.data.ActionIdempotencyEntity
import com.dramafactory.app.storage.DefaultStorageGuard
import com.dramafactory.app.storage.StorageState
import com.dramafactory.app.storage.StorageUnavailableException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 存储持久化合同回归测试（P0）。
 * 覆盖：Broken DAO 显式失败（禁止空集合/0L/Unit 静默成功）；Room 阻断后守卫拦截 Provider；
 * 项目/剧本/资产/分镜四类写后读回校验；读回为空/字段不符失败；批量第 N 项失败停止；URL 回填失败。
 */
class StoragePersistenceContractTest {

    // ---------- 1. Broken DAO 显式失败 ----------

    @Test
    fun `BrokenDramaDao 写方法抛 StorageUnavailableException 而非 no-op`() = runBlocking {
        val dao = com.dramafactory.app.data.BrokenDramaDao()
        val e = assertFailsWith<StorageUnavailableException> {
            dao.upsertProject(ProjectEntity("p1", "x", created_at = 0L))
        }
        assertEquals("DAO_UNAVAILABLE", e.code)
    }

    @Test
    fun `BrokenDramaDao 读方法也抛异常而非返回空集合`() {
        runBlocking {
            val dao = com.dramafactory.app.data.BrokenDramaDao()
            assertFailsWith<StorageUnavailableException> { dao.listProjects() }
            assertFailsWith<StorageUnavailableException> { dao.assetsAllOf("p1") }
            assertFailsWith<StorageUnavailableException> { dao.shotsOf("p1_ep1") }
            assertFailsWith<StorageUnavailableException> { dao.episode("p1_ep1") }
        }
    }

    @Test
    fun `BrokenMovieLibraryDao 写方法抛异常而非返回 0`() {
        runBlocking {
            val dao = com.dramafactory.app.data.BrokenMovieLibraryDao()
            assertFailsWith<StorageUnavailableException> {
                dao.upsertFilmOf(FinishedFilmEntity(film_id = "f", episode_id = "e", project_id = "p",
                    filePath = "/x.mp4", fileSize = 1L, durationMs = 0L, createdAt = 0L))
            }
            assertFailsWith<StorageUnavailableException> { dao.deleteFilmOf("e") }
        }
    }

    // ---------- 2. 存储阻断后守卫拦截 ----------

    @Test
    fun `存储 Blocked 后守卫拦截 requireReady 抛异常并返回阻断状态`() {
        val guard = DefaultStorageGuard()
        guard.block("ROOM_INIT_FAILED", "本地存储不可用", "diag-1")
        assertIs<StorageState.Blocked>(guard.current())
        val blocked = guard.blockedState()
        assertEquals("diag-1", blocked?.diagnosticId)
        assertFailsWith<StorageUnavailableException> { guard.requireReady() }
    }

    @Test
    fun `存储阻断时写入执行器抛异常且不触达 DAO`() = runBlocking {
        val guard = DefaultStorageGuard()
        guard.block("ROOM_INIT_FAILED", "本地存储不可用", "diag-2")
        val dao = FakeDramaDao()
        assertFailsWith<StorageUnavailableException> {
            PersistenceActionExecutor.writeProject(dao, guard, "项目", "act-1")
        }
        assertEquals(0, dao.projectWrites) // 阻断时不得调用 DAO 写入
    }

    // ---------- 3. 四类实体写后读回成功 ----------

    @Test
    fun `项目写入成功后有读回证据且字段一致`() = runBlocking {
        val dao = FakeDramaDao()
        val guard = DefaultStorageGuard()
        val receipt = PersistenceActionExecutor.writeProject(dao, guard, "雪夜镖局", "act-new")
        assertTrue(receipt.verified)
        assertEquals("project", receipt.entityType)
        val pid = receipt.entityIds.single()
        assertEquals("雪夜镖局", dao.project(pid)?.name)
    }

    @Test
    fun `剧本(剧集)写入成功后有读回证据且 projectId 与 script 一致`() = runBlocking {
        val dao = FakeDramaDao()
        val guard = DefaultStorageGuard()
        val script = "第一幕：雪夜…" + "x".repeat(200)
        val ep = EpisodeEntity(episode_id = "p_1_ep1", project_id = "p_1", ep_no = 1, script_json = script)
        val receipt = PersistenceActionExecutor.writeEpisode(dao, guard, ep, "act-ep", expectedScript = script)
        assertTrue(receipt.verified)
        assertEquals(script, dao.episode("p_1_ep1")?.script_json)
    }

    @Test
    fun `资产写入成功后有读回证据且 projectId kind 一致`() = runBlocking {
        val dao = FakeDramaDao()
        val guard = DefaultStorageGuard()
        val asset = AssetEntity(asset_id = "a1", project_id = "p_1", kind = "character",
            prompt = "主角", updated_at = 0L)
        val receipt = PersistenceActionExecutor.writeAsset(dao, guard, asset, "act-a")
        assertTrue(receipt.verified)
        assertEquals("character", dao.assetsAllOf("p_1").single().kind)
    }

    @Test
    fun `分镜批量写入成功后有读回证据且集合一致`() = runBlocking {
        val dao = FakeDramaDao()
        val guard = DefaultStorageGuard()
        val shots = listOf(
            ShotEntity(shot_id = "p_1_ep1_shot1", episode_id = "p_1_ep1", project_id = "p_1", shot_no = 1),
            ShotEntity(shot_id = "p_1_ep1_shot2", episode_id = "p_1_ep1", project_id = "p_1", shot_no = 2),
        )
        val receipt = PersistenceActionExecutor.writeShotsBatch(dao, guard, "p_1_ep1", shots, "act-shots")
        assertTrue(receipt.verified)
        assertEquals(setOf("p_1_ep1_shot1", "p_1_ep1_shot2"), dao.shotsOf("p_1_ep1").map { it.shot_id }.toSet())
    }

    // ---------- 4. 读回为空 / 字段不符 失败 ----------

    @Test
    fun `项目写后读回为空返回 FAILED 语义异常`() = runBlocking {
        val dao = ReadBackNullDramaDao()
        val guard = DefaultStorageGuard()
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeProject(dao, guard, "项目", "act-new")
        }
        assertEquals("project.write", e.stage)
        assertEquals("act-new", e.actionId)
    }

    @Test
    fun `剧本写后字段不符返回 FAILED 语义异常`() = runBlocking {
        val dao = WrongFieldDramaDao()
        val guard = DefaultStorageGuard()
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeEpisode(
                dao, guard,
                EpisodeEntity("p_1_ep1", "p_1", 1, script_json = "剧本"),
                "act-ep",
                expectedScript = "剧本",
            )
        }
        assertEquals("episode.write", e.stage)
    }

    // ---------- 5. 批量第 N 项失败停止 ----------

    @Test
    fun `资产批量第 N 项失败立即抛且不写入第 N+1 项`() = runBlocking {
        val dao = FakeDramaDao()
        dao.failOnAssetId = "a2" // 第 2 项写入抛错
        val guard = DefaultStorageGuard()
        val assets = listOf(
            AssetEntity("a1", "p_1", "character", prompt = "A", updated_at = 0L),
            AssetEntity("a2", "p_1", "scene", prompt = "B", updated_at = 0L),
            AssetEntity("a3", "p_1", "prop", prompt = "C", updated_at = 0L),
        )
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeAssetsBatch(dao, guard, "p_1", assets, "act-batch")
        }
        assertEquals("asset.batch[1]", e.stage)
        // 第 2 项失败，不得继续第 3 项
        assertEquals(null, dao.assetsAllOf("p_1").firstOrNull { it.asset_id == "a3" })
        assertTrue(dao.assetsAllOf("p_1").any { it.asset_id == "a1" })
    }

    @Test
    fun `分镜批量读回集合不一致返回 FAILED 语义异常`() = runBlocking {
        val dao = ReadBackNullShotsDramaDao()
        val guard = DefaultStorageGuard()
        val shots = listOf(ShotEntity("p_1_ep1_shot1", "p_1_ep1", "p_1", 1))
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeShotsBatch(dao, guard, "p_1_ep1", shots, "act-shots")
        }
        assertEquals("shot.batch[0]", e.stage)
    }

    // ---------- 6. URL 回填失败 ----------

    @Test
    fun `URL 回填读回不一致返回 FAILED 语义异常`() = runBlocking {
        val dao = FakeDramaDao()
        dao.urlWriteMismatch = true
        val guard = DefaultStorageGuard()
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.setAssetRemoteUrlVerified(dao, guard, "a1", "https://img/x.png", "act-gen")
        }
        assertEquals("asset.remote_url", e.stage)
        assertEquals(listOf("a1"), e.entityIds)
    }

    @Test
    fun `URL 回填成功后有读回证据`() = runBlocking {
        val dao = FakeDramaDao()
        dao.assets["a1"] = AssetEntity("a1", "p_1", "character", prompt = "A", updated_at = 0L)
        val guard = DefaultStorageGuard()
        val receipt = PersistenceActionExecutor.setAssetRemoteUrlVerified(dao, guard, "a1", "https://img/y.png", "act-gen")
        assertTrue(receipt.verified)
        assertEquals("https://img/y.png", dao.assetRemoteUrl("a1"))
    }

    // ---------- 7. 剧本字段比对失败（project_id 不符，禁止把 episodeId 当 projectId） ----------

    @Test
    fun `资产 project_id 归属校验在 executor 层保证（防止把 episodeId 当 projectId）`() = runBlocking {
        val dao = PolluteProjectIdDao()
        val guard = DefaultStorageGuard()
        // 资产应写入真实项目 p_9；fake 把读回污染成 episodeId 式 project_id → 读回校验必须失败
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeAsset(
                dao, guard,
                AssetEntity("a1", "p_9", "character", prompt = "A", updated_at = 0L),
                "act-a")
        }
        assertEquals("asset.write", e.stage)
        assertTrue(e.message!!.contains("project"))
    }

    @Test
    fun `资产 prompt 和姿态字段读回不一致时失败`() = runBlocking {
        val dao = PollutePromptDao()
        val e = assertFailsWith<PersistenceWriteException> {
            PersistenceActionExecutor.writeAsset(
                dao, DefaultStorageGuard(),
                AssetEntity("a1", "p_9", "character", parent_id = "c1", pose_role = "front", prompt = "expected", updated_at = 0L),
                "act-pose")
        }
        assertEquals("asset.write", e.stage)
    }

    @Test
    fun `本地资产更新逐字段读回`() = runBlocking {
        val dao = LocalUpdateDao()
        val asset = AssetEntity("local1", "p_9", "local", prompt = "before", updated_at = 0L)
        dao.assets[asset.asset_id] = asset
        PersistenceActionExecutor.updateAssetLocalVerified(
            dao, DefaultStorageGuard(), asset, "act-local", "local", "content://image", null, null, "after")
        val saved = dao.assets.getValue("local1")
        assertEquals("content://image", saved.image_uri)
        assertEquals("after", saved.prompt)
    }

    // ============================================================
    // 测试 DAO
    // ============================================================

    open class FakeDramaDao : com.dramafactory.app.data.DramaDao {
        var projectWrites = 0
        var failOnAssetId: String? = null
        var urlWriteMismatch = false
        val projects = mutableMapOf<String, ProjectEntity>()
        val episodes = mutableMapOf<String, EpisodeEntity>()
        val assets = mutableMapOf<String, AssetEntity>()
        val shots = mutableMapOf<String, ShotEntity>()
        val renderTasks = mutableMapOf<String, RenderTaskEntity>()

        override suspend fun reserveAction(key: ActionIdempotencyEntity): Long = 1L
        override suspend fun completeAction(key: String, updatedAt: Long) {}
        override suspend fun releaseAction(key: String) {}
        override suspend fun upsertProject(p: ProjectEntity) { projectWrites++; projects[p.project_id] = p }
        override suspend fun listProjects(): List<ProjectEntity> = projects.values.sortedByDescending { it.created_at }
        override suspend fun project(id: String): ProjectEntity? = projects[id]
        override suspend fun deleteProject(id: String) { projects.remove(id) }
        override suspend fun upsertAsset(a: AssetEntity) {
            if (a.asset_id == failOnAssetId) throw IllegalStateException("注入的资产写入失败")
            assets[a.asset_id] = a
        }
        override suspend fun assetsOf(projectId: String, kind: String): List<AssetEntity> =
            assets.values.filter { it.project_id == projectId && it.kind == kind }
        override suspend fun assetsAllOf(projectId: String): List<AssetEntity> =
            assets.values.filter { it.project_id == projectId }
        override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) {}
        override suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long) {}
        override suspend fun setAssetQuality(assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?, rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long) {}
        override suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long) {}
        override suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long) {
            val a = assets[assetId] ?: throw IllegalStateException("no asset $assetId")
            assets[assetId] = if (urlWriteMismatch) a else a.copy(remote_url = remoteUrl)
        }
        override suspend fun setAssetEnrichedPrompt(assetId: String, enrichedPrompt: String?, updatedAt: Long) {}
        override suspend fun assetRemoteUrl(assetId: String): String? = assets[assetId]?.remote_url
        override suspend fun deleteAsset(assetId: String) { assets.remove(assetId) }
        override suspend fun assetQuality(assetId: String): AssetQualityRow? = null
        override suspend fun assetQualities(projectId: String): List<AssetQualityRow> = emptyList()
        override suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String) {}
        override suspend fun episodeAllowedCrossEra(episodeId: String): String? = null
        override suspend fun setReviewState(assetId: String, state: String) {}
        override suspend fun upsertShot(s: ShotEntity) { shots[s.shot_id] = s }
        override suspend fun shotsOf(episodeId: String): List<ShotEntity> = shots.values.filter { it.episode_id == episodeId }
        override suspend fun deleteShotsOf(episodeId: String) { shots.values.removeAll { it.episode_id == episodeId } }
        override suspend fun deleteShot(shotId: String) { shots.remove(shotId) }
        override suspend fun deleteRenderTask(shotId: String) { renderTasks.remove(shotId) }
        override suspend fun deleteRenderTasksOf(episodeId: String) { renderTasks.values.removeAll { it.episode_id == episodeId } }
        override suspend fun renderStatesOf(episodeId: String): List<RenderStateRow> = emptyList()
        override suspend fun setShotKeyframes(shotId: String, first: String?, last: String?) {}
        override suspend fun setShotReferenceVideo(shotId: String, uri: String?) {}
        override suspend fun shotKeyframes(shotId: String): ShotEntity? = shots[shotId]
        override suspend fun shotReferenceVideo(shotId: String): String? = null
        override suspend fun upsertRenderTask(t: RenderTaskEntity) { renderTasks[t.shot_id] = t }
        override suspend fun renderTasksOf(ep: String): List<RenderTaskEntity> = renderTasks.values.filter { it.episode_id == ep }
        override suspend fun renderTask(shotId: String): RenderTaskEntity? = renderTasks[shotId]
        override suspend fun renderTasksOfShot(shotId: String): List<RenderTaskEntity> = renderTasks.values.filter { it.shot_id == shotId }
        override suspend fun allEpisodeIds(): List<String> = renderTasks.keys.map { it.substringBeforeLast("_shot") }.distinct()
        override suspend fun renderTasksOfEpOrdered(ep: String): List<RenderTaskEntity> = renderTasksOf(ep)
        override suspend fun pendingRepoll(ep: String): List<RenderTaskEntity> = renderTasksOf(ep).filter { it.state == "SUBMITTED" }
        override suspend fun upsertProviderConfig(c: ProviderConfigEntity) {}
        override suspend fun verifiedConfig(channel: String): ProviderConfigEntity? = null
        override suspend fun upsertEpisode(e: EpisodeEntity) { episodes[e.episode_id] = e }
        override suspend fun episode(id: String): EpisodeEntity? = episodes[id]
        override suspend fun episodesOf(projectId: String): List<EpisodeEntity> = episodes.values.filter { it.project_id == projectId }
    }

    /** 写后读回总是返回 null（模拟 DAO 读回缺失）。 */
    private class ReadBackNullDramaDao : FakeDramaDao() {
        override suspend fun project(id: String): ProjectEntity? = null
    }

    private class PollutePromptDao : FakeDramaDao() {
        override suspend fun assetsAllOf(projectId: String): List<AssetEntity> =
            super.assetsAllOf(projectId).map { it.copy(prompt = "polluted") }
    }

    private class LocalUpdateDao : FakeDramaDao() {
        override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) {
            val a = assets[assetId] ?: return
            assets[assetId] = a.copy(source = source, image_uri = imageUri, video_uri = videoUri, reference_image_uri = referenceImageUri, prompt = prompt, updated_at = updatedAt)
        }
    }

    /** 资产读回时把 project_id 污染为 episodeId 式归属（模拟"把 episodeId 当 projectId"的落库错误）。 */
    private class PolluteProjectIdDao : FakeDramaDao() {
        override suspend fun assetsAllOf(projectId: String): List<AssetEntity> =
            assets.values.map { it.copy(project_id = "p_9_ep1") }
    }

    /** 写后读回字段被篡改（模拟 DAO 未真正落库）。 */
    private class WrongFieldDramaDao : FakeDramaDao() {
        override suspend fun episode(id: String): EpisodeEntity? = episodes[id]?.copy(project_id = "someone_else")
    }

    /** 分镜写后按 episode 读回为空（模拟未落库）。 */
    private class ReadBackNullShotsDramaDao : FakeDramaDao() {
        override suspend fun shotsOf(episodeId: String): List<ShotEntity> = emptyList()
    }

    /** 记录 DAO 写入调用次数（用于验证阻断时零写入）。 */
    private class RecordingFakeDramaDao : FakeDramaDao()
}