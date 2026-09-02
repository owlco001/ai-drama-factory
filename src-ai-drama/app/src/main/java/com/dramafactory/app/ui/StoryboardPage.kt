package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import com.dramafactory.app.ui.components.LoadingRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dramafactory.app.ui.components.HeroButton
import com.dramafactory.app.ui.components.PrimaryButton

/** 图标 + 短文案 状态标记（替代此前 ✓ / ⚠ 等 emoji 前缀） */
@Composable
private fun StatusChip(icon: ImageVector, text: String, tint: Color) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = text, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/** v1.9.2：详情弹窗的「标签 + 内容」行（只读展示） */
@Composable
private fun DetailLine(label: String, value: String, tint: Color? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}

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
    // v1.7.1 实时联动：进入分镜页/切项目(episodeId 变化)时强制重拉，
    // 让 AI 生成的分镜立刻可见（否则只在 VM 首次构造时拉一次，切走再切回是陈旧数据）。
    androidx.compose.runtime.LaunchedEffect(episodeId) { vm.refresh() }
    // v1.7.3：项目内查看剧本——进入分镜页即加载本集剧本原文，折叠卡展示
    var scriptText by remember { mutableStateOf<String?>(null) }
    var showScript by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(episodeId) {
        scriptText = runCatching {
            withContext(Dispatchers.IO) { com.dramafactory.app.AppGraph.dao.episode(episodeId)?.script_json }
        }.getOrNull()
    }
    // v1.9.2：分镜详情（点击卡片查看全部内容 + 引用资产）；编辑仍走「编辑」按钮
    var viewingShotId by remember { mutableStateOf<String?>(null) }
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

        // v1.7.3：项目内查看剧本——折叠卡，点开看本集剧本原文（AI 提取资产/分镜的依据）
        scriptText?.let { script ->
            if (script.isNotBlank()) {
                item {
                    DramaCard(
                        onClick = { showScript = !showScript },
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier) {
                            Row(Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Default.List, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp))
                                    Text("剧本原文（${script.length}字）",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(if (showScript) "收起" else "展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                    Icon(
                                        if (showScript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (showScript) "收起" else "展开",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (showScript) {
                                Text(script, Modifier.padding(top = 8.dp)
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        item {
            HeroButton(
                text = if (st.generating) "AI 生成中…" else "AI 生成分镜（编剧+导演）",
                onClick = { vm.generateWithAi() },
                enabled = !st.generating,
                modifier = Modifier.fillMaxWidth(),
            )
            if (st.generating) {
                LoadingRow("正在拆解镜头并生成视觉指令，通常需要十几秒…")
            }
        }
        if (st.shots.isNotEmpty()) {
            item {
                PrimaryButton(
                    text = "渲染本集全部 ${st.shots.size} 镜",
                    onClick = { vm.enqueueRender { n -> queuedCount = n } },
                    enabled = !st.generating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        queuedCount?.let { n ->
            item {
                DramaCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (n > 0) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        Text(if (n > 0) "已入队 $n 镜，进度见「渲染」标签页" else "没有可渲染的镜头",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        st.message?.let { msg ->
            item {
                DramaCard(Modifier.fillMaxWidth()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("已生成") || msg.contains("✓")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!st.generating && st.shots.isEmpty()) {
            item {
                EmptyState(
                    icon = { Icon(painterResource(R.drawable.ic_movie), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                    title = "还没有分镜",
                    subtitle = "导入剧本后点上方「AI 生成分镜」，大模型会自动拆解镜头并生成视觉指令。",
                )
            }
        }
        items(st.shots, key = { it.shot_id }) { shot ->
            DramaCard(
                onClick = { viewingShotId = shot.shot_id },   // v1.9.2：点卡=查看详情
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${shot.shot_no} · ${shot.duration_seconds.toInt()}秒",
                            style = MaterialTheme.typography.titleSmall)
                        when {
                            shot.sb_check == "pass" -> StatusChip(
                                icon = Icons.Default.CheckCircle, text = "校验通过",
                                tint = MaterialTheme.colorScheme.primary)
                            shot.sb_check == "pending" -> StatusChip(
                                icon = Icons.Default.DateRange, text = "待生成",
                                tint = MaterialTheme.colorScheme.outline)
                            else -> StatusChip(
                                icon = Icons.Default.Warning,
                                text = shot.sb_check.removePrefix("error:"),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    shot.action?.let { Text("动作：$it", style = MaterialTheme.typography.bodyMedium) }
                    shot.visual_prompt?.let {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_movie), contentDescription = "视觉指令",
                                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    // v1.7.17：把「本镜引用了哪些资产」显式摆出来。
                    // 此前这层完全不可见——LLM 引错（或引了还没生图的卡）用户无从察觉，
                    // 只会在成片里看到角色换脸。引用为空即代表渲染时回退项目级前4张，锁不住脸。
                    val refs = AssetCatalog.parseRefIds(shot.first_asset_ids)
                    if (refs.isNotEmpty()) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_link), contentDescription = "引用资产",
                                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                            Text("引用：${refs.joinToString("、") { st.assetNames[it] ?: it }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    } else {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "未引用资产",
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text("未引用资产：渲染将回退项目级前4张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    shot.dialogue?.let { Text("台词：「$it」", style = MaterialTheme.typography.bodySmall) }
                    shot.narration?.let { Text("旁白：$it", style = MaterialTheme.typography.bodySmall) }
                    shot.carry_over?.let { Text("承接：$it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 第十三轮：已出产视频 → 预览入口
                        st.videoUris[shot.shot_id]?.let { uri ->
                            OutlinedButton(onClick = { previewUri = uri }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "预览",
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("预览")
                            }
                        }
                        OutlinedButton(onClick = { editingShotId = shot.shot_id }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑",
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑")
                        }
                        OutlinedButton(onClick = { confirmDeleteShotId = shot.shot_id }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }

    // ---- v1.9.2：单镜详情对话框（点击卡片查看全部内容 + 引用资产缩略图）----
    val viewingShot = viewingShotId?.let { id -> st.shots.firstOrNull { it.shot_id == id } }
    if (viewingShot != null) {
        AlertDialog(
            onDismissRequest = { viewingShotId = null },
            title = { Text("镜头 #${viewingShot.shot_no} · ${viewingShot.duration_seconds.toInt()}秒") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        viewingShot.sb_check == "pass" -> StatusChip(
                            Icons.Default.CheckCircle, "校验通过", MaterialTheme.colorScheme.primary)
                        viewingShot.sb_check == "pending" -> StatusChip(
                            Icons.Default.DateRange, "待生成", MaterialTheme.colorScheme.outline)
                        else -> StatusChip(
                            Icons.Default.Warning,
                            viewingShot.sb_check.removePrefix("error:"),
                            MaterialTheme.colorScheme.error)
                    }
                    viewingShot.action?.let { DetailLine("动作", it) }
                    viewingShot.visual_prompt?.let {
                        DetailLine("视觉指令", it, MaterialTheme.colorScheme.secondary) }
                    viewingShot.dialogue?.let { DetailLine("台词", "「$it」") }
                    viewingShot.narration?.let { DetailLine("旁白", it) }
                    viewingShot.carry_over?.let {
                        DetailLine("承接", it, MaterialTheme.colorScheme.outline) }

                    Text("引用资产", style = MaterialTheme.typography.titleSmall)
                    val refs = AssetCatalog.parseRefIds(viewingShot.first_asset_ids)
                    if (refs.isEmpty()) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text("未引用资产：渲染将回退项目级前4张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        for (refId in refs) {
                            val name = st.assetNames[refId] ?: refId
                            val thumb = st.assetThumbs[refId]
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (thumb != null) {
                                    AsyncImage(
                                        model = thumb, contentDescription = name,
                                        modifier = Modifier.size(46.dp)
                                            .clip(MaterialTheme.shapes.extraSmall),
                                        contentScale = ContentScale.Crop)
                                } else {
                                    Box(
                                        Modifier.size(46.dp)
                                            .clip(MaterialTheme.shapes.extraSmall)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = androidx.compose.ui.Alignment.Center,
                                    ) {
                                        Icon(painterResource(R.drawable.ic_image), contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (thumb != null) "已生图 · 渲染时注入锁脸"
                                        else "尚无图 · 渲染时可能失锁",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (thumb != null) MaterialTheme.colorScheme.outline
                                                else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    editingShotId = viewingShot.shot_id
                    viewingShotId = null
                }) { Text("编辑") }
            },
            dismissButton = { OutlinedButton(onClick = { viewingShotId = null }) { Text("关闭") } },
        )
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
