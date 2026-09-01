package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 第十轮：剧集列表页——项目内分集管理。
 * 进入项目先到本页：列出该项目全部剧集（第1集/第2集…），点击进入对应剧集的资产页。
 * 每集独立持有资产/分镜/渲染记录（episodes + assets.project_id + shots.episode_id），
 * 支持新增剧集（ep_no 自增，剧本可后续在资产页导入/粘贴）。
 */
@Composable
fun EpisodePage(
    projectId: String,
    projectName: String?,
    onOpenEpisode: (String) -> Unit,     // 进入某集 → 资产页（带episodeId上下文）
) {
    val episodes = remember { mutableStateOf<List<com.dramafactory.app.data.EpisodeEntity>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }

    suspend fun reload() {
        episodes.value = runCatching {
            com.dramafactory.app.AppGraph.dao.episodesOf(projectId)
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(projectId) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("剧集 · ${projectName ?: projectId}", style = MaterialTheme.typography.headlineSmall)

        if (episodes.value.isEmpty()) {
            EmptyState(
                icon = { Icon(painterResource(R.drawable.ic_movie), contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                title = "还没有剧集",
                subtitle = "点下方「新增剧集」开始制作，AI 会从剧本拆解镜头并生成成片。",
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ep in episodes.value) {
                    item(key = ep.episode_id) {
                        DramaCard(Modifier.fillMaxWidth()) {
                            Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("第${ep.ep_no}集", style = MaterialTheme.typography.titleMedium)
                                    val chars = ep.script_json?.length ?: 0
                                    Text(if (chars > 0) "剧本 ${chars}字" else "未导入剧本",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline)
                                }
                                Button(onClick = { onOpenEpisode(ep.episode_id) }) { Text("进入") }
                            }
                        }
                    }
                }
            }
        }

        val scope = androidx.compose.runtime.rememberCoroutineScope()
        OutlinedButton(
            onClick = {
                creating = true
                scope.launch {
                    runCatching {
                        val nextNo = (com.dramafactory.app.AppGraph.dao.episodesOf(projectId)
                            .maxOfOrNull { it.ep_no } ?: 0) + 1
                        com.dramafactory.app.AppGraph.dao.upsertEpisode(
                            com.dramafactory.app.data.EpisodeEntity(
                                episode_id = "${projectId}_ep$nextNo",
                                project_id = projectId, ep_no = nextNo))
                    }
                    reload()
                    creating = false
                }
            },
            enabled = !creating,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (creating) "创建中…" else "＋ 新增剧集") }
    }
}
