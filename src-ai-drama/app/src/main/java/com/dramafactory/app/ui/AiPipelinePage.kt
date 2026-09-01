package com.dramafactory.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import com.dramafactory.app.ui.components.HeroButton
import com.dramafactory.app.ui.components.LoadingRow
import com.dramafactory.app.ui.components.StatusCard
import com.dramafactory.app.ui.components.StatusMessage
import com.dramafactory.app.ui.components.statusErr
import com.dramafactory.app.ui.components.statusInfo
import com.dramafactory.app.ui.components.statusOk
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dramafactory.app.ui.Page
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.dramafactory.app.ui.theme.DramaColor
import com.dramafactory.app.ui.theme.BubbleAiShape
import com.dramafactory.app.ui.theme.BubbleUserShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dramafactory.core.orchestrate.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T014 任务2：AI 模式流式对话页面。
 * 两阶段合一：
 *   1) 对话阶段（BriefDialogue）：AI 主动问 → 用户答/自由补充 → 确认卡片
 *   2) 流水线阶段（AiOrchestrator）：确认后跑五阶段，实时进度流，支持中途插话/取消
 *
 * 用户始终可「跳过对话，直接一键成片」——点"跳过"按钮直接用原始文本进流水线。
 */
class AiPipelineViewModel : ViewModel() {
    // ---- 智能体对话引擎 ----
    private var agent: AiAgent? = null
    val historyFlow = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val canGenerateFlow = MutableStateFlow(false)
    /** 输入框草稿：用户粘入但未发送的剧本也作为开工候选（避免"粘了没发就报太短"） */
    var inputDraft by mutableStateOf("")
    fun updateInputDraft(text: String) {
        inputDraft = text
        // 输入框有≥100字剧本时，即使没发送也允许开工
        canGenerateFlow.value = inputDraft.length >= 100 || (agent?.canGenerate() ?: false)
    }

    private fun resolveScriptWithDraft(): String {
        val a = agent ?: return inputDraft.takeIf { it.length >= 100 } ?: ""
        val fromAgent = a.resolveScript()
        if (fromAgent.length >= 100) return fromAgent
        // agent 里不足100，用输入框草稿兜底
        return inputDraft.takeIf { it.length >= 100 } ?: fromAgent
    }

    // ---- 流水线状态 ----
    var statusMsg by mutableStateOf<StatusMessage?>(null)
        private set
    var pipelineEvents by mutableStateOf(emptyList<ProgressEvent>())
        private set
    var finishedEpId by mutableStateOf<String?>(null)
        private set
    var isThinking by mutableStateOf(false)
        private set

    /** AI 模式内嵌子视图：直接在 AI 模式里看/管资产、分镜、成片，不跳出 AI */
    enum class SubView { NONE, ASSETS, STORYBOARD, LIBRARY }
    var subView by mutableStateOf(SubView.NONE)
        private set
    fun showAssets() { subView = SubView.ASSETS }
    fun showStoryboard() { subView = SubView.STORYBOARD }
    fun showLibrary() { subView = SubView.LIBRARY }
    fun backToAi() { subView = SubView.NONE }

    /** 供 AI 模式内嵌子页面读取当前集（private 字段的只读出口） */
    val currentEpisodeId: String? get() = _currentEpisodeId

    /** 退出重进恢复上下文：DramaApp 已从 SharedPreferences 恢复 currentEpisodeId，注入 VM */
    fun restoreContext(episodeId: String) {
        _currentEpisodeId = episodeId
        if (_currentProjectId == null) _currentProjectId = episodeId.substringBeforeLast("_ep")
    }

    /** 进入 AI 模式：用当前激活模型初始化智能体（异步，避免主线程网络） */
    fun initAgent() {
        if (agent != null || _agentBuilding) return
        _agentBuilding = true
        viewModelScope.launch {
            runCatching { buildAgent() }
            _agentBuilding = false
        }
    }

    /** 切换模型后重建智能体（异步） */
    fun reinitAgent() {
        agent = null
        _agentBuilding = false
        initAgent()
    }

    private var _agentBuilding = false

    /** 切换模型（suspend 包一层） */
    fun viewModelScopeSafeSetModel(modelId: String) {
        viewModelScope.launch {
            com.dramafactory.app.AppGraph.textModelRouter.setActiveTextModel(modelId)
        }
    }

    private var _currentProjectId: String? = null
    private var _currentEpisodeId: String? = null
    private val assetsLogic = com.dramafactory.app.ui.AssetsLogic()

    private suspend fun buildAgent() {
        val router = com.dramafactory.app.AppGraph.textModelRouter
        val modelId = router.activeTextModelId()
        val provider = router.resolve(modelId)  // 已在 viewModelScope，挂起安全
        agent = AiAgent(
            textProvider = provider,
            modelId = modelId,
            actionHandler = { act, onNotice -> handleAction(act, onNotice) },
        )
        val welcome = DialogueTurn(DialogueTurn.Side.AI,
            "嗨，我是你的短剧编剧导演搭档 🎬 把小说/剧本粘给我，或者聊聊你的想法，咱们边聊边理清风格，聊好了你说「开工」我就动手。")
        historyFlow.value = listOf(welcome)
    }

    /** AI 大脑指令 → 调用 App 能力（端侧执行，返回回显文案；null=无法执行） */
    private suspend fun handleAction(act: ActionIntent, onNotice: (String) -> Unit = {}): String? {
        val dao = com.dramafactory.app.AppGraph.dao
        val projectId = _currentProjectId
        val epId = _currentEpisodeId
        return when (act.verb) {
            "set_cross_era" -> {
                val proj = projectId ?: return "（还没有项目，先开工建项目后再设时代红线）"
                val allowed = act.paramList("allowed")
                if (allowed.isEmpty()) return "（请告知要放开的器物，例如 allowed=手机,眼镜）"
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dao.setEpisodeAllowedCrossEra("${proj}_ep1",
                        "[" + allowed.joinToString(",") { "\"$it\"" } + "]")
                }
                "已放开跨时代器物：${allowed.joinToString("、")}"
            }
            "list_assets" -> {
                // 原实现判空的是 epId、实际用的却是 projectId!! —— 两个变量可能不同源，
                // projectId 为空时必抛 NPE。改为对真正要用的 projectId 判空。
                val pid = projectId ?: return "（还没有项目，先开工建项目）"
                val assets = withContext(kotlinx.coroutines.Dispatchers.IO) { dao.assetsAllOf(pid) }
                if (assets.isEmpty()) "（当前项目还没有资产）"
                else "当前项目共 ${assets.size} 个资产：\n" +
                    assets.joinToString("\n") { "· ${it.kind}（id=${it.asset_id}，描述：${it.prompt.take(20)}…）" } +
                    "\n你可让我对这些资产操作，例如：删掉某角色 / 改某资产描述 / 让某角色过审"
            }
            "generate" -> {
                val id = act.param("assetId") ?: return null
                assetsLogic.generate(id)
                "已触发重新生成：$id"
            }
            "stop_generate" -> {
                val id = act.param("assetId") ?: return null
                assetsLogic.stopGenerate(id)
                "已停止生成：$id"
            }
            "remove_asset" -> {
                val id = act.param("assetId") ?: return null
                val ids = assetsLogic.removeAssetsCascade(listOf(id))
                withContext(kotlinx.coroutines.Dispatchers.IO) { for (i in ids) runCatching { dao.deleteAsset(i) } }
                "已删除资产：$id${if (ids.size > 1) "（含 ${ids.size - 1} 张子卡）" else ""}"
            }
            "remove_asset_batch" -> {
                val ids = act.paramList("assetIds").ifEmpty { act.paramList("assetId") }
                if (ids.isEmpty()) return null
                val all = assetsLogic.removeAssetsCascade(ids)
                withContext(kotlinx.coroutines.Dispatchers.IO) { for (i in all) runCatching { dao.deleteAsset(i) } }
                "已批量删除 ${all.size} 个资产"
            }
            "edit_asset" -> {
                val id = act.param("assetId") ?: return null
                val newPrompt = act.param("prompt") ?: return "（请告知新的描述，例如 prompt=穿红衣的少女）"
                val pid = projectId ?: return "（还没有项目，先开工建项目）"
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val cur = dao.assetsAllOf(pid).firstOrNull { it.asset_id == id }
                    if (cur != null) dao.updateAssetLocal(id, cur.source, cur.image_uri, cur.video_uri,
                        cur.reference_image_uri, newPrompt, System.currentTimeMillis())
                }
                "已更新资产描述：$id → $newPrompt"
            }
            "review_pass" -> {
                val id = act.param("assetId") ?: return null
                assetsLogic.review(id, true)
                "已通过评审：$id"
            }
            "review_all_pass" -> {
                assetsLogic.reviewAllPassed()
                "已全部通过评审"
            }
            "build_pose_pack" -> {
                val cid = act.param("characterId") ?: act.param("assetId") ?: return null
                val n = assetsLogic.buildReferenceSheet(cid) { "ref_${System.currentTimeMillis()}_${System.nanoTime()}" }
                "已为角色 $cid 生成 $n 张独立参考图（基准正面半身/45°右前/正侧面/正面全身，各自成图不拼图）"
            }
            "retry_stage" -> {
                "（retry_stage 暂由流水线内部自动重试，AI模式暂不直接触发）"
            }
            else -> null
        }
    }

    /** 用户发送一句话（智能体式自由对话） */
    fun sendUserMessage(text: String) {
        if (agent == null) {
            statusMsg = statusInfo("智能体初始化中，稍等…")
            return
        }
        val a = agent!!
        if (text.isBlank() || isThinking) return
        viewModelScope.launch {
            isThinking = true
            runCatching { a.say(text) { notice ->
                // v1.7.3：AI 执行长任务时实时汇报进度（非流式，阶段提示逐条追加）
                historyFlow.value = historyFlow.value + DialogueTurn(DialogueTurn.Side.AI, notice)
            } }
                .onSuccess {
                    historyFlow.value = a.history
                    canGenerateFlow.value = a.canGenerate()
                    // 大脑控制 APP：AI 主动说要开工，自动进流水线
                    if (a.lastAiWantsGenerate()) {
                        isThinking = false
                        triggerGenerateFromAgent()
                        return@launch
                    }
                }
                .onFailure { e ->
                    historyFlow.value = a.history + DialogueTurn(DialogueTurn.Side.AI,
                        "⚠️ 调用模型失败：${e.message?.take(120)}")
                }
            isThinking = false
        }
    }

    /** AI 说要开工时自动触发（UI 层观察到 running 变化） */
    fun triggerGenerateFromAgent(onFinish: (episodeId: String?) -> Unit = {}) {
        val a = agent ?: return
        val script = resolveScriptWithDraft()
        if (script.length < 100) {
            statusMsg = statusErr("剧本太短（需≥100字），先多聊点或者粘入文本后点开工")
            return
        }
        launchPipeline(script, null, onFinish)
    }

    /** 从对话进流水线（UI 按钮调用） */
    fun generateFromAgent(onFinish: (episodeId: String?) -> Unit = {}) {
        triggerGenerateFromAgent(onFinish)
    }

    /** 跳过对话直接成片（手动粘文本） */
    fun skipAndRun(script: String, onFinish: (episodeId: String?) -> Unit = {}) {
        launchPipeline(script, null, onFinish)
    }

    var isRunning by mutableStateOf(false)
        private set
    /** 开工后永真：保证进度区常驻显示，不会被 running 一结束就消失 */
    var hasStarted by mutableStateOf(false)
        private set

    /** 由 UI 注入：开工建项目后回调（用于同步 DramaApp 导航状态） */
    var onAutoCreatedCallback: ((projectId: String, episodeId: String) -> Unit)? = null

    private var eventsJob: kotlinx.coroutines.Job? = null
    /** 重新绑定事件流到当前 aiOrchestrator 实例（避免 init 时收集到默认实例而收不到进度） */
    private fun rebindEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            com.dramafactory.app.AppGraph.aiOrchestrator.events.collect { pipelineEvents = it }
        }
    }

    init {
        rebindEvents()
    }

    var finishedFilmPath: String? = null
        private set

    private fun launchPipeline(
        script: String,
        brief: Brief?,
        onFinish: (String?) -> Unit,
    ) {
        isRunning = true
        hasStarted = true
        finishedFilmPath = null
        rebindEvents()  // 确保收集的是当前真实 aiOrchestrator 实例的进度流
        viewModelScope.launch(kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            // 协程未捕获异常兜底：写崩溃日志 + 显示，绝不杀进程
            android.util.Log.e("DramaAI", "launchPipeline crashed", e)
            com.dramafactory.app.AppGraph.CrashLog.record(
                com.dramafactory.app.AppGraph.appContext() ?: return@CoroutineExceptionHandler, "launchPipeline", e)
            statusMsg = statusErr("运行异常：" + (e.message ?: e.javaClass.simpleName))
            isRunning = false
        }) {
            val orchestrator = com.dramafactory.app.AppGraph.aiOrchestrator
            val res = orchestrator.run(script, brief = brief, onAutoCreatedProject = { p, e ->
                _currentProjectId = p
                _currentEpisodeId = e
                onAutoCreatedCallback?.invoke(p, e)
            })
            res.onSuccess { run ->
                android.util.Log.d("DramaAI", "run onSuccess ep=${run.episodeId} success=${run.success} errs=${run.errors.size}")
                statusMsg = if (run.success) statusOk("全流程完成，共 ${run.errors.size} 条异常")
                else statusErr("流水线异常：" + run.errors.firstOrNull()?.msg)
                finishedEpId = run.episodeId.takeIf { it.isNotBlank() }
                onFinish(finishedEpId)
                // 自动接力：等渲染完成 → 合成成片 → 展示
                if (finishedEpId != null) pollRenderAndCompose(finishedEpId!!)
                else android.util.Log.w("DramaAI", "finishedEpId 为空，未启动渲染轮询")
            }.onFailure { e ->
                android.util.Log.e("DramaAI", "run onFailure", e)
                statusMsg = when (e) {
                    is AiOrchestrator.AiError.InputTooShort -> statusErr("文本太短：" + e.msg)
                    is AiOrchestrator.AiError.ModelBlocked -> statusErr("模型不可用（${e.modelId}）：" + e.msg)
                    else -> statusErr(e.message ?: e.javaClass.simpleName)
                }
                onFinish(null)
            }
            isRunning = false
        }
    }

    /** 轮询渲染进度，全部完成则自动合成成片（最多约3分钟） */
    private suspend fun pollRenderAndCompose(episodeId: String) {
        val dao = com.dramafactory.app.AppGraph.dao
        val ctx = com.dramafactory.app.AppGraph.appContext()
        android.util.Log.d("DramaAI", "pollRenderAndCompose start ep=$episodeId ctx=${ctx != null}")
        repeat(60) { i ->
            val tasks = runCatching { dao.renderTasksOf(episodeId) }.getOrNull() ?: emptyList()
            val total = tasks.size
            val done = tasks.count { it.state == "COMPLETED" }
            android.util.Log.d("DramaAI", "poll#$i ep=$episodeId total=$total done=$done")
            statusMsg = if (total == 0) statusInfo("渲染任务准备中…")
            else statusInfo("渲染中 $done/$total 镜…")
            if (total > 0 && done >= total) {
                statusMsg = statusInfo("渲染完成，正在合成成片…")
                val file = if (ctx != null) {
                    runCatching { com.dramafactory.app.AppGraph.composeFilmIfReady(episodeId, ctx) }.getOrNull()
                } else null
                android.util.Log.d("DramaAI", "compose result=${file?.absolutePath}")
                if (file != null) {
                    finishedFilmPath = file.absolutePath
                    statusMsg = statusOk("成片已生成，可播放/分享")
                } else {
                    statusMsg = statusOk("渲染完成，但暂无可用视频片段合成（检查视频生成 key）")
                }
                return
            }
            kotlinx.coroutines.delay(3000)
        }
        statusMsg = statusInfo("渲染超时（3分钟未完成），可稍后到「成片库」手动合成")
    }
}

@Composable
fun AiPipelinePage(
    onBack: () -> Unit,
    onNavigate: (Page) -> Unit = {},
    onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
    currentEpisodeId: String? = null,
) {
    val vm = viewModel<AiPipelineViewModel>()
    val history by vm.historyFlow.collectAsState()
    val canGenerate by vm.canGenerateFlow.collectAsState()
    val thinking = vm.isThinking
    val running = vm.isRunning
    LaunchedEffect(Unit) { vm.onAutoCreatedCallback = onAutoCreated }
    // 退出重进恢复：DramaApp 已恢复 nav.currentEpisodeId，注入 AI VM 避免空白
    LaunchedEffect(currentEpisodeId) {
        if (currentEpisodeId != null) vm.restoreContext(currentEpisodeId)
    }

    LaunchedEffect(Unit) { vm.initAgent() }

    // AI 模式内嵌子视图：直接在 AI 模式里看/管资产、分镜、成片，不跳出 AI
    if (vm.subView != AiPipelineViewModel.SubView.NONE) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Face, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text("AI · " + when (vm.subView) {
                        AiPipelineViewModel.SubView.ASSETS -> "资产库"
                        AiPipelineViewModel.SubView.STORYBOARD -> "分镜"
                        AiPipelineViewModel.SubView.LIBRARY -> "成片库"
                        else -> ""
                    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.backToAi() }) { Text("← 返回 AI") }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (vm.subView) {
                AiPipelineViewModel.SubView.ASSETS ->
                    AssetsPage(projectId = vm.currentEpisodeId, onContinue = { vm.backToAi() })
                AiPipelineViewModel.SubView.STORYBOARD ->
                    StoryboardPage(episodeId = vm.currentEpisodeId ?: "default")
                AiPipelineViewModel.SubView.LIBRARY ->
                    LibraryPage()
                else -> {}
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶栏
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Face, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text("AI 智能体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ModelChip(vm)
                TextButton(onClick = onBack) { Text("退出") }
            }
        }

        // 状态提示（初始化中/错误等）
        vm.statusMsg?.let { StatusCard(it) }

        // 对话区
        if (!running) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(history, key = { index, _ -> "turn_$index" }) { _, turn ->
                    ChatBubble(turn)
                }
                if (thinking) item { ThinkingBubble() }
            }

            // 输入卡片
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.inputDraft, onValueChange = { vm.updateInputDraft(it) },
                        modifier = Modifier.fillMaxWidth()
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyUp && ev.key == Key.Enter) {
                                    if (vm.inputDraft.isNotBlank() && !thinking) { vm.sendUserMessage(vm.inputDraft); vm.updateInputDraft("") }
                                    true
                                } else false
                            },
                        placeholder = { Text("跟 AI 聊聊剧本/想法，或直接粘贴文本…（回车发送；粘文本后也可直接点开工）") },
                        minLines = 2, maxLines = 5,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (vm.inputDraft.isNotBlank()) { vm.sendUserMessage(vm.inputDraft); vm.updateInputDraft("") } },
                            enabled = !thinking,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("发送")
                        }
                        HeroButton(
                            text = "开工成片",
                            onClick = { vm.generateFromAgent(onFinish = {}) },
                            enabled = canGenerate && !thinking,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 流水线进度流：开工后就常驻显示（hasStarted），不再因 running 一结束就消失
        if (vm.hasStarted || vm.finishedEpId != null) {
            if (running) {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                LoadingRow("流水线运行中：提取资产 → 生成图像 → 审计 → 分镜 → 渲染")
            }
            PipelineProgressSection(vm = vm, onBack = onBack, onNavigate = onNavigate)
        }
    }
}

@Composable
private fun ChatBubble(turn: DialogueTurn) {
    val isAi = turn.side == DialogueTurn.Side.AI
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (isAi) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Face, contentDescription = "AI",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp).wrapContentSize())
            }
            Spacer(Modifier.size(6.dp))
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAi) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            ),
            shape = if (isAi) BubbleAiShape else BubbleUserShape,
            modifier = Modifier.fillMaxWidth(0.82f),
        ) {
            Text(
                turn.content,
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAi) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        if (!isAi) {
            Spacer(Modifier.size(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = CircleShape,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Person, contentDescription = "我",
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.size(18.dp).wrapContentSize())
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.Default.Face, contentDescription = "AI",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp).wrapContentSize())
        }
        Spacer(Modifier.size(6.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = BubbleAiShape) {
            Row(Modifier.padding(12.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Text("●", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f + it * 0.25f))
                }
            }
        }
    }
}

@Composable
private fun ModelChip(vm: AiPipelineViewModel) {
    val router = com.dramafactory.app.AppGraph.textModelRouter
    var expanded by remember { mutableStateOf(false) }
    val models = router.registeredTextModels()
    val active = router.activeTextModelId()
    val activeLabel = models.firstOrNull { it.providerId == active }?.label ?: active
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text(activeLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    trailingIcon = {
                        if (m.providerId == active) Icon(Icons.Default.Check, contentDescription = "当前模型",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    },
                    onClick = { vm.viewModelScopeSafeSetModel(m.modelId); expanded = false; vm.reinitAgent() },
                )
            }
        }
    }
}

@Composable
private fun PipelineProgressSection(vm: AiPipelineViewModel, onBack: () -> Unit, onNavigate: (Page) -> Unit) {
    val events = vm.pipelineEvents
    val listState = rememberLazyListState()
    LaunchedEffect(events.lastOrNull()?.elapsedMs) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Send, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    Text("流水线进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (events.isEmpty()) {
            item { Text("等待启动…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.outline) }
        }
        items(events, key = { "ev_${it.stage.ordinal}_${it.subStep}_${it.elapsedMs}" }) { ev ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 阶段序号圆
                val done = ev.level != ProgressEvent.Level.INFO || ev.elapsedMs > 0
                Surface(
                    color = levelColor(ev.level),
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stageNumber(ev.stage), color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${ev.stage.label}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(ev.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text((ev.elapsedMs / 1000).toString() + "s", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
    vm.statusMsg?.let { StatusCard(it, dense = false) }
    if (vm.finishedEpId != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.showAssets() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("资产库")
            }
            OutlinedButton(onClick = { vm.showStoryboard() }, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_movie), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("分镜")
            }
            OutlinedButton(onClick = { vm.showLibrary() }, modifier = Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_video_library), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("成片库")
            }
        }
    }
    // 成品展示：成片就绪直接内嵌播放器
    vm.finishedFilmPath?.let { path ->
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_movie), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("成品成片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoPath(path)
                            setOnPreparedListener { it.isLooping = true; start() }
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                (220 * ctx.resources.displayMetrics.density).toInt())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val ctx = com.dramafactory.app.AppGraph.appContext()
                        ctx?.let {
                            val f = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                it, it.packageName + ".fileprovider", f)
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            it.startActivity(android.content.Intent.createChooser(intent, "播放成片"))
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "播放", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("播放")
                    }
                    OutlinedButton(onClick = { onNavigate(Page.LIBRARY) }, modifier = Modifier.weight(1f)) { Text("去成片库") }
                }
            }
        }
    }
}

private fun stageNumber(stage: PipelineStage5): String = when (stage) {
    PipelineStage5.EXTRACT_ASSETS -> "1"
    PipelineStage5.GENERATE_IMAGES -> "2"
    PipelineStage5.AUDIT -> "3"
    PipelineStage5.GENERATE_STORYBOARD -> "4"
    PipelineStage5.ENQUEUE_RENDER -> "5"
    PipelineStage5.ENQUEUE_RENDER_DONE -> "5"
}

/**
 * 事件等级配色 —— 唯一来源，阶段序号圆与日志文本共用。
 * 原实现把同一套映射在文件里写了两遍（一处内联硬编码、一处本函数但从未被调用），
 * 现统一收口；色值一律取 DramaColor 语义色，不再硬编码。
 */
@Composable
private fun levelColor(it: ProgressEvent.Level): Color = when (it) {
    ProgressEvent.Level.INFO -> MaterialTheme.colorScheme.primary
    ProgressEvent.Level.WARN -> DramaColor.Warning
    ProgressEvent.Level.ERROR -> DramaColor.Error
}
