package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 分镜编辑页（第十二轮：可操作化）：
 * - AI编剧+导演一键生成分镜；每镜展示序号/时长/校验位/动作/视觉指令/台词/旁白/承接
 * - 点卡片或「✏ 编辑」进入单镜编辑（动作/台词/旁白/视觉指令/时长）
 * - 「🗑 删除」二次确认删单镜；「🎬 渲染本集」一键入队渲染队列
 */
@Composable
fun StoryboardPage(
    episodeId: String,
    vm: StoryboardViewModel = viewModel(
        key = "sb_$episodeId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return StoryboardViewModel(episodeId) as T
            }
        },
    ),
) {
    val st by vm.state.collectAsState()
    var editingShotId by remember { mutableStateOf<String?>(null) }
    var confirmDeleteShotId by remember { mutableStateOf<String?>(null) }
    var queuedCount by remember { mutableStateOf<Int?>(null) }
    // 第十三轮：分镜视频预览（非空=正在播放该本地路径）
    var previewUri by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("分镜 · $episodeId", style = MaterialTheme.typography.headlineSmall) }

        item {
            Button(onClick = { vm.generateWithAi() }, enabled = !st.generating,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (st.generating) "AI 生成中…" else "🤖 AI 生成分镜（编剧+导演）")
            }
        }
        if (st.shots.isNotEmpty()) {
            item {
                Button(onClick = { vm.enqueueRender { n -> queuedCount = n } },
                    enabled = !st.generating, modifier = Modifier.fillMaxWidth()) {
                    Text("🎬 渲染本集全部 ${st.shots.size} 镜")
                }
            }
        }
        queuedCount?.let { n ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(if (n > 0) "已入队 $n 镜，进度见「渲染」标签页 ✓" else "没有可渲染的镜头",
                        Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        st.message?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("已生成") || msg.contains("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!st.generating && st.shots.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("本集暂无分镜。导入剧本后点上方「AI 生成分镜」，由大模型自动拆解镜头并生成视觉指令。",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        items(st.shots, key = { it.shot_id }) { shot ->
            Card(
                onClick = { editingShotId = shot.shot_id },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${shot.shot_no} · ${shot.duration_seconds.toInt()}秒",
                            style = MaterialTheme.typography.titleSmall)
                        when {
                            shot.sb_check == "pass" -> Text("校验✓",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            shot.sb_check == "pending" -> Text("待生成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                            else -> Text("⚠${shot.sb_check.removePrefix("error:")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    shot.action?.let { Text("动作：$it", style = MaterialTheme.typography.bodyMedium) }
                    shot.visual_prompt?.let {
                        Text("🎬 $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    shot.dialogue?.let { Text("台词：「$it」", style = MaterialTheme.typography.bodySmall) }
                    shot.narration?.let { Text("旁白：$it", style = MaterialTheme.typography.bodySmall) }
                    shot.carry_over?.let { Text("承接：$it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 第十三轮：已出产视频 → 预览入口
                        st.videoUris[shot.shot_id]?.let { uri ->
                            Button(onClick = { previewUri = uri }) { Text("▶ 预览") }
                        }
                        OutlinedButton(onClick = { editingShotId = shot.shot_id }) { Text("✏ 编辑") }
                        OutlinedButton(onClick = { confirmDeleteShotId = shot.shot_id }) { Text("🗑 删除") }
                    }
                }
            }
        }
    }

    // ---- 单镜编辑对话框（LazyColumn 外，composable 上下文）----
    val editingShot = editingShotId?.let { id -> st.shots.firstOrNull { it.shot_id == id } }
    if (editingShot != null) {
        var eAction by remember(editingShot.shot_id) { mutableStateOf(editingShot.action ?: "") }
        var eDialogue by remember(editingShot.shot_id) { mutableStateOf(editingShot.dialogue ?: "") }
        var eNarration by remember(editingShot.shot_id) { mutableStateOf(editingShot.narration ?: "") }
        var eVisual by remember(editingShot.shot_id) { mutableStateOf(editingShot.visual_prompt ?: "") }
        var eDur by remember(editingShot.shot_id) {
            mutableStateOf(editingShot.duration_seconds.toInt().toString())
        }
        AlertDialog(
            onDismissRequest = { editingShotId = null },
            title = { Text("编辑镜头 #${editingShot.shot_no}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = eAction, onValueChange = { eAction = it },
                        label = { Text("动作描述") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedTextField(value = eDialogue, onValueChange = { eDialogue = it },
                        label = { Text("台词（留空删除）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eNarration, onValueChange = { eNarration = it },
                        label = { Text("旁白（留空删除）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eVisual, onValueChange = { eVisual = it },
                        label = { Text("视觉指令（运镜/景别）") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eDur,
                        onValueChange = { txt -> eDur = txt.filter(Char::isDigit) },
                        label = { Text("时长（秒，1-60）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.updateShot(editingShot.shot_id, eAction,
                        eDialogue.ifBlank { null }, eNarration.ifBlank { null },
                        eVisual.ifBlank { null }, (eDur.toIntOrNull() ?: 6).toDouble())
                    editingShotId = null
                }) { Text("保存") }
            },
            dismissButton = { OutlinedButton(onClick = { editingShotId = null }) { Text("取消") } },
        )
    }

    // ---- 第十三轮：分镜视频预览（原生VideoView，零新增依赖）----
    previewUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { previewUri = null },
            title = { Text("镜头预览") },
            text = {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.VideoView(ctx).apply {
                            setVideoPath(uri)
                            setOnPreparedListener { it.isLooping = true; it.start() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { Button(onClick = { previewUri = null }) { Text("关闭") } },
        )
    }

    // ---- 删除镜二次确认 ----
    confirmDeleteShotId?.let { delId ->
        AlertDialog(
            onDismissRequest = { confirmDeleteShotId = null },
            title = { Text("删除该镜头？") },
            text = { Text("镜头将被移除，不可恢复。") },
            confirmButton = {
                Button(onClick = { vm.deleteShot(delId); confirmDeleteShotId = null }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDeleteShotId = null }) { Text("取消") } },
        )
    }
}
