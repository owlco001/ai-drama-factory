package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 分镜编辑页（S5占位增强）：展示某集镜头列表（台词/旁白/动作）与六铁律校验位。
 * 分镜自动生成本体属引擎迭代；本页提供只读列表+手动触发入口。
 */
@Composable
fun StoryboardPage(episodeId: String) {
    val shotsState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<kotlin.collections.List<com.dramafactory.app.data.ShotEntity>>(
            emptyList<com.dramafactory.app.data.ShotEntity>())
    }
    val shots = shotsState.value
    androidx.compose.runtime.LaunchedEffect(episodeId) {
        shotsState.value = runCatching { com.dramafactory.app.AppGraph.dao.shotsOf(episodeId) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("分镜编辑 · $episodeId", style = MaterialTheme.typography.headlineSmall)

        if (shots.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("本集暂无分镜。导入小说并完成资产评审后，由引擎自动生成分镜。",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (shot in shots) {
                    item(key = shot.shot_id) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("#${shot.shot_no}", style = MaterialTheme.typography.titleSmall)
                                    Text(if (shot.sb_check == "pass") "六铁律✓" else "校验:${shot.sb_check}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (shot.sb_check == "pass") MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.error)
                                }
                                val dlg = shot.dialogue
                                val nar = shot.narration
                                val act = shot.action
                                if (dlg != null) Text("台词：$dlg", style = MaterialTheme.typography.bodySmall)
                                if (nar != null) Text("旁白：$nar", style = MaterialTheme.typography.bodySmall)
                                if (act != null) Text("动作：$act", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { /* 手动刷新 */ }, modifier = Modifier.fillMaxWidth()) {
            Text("刷新分镜")
        }
    }
}

// ---------- Compose预览 ----------

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewStoryboard() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分镜编辑", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("#1"); Text("六铁律✓", color = MaterialTheme.colorScheme.primary)
                    }
                    Text("台词：你终于回来了…")
                    Text("动作：她转身望向门口")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("#2"); Text("校验:error", color = MaterialTheme.colorScheme.error)
                    }
                    Text("旁白：雨下了一整夜")
                }
            }
        }
    }
}
