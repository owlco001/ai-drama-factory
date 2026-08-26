package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dramafactory.core.orchestrate.PipelineStage5
import com.dramafactory.core.orchestrate.ProgressEvent
import kotlinx.coroutines.launch

/**
 * 第十三轮 P0-2：AI 全托管进度流页面。
 * 用户粘剧本 → 点一键成片 → 实时日志流展示五阶段进度 → 结束跳转分镜页。
 */
class AiPipelineViewModel : androidx.lifecycle.ViewModel() {
    var statusMsg by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set

    fun start(
        script: String,
        onAutoCreated: (projectId: String, episodeId: String) -> Unit = { _, _ -> },
        onFinish: (episodeId: String?) -> Unit = {},
    ) {
        viewModelScope.launch {
            val orchestrator = com.dramafactory.app.AppGraph.aiOrchestrator
            val res = orchestrator.run(script, onAutoCreatedProject = onAutoCreated)
            res.onSuccess { run ->
                statusMsg = if (run.success) {
                    "✅ 全流程完成，共 ${run.errors.size} 条异常"
                } else {
                    "⚠️ 流水线异常：" + run.errors.firstOrNull()?.msg
                }
                onFinish(run.episodeId.takeIf { it.isNotBlank() })
            }.onFailure { e ->
                statusMsg = when (e) {
                    is com.dramafactory.core.orchestrate.AiOrchestrator.AiError.InputTooShort ->
                        "❌ 文本太短：" + e.msg
                    is com.dramafactory.core.orchestrate.AiOrchestrator.AiError.ModelBlocked ->
                        "❌ 模型不可用（${e.modelId}）：" + e.msg
                    else -> "❌ " + e.message
                }
                onFinish(null)
            }
        }
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
private fun levelColor(it: ProgressEvent.Level): androidx.compose.ui.graphics.Color =
    when (it) {
        ProgressEvent.Level.INFO -> MaterialTheme.colorScheme.onSurface
        ProgressEvent.Level.WARN -> androidx.compose.ui.graphics.Color(0xFFFFB300)
        ProgressEvent.Level.ERROR -> androidx.compose.ui.graphics.Color(0xFFEF5350)
    }

@Composable
fun AiPipelinePage(onBack: () -> Unit) {
    val vm = viewModel<AiPipelineViewModel>()
    var scriptText by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf(emptyList<ProgressEvent>()) }
    var finishedEpId by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val orchestrator = remember { com.dramafactory.app.AppGraph.aiOrchestrator }

    LaunchedEffect(orchestrator) {
        orchestrator.events.collect { events = it }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🤖 AI 全托管", style = MaterialTheme.typography.headlineSmall)
            Box(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("退出 AI 模式") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "粘贴小说/剧本（≥100 字），AI 将自动：提取资产 → 生成图像 → 质量审计 → 生成分镜 → 入队渲染",
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
                Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("输入字数：${scriptText.length}", style = MaterialTheme.typography.bodySmall,
                        color = if (scriptText.length < 100) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            running = true
                            vm.start(
                                scriptText,
                                onAutoCreated = { pid, _ -> },
                                onFinish = { epId -> running = false; finishedEpId = epId })
                        },
                        enabled = !running && scriptText.length >= 100,
                    ) { Text("🚀 一键成片") }
                }
            }
        }

        if (finishedEpId != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("✅ 流水线完成", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onBack) { Text("去分镜页查看") }
                }
            }
        }

        Text("进度日志", style = MaterialTheme.typography.titleMedium)
        val listState = rememberLazyListState()
        LaunchedEffect(events.lastOrNull()?.elapsedMs) {
            if (events.isNotEmpty()) listState.animateScrollToItem(events.lastIndex)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (events.isEmpty()) {
                item {
                    Text("尚未开始。", textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            items(events, key = { "${it.stage.ordinal}_${it.elapsedMs}_${it.subStep}" }) { ev ->
                Card(Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (ev.level == ProgressEvent.Level.ERROR)
                            androidx.compose.ui.graphics.Color(0x1A000000) else
                            MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(levelIcon(ev.level), color = levelColor(ev.level),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            text = "${ev.stage.label} ${ev.message}",
                            color = levelColor(ev.level),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (ev.elapsedMs / 1000).toString() + "s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        vm.statusMsg?.let {
            Card(Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0x1AFF0000))) {
                Text(it, Modifier.padding(10.dp))
            }
        }
    }
}
