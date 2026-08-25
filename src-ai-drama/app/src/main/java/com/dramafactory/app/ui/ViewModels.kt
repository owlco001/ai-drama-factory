package com.dramafactory.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
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

    /** 新建项目并返回新id（导航进入项目用） */
    fun create(onCreated: (String?) -> Unit) = viewModelScope.launch {
        onCreated(logic.createProject())
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
class AssetsViewModel(private val projectId: String) : ViewModel() {

    private val logic = AssetsLogic().apply {
        // 资产生成：文本通道出细化prompt → 图像通道出图（双Provider桩接线）
        generateHandler = { card ->
            withContext(Dispatchers.IO) {
                val refined = runCatching {
                    AppGraph.text.chat(com.dramafactory.core.model.ChatRequest(messages = listOf(
                        com.dramafactory.core.model.ChatMessage("user",
                            "为短剧资产生成一段中文图像提示词（50字内）：${card.prompt}"))))
                }.getOrNull()?.content ?: card.prompt
                val url = AppGraph.image.generateImage(
                    com.dramafactory.core.model.ImageGenRequest(prompt = refined))
                Result.success(url)
            }
        }
        reviewPersist = { assetId, st ->
            withContext(Dispatchers.IO) { AppGraph.dao.setReviewState(assetId, st) }
        }
    }
    val assets: StateFlow<List<AssetsLogic.AssetCard>> get() = logic.assets

    /** 剧本模式状态：stage_flags.script_mode=true 时资产页显示「一键提取」入口 */
    private val _scriptMode = kotlinx.coroutines.flow.MutableStateFlow(false)
    val scriptMode: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _scriptMode
    /** 剧本原文（从episodes.script_json读取，供一键提取） */
    private var scriptText: String? = null
    /** 一键提取结果提示（如"已提取12张卡" / "未识别到可提取的资产"） */
    private val _extractMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val extractMessage: kotlinx.coroutines.flow.StateFlow<String?> get() = _extractMessage

    init {
        viewModelScope.launch {
            // 读取本项目第一集的剧本与stage_flags（剧本导入时由ProjectsViewModel写入）
            val row = runCatching { withContext(Dispatchers.IO) { AppGraph.dao.episode("${projectId}_ep1") } }.getOrNull()
            scriptText = row?.script_json
            _scriptMode.value = AssetsLogic.ScriptAssetExtractor.isScriptMode(row?.stage_flags)
        }
    }

    /** 一键「从剧本提取资产卡」：提取→落库→逐卡触发生成（MVP不要求LLM） */
    fun extractFromScript() = viewModelScope.launch {
        val text = scriptText ?: return@launch
        var seq = 0
        val count = logic.extractFromScript(text) { "sa_${System.currentTimeMillis()}_${seq++}" }
        if (count == 0) {
            _extractMessage.value = "未能从剧本识别到资产（可用下方输入框手动添加）"
            return@launch
        }
        _extractMessage.value = "已从剧本提取${count}张资产卡"
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

    fun remove(assetId: String) = logic.removeAsset(assetId)
    fun generate(assetId: String) = viewModelScope.launch { logic.generate(assetId) }
    fun review(assetId: String, keep: Boolean) = viewModelScope.launch { logic.review(assetId, keep) }
    fun reviewAllPassed() = logic.reviewAllPassed()
}
