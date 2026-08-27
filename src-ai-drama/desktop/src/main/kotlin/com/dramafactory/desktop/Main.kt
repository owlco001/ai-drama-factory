package com.dramafactory.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent as AwtKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.dramafactory.core.orchestrate.AiAgent
import com.dramafactory.core.orchestrate.DefaultAiOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

data class Turn(val side: String, val text: String)

@Composable
fun App() {
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(listOf<Turn>()) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("初始化中…") }
    var running by remember { mutableStateOf(false) }
    var filmPath by remember { mutableStateOf<String?>(null) }
    var agent by remember { mutableStateOf<AiAgent?>(null) }

    LaunchedEffect(Unit) {
        DesktopAppGraph.init()
        val router = DesktopAppGraph.textModelRouter
        val modelId = router.activeTextModelId()
        val provider = runBlocking { router.resolve(modelId) }
        agent = AiAgent(textProvider = provider, modelId = modelId)
        history = history + Turn("ai", "你好，我是 AI 短剧工厂智能体。把剧本粘进来，或聊聊想法，准备好就说「开工」🎬")
        status = "就绪"
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🤖 AI 短剧工厂 · 桌面版", style = MaterialTheme.typography.titleLarge)
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(status, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { turn ->
                val isAi = turn.side == "ai"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End) {
                    Surface(
                        color = if (isAi) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(0.85f),
                    ) { Text(turn.text, Modifier.padding(12.dp)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("粘剧本或聊想法…（回车发送）") },
                modifier = Modifier.weight(1f).onKeyEvent { ev: KeyEvent ->
                    if (ev.key == Key.Enter) {
                        if (input.isNotBlank() && !running) {
                            val u = input; input = ""
                            scope.launch(Dispatchers.IO) {
                                agent?.let { a ->
                                    history = history + Turn("user", u)
                                    val reply = a.say(u)
                                    history = history + Turn("ai", reply)
                                    if (a.lastAiWantsGenerate()) {
                                        running = true; status = "流水线运行中…"
                                        runPipeline(a.resolveScript()) { _, path -> status = path ?: status; filmPath = path; running = false }
                                    }
                                }
                            }
                        }
                        true
                    } else false
                },
            )
            Button(onClick = {
                if (input.isNotBlank() && !running) {
                    val u = input; input = ""
                    scope.launch(Dispatchers.IO) {
                        agent?.let { a ->
                            history = history + Turn("user", u)
                            val reply = a.say(u)
                            history = history + Turn("ai", reply)
                        }
                    }
                }
            }) { Text("发送") }
            Button(enabled = !running, onClick = {
                scope.launch(Dispatchers.IO) {
                    agent?.resolveScript()?.let { script ->
                        if (script.length >= 100) {
                            running = true; status = "流水线运行中…"
                            runPipeline(script) { _, path -> status = path ?: status; filmPath = path; running = false }
                        } else status = "❌ 剧本太短（需≥100字）"
                    }
                }
            }) { Text("🎬 开工") }
        }
        filmPath?.let { p ->
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("🎬 成品成片已生成", style = MaterialTheme.typography.titleMedium)
                    Text(p, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { java.awt.Desktop.getDesktop().open(File(p)) }) { Text("▶ 打开成片") }
                }
            }
        }
    }
}

private fun runPipeline(script: String, onDone: (String?, String?) -> Unit) {
    val orch: DefaultAiOrchestrator = DesktopAppGraph.aiOrchestrator
    runBlocking {
        val res = orch.run(script, brief = null)
        res.onSuccess {
            val ep = it.episodeId
            val film = DesktopAppGraph.composeFilmIfReady(ep)
            onDone(ep, film?.absolutePath)
        }.onFailure { onDone(null, null) }
    }
}

@Preview
@Composable
fun AppPreview() = MaterialTheme { App() }

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AI 短剧工厂 · 桌面版") {
        MaterialTheme { App() }
    }
}
