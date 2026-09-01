package com.dramafactory.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.DramaFilterChip
import com.dramafactory.app.ui.components.EmptyState
import com.dramafactory.app.ui.components.LocalDramaSnackbar
import androidx.compose.ui.Alignment
import com.dramafactory.app.ui.components.PageHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 项目列表页（S1+S2）：新建项目（名称+导入小说TXT/MD）、进入项目、删除。
 */
@Composable
fun ProjectsPage(
    vm: ProjectsViewModel = viewModel(),
    onEnterProject: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = LocalDramaSnackbar.current
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
            PageHeader(title = "AI短剧工厂", subtitle = "创建项目 · 导入剧本 · 进入制作")
        }
        // 第九轮：数据库初始化失败诊断横幅（建项目无反应的根因可视化）
        if (com.dramafactory.app.AppGraph.roomInitError != null) {
            item {
                DramaCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text("数据库不可用（建项目/渲染均会失败）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text("根因：" + (com.dramafactory.app.AppGraph.roomInitError ?: "") +
                                "\n建议：卸载重装本应用（将清空本地数据）。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            HomeModelConfigCard(onOpenSettings)
        }
        item {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新建项目", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = st.newName, onValueChange = vm::onNameChanged,
                        label = { Text("项目名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    // ---- 导入模式切换（第四轮：小说/剧本）----
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        DramaFilterChip(
                            selected = st.importMode == ProjectsLogic.ImportMode.NOVEL,
                            onClick = { vm.selectMode(ProjectsLogic.ImportMode.NOVEL) },
                            label = { Text("小说模式") })
                        DramaFilterChip(
                            selected = st.importMode == ProjectsLogic.ImportMode.SCRIPT,
                            onClick = { vm.selectMode(ProjectsLogic.ImportMode.SCRIPT) },
                            label = { Text("剧本模式") })
                    }
                    if (st.importMode == ProjectsLogic.ImportMode.SCRIPT) {
                        Text("剧本模式：跳过资产文本分析，直接进入分镜编辑。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }

                    // ---- 文件导入 ----
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            picker.launch(arrayOf("text/plain", "text/markdown"))
                        }) { Text(if (st.importedNovel == null || st.importedPasted) "导入文件 TXT/MD" else "重新选择文件") }
                        Button(onClick = { vm.create { id -> id?.let(onEnterProject) } },
                            enabled = st.newName.isNotBlank() && !st.creating) { Text("创建") }
                    }
                    // ---- 粘贴文本导入（第四轮）----
                    OutlinedTextField(value = st.pasteInput, onValueChange = vm::onPasteInputChanged,
                        label = { Text("或直接粘贴${st.importMode.label}文本…") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    OutlinedButton(
                        onClick = { vm.importDocument(st.importMode, "pasted_${st.importMode.name.lowercase()}.txt", st.pasteInput, pasted = true) },
                        enabled = st.pasteInput.isNotBlank()) { Text("使用粘贴内容") }

                    st.importedFileName?.let {
                        val sceneInfo = if (st.importMode == ProjectsLogic.ImportMode.SCRIPT && st.sceneHint > 0)
                            " · ${st.sceneHint}场" else ""
                        Text("${st.importMode.label}：$it · ${st.importedNovel?.length ?: 0}字$sceneInfo",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    st.importError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (st.projects.isEmpty()) {
            item {
                EmptyState(
                    icon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有项目",
                    subtitle = "填个名字、贴一段小说或剧本，AI 会帮你拆成资产、分镜和成片。",
                )
            }
        }
        items(st.projects, key = { it.projectId }) { p ->
            DramaCard(Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(),
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
                Button(onClick = {
                    vm.delete(target.projectId)
                    snackbar.show("已删除项目「${target.name}」")
                    deleteTarget = null
                }) { Text("删除") }
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

/**
 * v1.7.18：首页「模型配置」入口卡。
 * 三通道（文本/视频/图像）Key 状态一目了然，未配置的通道高亮提示，一键跳设置页补配。
 */
@Composable
private fun HomeModelConfigCard(onOpenSettings: () -> Unit) {
    var hasText by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(false) }
    var hasImage by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasText = runCatching { com.dramafactory.app.AppGraph.hasAnyTextKey() }.getOrDefault(false)
        hasVideo = runCatching { !com.dramafactory.app.AppGraph.keyVault.load(com.dramafactory.app.AppGraph.CONFIG_VIDEO).isNullOrBlank() }.getOrDefault(false)
        hasImage = runCatching { !com.dramafactory.app.AppGraph.keyVault.load(com.dramafactory.app.AppGraph.CONFIG_IMAGE).isNullOrBlank() }.getOrDefault(false)
        checked = true
    }
    if (!checked) return

    val missing = listOf(!hasText to "文本", !hasVideo to "视频", !hasImage to "图像").filter { it.first }
    DramaCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (missing.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text("全部就绪", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text("待配置：${missing.joinToString("、") { it.second }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusDot("文本", hasText)
                StatusDot("视频", hasVideo)
                StatusDot("图像", hasImage)
            }
            Text("模型 Key 决定 AI 对话 / 出图 / 渲染能否跑通。首次使用先到这里补 Key。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (missing.isEmpty()) "管理模型 Key" else "去配置模型 Key")
            }
        }
    }
}

@Composable
private fun StatusDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.padding(end = 2.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewProjects() {
    MaterialTheme {
        Column {
            Text("AI短剧工厂", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
            DramaCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier) {
                    Text("新建项目", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = "", onValueChange = {}, label = { Text("项目名称") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}) { Text("导入小说 TXT/MD") }
                        Button(onClick = {}) { Text("创建") }
                    }
                }
            }
            DramaCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier) {
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
