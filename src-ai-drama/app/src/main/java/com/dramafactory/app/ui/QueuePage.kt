package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 渲染队列页（S6）：每集渲染进度列表（镜状态机实时刷新）、暂停/恢复/取消、
 * 预算确认弹窗（budgetConfirmed放行位对齐）、RECONCILE人工处置对话框。
 */
@Composable
fun QueuePage(vm: QueueViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "queue_default")) {
    val st by vm.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("渲染队列", style = MaterialTheme.typography.headlineSmall)

        // ---- 总进度卡 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

                // ---- 暂停/恢复按钮组 ----
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
            }
        }

        // ---- 镜状态列表（状态机六态实时刷新）----
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((shotId, stateName) in st.shotStates) {
                item(key = shotId) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(shotId, style = MaterialTheme.typography.bodyMedium)
                                Text(shotStateLabel(stateName), style = MaterialTheme.typography.bodySmall,
                                    color = shotStateColor(stateName))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (stateName == "RECONCILE") {
                                    // 待对账镜 → 人工处置对话框（复审N-2条件项）
                                    OutlinedButton(onClick = {
                                        vm.openReconcileDialog(shotId, "提交结果未知，需人工核实")
                                    }) { Text("处置") }
                                } else if (stateName in listOf("PENDING", "SUBMITTING", "SUBMITTED")) {
                                    OutlinedButton(onClick = { vm.cancelShot(shotId) }) { Text("取消") }
                                }
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

    // ---- 入队错误横幅 ----
    st.enqueueError?.let { err ->
        AlertDialog(onDismissRequest = { vm.clearEnqueueError() },
            title = { Text("入队失败") }, text = { Text(err) },
            confirmButton = { Button(onClick = { vm.clearEnqueueError() }) { Text("知道了") } })
    }
}

/** 状态机中文标签 */
internal fun shotStateLabel(s: String) = when (s) {
    "PENDING" -> "⏳ 待处理"
    "SUBMITTING" -> "📤 提交中"
    "SUBMITTED" -> "🔄 已提交·生成中"
    "COMPLETED" -> "✅ 已完成"
    "FAILED" -> "❌ 失败"
    "BLOCKED" -> "⛔ 已放弃"
    "RECONCILE" -> "⚠️ 待对账"
    else -> s
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
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
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(sid); Text(label, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun shotStateLabelStatic(s: String) = when (s) {
    "SUBMITTED" -> "🔄 已提交·生成中"; "COMPLETED" -> "✅ 已完成"; "RECONCILE" -> "⚠️ 待对账"; else -> s
}
