package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dramafactory.core.orchestrate.*
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
    // ---- 对话状态机 ----
    private val dialogue = BriefDialogue()
    val briefState = dialogue.state
    val brief = dialogue.brief
    val history = dialogue.history
    val nextQuestion = dialogue.nextQuestion

    // ---- 流水线状态 ----
    var statusMsg by mutableStateOf<String?>(null)
        private set
    var pipelineEvents by mutableStateOf(emptyList<ProgressEvent>())
        private set
    var finishedEpId by mutableStateOf<String?>(null)
        private set

    fun startBrief(script: String) = dialogue.start(script)
    fun answerBrief(field: BriefField, value: String) = dialogue.onAnswer(field, value)
    fun noteBrief(note: String) = dialogue.onUserNote(note)
    fun requestConfirmBrief() = dialogue.requestConfirm()
    fun confirmBrief() = dialogue.confirm()
    fun cancelBrief() = dialogue.cancel()

    /** 从已确认 brief 进流水线 */
    fun runPipeline(
        script: String,
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        val b = dialogue.brief.value.takeIf { it.confirmed }
        launchPipeline(script, b, onAutoCreated, onFinish)
    }

    /** 跳过对话直接成片 */
    fun skipAndRun(
        script: String,
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        launchPipeline(script, null, onAutoCreated, onFinish)
    }

    private fun launchPipeline(
        script: String,
        brief: Brief?,
        onAutoCreated: (String, String) -> Unit,
        onFinish: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            val orchestrator = com.dramafactory.app.AppGraph.aiOrchestrator
            orchestrator.events.collect { pipelineEvents = it }
        }
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
        }
    }
}

@Composable
fun AiPipelinePage(onBack: () -> Unit) {
    val vm = viewModel<AiPipelineViewModel>()
    var scriptText by remember { mutableStateOf("") }
    var userInput by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🤖 AI 全托管", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("退出 AI 模式") }
        }

        // 阶段一：剧本输入
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴小说/剧本（≥100 字）。AI 会与你对谈确认风格，或你可直接一键成片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = scriptText, onValueChange = { scriptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("在此粘贴剧本文本…") },
                    minLines = 4, maxLines = 8,
                    enabled = !running,
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("字数：${scriptText.length}", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { vm.startBrief(scriptText) },
                        enabled = !running && scriptText.length >= 100 && vm.briefState.value == BriefState.IDLE,
                    ) { Text("🗣 开始对话") }
                    OutlinedButton(
                        onClick = { running = true; vm.skipAndRun(scriptText, onFinish = {}) },
                        enabled = !running && scriptText.length >= 100,
                    ) { Text("⏩ 跳过对话直接成片") }
                }
            }
        }

        // 阶段二：对话 / 流水线 分区
        if (vm.briefState.value != BriefState.IDLE && vm.briefState.value != BriefState.CONFIRMED && !running) {
            BriefChatSection(vm = vm, userInput = userInput, onUserInput = { userInput = it })
        }

        // 流水线进度流（确认 brief 或跳过对话后显示）
        if (running || vm.pipelineEvents.isNotEmpty()) {
            PipelineProgressSection(vm = vm, onBack = onBack)
        }
    }
}

@Composable
private fun BriefChatSection(vm: AiPipelineViewModel, userInput: String, onUserInput: (String) -> Unit) {
    val state = vm.briefState.value
    val history = vm.history.value
    val nextQ = vm.nextQuestion.value
    val listState = rememberLazyListState()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex)
    }

    Text("🗣 AI 对谈", style = MaterialTheme.typography.titleMedium)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(history, key = { it.content + it.side.name + history.indexOf(it) }) { turn ->
            val isAi = turn.side == DialogueTurn.Side.AI
            Row(horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End,
                modifier = Modifier.fillMaxWidth()) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (isAi) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(turn.content, Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    // 输入区
    when (state) {
        BriefState.QUESTIONING -> {
            val field = guessField(nextQ ?: "")
            OutlinedTextField(
                value = userInput, onValueChange = onUserInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(nextQ ?: "输入你的回答…") },
                minLines = 1, maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (userInput.isNotBlank()) { vm.answerBrief(field, userInput); onUserInput("") } }) {
                    Text("发送")
                }
                OutlinedButton(onClick = { vm.noteBrief(userInput); onUserInput("") }) {
                    Text("补充细节")
                }
            }
        }
        BriefState.ANSWERED -> {
            Button(onClick = vm::requestConfirmBrief) { Text("✅ 确认 Brief，进入流水线") }
        }
        BriefState.CONFIRMING -> {
            BriefConfirmCard(vm = vm)
        }
        else -> {}
    }
}

/** 根据 AI 当前问题猜字段（UI 简化：靠关键词匹配） */
private fun guessField(q: String): BriefField = when {
    q.contains("时代") -> BriefField.ERA
    q.contains("风格") -> BriefField.STYLE
    q.contains("角色数") -> BriefField.CHARACTER_COUNT
    q.contains("情绪") -> BriefField.MOOD
    q.contains("配音") -> BriefField.WITH_AUDIO
    else -> BriefField.STYLE
}

@Composable
private fun BriefConfirmCard(vm: AiPipelineViewModel) {
    val b = vm.brief.value
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📋 确认 Brief", style = MaterialTheme.typography.titleSmall)
            Text(b.renderSummary(), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::confirmBrief) { Text("✅ 确认并生成") }
                OutlinedButton(onClick = vm::cancelBrief) { Text("✏️ 返回修改") }
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
    Text("🚀 流水线进度", style = MaterialTheme.typography.titleMedium)
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (events.isEmpty()) {
            item { Text("等待启动…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.outline) }
        }
        items(events, key = { "${it.stage.ordinal}_${it.elapsedMs}_${it.subStep}" }) { ev ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(levelIcon(ev.level), color = levelColor(ev.level), fontWeight = FontWeight.Bold)
                    Text("${ev.stage.label} ${ev.message}", color = levelColor(ev.level),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text((ev.elapsedMs / 1000).toString() + "s", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    vm.statusMsg?.let {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0x1AFF0000))) {
            Text(it, Modifier.padding(10.dp))
        }
    }
    if (vm.finishedEpId != null) {
        Button(onClick = onBack) { Text("去分镜页查看") }
    }
}

private val stageIcon = listOf("①", "②", "③", "④", "⑤", "✓")
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
