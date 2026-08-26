package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 分镜编辑页（第十轮重写）：AI编剧+导演一键生成分镜。
 * 每镜展示：序号/时长/六铁律校验位/动作/导演视觉指令/台词/旁白/承接。
 * 「AI生成分镜」重新生成会覆盖本集旧镜。
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

        st.message?.let { msg ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(msg, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
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
            Card(Modifier.fillMaxWidth()) {
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
                }
            }
        }

        // 渲染入口提示（渲染队列按集工作，此处仅导航说明）
        item {
            OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("渲染请到「渲染」标签页")
            }
        }
    }
}
