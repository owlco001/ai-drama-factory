package com.dramafactory.app.ui
import com.dramafactory.core.provider.DefaultTextModelRouter

import kotlinx.coroutines.launch
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * T014 v1.4.0：新增「文本模型」区块（DeepSeek / Agnes 单选 + Key 各自独立保存）。
 */
@Composable
fun SettingsPage(vm: SettingsViewModel = viewModel()) {
    val st by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        // ---- 供应商选择（第四轮：多模型选择器）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("视频模型供应商", style = MaterialTheme.typography.titleMedium)
                Text("当前：${st.providerLabel}", style = MaterialTheme.typography.bodyMedium)
                for (p in ProviderRegistry.ALL) {
                    val selected = st.selectedProviderId == p.id
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { vm.selectProvider(p.id) })
                        Column(Modifier.weight(1f)) {
                            Text("${if (selected) "●" else "○"} ${p.label}",
                                style = MaterialTheme.typography.bodyMedium)
                            Text(if (p.status == ProviderRegistry.Status.AVAILABLE) p.note
                                 else "待接入 · 选择后暂不可渲染",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // ---- 自定义模型（OpenAI兼容格式，第四轮）----
        if (st.selectedProviderId == "custom") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("自定义模型配置", style = MaterialTheme.typography.titleMedium)
                    Text("协议默认模板：POST {base_url}/videos 提交，GET {base_url}/videos/{id} 轮询（OpenAI兼容/pavo agnes_client.py 同构）。提交体含 model、prompt、keyframes 双帧、generate_audio。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(value = st.customBaseUrl, onValueChange = { vm.onCustomFieldChanged("baseUrl", it) },
                        label = { Text("Base URL（如 https://api.example.com/v1）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = st.customModelId, onValueChange = { vm.onCustomFieldChanged("modelId", it) },
                        label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = st.customApiKey, onValueChange = { vm.onCustomFieldChanged("apiKey", it) },
                        label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = st.customNote, onValueChange = { vm.onCustomFieldChanged("note", it) },
                        label = { Text("提交方式说明（可选备注）") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.saveCustomModel() }) { Text("保存自定义模型") }
                        if (st.customSaved) Text("已保存 ✓ Key加密入库", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // ---- API Key输入 + 测试连通 + 保存 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API Key", style = MaterialTheme.typography.titleMedium)
                Text("（视频通道 · 当前供应商：${st.providerLabel}）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                st.maskedSaved?.let { masked ->
                    Text("已保存：$masked", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
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

        // ---- 文本模型选择（T014 v1.4.0 · Q4：文本/视频 Key 各自独立保存）----
        TextModelSettingsBlock()

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

        // ---- 开源信息（MIT License + 项目地址，点击可复制）----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("关于 · 开源", style = MaterialTheme.typography.titleMedium)
                Text("AI短剧工厂", style = MaterialTheme.typography.bodyMedium)
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val copied = remember { mutableStateOf<String?>(null) }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "ai-drama-factory", About.PROJECT_URL))
                    copied.value = "项目地址已复制"
                }) { Text(About.PROJECT_URL) }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "license", About.LICENSE_TEXT))
                    copied.value = "许可证文本已复制"
                }) { Text("License: MIT（点按复制全文）") }
                Text("本软件基于 MIT License 开源发布。使用本项目生成的内容版权归内容创作者所有；" +
                        "请遵守目标平台的内容规范与相关法律法规。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                copied.value?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// ---------- 文本模型区块（T014 v1.4.0 · Q4）----------

/**
 * 文本模型选择 + API Key 输入 + 测试连通 + 保存。
 * 状态用 DefaultTextModelRouter 的 store 承载（Key 各自独立保存，Q4 b 决议）。
 *
 * 设计原则：
 * - 不复用上方视频 API Key 输入框（两套 Key 彼此独立）；
 * - UI 只见掩码，永不回显明文；
 * - 保存前先 validate，防止坏 Key 覆盖好 Key。
 */
@Composable
fun TextModelSettingsBlock() {
    val scope = kotlinx.coroutines.GlobalScope
    val router = remember { DefaultTextModelRouter }
    var activeModelId by remember { mutableStateOf(router.activeTextModelId()) }
    var keyInput by remember { mutableStateOf("") }
    var masked by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<SettingsLogic.TestResult?>(null) }
    var saved by remember { mutableStateOf(false) }

    val entries = remember(activeModelId, masked, testResult) { router.registeredTextModels() }
    val activeEntry = entries.firstOrNull { it.providerId == activeModelId }

    // 切换模型时刷新当前掩码
    LaunchedEffect(activeModelId) {
        masked = router.registeredTextModels().firstOrNull { it.providerId == activeModelId }?.keyMasked
        saved = false
        testResult = null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文本模型（AI 全托管用")
            Text("选择「大脑」：AI 模式内粘贴剧本文本自动生成内容时调用此模型。视频/图像生成走上方供应商，不受影响。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            for (entry in entries) {
                val selected = activeModelId == entry.providerId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { activeModelId = entry.providerId })
                    Column(Modifier.weight(1f)) {
                        Text("${if (selected) "●" else "○"} ${entry.label}",
                            style = MaterialTheme.typography.bodyMedium)
                        Text("模型：${entry.modelId} · Base：${entry.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    if (entry.isVerified)
                        Text("✓ 已验证", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall)
                    else if (entry.keyMasked != null)
                        Text("已保存未验证", color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall)
                }
            }

            // DeepSeek 提供独立 Key 输入（Agnes 视频通道 Key 已在上文独立保存，此处仍允许配置文本专用 Key）
            val activeProvider = activeEntry?.providerId
            Text("${activeEntry?.label ?: activeModelId} · API Key",
                style = MaterialTheme.typography.titleMedium)
            masked?.let { m ->
                Text("已保存：$m", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (masked == null) "输入 sk- 开头的 API Key" else "输入新 Key 以更换") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (keyInput.trim().isEmpty()) {
                            testResult = SettingsLogic.TestResult.Failure("请先输入 API Key")
                            return@OutlinedButton
                        }
                        testing = true; testResult = null
                        scope.launch {
                            val r = router.validate(activeProvider ?: "deepseek", keyInput.trim())
                            testing = false
                            testResult = when {
                                r.isSuccess -> {
                                    val info = r.getOrThrow()
                                    if (info.ok) SettingsLogic.TestResult.Success(info.latencyMs)
                                    else SettingsLogic.TestResult.Failure(info.detail.ifEmpty { "连通失败" })
                                }
                                else -> SettingsLogic.TestResult.Failure(
                                    r.exceptionOrNull()?.message ?: "未知错误")
                            }
                        }
                    },
                    enabled = !testing
                ) { Text("测试连通") }
                Button(
                    onClick = {
                        val k = keyInput.trim()
                        if (k.isEmpty()) return@Button
                        val tested = (testResult as? SettingsLogic.TestResult.Success) != null
                        if (!tested) return@Button
                        scope.launch {
                            val r = router.saveKey(activeProvider ?: "deepseek", k)
                            if (r.isSuccess) {
                                keyInput = ""; saved = true
                                masked = router.registeredTextModels()
                                    .firstOrNull { it.providerId == activeProvider }?.keyMasked
                            } else {
                                testResult = SettingsLogic.TestResult.Failure(
                                    r.exceptionOrNull()?.message ?: "保存失败")
                            }
                        }
                    },
                    enabled = !testing && keyInput.isNotBlank()
                ) { Text("保存（加密）") }
                if (testing) CircularProgressIndicator(Modifier.padding(start = 4.dp))
            }
            when (val r = testResult) {
                is SettingsLogic.TestResult.Success ->
                    Text("✓ 连通成功 · 延迟${r.latencyMs}ms", color = MaterialTheme.colorScheme.primary)
                is SettingsLogic.TestResult.Failure ->
                    Text("✗ ${r.message}", color = MaterialTheme.colorScheme.error)
                null -> {}
            }
            if (saved) Text("已加密保存至 Keystore（Key 与视频通道独立）",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Text("提示：DeepSeek Chat 支持中文原生、上下文充足；Agnes 文本按输入规模自动选模（2.5/2.0/1.5 Flash）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 开源元信息单一事实源（与根目录 LICENSE / README 保持一致） */
object About {
    const val PROJECT_URL = "https://github.com/owlco001/ai-drama-factory"
    const val LICENSE_ID = "MIT"
    const val LICENSE_TEXT = """AI短剧工厂 Copyright (c) 2026 owlco001
Licensed under the MIT License.
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the SOFTWARE.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""
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
