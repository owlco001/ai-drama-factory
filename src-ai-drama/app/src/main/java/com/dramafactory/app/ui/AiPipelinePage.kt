package com.dramafactory.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch

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

    // ---- 流水线状态 ----
    var statusMsg by mutableStateOf<String?>(null)
        private set
    var pipelineEvents by mutableStateOf(emptyList<ProgressEvent>())
        private set
    var finishedEpId by mutableStateOf<String?>(null)
        private set
    var isThinking by mutableStateOf(false)
        private set

    /** 进入 AI 模式：用当前激活模型初始化智能体 */
    fun initAgent() {
        if (agent != null) return
        buildAgent()
    }

    /** 切换模型后重建智能体（保留历史 UI 由调用方决定是否清空） */
    fun reinitAgent() {
        agent = null
        buildAgent()
    }

    /** 切换模型（suspend 包一层） */
    fun viewModelScopeSafeSetModel(modelId: String) {
        viewModelScope.launch {
            com.dramafactory.app.AppGraph.textModelRouter.setActiveTextModel(modelId)
        }
    }

    private fun buildAgent() {
        val router = com.dramafactory.app.AppGraph.textModelRouter
        val modelId = router.activeTextModelId()
        val provider = kotlinx.coroutines.runBlocking { router.resolve(modelId) }
        agent = AiAgent(textProvider = provider, modelId = modelId)
        val welcome = DialogueTurn(DialogueTurn.Side.AI,
            "嗨，我是你的短剧编剧导演搭档 🎬 把小说/剧本粘给我，或者聊聊你的想法，咱们边聊边理清风格，聊好了你说「开工」我就动手。")
        historyFlow.value = listOf(welcome)
    }

    /** 用户发送一句话（智能体式自由对话） */
    fun sendUserMessage(text: String) {
        val a = agent ?: return
        if (text.isBlank() || isThinking) return
        viewModelScope.launch {
            isThinking = true
            runCatching { a.say(text) }
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
    fun triggerGenerateFromAgent(
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        val a = agent ?: return
        val script = a.resolveScript()
        if (script.length < 100) {
            statusMsg = "❌ 剧本太短（需≥100字），先多聊点或者粘入文本"
            return
        }
        launchPipeline(script, null, onAutoCreated, onFinish)
    }

    /** 从对话进流水线（UI 按钮调用） */
    fun generateFromAgent(
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        triggerGenerateFromAgent(onAutoCreated, onFinish)
    }

    /** 跳过对话直接成片（手动粘文本） */
    fun skipAndRun(
        script: String,
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        launchPipeline(script, null, onAutoCreated, onFinish)
    }

    var isRunning by mutableStateOf(false)
        private set

    init {
        // 一次性收集编排器事件流（避免多次 collect 同一 StateFlow 抛异常）
        viewModelScope.launch {
            com.dramafactory.app.AppGraph.aiOrchestrator.events.collect { pipelineEvents = it }
        }
    }

    private fun launchPipeline(
        script: String,
        brief: Brief?,
        onAutoCreated: (String, String) -> Unit,
        onFinish: (String?) -> Unit,
    ) {
        isRunning = true
        viewModelScope.launch {
            val orchestrator = com.dramafactory.app.AppGraph.aiOrchestrator
            val res = orchestrator.run(script, brief = brief, onAutoCreatedProject = onAutoCreated)
            res.onSuccess { run ->
                statusMsg = if (run.success) "✅ 全流程完成，共 ${run.errors.size} 条异常"
                else "⚠️ 流水线异常：" + run.errors.firstOrNull()?.msg
                finishedEpId = run.episodeId.takeIf { it.isNotBlank() }
                onFinish(finishedEpId)
            }.onFailure { e ->
                statusMsg = when (e) {
                    is AiOrchestrator.AiError.InputTooShort -> "❌ 文本太短：" + e.msg
                    is AiOrchestrator.AiError.ModelBlocked -> "❌ 模型不可用（${e.modelId}）：" + e.msg
                    else -> "❌ " + e.message
                }
                onFinish(null)
            }
            isRunning = false
        }
    }
}

@Composable
fun AiPipelinePage(onBack: () -> Unit) {
    val vm = viewModel<AiPipelineViewModel>()
    var userInput by remember { mutableStateOf("") }
    val history by vm.historyFlow.collectAsState()
    val canGenerate by vm.canGenerateFlow.collectAsState()
    val thinking = vm.isThinking
    val running = vm.isRunning

    LaunchedEffect(Unit) { vm.initAgent() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶栏
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🤖 AI 智能体", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ModelChip(vm)
                TextButton(onClick = onBack) { Text("退出") }
            }
        }

        // 对话区
        if (!running) {
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(history, key = { "${it.side.name}_${history.indexOf(it)}_${it.content.take(8)}" }) { turn ->
                    ChatBubble(turn)
                }
                if (thinking) item { ThinkingBubble() }
            }

            // 输入卡片
            Surface(
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = userInput, onValueChange = { userInput = it },
                        modifier = Modifier.fillMaxWidth()
                            .onKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyUp && ev.key == Key.Enter) {
                                    if (userInput.isNotBlank() && !thinking) { vm.sendUserMessage(userInput); userInput = "" }
                                    true
                                } else false
                            },
                        placeholder = { Text("跟 AI 聊聊剧本/想法，或直接粘贴文本…（回车发送）") },
                        minLines = 2, maxLines = 5,
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (userInput.isNotBlank()) { vm.sendUserMessage(userInput); userInput = "" } },
                            enabled = !thinking,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("发送")
                        }
                        Button(
                            onClick = { vm.generateFromAgent(onFinish = {}) },
                            enabled = canGenerate && !thinking,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🎬 开工成片")
                        }
                    }
                }
            }
        }

        // 流水线进度流
        if (running || vm.pipelineEvents.isNotEmpty()) {
            PipelineProgressSection(vm = vm, onBack = onBack)
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(32.dp),
            ) {
                Text("🤖", Modifier.wrapContentSize(), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.size(6.dp))
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAi) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
            ),
            shape = RoundedCornerShape(
                topStart = if (isAi) 4.dp else 16.dp,
                topEnd = if (isAi) 16.dp else 4.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp,
            ),
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.size(32.dp),
            ) {
                Text("👤", Modifier.wrapContentSize(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(32.dp),
        ) { Text("🤖", Modifier.wrapContentSize(), style = MaterialTheme.typography.titleSmall) }
        Spacer(Modifier.size(6.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)) {
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
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.clickable { expanded = true },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🧠", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Text(activeLabel, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text("${m.label}${if (m.providerId == active) "  ✓" else ""}") },
                    onClick = { vm.viewModelScopeSafeSetModel(m.modelId); expanded = false; vm.reinitAgent() },
                )
            }
        }
    }
}

@Composable
private fun PipelineProgressSection(vm: AiPipelineViewModel, onBack: () -> Unit) {
    val events = vm.pipelineEvents
    val listState = rememberLazyListState()
    LaunchedEffect(events.lastOrNull()?.elapsedMs) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
    }
    Text("🚀 流水线进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (events.isEmpty()) {
            item { Text("等待启动…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.outline) }
        }
        items(events, key = { "${it.stage.ordinal}_${it.elapsedMs}_${it.subStep}" }) { ev ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 阶段序号圆
                val done = ev.level != ProgressEvent.Level.INFO || ev.elapsedMs > 0
                Surface(
                    color = when (ev.level) {
                        ProgressEvent.Level.ERROR -> Color(0xFFEF5350)
                        ProgressEvent.Level.WARN -> Color(0xFFFFB300)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(26.dp),
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
    vm.statusMsg?.let {
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp),
            color = if (it.startsWith("✅")) MaterialTheme.colorScheme.primaryContainer else Color(0x1AFF0000),
            modifier = Modifier.fillMaxWidth()) {
            Text(it, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (vm.finishedEpId != null) {
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("去分镜页查看 →") }
    }
}

private fun stageNumber(stage: PipelineStage5): String = when (stage) {
    PipelineStage5.EXTRACT_ASSETS -> "1"
    PipelineStage5.GENERATE_IMAGES -> "2"
    PipelineStage5.AUDIT -> "3"
    PipelineStage5.GENERATE_STORYBOARD -> "4"
    PipelineStage5.ENQUEUE_RENDER -> "5"
    PipelineStage5.ENQUEUE_RENDER_DONE -> "✓"
}

private val levelIcon: ProgressEvent.Level.() -> String = {
    when (this) {
        ProgressEvent.Level.INFO -> "›"
        ProgressEvent.Level.WARN -> "⚠"
        ProgressEvent.Level.ERROR -> "✗"
    }
}
@Composable
private fun levelColor(it: ProgressEvent.Level): Color = when (it) {
    ProgressEvent.Level.INFO -> MaterialTheme.colorScheme.onSurface
    ProgressEvent.Level.WARN -> Color(0xFFFFB300)
    ProgressEvent.Level.ERROR -> Color(0xFFEF5350)
}
