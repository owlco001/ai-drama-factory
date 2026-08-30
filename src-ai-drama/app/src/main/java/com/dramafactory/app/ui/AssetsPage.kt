package com.dramafactory.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.dramafactory.app.AppGraph
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
    var kind by remember { mutableStateOf(AssetsLogic.Kind.CHARACTER) }
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

    // 资产流状态提升到composable上下文（LazyColumn content非@Composable）
    val assetsState = vm?.assets?.collectAsState()
        // 第九轮修复：手机小屏显示不全+下拉无反应——原布局为 Column 嵌套 LazyColumn，
    // 外层不可滚、内层被限高。改为单层可滚动 LazyColumn（头部区块各占一个 item）。
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("资产库", style = MaterialTheme.typography.headlineSmall) }

        if (projectId == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("请先在「项目」页创建/进入一个项目", Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.outline)
                }
            }
            return@LazyColumn
        }

        // ---- 分组筛选chips + 添加资产 ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in AssetsLogic.Kind.entries) {
                    FilterChip(selected = kind == k && !selectionMode,
                        onClick = { if (!selectionMode) kind = k }, label = { Text(k.label) },
                        enabled = !selectionMode,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !selectionMode, selected = kind == k && !selectionMode,
                            borderColor = if (kind == k && !selectionMode) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.outlineVariant))
                }
                Spacer(Modifier.weight(1f))
                // 第十二轮：批量管理开关
                FilterChip(selected = selectionMode,
                    onClick = {
                        selectionMode = !selectionMode
                        selectedIds.value = emptySet()
                    }, label = { Text(if (selectionMode) "退出选择" else "☑ 批量") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = selectionMode,
                        borderColor = if (selectionMode) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        // 多选操作栏
        if (selectionMode) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selectedIds.value.size} 项",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            // 全选当前显示资产
                            selectedIds.value = (assetsState?.value ?: emptyList()).map { it.assetId }.toSet()
                        }) { Text("全选") }
                        Button(onClick = {
                            val ids = selectedIds.value.toList()
                            selectedIds.value = emptySet()
                            selectionMode = false
                            confirmBatchDeleteIds = ids
                        }, enabled = selectedIds.value.isNotEmpty()) { Text("🗑 删除所选") }
                    }
                }
            }
        }

        // ★v0.4修复：剧本模式资产生成入口缺失——剧本模式只是跳过文本分析自动建卡，
        // 用户仍需从剧本文本一键提取角色/场景/道具卡并生成图像（分镜渲染依赖资产ID）。
        item {
        val scriptMode by vm?.scriptMode?.collectAsState() ?: remember { mutableStateOf(false) }
        if (scriptMode && vm != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("剧本模式 · 资产生成", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.extractFromScript() }) { Text("一键从剧本提取资产卡") }
                        OutlinedButton(onClick = { vm.generatePendingOfKind(kind) }) {
                            Text("逐类生成图像（${kind.label}）")
                        }
                    }
                    val extractMsg by vm.extractMessage.collectAsState()
                    extractMsg?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        }

        item {
        // ---- 第九轮+第十三轮：时代红线（按剧本自动推断朝代，可按剧集放行跨时代器物）----
        val eraAllowed by vm?.allowedCrossEra?.collectAsState() ?: remember { mutableStateOf(emptyList<String>()) }
        val eraLabel by vm?.eraLabel?.collectAsState() ?: remember { mutableStateOf("西汉末年至新莽时期（默认）") }
        if (vm != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时代红线 · $eraLabel", style = MaterialTheme.typography.titleMedium)
                    Text("已根据剧本自动推断时代约束；若剧本为穿越设定，" +
                            "可声明本集允许出现的跨时代器物放行。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                    // 预设放行候选（历史向剧本才有意义；现代/架空无禁词则隐藏）
                    val hasForbidden = com.dramafactory.app.ui.QualityEngine.HAN_PRESET.forbiddenEraTerms.isNotEmpty()
                    val candidates = listOf("游标卡尺", "短裙", "现代招牌", "手机", "相机", "电脑")
                    val selected = remember { mutableStateOf(eraAllowed.toSet()) }
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (c in candidates) {
                            FilterChip(
                                selected = selected.value.contains(c),
                                onClick = {
                                    selected.value = if (selected.value.contains(c)) selected.value - c else selected.value + c
                                    vm.setEpisodeAllowedCrossEra(selected.value.toList())
                                },
                                label = { Text(c) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected.value.contains(c),
                                    borderColor = if (selected.value.contains(c)) MaterialTheme.colorScheme.primary
                                                 else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
            }
        }
        }

        item {
        // ---- 第六轮：本地上传入口（拍摄/相册图/相册视频）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地上传（图生图/图生视频素材）", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { startCapture(CaptureKind.IMAGE) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("拍摄图片") }
                    OutlinedButton(
                        onClick = { startCapture(CaptureKind.VIDEO) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text("拍摄视频") }
                }
                // 第八轮：拍摄/上传失败提示（权限被拒/无相机/文件无效），替代闪退
                captureError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { albumImageLauncher.launch("image/*") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text("相册图片")
                    }
                    OutlinedButton(onClick = { albumVideoLauncher.launch("video/*") }) {
                        Text("相册视频")
                    }
                }
            }
        }
        }

        item {
        OutlinedTextField(value = prompt, onValueChange = { prompt = it },
            label = { Text("${kind.label}描述，如：女主·冷艳·黑长直") },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val p = prompt; prompt = ""
            vm?.add("a_${System.currentTimeMillis()}", kind, p)
        }, enabled = prompt.isNotBlank()) { Text("添加并生成") }
        }

        // ---- 资产卡片流（分组排序：同kind相邻）----
        // ★第五轮修复：vm可能为null（引擎未就绪/预览），不再 vm!! 硬断言，降级为提示
        if (vm == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("引擎未就绪，请重启应用后重试", Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
        val assets = assetsState?.value ?: emptyList()
        // 分组卡片流：同kind相邻，组头为独立item（单层LazyColumn，全页可滚动）
        val grouped: kotlin.collections.Map<AssetsLogic.Kind, kotlin.collections.List<AssetsLogic.AssetCard>> =
            assets.groupBy { it.kind }
        for ((g: AssetsLogic.Kind, cards: kotlin.collections.List<AssetsLogic.AssetCard>) in grouped) {
            item(key = "hdr_$g") { Text(g.label, style = MaterialTheme.typography.titleMedium) }
            for (card in cards) {
                item(key = card.assetId) {
                    AssetCardView(
                        card = card,
                        onRegen = { vm.generate(card.assetId) },
                        onReviewKeep = { vm.review(card.assetId, keep = true) },
                        onReviewRegen = { vm.review(card.assetId, keep = false) },
                        onSetReference = if (card.kind == AssetsLogic.Kind.LOCAL && card.imageUri != null) {
                            { vm.setReferenceImage(card.assetId, card.imageUri) }
                        } else null,
                        onClearReference = { vm.setReferenceImage(card.assetId, null) },
                        onBuildPosePack = if (card.kind == AssetsLogic.Kind.CHARACTER && card.parentId == null) {
                            { vm.buildCharacterPosePack(card.assetId) }
                        } else null,
                        onOpenEditor = {
                            if (selectionMode) {
                                // 多选模式：点击切换勾选
                                selectedIds.value = if (card.assetId in selectedIds.value)
                                    selectedIds.value - card.assetId else selectedIds.value + card.assetId
                            } else editingAssetId = card.assetId
                        },
                        batchSelected = selectionMode,
                        selected = card.assetId in selectedIds.value,
                    )
                }
            }
        }

        // ---- 评审闸门：全keep才亮按钮（GateReport.reviewPassed语义）----
        item {
            Button(onClick = onContinue,
                enabled = vm.reviewAllPassed(),
                modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.reviewAllPassed()) "评审通过 · 去渲染" else "请先完成全部资产评审（保留）")
            }
        }
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
                                Text(if (editorCard.referenceImageUri == null) "🖼 上传参考图" else "🖼 更换参考图")
                            }
                            if (editorCard.referenceImageUri != null) {
                                OutlinedButton(onClick = { vm?.setReferenceImage(editorCard.assetId, null) }) {
                                    Text("清除参考图")
                                }
                            }
                        }
                        if (editorCard.referenceImageUri != null)
                            Text("✓ 已挂参考图，重生成时作为 input_images",
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
                                modifier = Modifier.padding(end = 8.dp)) { Text("⏹ 停止") }
                        }
                        // 删除资产（含子卡），确认后执行
                        if (vm != null) {
                            OutlinedButton(onClick = { confirmDeleteId = editorCard.assetId },
                                modifier = Modifier.padding(end = 8.dp)) { Text("🗑 删除") }
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
                text = { Text("将同时删除其6姿态子卡与已生成的图，不可恢复。") },
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
                text = { Text("将同时删除关联的6姿态子卡与已生成内容，不可恢复。") },
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

/** 单张资产卡：URL占位 + 生成转圈 + 评审checkbox（保留=勾选 / 重生成=未勾且触发再生成）
 * 第六轮：本地资产显示图片/视频URI；可设图生图参考图；参考图态高亮。 */
@Composable
fun AssetCardView(
    card: AssetsLogic.AssetCard,
    onRegen: () -> Unit,
    onReviewKeep: () -> Unit,
    onReviewRegen: () -> Unit,
    onSetReference: (() -> Unit)? = null,
    onClearReference: (() -> Unit)? = null,
    onBuildPosePack: (() -> Unit)? = null,
    /** 第十一轮：点击卡片进入编辑（描述/参考图上传） */
    onOpenEditor: (() -> Unit)? = null,
    /** 第十二轮：批量选择——batchSelected=true 进入多选态；selected=本卡是否已勾选 */
    batchSelected: Boolean = false,
    selected: Boolean = false,
) {
    Card(
        onClick = { onOpenEditor?.invoke() },
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 第八轮：资产缩略图预览（本地 imageUri / 生成 remoteUrl），无图/失败有占位
            Box {
                AssetThumb(card)
                if (batchSelected) {
                    Box(Modifier.size(22.dp).align(Alignment.TopEnd)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            MaterialTheme.shapes.extraSmall)
                        .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall),
                        contentAlignment = Alignment.Center) {
                        if (selected) Text("✓", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.prompt, style = MaterialTheme.typography.bodyMedium)
                // 第六轮：本地上传资产 URI 展示
                if (card.source == "local") {
                    when {
                        card.imageUri != null ->
                            Text("📷 本地图片 ${card.imageUri.takeLast(28)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        card.videoUri != null ->
                            Text("🎬 本地视频 ${card.videoUri.takeLast(28)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                    }
                }
                when {
                    card.generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("生成中… 点卡片可停止", style = MaterialTheme.typography.bodySmall)
                    }
                    card.remoteUrl != null ->
                        Text("已生成 ✓ ${card.remoteUrl.takeLast(24)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    else -> Text("未生成", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                // 第九轮：G1+G2 资产质量闸门状态展示
                if (card.auditState == "approved") {
                    Text("✅ 质量达标 ${(card.qualityScore ?: 0.0).let { "%.2f".format(it) }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (card.auditState == "rejected") {
                    Text("⛔ 质量拒绝：${card.rejectReason ?: card.defectsJson ?: ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else if (card.remoteUrl != null) {
                    Text("⏳ 质量审计中…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                // 第六轮：图生图参考图态
                if (card.referenceImageUri != null)
                    Text("🖼 已设为参考图（生成时作为input_images）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary)
                if (card.reviewState == "regen")
                    Text("已标记重生成 ↻", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                // 第十一轮：编辑入口提示
                Text("点按卡片可编辑描述 / 上传参考图", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
            }
            Checkbox(checked = card.reviewState == "keep", onCheckedChange = { checked ->
                if (checked) onReviewKeep() else onReviewRegen()
            })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 第六轮：图生图——有参考图则标签切换为「用参考图生成」
                OutlinedButton(onClick = onRegen, enabled = !card.generating) {
                    Text(if (card.referenceImageUri != null) "用参考图生成" else "重生成")
                }
                // 第九轮：角色母卡 → 生成 6 姿态 DNA 资产包
                if (onBuildPosePack != null) {
                    OutlinedButton(onClick = onBuildPosePack) { Text("生成6姿态") }
                }
                // 设/取消参考图（仅本地图片资产）
                when {
                    onSetReference != null ->
                        OutlinedButton(onClick = onSetReference) { Text("设为参考图") }
                    card.referenceImageUri != null && onClearReference != null ->
                        OutlinedButton(onClick = onClearReference) { Text("取消参考图") }
                }
            }
        }
    }
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
    fun placeholder(emoji: String) {
        Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
        }
    }
    if (card.videoUri != null) { placeholder("🎬"); return }
    val model: Any? = card.imageUri ?: card.remoteUrl
    if (model == null) { placeholder("🖼"); return }
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
            AssetCardView(
                card = AssetsLogic.AssetCard("a1", AssetsLogic.Kind.CHARACTER, "女主·冷艳·黑长直",
                    remoteUrl = "https://cdn.example.com/img/abc123.jpg", reviewState = "keep"),
                onRegen = {}, onReviewKeep = {}, onReviewRegen = {})
            AssetCardView(
                card = AssetsLogic.AssetCard("a2", AssetsLogic.Kind.SCENE, "雨夜霓虹街头",
                    generating = true),
                onRegen = {}, onReviewKeep = {}, onReviewRegen = {})
            AssetCardView(
                card = AssetsLogic.AssetCard("a3", AssetsLogic.Kind.LOCAL, "本地图片",
                    source = "local", imageUri = "content://media/external/images/media/123"),
                onRegen = {}, onReviewKeep = {}, onReviewRegen = {},
                onSetReference = {}, onClearReference = {})
        }
    }
}
