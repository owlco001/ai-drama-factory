package com.dramafactory.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.EmptyState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import com.dramafactory.app.ui.components.HeroButton
import com.dramafactory.app.ui.components.IconActionButton
import com.dramafactory.app.ui.components.PageHeader
import coil.compose.AsyncImage
import com.dramafactory.app.AppGraph
import com.dramafactory.app.ui.theme.DramaColor
import com.dramafactory.app.ui.theme.DramaGradient
import java.io.File
import kotlinx.coroutines.launch

/** 拍摄类型（决定权限请求后启动哪个相机Launcher） */
private enum class CaptureKind { IMAGE, VIDEO }

/**
 * 资产库页（S3/S4）：角色/场景/道具分组卡片流；资产生成按钮（Text/Image Provider）；
 * 评审勾选（保留✓/重生成↻，F04）。全部「保留」后可进入渲染（评审闸门放行）。
 * 第六轮新增：本地上传（拍摄/相册图/相册视频）、图生图参考图、本地资产预览。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssetsPage(
    projectId: String?,
    onContinue: () -> Unit,
    vm: AssetsViewModel? = projectId?.let {
        // ★第五轮修复：进入项目闪退根因——此前调用方不传vm，页面内 vm!! 直接NPE。
        // 现按projectId构造真实VM（key区分项目），并整体防崩溃：引擎未就绪时降级提示。
        androidx.lifecycle.viewmodel.compose.viewModel(
            key = "assets_$it",
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return AssetsViewModel(it) as T
                }
            })
    },
) {
    var kind by remember { mutableStateOf<AssetsLogic.Kind?>(null) }
    var prompt by remember { mutableStateOf("") }
    // v1.7.1 实时联动：进入资产页时从 Room 重读，让 AI 写入的资产立刻可见。
    // v1.7.4 修复：改用 LaunchedEffect(Unit)，因为 when(page) 切换会销毁重建本页 Composable，
    // 每次进入资产页都应重读——仅按 projectId 变化触发会漏掉「AI在其它标签提取资产后切回」的场景。
    androidx.compose.runtime.LaunchedEffect(Unit) { vm?.refreshFromDb(projectId ?: return@LaunchedEffect) }
    // 第十一轮：资产编辑器（点击卡片弹出）：非空=正在编辑该资产id
    var editingAssetId by remember { mutableStateOf<String?>(null) }
    // 参考图上传选择器（编辑器内触发）
    var refPickForAsset by remember { mutableStateOf<String?>(null) }
    // 第十一轮：删除资产二次确认
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    // 第十二轮：批量选择模式（非空集合=多选模式激活）
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateOf(setOf<String>()) }
    var confirmBatchDeleteIds by remember { mutableStateOf<List<String>?>(null) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 相机拍摄输出URI（TakePicture/TakeVideo 需先准备输出Uri）
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCaptureKind by remember { mutableStateOf(CaptureKind.IMAGE) }
    // 拍摄/上传错误提示（权限被拒/无相机应用/文件无效），不再以闪退形式出现
    var captureError by remember { mutableStateOf<String?>(null) }

    fun captureUri(ext: String): Uri {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.$ext")
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    }

    // ---- 本地上传 Launchers（第六轮+第八轮加固）----
    // 拍摄图片：★第八轮根因修复——TakePicture 回调类型是 Boolean（非 Bitmap?），
    // 旧代码 `bitmap != null` 恒真：用户取消拍摄也会把空文件落库。现在仅 success=true 才处理，
    // 且输出文件拷入 filesDir/uploads/（cacheDir 可能被系统清理，预览与图生图引用需稳定路径）。
    val cameraImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()) { success: Boolean ->
        val u = pendingCaptureUri
        pendingCaptureUri = null
        if (success && u != null) {
            scope.launch {
                val internal = AssetFiles.copyToInternal(ctx, u, isVideo = false)
                if (internal != null) vm?.uploadLocal(imageUri = internal, videoUri = null, prompt = "拍摄图片")
                else captureError = "拍摄图片失败（文件为空或不可读）"
            }
        }
    }
    // 拍摄视频：TakeVideo 回调为 Bitmap? 缩略图（非空=拍摄成功）；
    // 个别ROM成功也不回缩略图，故以输出文件非空兜底（copyToInternal 空文件返回null）。
    val cameraVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakeVideo()) { thumb: Bitmap? ->
        val u = pendingCaptureUri
        pendingCaptureUri = null
        if (u != null) {
            scope.launch {
                val internal = AssetFiles.copyToInternal(ctx, u, isVideo = true)
                if (internal != null) vm?.uploadLocal(imageUri = null, videoUri = internal, prompt = "拍摄视频")
                else captureError = if (thumb == null) "已取消拍摄" else "拍摄视频失败（文件为空或不可读）"
            }
        }
    }
    // 相册图片（GetContent 返回 content:// URI，临时读权限仅回调内有效 → 立即拷贝到内部目录）
    val albumImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val internal = AssetFiles.copyToInternal(ctx, it, isVideo = false)
                if (internal != null) vm?.uploadLocal(imageUri = internal, videoUri = null, prompt = "相册图片")
                else captureError = "图片读取失败（可能已无读权限）"
            }
        }
    }
    // 相册视频
    val albumVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val internal = AssetFiles.copyToInternal(ctx, it, isVideo = true)
                if (internal != null) vm?.uploadLocal(imageUri = null, videoUri = internal, prompt = "相册视频")
                else captureError = "视频读取失败（可能已无读权限）"
            }
        }
    }
    /** 启动相机 intent：runCatching 兜底（无相机应用 ActivityNotFoundException / 权限异常等），崩溃写日志不闪退 */
    fun launchCamera(kind: CaptureKind) {
        try {
            val u = captureUri(if (kind == CaptureKind.IMAGE) "jpg" else "mp4")
            pendingCaptureUri = u
            if (kind == CaptureKind.IMAGE) cameraImageLauncher.launch(u) else cameraVideoLauncher.launch(u)
        } catch (t: Throwable) {
            pendingCaptureUri = null
            AppGraph.CrashLog.record(ctx, "AssetsPage.camera", t)   // 复用 files/crash/last_crash.txt 机制
            captureError = "无法启动相机：${t.message ?: t.javaClass.simpleName}"
        }
    }

    // 相机权限请求：Manifest 声明了 CAMERA 但从不运行时申请 → target 34 下
    // ACTION_IMAGE_CAPTURE 直接抛 SecurityException（官方文档明确），即「点拍摄闪退」主根因。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera(pendingCaptureKind)
        } else {
            pendingCaptureUri = null
            captureError = "拍摄需要相机权限，请在系统设置中授予「相机」权限后重试"
        }
    }

    /** 拍摄入口：先检查 CAMERA 运行时权限，未授予则请求；授予后按暂存类型启动相机 */
    fun startCapture(kind: CaptureKind) {
        captureError = null
        pendingCaptureKind = kind
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                .onFailure {
                    AppGraph.CrashLog.record(ctx, "AssetsPage.permission", it)
                    captureError = "无法请求相机权限：${it.message ?: it.javaClass.simpleName}"
                }
        } else {
            launchCamera(kind)
        }
    }

    // 资产流状态提升到composable上下文
    val assetsState = vm?.assets?.collectAsState()
    val scriptMode by vm?.scriptMode?.collectAsState() ?: remember { mutableStateOf(false) }
    val eraAllowed by vm?.allowedCrossEra?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
    val eraLabel by vm?.eraLabel?.collectAsState() ?: remember { mutableStateOf("西汉末年至新莽时期（默认）") }
    val extractMsg by vm?.extractMessage?.collectAsState() ?: remember { mutableStateOf<String?>(null) }
    val assets = assetsState?.value ?: emptyList()
    val filteredAssets = remember(assets, kind) {
        if (kind == null) assets else assets.filter { it.kind == kind }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 92.dp)
        ) {
            // ---- 页面标题（设置入口统一在顶部 TopAppBar 齿轮）----
            item(span = { GridItemSpan(2) }) {
                PageHeader(title = "资产库", subtitle = "莽途·墨痕初现 · 第 1 集")
            }

            // ---- 分类筛选 chips ----
            item(span = { GridItemSpan(2) }) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
                    val kinds = listOf(null) + AssetsLogic.Kind.entries
                    for (k in kinds) {
                        val label = k?.label ?: "全部"
                        val selected = (k == null && kind == null) || k == kind
                        FilterChip(
                            selected = selected,
                            onClick = { kind = k },
                            label = { Text(label) },
                            enabled = !selectionMode,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = !selectionMode, selected = selected,
                                borderColor = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
                    // 批量管理
                    FilterChip(
                        selected = selectionMode,
                        onClick = {
                            selectionMode = !selectionMode
                            selectedIds.value = emptySet()
                        },
                        label = { Text(if (selectionMode) "退出选择" else "批量") },
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selectionMode,
                            borderColor = if (selectionMode) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }

            // ---- 剧本模式 · 资产生成 主卡片 ----
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(DramaGradient.hero()))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(R.drawable.ic_sparkle), contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Text("剧本模式 · 资产生成", style = MaterialTheme.typography.titleMedium)
                        }
                        HeroButton(
                            text = "一键从剧本提取资产卡",
                            onClick = { vm?.extractFromScript() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (k in AssetsLogic.Kind.entries.filter { it != AssetsLogic.Kind.LOCAL }) {
                                AssistChip(
                                    onClick = { vm?.generatePendingOfKind(k) },
                                    label = { Text("逐类生成 · ${k.label}") },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = AssistChipDefaults.assistChipBorder(
                                        enabled = true,
                                        borderColor = MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                            }
                        }
                        extractMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // ---- 时代红线 ----
            item(span = { GridItemSpan(2) }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_shield), contentDescription = "时代红线",
                                tint = DramaColor.Warning, modifier = Modifier.size(22.dp))
                            Text("时代红线", style = MaterialTheme.typography.titleMedium)
                            Surface(
                                color = DramaColor.Warning.copy(alpha = 0.15f),
                                shape = MaterialTheme.shapes.extraSmall,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Text(" $eraLabel ", style = MaterialTheme.typography.labelSmall,
                                    color = DramaColor.Warning,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        Text("已根据剧本自动推断时代约束；若剧本为穿越设定，请手动改写红线后再生成图像。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val selected = remember(eraAllowed) { eraAllowed.toSet() }
                            for (c in listOf("游标卡尺", "短裙", "现代招牌", "手机", "相机", "电脑")) {
                                FilterChip(
                                    selected = selected.contains(c),
                                    onClick = {
                                        val newSet = if (selected.contains(c)) selected - c else selected + c
                                        vm?.setEpisodeAllowedCrossEra(newSet.toList())
                                    },
                                    label = { Text(c) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true, selected = selected.contains(c),
                                        borderColor = if (selected.contains(c)) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.outlineVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ---- 添加资产 ----
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    Text("添加资产", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("${kind?.label ?: "人物"}描述，如：女主·冷艳·黑长直") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val p = prompt; prompt = ""
                                    if (p.isNotBlank()) vm?.add("a_${System.currentTimeMillis()}", kind ?: AssetsLogic.Kind.CHARACTER, p)
                                },
                                enabled = prompt.isNotBlank()
                            ) { Icon(Icons.Default.Add, contentDescription = "添加") }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconActionButton(label = "拍摄图片", onClick = { startCapture(CaptureKind.IMAGE) }, modifier = Modifier.weight(1f),
                            icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = "拍摄图片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                        IconActionButton(label = "拍摄视频", onClick = { startCapture(CaptureKind.VIDEO) }, modifier = Modifier.weight(1f),
                            icon = { Icon(painterResource(R.drawable.ic_videocam), contentDescription = "拍摄视频",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                        IconActionButton(label = "相册图片", onClick = { albumImageLauncher.launch("image/*") }, modifier = Modifier.weight(1f),
                            icon = { Icon(painterResource(R.drawable.ic_image), contentDescription = "相册图片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                        IconActionButton(label = "相册视频", onClick = { albumVideoLauncher.launch("video/*") }, modifier = Modifier.weight(1f),
                            icon = { Icon(painterResource(R.drawable.ic_video_library), contentDescription = "相册视频",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                    }
                    captureError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // ---- 资产网格头部 ----
            item(span = { GridItemSpan(2) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("资产", style = MaterialTheme.typography.titleMedium)
                    val selectedCount = selectedIds.value.size
                    val total = filteredAssets.size
                    Text(
                        if (selectionMode) "已选 $selectedCount 项 · 全选" else "共 $total 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // ---- 多选操作栏 ----
            if (selectionMode) {
                item(span = { GridItemSpan(2) }) {
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selectedIds.value.size} 项",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                selectedIds.value = assets.map { it.assetId }.toSet()
                            }) { Text("全选") }
                            Button(onClick = {
                                val ids = selectedIds.value.toList()
                                selectedIds.value = emptySet()
                                selectionMode = false
                                confirmBatchDeleteIds = ids
                            }, enabled = selectedIds.value.isNotEmpty()) { Text("删除所选") }
                        }
                    }
                }
            }

            // ---- 引擎未就绪 ----
            if (vm == null) {
                item(span = { GridItemSpan(2) }) {
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                        Text("引擎未就绪，请重启应用后重试", Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            } else if (filteredAssets.isEmpty()) {
                // v1.8.3：改用统一 EmptyState 组件（保留插画主视觉）
                item(span = { GridItemSpan(2) }) {
                    EmptyState(
                        illustration = painterResource(R.drawable.ui_empty_assets),
                        illustrationDesc = "资产为空",
                        title = "还没有资产",
                        subtitle = "上传图片或让 AI 从剧本提取角色/场景/道具",
                    )
                }
            } else {
                // 两列资产网格
                items(filteredAssets, key = { it.assetId }) { card ->
                    GridAssetCard(
                        card = card,
                        selectionMode = selectionMode,
                        selected = card.assetId in selectedIds.value,
                        onToggleSelect = {
                            selectedIds.value = if (card.assetId in selectedIds.value)
                                selectedIds.value - card.assetId else selectedIds.value + card.assetId
                        },
                        onOpenEditor = { editingAssetId = card.assetId },
                        onRegen = { vm.generate(card.assetId) },
                        onBuildReferenceSheet = if (card.kind == AssetsLogic.Kind.CHARACTER && card.parentId == null) {
                            { vm.buildCharacterReferenceSheet(card.assetId) }
                        } else null
                    )
                }
            }
        }

        // ---- 粘性底部 CTA：继续渲染 ----
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
        ) {
            val passed = vm?.reviewAllPassed() == true
            HeroButton(
                text = if (passed) "继续渲染" else "请先完成全部资产评审（保留）",
                onClick = onContinue,
                enabled = passed,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
        // ---- 第十一轮：资产编辑器（点击卡片弹出：改描述/上传参考图）----
        val editorCard = editingAssetId?.let { id ->
            vm?.assets?.value?.firstOrNull { it.assetId == id }
        }
        if (editorCard != null) {
            var editPrompt by remember(editorCard.assetId) { mutableStateOf(editorCard.prompt) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editingAssetId = null },
                title = { Text("编辑资产") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AssetThumb(editorCard)
                        OutlinedTextField(
                            value = editPrompt,
                            onValueChange = { editPrompt = it },
                            label = { Text("资产描述") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { refPickForAsset = editorCard.assetId }) {
                                Text(if (editorCard.referenceImageUri == null) "上传参考图" else "更换参考图")
                            }
                            if (editorCard.referenceImageUri != null) {
                                OutlinedButton(onClick = { vm?.setReferenceImage(editorCard.assetId, null) }) {
                                    Text("清除参考图")
                                }
                            }
                        }
                        if (editorCard.referenceImageUri != null)
                            Text("已挂参考图，重生成时作为 input_images",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        Text("保存后需点「重生成」才会按新描述/新参考图重新出图。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val v = vm
                        if (v != null) v.editAsset(editorCard.assetId, editPrompt) { changed ->
                            editingAssetId = null
                            if (changed) v.generate(editorCard.assetId)  // 描述变了→自动重新生成
                        }
                    }) { Text("保存并重新生成") }
                },
                dismissButton = {
                    Row {
                        // 第十一轮：生成中 → 停止按钮
                        if (editorCard.generating && vm != null) {
                            OutlinedButton(onClick = { vm.stopGenerate(editorCard.assetId) },
                                modifier = Modifier.padding(end = 8.dp)) { Text("停止") }
                        }
                        // 删除资产（含子卡），确认后执行
                        if (vm != null) {
                            OutlinedButton(onClick = { confirmDeleteId = editorCard.assetId },
                                modifier = Modifier.padding(end = 8.dp)) { Text("删除") }
                        }
                        OutlinedButton(onClick = { editingAssetId = null }) { Text("关闭") }
                    }
                },
            )
        }

        // ---- 第十一轮：删除资产二次确认 ----
        confirmDeleteId?.let { delId ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDeleteId = null },
                title = { Text("删除该资产？") },
                text = { Text("将同时删除其参考图子卡与已生成的图，不可恢复。") },
                confirmButton = {
                    Button(onClick = {
                        vm?.remove(delId)
                        editingAssetId = null
                        confirmDeleteId = null
                    }) { Text("删除") }
                },
                dismissButton = { OutlinedButton(onClick = { confirmDeleteId = null }) { Text("取消") } },
            )
        }

        // ---- 第十二轮：批量删除二次确认 ----
        confirmBatchDeleteIds?.let { ids ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmBatchDeleteIds = null },
                title = { Text("删除所选 ${ids.size} 项资产？") },
                text = { Text("将同时删除关联的参考图子卡与已生成内容，不可恢复。") },
                confirmButton = {
                    Button(onClick = {
                        vm?.removeBatch(ids)
                        confirmBatchDeleteIds = null
                    }) { Text("删除") }
                },
                dismissButton = { OutlinedButton(onClick = { confirmBatchDeleteIds = null }) { Text("取消") } },
            )
        }

        // 参考图相册选择器
        val refPickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
            val assetId = refPickForAsset
            refPickForAsset = null
            if (uri != null && assetId != null && vm != null) {
                scope.launch { vm.uploadReferenceImage(assetId, uri) {} }
            }
        }
        androidx.compose.runtime.LaunchedEffect(refPickForAsset) {
            if (refPickForAsset != null) refPickLauncher.launch("image/*")
    }
}

/** 设计稿 .asset 卡片：92dp 缩略图 + 标题/描述 + 状态 badge/操作 */
@Composable
private fun GridAssetCard(
    card: AssetsLogic.AssetCard,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onOpenEditor: () -> Unit,
    onRegen: () -> Unit,
    onBuildReferenceSheet: (() -> Unit)? = null,
) {
    val borderColor = if (card.reviewState == "regen" || card.auditState == "rejected") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        onClick = { if (selectionMode) onToggleSelect() else onOpenEditor() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column {
            // 缩略图区
            Box(
                modifier = Modifier.fillMaxWidth().height(92.dp)
                    .background(gridThumbBg(card)),
                contentAlignment = Alignment.Center
            ) {
                val model = card.imageUri ?: card.remoteUrl
                if (model != null) {
                    var failed by remember(model) { mutableStateOf(false) }
                    if (failed) {
                        Text("图片加载失败", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
                    } else {
                        AsyncImage(
                            model = model,
                            contentDescription = card.prompt,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                            onError = { failed = true },
                            onSuccess = { failed = false }
                        )
                    }
                } else {
                    Icon(gridThumbPainter(card), contentDescription = null,
                        modifier = Modifier.size(40.dp), tint = gridThumbTint(card))
                }
                // 多选态复选框
                if (selectionMode) {
                    Box(
                        modifier = Modifier.size(26.dp).align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.extraSmall)
                            .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) Icon(Icons.Default.Check, contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 内容区
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(card.prompt, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(gridStatusLine(card), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态 badge
                    when {
                        card.reviewState == "keep" -> StatusBadge("就绪", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                        card.reviewState == "regen" -> StatusBadge("重生成", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                        card.generating -> StatusBadge("生成中", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        card.remoteUrl != null -> StatusBadge("已生成", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        else -> StatusBadge("待生成", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // 操作按钮（评审 + 重生成/生成参考图）
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 保留 / 重生成 切换（小按钮）
                        val kept = card.reviewState == "keep"
                        val regen = card.reviewState == "regen"
                        OutlinedButton(
                            onClick = { if (kept) onOpenEditor() else onRegen() },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            shape = MaterialTheme.shapes.extraSmall,
                            border = BorderStroke(1.dp, if (kept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (kept) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                contentColor = if (kept) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(if (kept) "已选" else if (regen) "重生成" else "保留", style = MaterialTheme.typography.labelSmall)
                        }
                        // 角色母卡 → 生成参考图套装（4 张独立图，不拼图）
                        if (onBuildReferenceSheet != null) {
                            OutlinedButton(
                                onClick = onBuildReferenceSheet,
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = MaterialTheme.shapes.extraSmall,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) { Text("参考图", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Surface(
        color = bg,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.padding(0.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun gridThumbBg(card: AssetsLogic.AssetCard): Brush {
    val c1 = when (card.kind) {
        AssetsLogic.Kind.SCENE -> DramaColor.Primary.copy(alpha = 0.22f)
        AssetsLogic.Kind.CHARACTER, AssetsLogic.Kind.PROP, AssetsLogic.Kind.LOCAL -> DramaColor.Secondary.copy(alpha = 0.18f)
    }
    val c2 = MaterialTheme.colorScheme.surfaceContainerLow
    return Brush.linearGradient(0f to c1, 1f to c2)
}

@Composable
private fun gridThumbPainter(card: AssetsLogic.AssetCard): Painter = when {
    card.videoUri != null -> rememberVectorPainter(Icons.Default.PlayArrow)
    card.kind == AssetsLogic.Kind.SCENE -> painterResource(R.drawable.ic_movie)
    card.kind == AssetsLogic.Kind.PROP -> rememberVectorPainter(Icons.Default.Build)
    card.kind == AssetsLogic.Kind.LOCAL -> painterResource(R.drawable.ic_image)
    else -> rememberVectorPainter(Icons.Default.Person)
}

private fun gridThumbTint(card: AssetsLogic.AssetCard): androidx.compose.ui.graphics.Color = when (card.kind) {
    AssetsLogic.Kind.SCENE -> DramaColor.Primary
    AssetsLogic.Kind.CHARACTER, AssetsLogic.Kind.PROP, AssetsLogic.Kind.LOCAL -> DramaColor.Secondary
}

private fun gridStatusLine(card: AssetsLogic.AssetCard): String = when {
    card.videoUri != null -> "本地视频 · ${card.videoUri.takeLast(18)}"
    card.imageUri != null -> "本地图片 · ${card.imageUri.takeLast(18)}"
    card.remoteUrl != null && card.auditState == "approved" -> "${card.kind.label} · 质量达标 ${"%.2f".format(card.qualityScore ?: 0.0)}"
    card.remoteUrl != null && card.auditState == "rejected" -> "${card.kind.label} · 质量拒绝：${card.rejectReason ?: ""}"
    card.remoteUrl != null -> "${card.kind.label} · 质量审计中"
    card.generating -> "${card.kind.label} · 生成中"
    else -> "${card.kind.label} · 未生成"
}

// ---------- Compose预览 ----------

/**
 * 第八轮：资产缩略图。
 * - 本地图片（内部 file:// / content://）与生成图（http/https）经 Coil 异步加载（AsyncImage）；
 * - 视频资产显示 🎬 图标；无图显示 🖼 占位；加载失败显示「图片加载失败」。
 * 生成图的 remoteUrl 在卡生成成功后立即可见，本地 URI 已在上传时拷贝到内部目录，读取稳定。
 */
@Composable
private fun AssetThumb(card: AssetsLogic.AssetCard) {
    val size = 72.dp
    val shape = MaterialTheme.shapes.small
    val bg = MaterialTheme.colorScheme.surfaceVariant
    @Composable
    fun placeholder(icon: Painter) {
        Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
    if (card.videoUri != null) { placeholder(rememberVectorPainter(Icons.Default.PlayArrow)); return }
    val model: Any? = card.imageUri ?: card.remoteUrl
    if (model == null) { placeholder(painterResource(R.drawable.ic_image)); return }
    var failed by remember(model) { mutableStateOf(false) }
    if (failed) {
        Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
            Text("图片加载失败", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    } else {
        AsyncImage(
            model = model,
            contentDescription = card.prompt,
            modifier = Modifier.size(size).clip(shape),
            contentScale = ContentScale.Crop,
            placeholder = ColorPainter(bg),
            onError = { failed = true },
            onSuccess = { failed = false },
        )
    }
}

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewAssetCards() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资产库", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GridAssetCard(
                    card = AssetsLogic.AssetCard("a1", AssetsLogic.Kind.CHARACTER, "女主·冷艳·黑长直",
                        remoteUrl = "https://cdn.example.com/img/abc123.jpg", reviewState = "keep"),
                    selectionMode = false, selected = false,
                    onToggleSelect = {}, onOpenEditor = {}, onRegen = {}, onBuildReferenceSheet = {})
                GridAssetCard(
                    card = AssetsLogic.AssetCard("a2", AssetsLogic.Kind.SCENE, "雨夜霓虹街头",
                        generating = true),
                    selectionMode = false, selected = false,
                    onToggleSelect = {}, onOpenEditor = {}, onRegen = {})
            }
        }
    }
}
