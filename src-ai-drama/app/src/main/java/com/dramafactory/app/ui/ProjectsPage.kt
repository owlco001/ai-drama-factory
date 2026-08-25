package com.dramafactory.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 项目列表页（S1+S2）：新建项目（名称+导入小说TXT/MD）、进入项目、删除。
 */
@Composable
fun ProjectsPage(vm: ProjectsViewModel = viewModel(), onEnterProject: (String) -> Unit) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<ProjectsLogic.ProjectItem?>(null) }

    // 小说文件选择器（TXT/MD）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text ->
                vm.importNovel(uri.lastPathSegment ?: "novel.txt", text)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("AI短剧工厂", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新建项目", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = st.newName, onValueChange = vm::onNameChanged,
                        label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            picker.launch(arrayOf("text/plain", "text/markdown"))
                        }) { Text(if (st.importedNovel == null) "导入小说 TXT/MD" else "已导入✓ 重新选") }
                        Button(onClick = { vm.create { id -> id?.let(onEnterProject) } },
                            enabled = st.newName.isNotBlank() && !st.creating) { Text("创建") }
                    }
                    st.importedFileName?.let {
                        Text("小说：$it · ${st.importedNovel?.length ?: 0}字",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    st.importError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        items(st.projects, key = { it.projectId }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        val dateStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                            .format(java.util.Date(p.createdAt))
                        Text("${p.novelChars}字 · $dateStr",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { onEnterProject(p.projectId) }) { Text("进入") }
                        OutlinedButton(onClick = { deleteTarget = p }) { Text("删除") }
                    }
                }
            }
        }
    }

    // 删除确认对话框
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目「${target.name}」？") },
            text = { Text("项目的资产、分镜与渲染记录将一并删除，不可恢复。") },
            confirmButton = {
                Button(onClick = { vm.delete(target.projectId); deleteTarget = null }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

/** 预览用纯静态版（无ViewModel依赖） */
@Composable
private fun SettingsStatic(state: SettingsLogic.UiState) {
    // 见SettingsPage.kt——此处仅为跨文件预览占位说明；实际预览在SettingsPage内
    Box {}
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewProjects() {
    MaterialTheme {
        Column {
            Text("AI短剧工厂", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("新建项目", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("项目名称") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}) { Text("导入小说 TXT/MD") }
                        Button(onClick = {}) { Text("创建") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("霸总爱上机器人")
                        Text("0字 · 08-25 12:00", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {}) { Text("进入") }
                    OutlinedButton(onClick = {}) { Text("删除") }
                }
            }
        }
    }
}
