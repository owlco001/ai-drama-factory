package com.dramafactory.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 第六轮回归测试（Round6LocalUploadI2iTest 的修复版）。
 *
 * 修复说明（原4个红测的根因）：
 * 1. 提取/空剧本/本地上传三测失败根因相同：AssetsViewModel.init 用 viewModelScope.launch(Dispatchers.Default)
 *    异步加载 episode，而测试用 StandardTestDispatcher 只 advance 了 main 调度器，
 *    Default 上的 init 协程从未执行 → scriptText 未complete、uploadLocal 后台落库协程未跑。
 *    生产代码语义正确（真机异步加载没问题）；修法=测试侧把 AppGraph 注入后先 runCurrent+advanceUntilIdle
 *    让 init 完成，再触发动作。原测试在 uploadLocal 前只 runCurrent() 一次不够（init 协程里还有 withContext(IO)
 *    切换，需要 advanceUntilIdle 驱动）。
 * 2. 渲染队列透传测失败：queue worker 在 this (runTest) scope 上启动真实 delay 循环，runTest 结束时
 *   未完成 job 报 UncaughtExceptionsBeforeTest。修法=用 backgroundScope 承载 queue，并用 awaitIdle 等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Round6LocalUploadI2iTest {

    private val dispatcher = UnconfinedTestDispatcher()  // launch立即执行，避免init协程不被驱动

    /** 内存假 DAO，覆盖第六轮新增方法 */
    class MemDao : com.dramafactory.app.data.DramaDao {
        val assets = mutableListOf<com.dramafactory.app.data.AssetEntity>()
        val shots = mutableListOf<com.dramafactory.app.data.ShotEntity>()
        var episode: com.dramafactory.app.data.EpisodeEntity? = null

        override suspend fun upsertProject(p: com.dramafactory.app.data.ProjectEntity) {}
        override suspend fun listProjects() = emptyList<com.dramafactory.app.data.ProjectEntity>()
        override suspend fun project(id: String): com.dramafactory.app.data.ProjectEntity? = null
        override suspend fun deleteProject(id: String) {}
        override suspend fun upsertAsset(a: com.dramafactory.app.data.AssetEntity) { assets.removeIf { it.asset_id == a.asset_id }; assets.add(a) }
        override suspend fun assetsOf(projectId: String, kind: String) = assets.filter { it.project_id == projectId && it.kind == kind }
        override suspend fun assetsAllOf(projectId: String) = assets.filter { it.project_id == projectId }
        override suspend fun updateAssetLocal(assetId: String, source: String, imageUri: String?, videoUri: String?, referenceImageUri: String?, prompt: String, updatedAt: Long) {
            val a = assets.firstOrNull { it.asset_id == assetId }
            if (a != null) {
                assets.removeIf { it.asset_id == assetId }
                assets.add(a.copy(source = source, image_uri = imageUri, video_uri = videoUri, reference_image_uri = referenceImageUri, prompt = prompt, updated_at = updatedAt))
            }
        }
        override suspend fun setAssetReferenceImage(assetId: String, referenceImageUri: String?, updatedAt: Long) {
            val a = assets.firstOrNull { it.asset_id == assetId } ?: return
            assets.removeIf { it.asset_id == assetId }
            assets.add(a.copy(reference_image_uri = referenceImageUri, updated_at = updatedAt))
        }
        override suspend fun setAssetQuality(assetId: String, qualityScore: Double?, auditState: String, defectsJson: String?, rejectReason: String?, g1ErrorCode: String?, faceRatio: Double?, poseRole: String?, updatedAt: Long) {}
        override suspend fun updateAssetPrompt(assetId: String, prompt: String, updatedAt: Long) {
            val a = assets.firstOrNull { it.asset_id == assetId } ?: return
            assets.removeIf { it.asset_id == assetId }
            assets.add(a.copy(prompt = prompt, updated_at = updatedAt))
        }
        override suspend fun setAssetRemoteUrl(assetId: String, remoteUrl: String, updatedAt: Long) {
            val a0 = assets.firstOrNull { it.asset_id == assetId } ?: return
            assets.removeIf { it.asset_id == assetId }
            assets.add(a0.copy(remote_url = remoteUrl, updated_at = updatedAt))
        }
        override suspend fun setAssetEnrichedPrompt(assetId: String, enrichedPrompt: String?, updatedAt: Long) {
            val a0 = assets.firstOrNull { it.asset_id == assetId } ?: return
            assets.removeIf { it.asset_id == assetId }
            assets.add(a0.copy(enriched_prompt = enrichedPrompt, updated_at = updatedAt))
        }
        override suspend fun assetRemoteUrl(assetId: String): String? =
            assets.firstOrNull { it.asset_id == assetId }?.remote_url
        override suspend fun deleteAsset(assetId: String) { assets.removeIf { it.asset_id == assetId } }
        override suspend fun assetQuality(assetId: String): com.dramafactory.app.data.AssetQualityRow? = null
        override suspend fun assetQualities(projectId: String): List<com.dramafactory.app.data.AssetQualityRow> = emptyList()
        override suspend fun setEpisodeAllowedCrossEra(episodeId: String, allowed: String) {}
        override suspend fun episodeAllowedCrossEra(episodeId: String): String? = null
        override suspend fun setReviewState(assetId: String, state: String) {}
        override suspend fun upsertShot(s: com.dramafactory.app.data.ShotEntity) { shots.removeIf { it.shot_id == s.shot_id }; shots.add(s) }
        override suspend fun shotsOf(episodeId: String) = shots.filter { it.episode_id == episodeId }
        override suspend fun deleteShotsOf(episodeId: String) { shots.removeIf { it.episode_id == episodeId } }
        override suspend fun deleteShot(shotId: String) { shots.removeIf { it.shot_id == shotId } }
        override suspend fun renderStatesOf(episodeId: String) = shots.map { com.dramafactory.app.data.RenderStateRow(it.shot_id, "PENDING", null) }
        override suspend fun setShotKeyframes(shotId: String, first: String?, last: String?) {
            val s = shots.firstOrNull { it.shot_id == shotId }
            if (s != null) { shots.removeIf { it.shot_id == shotId }; shots.add(s.copy(first_image_uri = first, last_image_uri = last)) }
        }
        override suspend fun setShotReferenceVideo(shotId: String, uri: String?) {
            val s = shots.firstOrNull { it.shot_id == shotId }
            if (s != null) { shots.removeIf { it.shot_id == shotId }; shots.add(s.copy(reference_video_uri = uri)) }
        }
        override suspend fun shotKeyframes(shotId: String) = shots.firstOrNull { it.shot_id == shotId }
        override suspend fun shotReferenceVideo(shotId: String) = shots.firstOrNull { it.shot_id == shotId }?.reference_video_uri
        override suspend fun upsertRenderTask(t: com.dramafactory.app.data.RenderTaskEntity) {}
        override suspend fun renderTasksOf(ep: String) = emptyList<com.dramafactory.app.data.RenderTaskEntity>()
        override suspend fun renderTask(shotId: String) = null
        override suspend fun renderTasksOfShot(shotId: String) = emptyList<com.dramafactory.app.data.RenderTaskEntity>()
        override suspend fun allEpisodeIds() = emptyList<String>()
        override suspend fun renderTasksOfEpOrdered(ep: String) = emptyList<com.dramafactory.app.data.RenderTaskEntity>()
        override suspend fun pendingRepoll(ep: String) = emptyList<com.dramafactory.app.data.RenderTaskEntity>()
        override suspend fun upsertProviderConfig(c: com.dramafactory.app.data.ProviderConfigEntity) {}
        override suspend fun verifiedConfig(channel: String) = null
        override suspend fun upsertEpisode(e: com.dramafactory.app.data.EpisodeEntity) { episode = e }
        override suspend fun episode(id: String) = episode?.takeIf { it.episode_id == id }
        override suspend fun episodesOf(projectId: String) = listOfNotNull(episode?.takeIf { it.project_id == projectId })
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun fakeAgnes() = com.dramafactory.core.provider.AgnesProvider(
        apiKeyProvider = { "sk-test" },
        client = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { respond("{}", io.ktor.http.HttpStatusCode.OK) }))

    // ---------------------------------------------------------------
    // 1) 提取资产卡无反应 根因修复（init异步竞态）
    // ---------------------------------------------------------------
    @Test
    fun 提取按钮_clicked_before_script_loaded_仍建卡() = kotlinx.coroutines.runBlocking {
        val dao = MemDao()
        dao.episode = com.dramafactory.app.data.EpisodeEntity(
            episode_id = "p1_ep1", project_id = "p1", ep_no = 1,
            script_json = "角色：林晚、陈默\n场景：雨夜街头",
            stage_flags = """{"script_mode":true}""")
        com.dramafactory.app.AppGraph.dao = dao
        com.dramafactory.app.AppGraph.agnes = fakeAgnes()

        val vm = com.dramafactory.app.ui.AssetsViewModel("p1")
        // init 协程用 withContext(Dispatchers.IO) 真实线程，测试侧真实等待其完成
        kotlinx.coroutines.withTimeout(5000) {
            while (!vm.scriptMode.value) kotlinx.coroutines.delay(10)
        }

        vm.extractFromScript()
        kotlinx.coroutines.withTimeout(5000) {
            while (vm.assets.value.size < 2) kotlinx.coroutines.delay(10)
        }

        val cards = vm.assets.value
        assertTrue("角色林晚应被提取", cards.any { it.kind == com.dramafactory.app.ui.AssetsLogic.Kind.CHARACTER && it.prompt == "林晚" })
        assertTrue("场景雨夜街头应被提取", cards.any { it.kind == com.dramafactory.app.ui.AssetsLogic.Kind.SCENE && it.prompt == "雨夜街头" })
        assertTrue("至少2张卡", cards.size >= 2)
    }

    @Test
    fun 空剧本_提取给出提示不崩溃() = kotlinx.coroutines.runBlocking {
        val dao = MemDao()
        dao.episode = com.dramafactory.app.data.EpisodeEntity(episode_id = "p2_ep1", project_id = "p2", ep_no = 1,
            script_json = null, stage_flags = "{}")
        com.dramafactory.app.AppGraph.dao = dao
        com.dramafactory.app.AppGraph.agnes = fakeAgnes()
        val vm = com.dramafactory.app.ui.AssetsViewModel("p2")
        // init 协程跑在真实IO线程，等其完成（scriptText.complete(null)）
        kotlinx.coroutines.delay(200)
        vm.extractFromScript()
        kotlinx.coroutines.withTimeout(5000) {
            while (vm.extractMessage.value == null) kotlinx.coroutines.delay(10)
        }
        assertEquals(0, vm.assets.value.size)
        assertEquals("未能读取剧本文本（请确认已导入剧本）", vm.extractMessage.value)
    }

    // ---------------------------------------------------------------
    // 2) 本地上传 URI 落库
    // ---------------------------------------------------------------
    @Test
    fun 本地上传图片_落库source与imageUri() = kotlinx.coroutines.runBlocking {
        val dao = MemDao()
        com.dramafactory.app.AppGraph.dao = dao
        com.dramafactory.app.AppGraph.agnes = fakeAgnes()
        val vm = com.dramafactory.app.ui.AssetsViewModel("p3")

        val id = vm.uploadLocal(imageUri = "content://media/image/9", videoUri = null, prompt = "我的剧照")
        assertTrue("应返回有效id", id.isNotBlank())
        // 等待落库协程（viewModelScope+IO真实线程）完成：先upsert再updateAssetLocal(source=local)
        kotlinx.coroutines.withTimeout(5000) {
            while (dao.assets.none { it.asset_id == id && it.source == "local" }) kotlinx.coroutines.delay(10)
        }

        val card = vm.assets.value.first { it.assetId == id }
        assertEquals(com.dramafactory.app.ui.AssetsLogic.Kind.LOCAL, card.kind)
        assertEquals("local", card.source)
        assertEquals("content://media/image/9", card.imageUri)

        val persisted = dao.assets.first { it.asset_id == id }
        assertEquals("local", persisted.source)
        assertEquals("content://media/image/9", persisted.image_uri)
    }

    @Test
    fun 本地上传视频_落库videoUri() = kotlinx.coroutines.runBlocking {
        val dao = MemDao()
        com.dramafactory.app.AppGraph.dao = dao
        com.dramafactory.app.AppGraph.agnes = fakeAgnes()
        val vm = com.dramafactory.app.ui.AssetsViewModel("p4")
        val id = vm.uploadLocal(imageUri = null, videoUri = "content://media/video/7")
        kotlinx.coroutines.withTimeout(5000) {
            while (vm.assets.value.none { it.assetId == id }) kotlinx.coroutines.delay(10)
        }
        val card = vm.assets.value.first { it.assetId == id }
        assertEquals("local", card.source)
        assertEquals("content://media/video/7", card.videoUri)
    }

    @Test
    fun 无URI_本地上传返回空() {
        val logic = com.dramafactory.app.ui.AssetsLogic()
        val id = logic.addLocalAsset(assetId = "x1", imageUri = null, videoUri = null)
        assertEquals("", id)
        assertEquals(0, logic.assets.value.size)
    }

    // ---------------------------------------------------------------
    // 3) 图生图（i2i）：参考图 → input_images
    // ---------------------------------------------------------------
    @Test
    fun 图生图_参考图作为inputImages传入() = runTest {
        val logic = com.dramafactory.app.ui.AssetsLogic()
        var capturedInputImages: List<String>? = null
        logic.generateHandler = { card ->
            capturedInputImages = com.dramafactory.core.model.ImageGenRequest(
                prompt = card.prompt,
                inputImages = if (card.referenceImageUri != null) listOf(card.referenceImageUri!!) else emptyList()).inputImages
            Result.success("data:image/png;base64,xxx")
        }
        logic.addAsset("c1", com.dramafactory.app.ui.AssetsLogic.Kind.CHARACTER, "女主")
        logic.setReferenceImage("c1", "content://media/ref/1")
        assertEquals("content://media/ref/1", logic.assets.value.first().referenceImageUri)

        logic.generate("c1")
        assertEquals(listOf("content://media/ref/1"), capturedInputImages)
    }

    @Test
    fun 图生图_无参考图_inputImages为空() = runTest {
        val logic = com.dramafactory.app.ui.AssetsLogic()
        var captured: List<String>? = null
        logic.generateHandler = { card ->
            captured = com.dramafactory.core.model.ImageGenRequest(
                prompt = card.prompt,
                inputImages = if (card.referenceImageUri != null) listOf(card.referenceImageUri!!) else emptyList()).inputImages
            Result.success("u")
        }
        logic.addAsset("c2", com.dramafactory.app.ui.AssetsLogic.Kind.SCENE, "雨夜")
        logic.generate("c2")
        assertEquals(emptyList<String>(), captured)
    }

    // ---------------------------------------------------------------
    // 4) 图生视频（keyframes）& 5) 视频参考：AgnesProvider 参数组装
    // ---------------------------------------------------------------
    private suspend fun captureSubmitBody(req: com.dramafactory.core.model.VideoSubmitRequest): String {
        var bodyText = ""
        val engine = io.ktor.client.engine.mock.MockEngine { req2 ->
            bodyText = (req2.body as? io.ktor.http.content.TextContent)?.text ?: ""
            respond("""{"video_id":"v1"}""", HttpStatusCode.OK)
        }
        val client = io.ktor.client.HttpClient(engine)
        val provider = com.dramafactory.core.provider.AgnesProvider(
            apiKeyProvider = { "sk" }, client = client)
        provider.submitVideo(req)
        return bodyText
    }

    @Test
    fun 图生视频_双帧_组装keyframes() = runTest {
        val body = captureSubmitBody(com.dramafactory.core.model.VideoSubmitRequest(
            shotId = "s1", prompt = "p", firstImageUri = "data:img/first", lastImageUri = "data:img/last"))
        assertTrue("应含 mode=keyframes", body.contains("\"mode\"") && body.contains("keyframes"))
        assertTrue("image 应为 [first,last]", body.contains("data:img/first") && body.contains("data:img/last"))
    }

    @Test
    fun 图生视频_单参考图_作为image首帧() = runTest {
        val body = captureSubmitBody(com.dramafactory.core.model.VideoSubmitRequest(
            shotId = "s2", prompt = "p", referenceImageUri = "data:img/ref"))
        assertFalse("非keyframes不应有mode", body.contains("\"mode\""))
        assertTrue("image 应为参考图", body.contains("data:img/ref"))
    }

    @Test
    fun 视频参考_组装referenceVideo() = runTest {
        val body = captureSubmitBody(com.dramafactory.core.model.VideoSubmitRequest(
            shotId = "s3", prompt = "p", referenceVideoUri = "content://media/video/rv"))
        assertTrue("应包含 reference_video", body.contains("content://media/video/rv"))
    }

    @Test
    fun 视频参考_模型标记支持() {
        val spec = com.dramafactory.core.model.ModelSpec("agnes-video-v2.0", "Agnes 视频").apply { supportsVideoReference = true }
        assertTrue(spec.supportsVideoReference)
        val spec2 = com.dramafactory.core.model.ModelSpec("kling", "可灵")
        assertFalse(spec2.supportsVideoReference)   // 默认 false，UI 据此隐藏入口
    }

    // ---------------------------------------------------------------
    // 渲染队列：关键帧 + 视频参考 resolver 透传
    // ---------------------------------------------------------------
    @Test
    fun 渲染队列_关键帧与视频参考透传给submit() = runTest(dispatcher) {
        val captured = mutableListOf<com.dramafactory.core.model.VideoSubmitRequest>()
        val provider = object : com.dramafactory.core.provider.VideoProvider {
            override val id = "fake"
            override suspend fun validateKey(key: String) = Result.success(com.dramafactory.core.model.ConnectionInfo(true))
            override fun listModels() = listOf(com.dramafactory.core.model.ModelSpec("agnes-video-v2.0", "Agnes 视频").apply { supportsVideoReference = true })
            override suspend fun submitVideo(req: com.dramafactory.core.model.VideoSubmitRequest): String { captured.add(req); return "t_${req.shotId}" }
            override suspend fun pollResult(providerTaskId: String) = com.dramafactory.core.model.PollResult.Completed("u")
        }
        val queue = com.dramafactory.core.pipeline.DefaultRenderQueue(
            scope = backgroundScope,
            videoProvider = provider,
            checkpointStore = com.dramafactory.core.storage.InMemoryCheckpointStore(),
            budgetGuard = com.dramafactory.core.pipeline.DefaultBudgetGuard(),
            downloader = { _, _ -> "f" to 1L },
        )
        queue.shotKeyframeResolver = { "sK" to "sL" }
        queue.shotReferenceVideoResolver = { "rv_uri" }

        queue.enqueueEpisode("ep1", listOf(com.dramafactory.core.model.ShotMeta("sh1", "ep1", "prompt")))
        // 轮询等待worker提交（worker跑在backgroundScope上由调度器驱动；此处直接advance驱动虚拟时间）
        dispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.withTimeout(5000) {
            while (captured.isEmpty()) kotlinx.coroutines.delay(10)
        }
        assertTrue(captured.isNotEmpty())
        val r = captured.first()
        assertEquals("sK", r.firstImageUri)
        assertEquals("sL", r.lastImageUri)
        assertEquals("rv_uri", r.referenceVideoUri)
    }
}
