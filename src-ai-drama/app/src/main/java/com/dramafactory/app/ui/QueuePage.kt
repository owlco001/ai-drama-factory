package com.dramafactory.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.dramafactory.app.R
import com.dramafactory.app.ui.components.DramaCard
import com.dramafactory.app.ui.components.EmptyState
import androidx.compose.ui.Alignment
import com.dramafactory.app.ui.components.PageHeader
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 渲染队列页（S6）：每集渲染进度列表（镜状态机实时刷新）、暂停/恢复/取消、
 * 预算确认弹窗（budgetConfirmed放行位对齐）、RECONCILE人工处置对话框。
 * 第六轮新增：每镜「设参考图」（图生视频，复用本地上传选图）、
 * 「上传参考视频」（仅当前视频模型标记 supportsVideoReference 时显示）。
 */
@Composable
fun QueuePage(
    episodeId: String = "default",
    vm: QueueViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "queue_$episodeId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return QueueViewModel(episodeId) as T
            }
        },
    ),
) {
    val st by vm.state.collectAsState()
    // 视频模型是否支持参考视频：原来是在列表 item 里同步查库，这里提升为一次性订阅
    val videoRefSupported by vm.videoRefSupported.collectAsState()

    // 第六轮：图生视频/视频参考 入口状态
    var pendingShotId by remember { mutableStateOf<String?>(null) }
    var showRefImagePicker by remember { mutableStateOf(false) }
    var showRefVideoPicker by remember { mutableStateOf(false) }

    // 相册图片 → 作为该镜首帧（图生视频 keyframes 起点；复用本地上传选图）
    val refImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        val shot = pendingShotId
        if (uri != null && shot != null) vm.setShotKeyframe(shot, first = uri.toString(), last = null)
        pendingShotId = null
    }
    // 相册视频 → 作为该镜视频参考（仅模型支持时）
    val refVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()) { uri: Uri? ->
        val shot = pendingShotId
        if (uri != null && shot != null) vm.setShotReferenceVideo(shot, uri.toString())
        pendingShotId = null
    }

    if (showRefImagePicker) {
        LaunchedPickEffect {
            refImageLauncher.launch("image/*")
            showRefImagePicker = false
        }
    }
    if (showRefVideoPicker) {
        LaunchedPickEffect {
            refVideoLauncher.launch("video/*")
            showRefVideoPicker = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader(title = "渲染队列", subtitle = "镜头状态机实时刷新 · 可暂停/恢复/取消")

        // ---- 总进度卡 ----
        DramaCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val snap = st.snapshot
                val pauseText: String? = snap.pausedReason
                Text("第${snap.episodeId ?: "-"}集 · ${snap.completedShots}/${snap.totalShots} 镜完成",
                    style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { if (snap.totalShots > 0) snap.completedShots.toFloat() / snap.totalShots else 0f },
                    modifier = Modifier.fillMaxWidth())
                val statusLine = when (pauseText) {
                    null -> if (snap.running) "渲染中…" else "空闲 · 点击「开始渲染」入队"
                    "budget_exceeded" -> "已暂停：预算达上限，等待确认"
                    "auth_401" -> "已暂停：API Key失效，请到设置页更新"
                    else -> "已暂停：$pauseText"
                }
                val isPaused = pauseText != null
                Text(statusLine, style = MaterialTheme.typography.bodySmall,
                    color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                // 预算用量条（条数型，决议Q2）
                Text("预算：${st.usage.used}/${st.usage.limit} 条" +
                        (if (st.usage.priceEstimateYuan > 0) " · 约¥%.2f".format(st.usage.priceEstimateYuan) else ""),
                    style = MaterialTheme.typography.bodySmall)

                // ---- 暂停/恢复/开始按钮组 ----
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pause() }, enabled = snap.running) { Text("暂停") }
                    Button(onClick = { vm.resume() },
                        enabled = !snap.running && snap.pausedReason != "budget_exceeded") { Text("恢复") }
                    Button(onClick = {
                        // 入队一集（shots由分镜层供给；MVP从checkpoint/DB取）
                        vm.enqueue(emptyList())
                    }, enabled = (!snap.running && snap.pausedReason == null && snap.totalShots > 0) ||
                            (!snap.running && st.shotStates.isNotEmpty())) {
                        Text(if (snap.running) "渲染中" else "开始渲染")
                    }
                }
                // v1.9.19：清空队列独立一行右置，避免与主控按钮挤在一行导致文字竖排
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { vm.requestClearQueue() },
                        enabled = !snap.running && st.shotStates.isNotEmpty()) {
                        Text("清空队列")
                    }
                }
            }
        }

        // ---- v1.9.20：storyboard_gate 详情直显（哪几镜、为什么），省去抓 logcat ----
        if (st.storyboardBlockDetail.isNotEmpty()) {
            DramaCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("分镜六铁律未过审 · 整集中止", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text("以下镜头分镜检查报 error，需回分镜页修复 / 重生成后再点「开始渲染」：",
                        style = MaterialTheme.typography.bodySmall)
                    for ((shotId, raw) in st.storyboardBlockDetail) {
                        val human = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString("；") { sbCheckLabel(it) }
                        Text("• $shotId：$human", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // ---- 镜状态列表（状态机六态实时刷新）----
        // weight(1f)：外层是 fillMaxSize 的 Column，不给高度约束的话 LazyColumn 会以
        // 「无限最大高度」测量 —— foundation 1.4+ 不再抛异常，但会退化为一次性全量排版
        // （丢掉复用）且超出屏幕部分滚不到。给它剩余空间才能正常懒加载 + 滚动。
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (st.shotStates.isEmpty()) {
                item {
                    EmptyState(
                        icon = { Icon(painterResource(R.drawable.ic_videocam), contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) },
                        title = "队列是空的",
                        subtitle = "先到分镜页生成镜头，再点「开始渲染」把它们送进渲染队列。",
                    )
                }
            }
            for ((shotId, stateName) in st.shotStates) {
                item(key = shotId) {
                    DramaCard(Modifier.fillMaxWidth()) {
                        Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(shotId, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(shotStateIcon(stateName), contentDescription = null,
                                        tint = shotStateColor(stateName), modifier = Modifier.size(16.dp))
                                    Text(shotStateLabel(stateName), style = MaterialTheme.typography.bodySmall,
                                        color = shotStateColor(stateName))
                                }
                                // v1.9.13：FAILED/BLOCKED/RECONCILE 直接展示原因，省去抓 logcat
                                val reason = st.shotReasons[shotId]
                                if (!reason.isNullOrBlank() && stateName in listOf("FAILED", "BLOCKED", "RECONCILE")) {
                                    Text(reason, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // 第六轮：图生视频「设参考图」入口（复用本地上传选图）
                                OutlinedButton(onClick = { pendingShotId = shotId; showRefImagePicker = true }) {
                                    Text("设参考图")
                                }
                                // 第六轮：视频参考入口——仅当前视频模型标记支持时显示
                                if (videoRefSupported) {
                                    OutlinedButton(onClick = { pendingShotId = shotId; showRefVideoPicker = true }) {
                                        Text("上传参考视频")
                                    }
                                }
                                if (stateName == "RECONCILE") {
                                    // 待对账镜 → 人工处置对话框（复审N-2条件项）
                                    OutlinedButton(onClick = {
                                        vm.openReconcileDialog(shotId, "提交结果未知，需人工核实")
                                    }) { Text("处置") }
                                } else if (stateName in listOf("PENDING", "SUBMITTING", "SUBMITTED")) {
                                    OutlinedButton(onClick = { vm.cancelShot(shotId) }) { Text("取消") }
                                }
                                // v1.9.18：失败/已放弃/待对账的镜可重试（重置 PENDING 并清失败原因）
                                if (stateName in listOf("FAILED", "BLOCKED", "RECONCILE")) {
                                    OutlinedButton(onClick = { vm.retryShot(shotId) }) { Text("重试") }
                                }
                                // v1.9.18：删除该镜（二次确认，彻底移除 shots + render_tasks）
                                OutlinedButton(onClick = { vm.requestDeleteShot(shotId) }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- 预算确认弹窗（resume(confirmedByUser=true)放行位语义）----
    if (st.showBudgetConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissBudgetConfirm() },
            title = { Text("预算已达上限") },
            text = { Text("已用 ${st.usage.used}/${st.usage.limit} 条。继续渲染将超出预设上限并产生额外费用。\n确认继续吗？") },
            confirmButton = { Button(onClick = { vm.confirmBudget() }) { Text("继续渲染（超限放行）") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissBudgetConfirm() }) { Text("暂不渲染") } },
        )
    }

    // ---- RECONCILE人工处置对话框：重试 / 放弃 ----
    st.reconcileShot?.let { (shotId, reason) ->
        AlertDialog(
            onDismissRequest = { vm.dismissReconcileDialog() },
            title = { Text("镜头待对账（RECONCILE）") },
            text = { Text("镜头 $shotId 提交结果未知，可能已在服务端创建任务并计费。\n原因：$reason\n\n「重试」将重置为待处理并续跑队列；「放弃」将其标记为终止态（不再自动处理）。") },
            confirmButton = { Button(onClick = { vm.resolveReconcile(retry = true) }) { Text("重试") } },
            dismissButton = { OutlinedButton(onClick = { vm.resolveReconcile(retry = false) }) { Text("放弃") } },
        )
    }

    // ---- v1.9.18：删除单镜二次确认（不可逆）----
    st.pendingDelete?.let { shotId ->
        AlertDialog(
            onDismissRequest = { vm.dismissDeleteShot() },
            title = { Text("删除镜头 $shotId？") },
            text = { Text("将彻底删除该镜：分镜数据（含台词/关键帧/资产引用）与渲染任务记录一并移除，且无法恢复。\n\n如果只是不想让它继续渲染，用「取消」即可。") },
            confirmButton = { Button(onClick = { vm.confirmDeleteShot() }) { Text("确认删除") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissDeleteShot() }) { Text("再想想") } },
        )
    }

    // ---- v1.9.18：清空本集队列二次确认 ----
    if (st.showClearConfirm) {
        AlertDialog(
            onDismissRequest = { vm.dismissClearQueue() },
            title = { Text("清空本集渲染队列？") },
            text = { Text("将清空本集全部渲染任务记录（含已完成/失败/待处理）。\n\n分镜数据（shots）会保留，之后仍可点「开始渲染」重新入队。") },
            confirmButton = { Button(onClick = { vm.confirmClearQueue() }) { Text("清空") } },
            dismissButton = { OutlinedButton(onClick = { vm.dismissClearQueue() }) { Text("取消") } },
        )
    }

    // ---- 入队错误横幅 ----
    st.enqueueError?.let { err ->
        AlertDialog(onDismissRequest = { vm.clearEnqueueError() },
            title = { Text("入队失败") }, text = { Text(err) },
            confirmButton = { Button(onClick = { vm.clearEnqueueError() }) { Text("知道了") } })
    }
}

/** 仅用于触发一次图片/视频选择器的副作用封装（避免Compose重组重复launch） */
@Composable
private fun LaunchedPickEffect(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

/** 分镜 sb_check 错误码 → 中文可读（对齐 AiStoryboardDirector 六铁律忠实性校验） */
internal fun sbCheckLabel(code: String) = when (code) {
    "dialogue_not_verbatim" -> "台词未逐字出现在剧本原文"
    "action_empty" -> "动作描述为空"
    else -> code
}

/** 状态机中文标签 */
internal fun shotStateLabel(s: String) = when (s) {
    "PENDING" -> "待处理"
    "SUBMITTING" -> "提交中"
    "SUBMITTED" -> "已提交·生成中"
    "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "BLOCKED" -> "已放弃"
    "RECONCILE" -> "待对账"
    else -> s
}

/** 状态机图标（与 shotStateColor 同一套语义色，替代 emoji 前缀） */
internal fun shotStateIcon(s: String): ImageVector = when (s) {
    "PENDING" -> Icons.Default.DateRange
    "SUBMITTING" -> Icons.Default.ArrowForward
    "SUBMITTED" -> Icons.Default.Refresh
    "COMPLETED" -> Icons.Default.CheckCircle
    "FAILED" -> Icons.Default.Warning
    "BLOCKED" -> Icons.Default.Clear
    "RECONCILE" -> Icons.Default.Warning
    else -> Icons.Default.Info
}

@Composable
private fun shotStateColor(s: String) = when (s) {
    "COMPLETED" -> MaterialTheme.colorScheme.primary
    "FAILED", "BLOCKED", "RECONCILE" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewQueuePage() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("渲染队列", style = MaterialTheme.typography.headlineSmall)
            DramaCard(Modifier.fillMaxWidth()) {
                Column(Modifier) {
                    Text("第p1_ep1集 · 12/24 镜完成", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { 0.5f }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}) { Text("暂停") }
                        Button(onClick = {}) { Text("恢复") }
                    }
                }
            }
            for ((sid, label) in mapOf(
                "s001" to shotStateLabelStatic("SUBMITTED"),
                "s002" to shotStateLabelStatic("COMPLETED"),
                "s003" to shotStateLabelStatic("RECONCILE"))) {
                DramaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sid); Text(label, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun shotStateLabelStatic(s: String) = when (s) {
    "SUBMITTED" -> "已提交·生成中"; "COMPLETED" -> "已完成"; "RECONCILE" -> "待对账"; else -> s
}
