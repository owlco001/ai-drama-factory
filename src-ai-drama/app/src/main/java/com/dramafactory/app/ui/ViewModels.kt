package com.dramafactory.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
import com.dramafactory.core.quality.AssetAuditor
import com.dramafactory.core.quality.StylePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页ViewModel（P0）——包装SettingsLogic并接AppGraph真实引擎。
 */
class SettingsViewModel : ViewModel() {

    private val logic = SettingsLogic(
        videoProvider = AppGraph.video,
        keyVault = AppGraph.keyVault,
        configId = AppGraph.CONFIG_VIDEO,   // MVP只做视频通道Key（Text/Image同供应商共用）
    )
    val state: StateFlow<SettingsLogic.UiState> get() = logic.state

    init { refresh() }

    fun refresh() = viewModelScope.launch { withContext(Dispatchers.IO) { logic.refresh() } }
    fun onKeyChanged(text: String) = logic.onKeyChanged(text)

    /** 「测试连通」按钮：调Agnes validateKey显示成功/失败 */
    fun testConnection() = viewModelScope.launch { logic.testConnection() }

    /** 保存到KeyVault（EncryptedSharedPreferences） */
    fun saveKey() = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { logic.saveKey(forceWithoutTest = false) }
        if (!ok) {
            // 未测试通过或输入为空：UI按saved=false展示提示（force路径留给用户显式选择）
            logic.onKeyChanged(logic.state.value.keyInput)
        }
    }

    fun deleteKey() = viewModelScope.launch { withContext(Dispatchers.IO) { logic.deleteKey() } }

    // ---- 供应商选择 + 自定义模型（第四轮）----
    fun selectProvider(providerId: String) { logic.selectProvider(providerId) }
    fun onCustomFieldChanged(field: String, value: String) = logic.onCustomFieldChanged(field, value)
    fun saveCustomModel() = viewModelScope.launch {
        withContext(Dispatchers.IO) { logic.saveCustomModel() }
    }
}

/**
 * 渲染队列页ViewModel——包装QueueLogic并接DefaultRenderQueue+Room。
 */
class QueueViewModel(private val episodeId: String) : ViewModel() {

    // ★第五轮加固：queueFor/budgetGuard构造失败（AppGraph未就绪等）时兜底Fakes队列，
    // VM仍可创建，页面显示空状态而非闪退。
    private val logic = runCatching {
        QueueLogic(queue = RenderRuntime.queue(), budgetGuard = AppGraph.budgetGuard)
    }.getOrElse {
        android.util.Log.e("QueueViewModel", "queue init failed, degraded", it)
        QueueLogic(queue = DegradedRenderQueue(),
            budgetGuard = DegradedBudgetGuard())
    }.apply {
        // RECONCILE处置落库：重试→PENDING / 放弃→BLOCKED（权威终态）
        onReconcileResolve = { shotId, retry ->
            withContext(Dispatchers.IO) {
                AppGraph.dao.renderTask(shotId)?.let { row ->
                    AppGraph.dao.upsertRenderTask(
                        if (retry) row.copy(state = "PENDING", blocked_reason = null)
                        else row.copy(state = "BLOCKED", blocked_reason = row.blocked_reason ?: "用户放弃")
                    )
                }
            }
        }
        // 镜状态实时刷新源：Room render_tasks表
        shotStateReader = {
            withContext(Dispatchers.IO) {
                AppGraph.dao.renderTasksOfEpOrdered(episodeId).associate { it.shot_id to it.state }
            }
        }
        // 第六轮：图生视频关键帧 + 视频参考解析器（从 shots 表读取本镜已设参考）
        setKeyframeResolver { shotId ->
            withContext(Dispatchers.IO) {
                AppGraph.dao.shotKeyframes(shotId)?.let { it.first_image_uri to it.last_image_uri }
                    ?: (null to null)
            }
        }
        setReferenceVideoResolver { shotId ->
            // 仅当当前视频模型支持视频参考时返回；否则空（Agnes标记支持）
            withContext(Dispatchers.IO) {
                val cfg = AppGraph.dao.verifiedConfig("video")
                val supported = (AppGraph.video.listModels().firstOrNull { it.id == (cfg?.model ?: "agnes") }
                    ?: AppGraph.video.listModels().first()).supportsVideoReference
                if (supported) AppGraph.dao.shotReferenceVideo(shotId) else null
            }
        }
    }
    val state: StateFlow<QueueLogic.UiState> get() = logic.state

    /** 队列运行时单例接线（见RenderRuntime） */
    init { logic.startWatching(viewModelScope) }
    override fun onCleared() { logic.stopWatching(); super.onCleared() }

    fun enqueue(shots: List<com.dramafactory.core.model.ShotMeta>) =
        viewModelScope.launch { logic.enqueue(episodeId, shots) }
    fun pause() = viewModelScope.launch { logic.pause() }
    fun resume() = viewModelScope.launch { logic.resume() }
    fun cancelShot(shotId: String) = logic.cancelShot(shotId)
    fun confirmBudget() = viewModelScope.launch { logic.confirmBudget() }
    fun dismissBudgetConfirm() = logic.dismissBudgetConfirm()
    fun openReconcileDialog(shotId: String, reason: String) = logic.openReconcileDialog(shotId, reason)
    fun resolveReconcile(retry: Boolean) = viewModelScope.launch { logic.resolveReconcile(retry) }
    fun dismissReconcileDialog() = logic.dismissReconcileDialog()
    fun clearEnqueueError() = logic.clearEnqueueError()

    // ==================== 第六轮：图生视频 / 视频参考 ====================
    /** 为某镜设置关键帧（图生视频）：首帧/尾帧 URI 落库 shots 表 */
    fun setShotKeyframe(shotId: String, first: String?, last: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { AppGraph.dao.setShotKeyframes(shotId, first, last) }
    }
    /** 为某镜设置视频参考（仅模型支持时由UI调用） */
    fun setShotReferenceVideo(shotId: String, uri: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { AppGraph.dao.setShotReferenceVideo(shotId, uri) }
    }
    /** 当前视频模型是否支持视频参考（UI门控「上传参考视频」入口） */
    fun videoModelSupportsReference(): Boolean {
        val cfg = runCatching { kotlinx.coroutines.runBlocking { AppGraph.dao.verifiedConfig("video") } }.getOrNull()
        val model = cfg?.model ?: "agnes"
        return AppGraph.video.listModels().firstOrNull { it.id == model }?.supportsVideoReference ?: false
    }
}

/**
 * 项目列表页ViewModel。
 */
class ProjectsViewModel : ViewModel() {

    private val logic = ProjectsLogic().apply {
        persistProject = { name, novel -> ioPersist(name, novel) }
        loadProjects = { ioLoad() }
        deleteProjectRow = { id -> withContext(Dispatchers.IO) { AppGraph.dao.deleteProject(id) } }
    }
    val state: StateFlow<ProjectsLogic.UiState> get() = logic.state
    private val _createError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val createError: kotlinx.coroutines.flow.StateFlow<String?> get() = _createError

    init { refresh() }

    fun refresh() = viewModelScope.launch { logic.refresh() }
    fun onNameChanged(text: String) = logic.onNameChanged(text)
    fun importNovel(fileName: String?, text: String?) = logic.importNovel(fileName, text)

    // ---- 剧本导入（第四轮）----
    fun selectMode(mode: ProjectsLogic.ImportMode) = logic.selectMode(mode)
    fun onPasteInputChanged(text: String) = logic.onPasteInputChanged(text)
    fun importDocument(mode: ProjectsLogic.ImportMode, fileName: String?, text: String?, pasted: Boolean) =
        logic.importDocument(mode, fileName, text, pasted)
    fun clearImportError() = logic.clearImportError()

    /** 新建项目并返回新id（导航进入项目用）；持久化异常不再静默——落crash日志并提示 */
    fun create(onCreated: (String?) -> Unit) = viewModelScope.launch {
        val id = try { logic.createProject() } catch (t: Throwable) {
            android.util.Log.e("ProjectsViewModel", "createProject failed", t)
            _createError.value = t.message ?: t.javaClass.simpleName
            null
        }
        onCreated(id)
    }

    fun delete(projectId: String) = viewModelScope.launch { logic.deleteProject(projectId) }

    // ---- Room IO ----
    private suspend fun ioPersist(name: String, novel: String?): String = withContext(Dispatchers.IO) {
        val projectId = "p_${System.currentTimeMillis()}"
        AppGraph.dao.upsertProject(com.dramafactory.app.data.ProjectEntity(
            project_id = projectId, name = name, created_at = System.currentTimeMillis()))
        if (novel != null) {
            val epId = "${projectId}_ep1"
            // 剧本模式：script_json存剧本原文；stage_flags标记SCRIPT_MODE，
            // 资产页据此跳过文本分析直接进分镜编辑（AssetsViewModel读取该标志）
            val isScript = logic.state.value.importMode == ProjectsLogic.ImportMode.SCRIPT
            val flags = if (isScript) """{"script_mode":true,"scene_hint":${logic.state.value.sceneHint}}""" else "{}"
            AppGraph.dao.upsertEpisode(com.dramafactory.app.data.EpisodeEntity(
                episode_id = epId, project_id = projectId, ep_no = 1,
                script_json = novel.take(100_000), stage_flags = flags))
        }
        projectId
    }
    private suspend fun ioLoad(): List<ProjectsLogic.ProjectItem> = withContext(Dispatchers.IO) {
        AppGraph.dao.listProjects().map {
            ProjectsLogic.ProjectItem(it.project_id, it.name, createdAt = it.created_at)
        }
    }
}

/**
 * 资产库页ViewModel。
 */
/**
 * 第十轮：资产页按「剧集」工作——episodeId 为准（项目内分集后，每集独立持有资产/剧本）。
 * projectId 由 episodeId 推导（{projectId}_ep{n}）。
 */
class AssetsViewModel(private val episodeId: String) : ViewModel() {
    private val projectId: String = episodeId.substringBeforeLast("_ep")

    private val logic = AssetsLogic().apply {
        // 资产生成：文本通道出细化prompt → 图像通道出图（双Provider桩接线）
        // 第九轮：生成 prompt 折叠 era 红线约束（C）；图生图参考图作为 input_images。
        generateHandler = { card ->
            withContext(Dispatchers.IO) {
                val preset = com.dramafactory.app.ui.QualityEngine.HAN_PRESET
                val basePrompt = runCatching {
                    AppGraph.text.chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                        com.dramafactory.core.model.ChatMessage("user",
                            "为短剧资产生成一段中文图像提示词（50字内）：${card.prompt}"))))
                }.getOrNull()?.content ?: card.prompt
                // C. era 红线：正负分离——prompt只带正向，禁词走 negative_prompt API字段
                // （第十一轮修复：旧实现把禁词表拼进正面prompt，图像模型把"手机/塑料"全画出来了）
                val erPrompt = preset.withEraConstraints(basePrompt)
                val negPrompt = preset.negativePromptFor()
                val url = AppGraph.image.generateImage(
                    com.dramafactory.core.model.ImageGenRequest(
                        prompt = erPrompt,
                        negativePrompt = negPrompt,
                        inputImages = if (card.referenceImageUri != null) listOf(card.referenceImageUri) else emptyList()))
                // A. 资产质量闸门：G1 文件级硬校验 + G2 多模态打分（defects 直接拒，重试≤3）
                runCatching { auditGeneratedAsset(card.assetId, url, erPrompt, card) }
                Result.success(url)
            }
        }
        reviewPersist = { assetId, st ->
            withContext(Dispatchers.IO) { AppGraph.dao.setReviewState(assetId, st) }
        }
        // ★第十一轮：生成结果落盘——内存卡与assets表双写，进程被杀不丢图
        generateResultPersist = { assetId, url ->
            withContext(Dispatchers.IO) {
                runCatching { AppGraph.dao.setAssetRemoteUrl(assetId, url, System.currentTimeMillis()) }
            }
        }
    }
    val assets: StateFlow<List<AssetsLogic.AssetCard>> get() = logic.assets

    // 第九轮：G2 多模态审计 describer（agnes-2.5-flash 带图，enable_thinking=false）
    private val describer = AssetAuditor.agnesDescriber(AppGraph.text)

    /**
     * 对生成结果执行 G1+G2 资产质量闸门，并落库质量状态（A 子模块）。
     * G1 失败直接 rejected（零模型成本）；G2 调 agnes-2.5-flash 打分，defects 非空直接拒。
     */
    private suspend fun auditGeneratedAsset(assetId: String, imageUrl: String, prompt: String, card: AssetsLogic.AssetCard) {
        // 仅对可下载的 http(s)/data uri 执行（本地 file:// 跳过网络解码，仅记 pending）
        if (!imageUrl.startsWith("http") && !imageUrl.startsWith("data:image")) return
        runCatching {
            val bytes = fetchBytes(imageUrl) ?: return@runCatching
            // ★第十轮：图像先降采样到512px内JPEG(quality 80)，base64后约几十KB——
            // 杜绝把百万级base64塞进请求（ContextWindowExceededError 根因）
            val smallUri = runCatching {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("decode fail")
                val scale = 512.0 / maxOf(bmp.width, bmp.height).coerceAtLeast(1)
                val w = (bmp.width * scale).toInt().coerceIn(1, 512)
                val h = (bmp.height * scale).toInt().coerceIn(1, 512)
                val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                val bos = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
                "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                    bos.toByteArray(), android.util.Base64.NO_WRAP)
            }.getOrNull() ?: return@runCatching
            val dataUri = if (imageUrl.startsWith("data:image") && imageUrl.length < smallUri.length * 4) imageUrl
                else smallUri
            val outcome = qualityEngine.auditAsset(
                imageBytes = bytes, imageDataUri = dataUri, description = prompt,
                assetType = card.kind.name.lowercase(), pose = card.poseRole ?: "",
                describer = describer)
            AppGraph.dao.setAssetQuality(
                assetId = assetId,
                qualityScore = outcome.qualityScore,
                auditState = outcome.auditState.name.lowercase(),
                defectsJson = outcome.defectsJson(),
                rejectReason = outcome.rejectReason,
                g1ErrorCode = outcome.g1ErrorCode,
                faceRatio = outcome.faceRatio,
                poseRole = outcome.poseRole,
                updatedAt = System.currentTimeMillis())
            // 同步到内存卡（UI 显示评分/拒绝原因）
            logic.updateQuality(assetId, outcome.auditState.name.lowercase(), outcome.qualityScore,
                outcome.rejectReason, outcome.defectsJson())
        }
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        if (url.startsWith("data:image")) {
            val b64 = url.substringAfter(",")
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } else {
            java.net.URL(url).openStream().use { it.readBytes() }
        }
    }.getOrNull()

    private val qualityEngine = QualityEngine()

    /** 剧本模式状态：stage_flags.script_mode=true 时资产页显示「一键提取」入口 */
    private val _scriptMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    val scriptMode: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _scriptMode
    /** 第九轮：本集已放行跨时代器物清单（时代红线按剧集放行） */
    private val _allowedCrossEra = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val allowedCrossEra: kotlinx.coroutines.flow.StateFlow<List<String>> get() = _allowedCrossEra
    /**
     * 第六轮修复（提取无反应根因）：剧本原文从 episodes.script_json 异步读取。
     * 旧实现用 `var scriptText` 普通字段，init 协程还没返回时按钮已可点击，
     * 此时 scriptText 仍为 null → extractFromScript 直接 return，列表永远不更新。
     * 改用 CompletableDeferred 持有剧本，提取前 await（挂起非阻塞），确保读到已加载的剧本，
     * 且不会在单线程测试调度器上造成阻塞死锁（CompletableFuture.get() 会阻塞线程）。
     */
    private val scriptText = kotlinx.coroutines.CompletableDeferred<String?>()
    /** 一键提取结果提示（如"已提取12张卡" / "未识别到可提取的资产"） */
    private val _extractMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val extractMessage: kotlinx.coroutines.flow.StateFlow<String?> get() = _extractMessage

    init {
        viewModelScope.launch {
            // 读取本项目第一集的剧本与stage_flags（剧本导入时由ProjectsViewModel写入）
            val row = runCatching { withContext(Dispatchers.IO) {
                AppGraph.dao.episode(episodeId) ?: AppGraph.dao.episode("${episodeId}_ep1")
            } }.getOrNull()
            scriptText.complete(row?.script_json)
            _scriptMode.value = AssetsLogic.ScriptAssetExtractor.isScriptMode(row?.stage_flags)
            // 第六轮：进入即预载本项目的已存资产（含本地上传/参考图），保证列表不空窗
            runCatching {
                withContext(Dispatchers.IO) {
                    AppGraph.dao.assetsOf(projectId, "local")
                }
            }.getOrNull()?.let { locals ->
                for (e in locals) logic.addLocalAsset(
                    assetId = e.asset_id,
                    imageUri = e.image_uri,
                    videoUri = e.video_uri,
                    prompt = e.prompt)
            }
            // ★第十一轮：回填全部已落库资产（含生成的 remote_url），重进项目不丢卡不丢图
            runCatching {
                withContext(Dispatchers.IO) { AppGraph.dao.assetsAllOf(projectId) }
            }.getOrNull()?.forEach { e ->
                if (e.source == "local") return@forEach   // 本地卡已由上面加载
                logic.restoreGenerated(
                    assetId = e.asset_id,
                    kindName = e.kind,
                    prompt = e.prompt,
                    parentId = e.parent_id,
                    poseRole = e.pose_role,
                    remoteUrl = e.remote_url,
                    reviewState = e.review_state)
            }
        }
    }

    /** 一键「从剧本提取资产卡」：提取→落库→逐卡触发生成（MVP不要求LLM） */
    fun extractFromScript() = viewModelScope.launch {
        // 第六轮修复：await 剧本加载（旧实现直接读可能为null的scriptText字段；
        // CompletableDeferred.await() 挂起非阻塞，避免单线程调度器死锁）
        val text = scriptText.await()
        if (text.isNullOrBlank()) {
            _extractMessage.value = "未能读取剧本文本（请确认已导入剧本）"
            return@launch
        }
        // 第十轮：大模型自动提取优先（agnes-2.5-flash 出结构化JSON），正则兜底
        _extractMessage.value = "正在用大模型分析文本提取资产…"
        var seq = 0
        val idGen = { "sa_${System.currentTimeMillis()}_${seq++}" }
        // LLM 提取仅在引擎就绪时启用（测试/未配置key环境直接走正则兜底，不发起网络）
        val llmReady = runCatching { AppGraph.isInitialized && !AppGraph.keyVault.load(AppGraph.CONFIG_VIDEO).isNullOrBlank() }.getOrDefault(false)
        val llm = if (llmReady) runCatching {
            com.dramafactory.core.quality.LlmAssetExtractor.extract(text) { req ->
                AppGraph.text.chat(req)
            }
        }.getOrElse {
            com.dramafactory.core.quality.LlmAssetExtractor.ExtractResult(emptyList(), usedLlm = false)
        } else com.dramafactory.core.quality.LlmAssetExtractor.ExtractResult(emptyList(), usedLlm = false)
        val count: Int = if (llm.usedLlm && llm.assets.isNotEmpty()) {
            var n = 0
            for (a in llm.assets) {
                val kind = when (a.kind) {
                    "character" -> AssetsLogic.Kind.CHARACTER
                    "scene" -> AssetsLogic.Kind.SCENE
                    else -> AssetsLogic.Kind.PROP
                }
                val desc = if (a.desc.isBlank()) a.name else "${a.name}·${a.desc}"
                val key = "${kind.name}:$desc"
                if (logic.assets.value.any { "${it.kind.name}:${it.prompt}" == key }) continue
                logic.addAsset(idGen(), kind, desc)
                n++
            }
            n
        } else {
            logic.extractFromScript(text, idGen)
        }
        if (count == 0) {
            _extractMessage.value = "未能从文本提取到资产（LLM与规则均未命中）"
            return@launch
        }
        _extractMessage.value = (if (llm.usedLlm) "大模型" else "规则") + "提取了${count}张资产卡，正在生成图像…"
        // 对新增且未生成的卡片触发生成并落库
        for (card in logic.assets.value.filter { it.remoteUrl == null && it.assetId.startsWith("sa_") }) {
            withContext(Dispatchers.IO) {
                AppGraph.dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                    asset_id = card.assetId, project_id = projectId, kind = card.kind.name.lowercase(),
                    prompt = card.prompt, updated_at = System.currentTimeMillis()))
            }
            logic.generate(card.assetId)
        }
    }

    /** 「逐类生成图像」：对当前分类尚未生成的卡片依次触发生成 */
    fun generatePendingOfKind(kind: AssetsLogic.Kind) = viewModelScope.launch {
        for (id in logic.pendingIdsOfKind(kind)) logic.generate(id)
    }

    fun clearExtractMessage() { _extractMessage.value = null }

    fun add(assetId: String, kind: AssetsLogic.Kind, prompt: String) = viewModelScope.launch {
        logic.addAsset(assetId, kind, prompt)
        withContext(Dispatchers.IO) {
            AppGraph.dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                asset_id = assetId, project_id = projectId, kind = kind.name.lowercase(),
                prompt = prompt.trim(), updated_at = System.currentTimeMillis()))
        }
        logic.generate(assetId)   // 添加即触发生成
    }

    fun remove(assetId: String) = viewModelScope.launch {
        val ids = logic.removeAssetCascade(assetId)
        withContext(Dispatchers.IO) {
            for (id in ids) runCatching { AppGraph.dao.deleteAsset(id) }
        }
    }
    /** 第十一轮：停止进行中的生成 */
    fun stopGenerate(assetId: String) = logic.stopGenerate(assetId)
    fun generate(assetId: String) = viewModelScope.launch { logic.generate(assetId) }
    fun review(assetId: String, keep: Boolean) = viewModelScope.launch { logic.review(assetId, keep) }
    fun reviewAllPassed() = logic.reviewAllPassed()

    // ==================== 第九轮 QualityEngine 接线 ====================

    /**
     * B. 角色 DNA 6 姿态资产包：为某角色母卡生成 6 张姿态子图卡（front_anchor/side_45/
     * full_body_riding/expression_serious|angry|calm），每张带中英双语构图指令，并落库 + 触发生成。
     * @return 新增子图卡数量（6）
     */
    fun buildCharacterPosePack(characterId: String): Int {
        var added = 0
        viewModelScope.launch {
            added = logic.buildPosePack(characterId) { "pose_${System.currentTimeMillis()}_${added++}" }
            for (child in logic.poseChildrenOf(characterId)) {
                withContext(Dispatchers.IO) {
                    AppGraph.dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                        asset_id = child.assetId, project_id = projectId, kind = "character",
                        parent_id = child.parentId, pose_role = child.poseRole,
                        prompt = child.prompt, updated_at = System.currentTimeMillis()))
                }
                logic.generate(child.assetId)
            }
        }
        return 6
    }

    /** C. 时代红线：设置本集允许出现的跨时代器物清单（按剧集放行）。 */
    fun setEpisodeAllowedCrossEra(allowed: List<String>) = viewModelScope.launch {
        _allowedCrossEra.value = allowed
        val json = "[" + allowed.joinToString(",") { "\"$it\"" } + "]"
        withContext(Dispatchers.IO) {
            AppGraph.dao.setEpisodeAllowedCrossEra("${projectId}_ep1", json)
        }
    }

    /** C. 时代红线：读取本集已声明放行的跨时代器物清单。 */
    fun episodeAllowedCrossEra(): List<String> {
        val raw = runCatching { kotlinx.coroutines.runBlocking {
            AppGraph.dao.episodeAllowedCrossEra("${projectId}_ep1")
        } }.getOrNull() ?: "[]"
        return runCatching { org.json.JSONArray(raw) }.getOrNull()
            ?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()
    }

    // ==================== 第六轮：本地上传 / 图生图 / 视频参考 ====================

    /**
     * 本地上传资产落库：三类来源（拍摄/相册图/相册视频）统一经 addLocalAsset 建卡，
     * 并持久化到 assets 表（source=local + image_uri/video_uri + prompt）。
     * @param imageUri 图片URI（相册图/拍摄）；null 表示视频上传
     * @param videoUri 视频URI（相册视频/拍摄）；null 表示图片上传
     * @param prompt   可选描述
     * @return 新建资产id（空=无URI失败）
     */
    fun uploadLocal(imageUri: String?, videoUri: String?, prompt: String = ""): String {
        val id = "local_${System.currentTimeMillis()}_${(imageUri ?: videoUri).hashCode()}"
        val created = logic.addLocalAsset(id, imageUri = imageUri, videoUri = videoUri, prompt = prompt)
        if (created.isBlank()) return ""
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 先 upsert 整行（INSERT OR REPLACE：行不存在也能建卡），再局部UPDATE落URI——
                // 纯 UPDATE 在行不存在时静默无操作，导致本地上传资产永不持久化（真机刷新即丢）。
                val promptText = prompt.ifBlank { logic.assets.value.firstOrNull { it.assetId == created }?.prompt } ?: ""
                AppGraph.dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                    asset_id = created, project_id = projectId, kind = "local",
                    prompt = promptText, updated_at = System.currentTimeMillis()))
                AppGraph.dao.updateAssetLocal(
                    assetId = created, source = "local",
                    imageUri = imageUri, videoUri = videoUri,
                    referenceImageUri = null, prompt = promptText,
                    updatedAt = System.currentTimeMillis())
            }
        }
        return created
    }

    /** 设置/清除图生图参考图（并持久化） */
    fun setReferenceImage(assetId: String, uri: String?) = viewModelScope.launch {
        logic.setReferenceImage(assetId, uri)
        withContext(Dispatchers.IO) {
            AppGraph.dao.setAssetReferenceImage(assetId, uri, System.currentTimeMillis())
        }
    }

    /**
     * 第十一轮：编辑资产——上传参考图到资产（相册选图→拷内部目录→设为该资产的参考图）。
     * 任何资产均可挂参考图（角色DNA锁定/场景风格统一），不再限于本地LOCAL卡。
     * @return 内部稳定URI；null=拷贝失败
     */
    fun uploadReferenceImage(assetId: String, pickedUri: android.net.Uri, onDone: (String?) -> Unit) =
        viewModelScope.launch {
            val ctx = AppGraph.appContext() ?: run { onDone(null); return@launch }
            val internal = runCatching {
                com.dramafactory.app.ui.AssetFiles.copyToInternal(ctx, pickedUri, isVideo = false)
            }.getOrNull()
            if (internal != null) setReferenceImage(assetId, internal)
            onDone(internal)
        }

    /** 第十一轮：编辑资产描述并落库；changed=true 时提示重新生成 */
    fun editAsset(assetId: String, newPrompt: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val changed = logic.editAsset(assetId, newPrompt)
        if (changed) {
            withContext(Dispatchers.IO) {
                runCatching { AppGraph.dao.updateAssetPrompt(assetId, newPrompt.trim(), System.currentTimeMillis()) }
            }
        }
        onResult(changed)
    }
}
