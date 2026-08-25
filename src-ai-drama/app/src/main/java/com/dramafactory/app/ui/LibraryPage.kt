package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 成片库页（S7）：已完成集数列表 + 导出/分享（Android ACTION_SEND分享mp4）。
 * 数据源：render_tasks中该集COMPLETED镜全部齐备 → FfmpegAssembler合成成片后可分享。
 */
@Composable
fun LibraryPage() {
    val context = LocalContext.current
    var shareMsg by remember { mutableStateOf<String?>(null) }

    // MVP：从Room读全部episode及其完成度（简单同步读取在VM更佳，此处直接组合DAO）
    val episodes = remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        episodes.value = runCatching {
            com.dramafactory.app.AppGraph.dao.allEpisodeIds().map { epId ->
                val tasks = com.dramafactory.app.AppGraph.dao.renderTasksOf(epId)
                Triple(epId, tasks.count { it.state == "COMPLETED" }, tasks.size)
            }
        }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("成片库", style = MaterialTheme.typography.headlineSmall)

        if (episodes.value.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("暂无剧集。完成渲染后成片会出现在这里。", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline)
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((epId, done, total) in episodes.value) {
                item(key = epId) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("第$epId 集", style = MaterialTheme.typography.titleMedium)
                                val doneAll = done == total && total > 0
                                Text(if (doneAll) "已完成 ✓ 可导出" else "$done/$total 镜完成",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (doneAll) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline)
                            }
                            Button(onClick = {
                                // 导出分享：ACTION_SEND发送mp4（FileProvider路径由系统缓存目录解析）
                                runCatching {
                                    val f = java.io.File(context.cacheDir, "$epId.mp4")
                                    if (f.exists()) {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, context.packageName + ".fileprovider", f)
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "分享成片"))
                                    } else shareMsg = "成片尚未合成到本地"
                                }.onFailure { shareMsg = it.message ?: "分享失败" }
                            }, enabled = done == total && total > 0) { Text("导出分享") }
                        }
                    }
                }
            }
        }
    }

    shareMsg?.let {
        androidx.compose.material3.AlertDialog(onDismissRequest = { shareMsg = null },
            title = { Text("提示") }, text = { Text(it) },
            confirmButton = { Button(onClick = { shareMsg = null }) { Text("知道了") } })
    }
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewLibrary() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("成片库", style = MaterialTheme.typography.headlineSmall)
            for ((name, sub) in listOf("第p1_ep1 集" to "已完成 ✓ 可导出", "第p2_ep1 集" to "12/24 镜完成")) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(name); Text(sub, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = {}) { Text("导出分享") }
                    }
                }
            }
        }
    }
}
