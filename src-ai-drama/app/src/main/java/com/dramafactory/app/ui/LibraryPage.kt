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
import androidx.compose.material3.AlertDialog
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
import android.widget.VideoView
import android.net.Uri
import com.dramafactory.app.data.RenderTaskEntity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp

/**
 * 成片库页（S7）：已完成集数列表 + 导出/分享（Android ACTION_SEND分享mp4）。
 * 数据源：render_tasks中该集COMPLETED镜全部齐备 → FfmpegAssembler合成成片后可分享。
 */
@Composable
fun LibraryPage() {
    val context = LocalContext.current
    var shareMsg by remember { mutableStateOf<String?>(null) }
    var composingEp by remember { mutableStateOf<String?>(null) }
    // v1.7.2：成片直接预览——点预览后弹出本地播放器播放已合成成片
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    val composer = remember { com.dramafactory.app.AppGraph.movieAssembler }

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
                        Column(modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第$epId 集", style = MaterialTheme.typography.titleMedium)
                            val doneAll = done == total && total > 0
                            Text(if (doneAll) "已完成 ✓ 可合成" else "$done/$total 镜完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (doneAll) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // v1.7.2：直接预览——播放已合成成片(cacheDir/$epId.mp4)，无则提示先合成
                                OutlinedButton(onClick = {
                                    val f = java.io.File(context.cacheDir, "$epId.mp4")
                                    if (f.exists() && f.length() > 0L) {
                                        previewUri = androidx.core.content.FileProvider.getUriForFile(
                                            context, context.packageName + ".fileprovider", f)
                                    } else shareMsg = "成片尚未合成，请先点「合成」"
                                }, modifier = Modifier.weight(1f)) { Text("预览") }
                                Button(onClick = {
                                    // 第十三轮 P0-3：点合成，触发后台合成
                                    composingEp = epId
                                }, enabled = doneAll, modifier = Modifier.weight(1f)) { Text("合成") }
                                Button(onClick = {
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
                                }, enabled = doneAll, modifier = Modifier.weight(1f)) { Text("分享") }
                            }
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

    // v1.7.2：成片库直接预览——本地 VideoView 弹窗播放已合成成片
    previewUri?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { previewUri = null },
            confirmButton = { Button(onClick = { previewUri = null }) { Text("关闭") } },
            text = {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(uri)
                            setOnPreparedListener { it.isLooping = false; it.start() }
                            setOnErrorListener { _, _, _ -> true }
                        }
                    },
                    update = { vv -> vv.setVideoURI(uri); vv.start() }
                )
            }
        )
    }

    // 第十三轮 P0-3：合成后台协程（LaunchedEffect 触发，UI 显示进度对话框）
    composingEp?.let { targetEpId ->
        LaunchedEffect(targetEpId) {
            val result = try {
                val tasks = com.dramafactory.app.AppGraph.dao.renderTasksOf(targetEpId)
                    .filter { it.state == "COMPLETED" && !it.local_file_uri.isNullOrBlank() }
                    .sortedBy { it.shot_id }
                val clips: List<java.io.File> = tasks.mapNotNull { java.io.File(it.local_file_uri ?: "") }
                    .filter { it.exists() && it.length() > 0L }
                if (clips.isEmpty()) {
                    kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                        "本集没有可合成的镜片段"))
                } else {
                    val outDir = java.io.File(context.filesDir, "movies")
                    if (!outDir.exists()) outDir.mkdirs()
                    val out = java.io.File(outDir, "$targetEpId.mp4")
                    // 同时写一份到 cacheDir，供分享按钮用（FileProvider 白名单走 cache）
                    val outCache = java.io.File(context.cacheDir, "$targetEpId.mp4")
                    val res = composer.assemble(clips, out)
                    if (res is com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Success
                        && res.output.exists()) {
                        // 同步到 cache 供分享
                        if (!outCache.exists()) outCache.outputStream().use { o ->
                            res.output.inputStream().copyTo(o)
                        }
                        // 落库
                        kotlin.runCatching {
                            com.dramafactory.app.AppGraph.movieLibraryDao.upsertFilmOf(
                                com.dramafactory.app.data.FinishedFilmEntity(
                                    film_id = targetEpId,
                                    episode_id = targetEpId,
                                    project_id = targetEpId.substringBefore("_ep"),
                                    filePath = out.absolutePath,
                                    fileSize = out.length(),
                                    durationMs = (res.durationSeconds * 1000).toLong(),
                                    createdAt = System.currentTimeMillis(),
                                ))
                        }
                        kotlin.Result.success(out)
                    } else if (res is com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Segmented) {
                        kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                            "合成降级为分段导出（${res.parts.size} 段），暂未支持拼装"))
                    } else {
                        kotlin.Result.failure(com.dramafactory.core.assemble.MovieAssembler.NotAvailableException(
                            (res as? com.dramafactory.core.assemble.MovieAssembler.AssembleResult.Failure)?.message
                                ?: "合成失败"))
                    }
                }
            } catch (e: com.dramafactory.core.assemble.MovieAssembler.NotAvailableException) {
                kotlin.Result.failure(e)
            } catch (e: Throwable) {
                kotlin.Result.failure(e)
            }
            result.onSuccess { shareMsg = "✅ 合成完成，可点击「分享」" }
            result.onFailure { shareMsg = "合成失败：" + it.message }
            composingEp = null
        }
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
