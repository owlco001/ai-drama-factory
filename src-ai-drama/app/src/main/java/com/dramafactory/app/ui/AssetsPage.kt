package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 资产库页（S3/S4）：角色/场景/道具分组卡片流；资产生成按钮（Text/Image Provider）；
 * 评审勾选（保留✓/重生成↻，F04）。全部「保留」后可进入渲染（评审闸门放行）。
 */
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("资产库", style = MaterialTheme.typography.headlineSmall)

        if (projectId == null) {
            Card(Modifier.fillMaxWidth()) {
                Text("请先在「项目」页创建/进入一个项目", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.outline)
            }
            return@Column
        }

        // ---- 分组筛选chips + 添加资产 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in AssetsLogic.Kind.entries) {
                FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
            }
        }

        // ★v0.4修复：剧本模式资产生成入口缺失——剧本模式只是跳过文本分析自动建卡，
        // 用户仍需从剧本文本一键提取角色/场景/道具卡并生成图像（分镜渲染依赖资产ID）。
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

        OutlinedTextField(value = prompt, onValueChange = { prompt = it },
            label = { Text("${kind.label}描述，如：女主·冷艳·黑长直") },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val p = prompt; prompt = ""
            vm?.add("a_${System.currentTimeMillis()}", kind, p)
        }, enabled = prompt.isNotBlank()) { Text("添加并生成") }

        // ---- 资产卡片流（分组排序：同kind相邻）----
        // ★第五轮修复：vm可能为null（引擎未就绪/预览），不再 vm!! 硬断言，降级为提示
        if (vm == null) {
            Card(Modifier.fillMaxWidth()) {
                Text("引擎未就绪，请重启应用后重试", Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }
        val assets by vm.assets.collectAsState()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val grouped: kotlin.collections.Map<AssetsLogic.Kind, kotlin.collections.List<AssetsLogic.AssetCard>> =
                assets.groupBy { asset -> asset.kind }
            for ((g: AssetsLogic.Kind, cards: kotlin.collections.List<AssetsLogic.AssetCard>) in grouped) {
                item(key = "hdr_$g") { Text(g.label, style = MaterialTheme.typography.titleMedium) }
                for (card in cards) {
                    item(key = card.assetId) {
                        AssetCardView(card = card,
                            onRegen = { vm?.generate(card.assetId) },
                            onReviewKeep = { vm?.review(card.assetId, keep = true) },
                            onReviewRegen = { vm?.review(card.assetId, keep = false) })
                    }
                }
            }
        }

        // ---- 评审闸门：全keep才亮按钮（GateReport.reviewPassed语义）----
        Button(onClick = onContinue,
            enabled = vm.reviewAllPassed(),
            modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.reviewAllPassed()) "评审通过 · 去渲染" else "请先完成全部资产评审（保留）")
        }
    }
}

/** 单张资产卡：URL占位 + 生成转圈 + 评审checkbox（保留=勾选 / 重生成=未勾且触发再生成） */
@Composable
fun AssetCardView(
    card: AssetsLogic.AssetCard,
    onRegen: () -> Unit,
    onReviewKeep: () -> Unit,
    onReviewRegen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.prompt, style = MaterialTheme.typography.bodyMedium)
                when {
                    card.generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("生成中…", style = MaterialTheme.typography.bodySmall)
                    }
                    card.remoteUrl != null ->
                        Text("已生成 ✓ ${card.remoteUrl.takeLast(24)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    else -> Text("未生成", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
                if (card.reviewState == "regen")
                    Text("已标记重生成 ↻", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
            }
            Checkbox(checked = card.reviewState == "keep", onCheckedChange = { checked ->
                if (checked) onReviewKeep() else onReviewRegen()
            })
            OutlinedButton(onClick = onRegen, enabled = !card.generating) { Text("重生成") }
        }
    }
}

// ---------- Compose预览 ----------

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
                card = AssetsLogic.AssetCard("a3", AssetsLogic.Kind.PROP, "古铜怀表"),
                onRegen = {}, onReviewKeep = {}, onReviewRegen = {})
        }
    }
}
