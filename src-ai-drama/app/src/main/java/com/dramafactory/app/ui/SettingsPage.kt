package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 设置页（P0）：视频模型供应商选择（MVP仅Agnes）+ API Key输入 + 「测试连通」+ 保存KeyVault。
 * 附ROM保活指引入口说明（Q7）。
 */
@Composable
fun SettingsPage(vm: SettingsViewModel = viewModel()) {
    val st by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        // ---- 供应商选择（MVP只有Agnes，单选卡片）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("视频模型供应商", style = MaterialTheme.typography.titleMedium)
                Text("● ${st.providerLabel}", style = MaterialTheme.typography.bodyMedium)
                Text("后续将支持 Kling / 即梦 等（通道独立适配）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ---- API Key输入 + 测试连通 + 保存 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API Key", style = MaterialTheme.typography.titleMedium)
                st.maskedSaved?.let { masked ->
                    Text("已保存：$masked", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)   // 仅掩码，永不回显明文
                }
                OutlinedTextField(
                    value = st.keyInput,
                    onValueChange = vm::onKeyChanged,
                    label = { Text(if (st.maskedSaved == null) "输入 sk- 开头的API Key" else "输入新Key以更换") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.testConnection() }, enabled = !st.testing) {
                        Text("测试连通")
                    }
                    Button(onClick = { vm.saveKey() }, enabled = !st.testing && st.keyInput.isNotBlank()) {
                        Text("保存到安全存储")
                    }
                    if (st.testing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
                }
                when (val r = st.testResult) {
                    is SettingsLogic.TestResult.Success ->
                        Text("✓ 连通成功 · 延迟${r.latencyMs}ms", color = MaterialTheme.colorScheme.primary)
                    is SettingsLogic.TestResult.Failure ->
                        Text("✗ ${r.message}", color = MaterialTheme.colorScheme.error)
                    null -> {}
                }
                if (st.saved) Text("已加密保存至Keystore（EncryptedSharedPreferences）",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Text("提示：建议先「测试连通」再保存；Key失效时渲染会自动暂停并回到本页。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        // ---- ROM保活指引入口（Q7）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("渲染保活指引", style = MaterialTheme.typography.titleMedium)
                Text("国产ROM（小米/华为/OPPO/vivo）需手动允许后台运行：",
                    style = MaterialTheme.typography.bodySmall)
                Text("1. 最近任务下拉本应用 → 加锁；\n" +
                     "2. 设置→电池→无限制/允许后台高耗电；\n" +
                     "3. 自启动权限开启。\n否则长时间渲染可能被系统杀进程（进度可断点续传，不重复扣费）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

// ---------- Compose预览 ----------

@Preview(showBackground = true, locale = "zh")
@Composable
private fun PreviewSettingsIdle() {
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("视频模型供应商", style = MaterialTheme.typography.titleMedium)
                    Text("● Agnes（MVP唯一供应商）")
                    OutlinedTextField(value = "sk-test", onValueChange = {}, label = { Text("输入 sk- 开头的API Key") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {}) { Text("测试连通") }
                        Button(onClick = {}) { Text("保存到安全存储") }
                    }
                }
            }
        }
    }
}
