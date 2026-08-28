package com.dramafactory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
import com.dramafactory.core.orchestrate.AiAgent
import com.dramafactory.core.orchestrate.ActionIntent
import com.dramafactory.core.orchestrate.DialogueTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局 AI 助手（悬浮球 + 聊天面板）。
 * - 全局单例语义：在 DramaApp 顶层用 remember 持有，所有标签页共享同一个对话与 agent。
 * - 知道"当前项目"（由 DramaApp 的导航选中项目注入 currentProjectId / currentEpisodeId）。
 * - AI 的 [ACT] 指令映射到 AppGraph 后端（资产/分镜/渲染/集），执行结果落 dao，
 *   即"生成对应的手动操作记录，可撤回/手改"（与手动操作并存、融合）。
 */
class AiAssistantViewModel : ViewModel() {
    private val _history = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val history: StateFlow<List<DialogueTurn>> = _history

    var isThinking by mutableStateOf(false)
        private set

    /** 当前项目上下文（由 DramaApp 注入，随底部导航选中项目变化） */
    var currentProjectId: String? = null
    var currentEpisodeId: String? = null

    private var agent: AiAgent? = null
    private var _building = false

    init {
        // 欢迎语
        _history.value = listOf(
            DialogueTurn(DialogueTurn.Side.AI,
                "嗨，我是你的短剧编剧导演搭档 🎬 选中一个项目后，我可以帮你提取资产、生成分镜、入渲染、审资产——直接跟我说就行，也可以随时手动操作。")
        )
    }

    /** 确保 agent 已构建（首次对话时懒加载，避免启动期网络调用） */
    private suspend fun ensureAgent() {
        if (agent != null || _building) return
        _building = true
        runCatching {
            val router = AppGraph.textModelRouter
            val modelId = router.activeTextModelId()
            val provider = router.resolve(modelId)
            agent = AiAgent(
                textProvider = provider,
                modelId = modelId,
                actionHandler = { act -> handleAction(act) },
            )
        }
        _building = false
    }

    /** AI 大脑指令 → 调用 App 能力（端侧执行，返回回显文案；null=无法执行） */
    private suspend fun handleAction(act: ActionIntent): String? {
        val dao = AppGraph.dao
        val projectId = currentProjectId ?: return "（请先在底部「项目」里选一个项目，我才能操作）"
        val epId = currentEpisodeId ?: "${projectId}_ep1"
        return when (act.verb) {
            "set_cross_era" -> {
                val allowed = act.paramList("allowed")
                if (allowed.isEmpty()) return "（请告知要放开的器物，例如 allowed=手机,眼镜）"
                withContext(Dispatchers.IO) {
                    dao.setEpisodeAllowedCrossEra(epId,
                        "[" + allowed.joinToString(",") { "\"$it\"" } + "]")
                }
                "已放开跨时代器物：${allowed.joinToString("、")}"
            }
            "list_assets" -> {
                val assets = withContext(Dispatchers.IO) { dao.assetsAllOf(projectId) }
                if (assets.isEmpty()) "（当前项目还没有资产，先让我「提取资产」吧）"
                else "当前项目共 ${assets.size} 个资产：\n" +
                    assets.joinToString("\n") { "· ${it.kind}（id=${it.asset_id}，描述：${it.prompt.take(20)}…）" }
            }
            "extract_assets" -> {
                val script = withContext(Dispatchers.IO) { dao.episode(epId)?.script_json } ?: ""
                if (script.isBlank()) return "（当前集还没有剧本/小说文本，请先在项目里上传剧本）"
                val n = runCatching {
                    val r = com.dramafactory.core.quality.LlmAssetExtractor.extract(script) { req ->
                        AppGraph.textModelRouter.resolve(AppGraph.textModelRouter.activeTextModelId()).chat(req)
                    }
                    withContext(Dispatchers.IO) {
                        r.assets.forEach { a ->
                            dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                                asset_id = "a_${System.currentTimeMillis()}_${a.name.hashCode()}",
                                project_id = projectId,
                                kind = a.kind,
                                prompt = a.name + "：" + a.desc,
                                updated_at = System.currentTimeMillis(),
                            ))
                        }
                    }
                    r.assets.size
                }.getOrElse { 0 }
                "已提取 $n 张资产卡（可到「资产」标签查看/手改）"
            }
            "generate" -> {
                val id = act.param("assetId") ?: return null
                val logic = AssetsLogic()
                logic.generate(id)
                "已触发重新生成：$id"
            }
            "stop_generate" -> {
                val id = act.param("assetId") ?: return null
                AssetsLogic().stopGenerate(id)
                "已停止生成：$id"
            }
            "remove_asset" -> {
                val id = act.param("assetId") ?: return null
                val logic = AssetsLogic()
                val ids = logic.removeAssetsCascade(listOf(id))
                withContext(Dispatchers.IO) { for (i in ids) runCatching { dao.deleteAsset(i) } }
                "已删除资产：$id${if (ids.size > 1) "（含 ${ids.size - 1} 张子卡）" else ""}"
            }
            "edit_asset" -> {
                val id = act.param("assetId") ?: return null
                val newPrompt = act.param("prompt") ?: return "（请告知新的描述，例如 prompt=穿红衣的少女）"
                withContext(Dispatchers.IO) {
                    val cur = dao.assetsAllOf(projectId).firstOrNull { it.asset_id == id }
                    if (cur != null) dao.updateAssetLocal(id, cur.source, cur.image_uri, cur.video_uri,
                        cur.reference_image_uri, newPrompt, System.currentTimeMillis())
                }
                "已更新资产描述：$id → $newPrompt"
            }
            "review_pass" -> {
                val id = act.param("assetId") ?: return null
                AssetsLogic().review(id, true)
                "已通过评审：$id"
            }
            "review_all_pass" -> {
                AssetsLogic().reviewAllPassed()
                "已全部通过评审"
            }
            "build_pose_pack" -> {
                val cid = act.param("characterId") ?: act.param("assetId") ?: return null
                val n = AssetsLogic().buildPosePack(cid) { "pose_${System.currentTimeMillis()}_${System.nanoTime()}" }
                "已为角色 $cid 生成 $n 张姿态子卡"
            }
            else -> null
        }
    }

    fun sendUserMessage(text: String) {
        val a = agent ?: run {
            // 首次未构建则同步一条提示，触发构建
            viewModelScope.launch {
                isThinking = true
                runCatching { ensureAgent() }
                    .onSuccess { sendUserMessage(text) }
                    .onFailure { e ->
                        _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI,
                            "⚠️ 智能体初始化失败：${e.message?.take(120)}")
                    }
                isThinking = false
            }
            return
        }
        if (text.isBlank() || isThinking) return
        viewModelScope.launch {
            isThinking = true
            runCatching { a.say(text) }
                .onSuccess { _history.value = a.history }
                .onFailure { e ->
                    _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI,
                        "⚠️ 调用模型失败：${e.message?.take(120)}")
                }
            isThinking = false
        }
    }
}

/** 右下角全局悬浮球 + 聊天面板（覆盖在当前页上） */
@Composable
fun AiAssistantFloating(vm: AiAssistantViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 悬浮球
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
        ) { Text("💬", style = MaterialTheme.typography.titleLarge) }

        if (expanded) {
            // 半屏聊天面板，覆盖在内容上方
            Surface(
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.8f)
                    .padding(8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("🤖 AI 助手", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        val proj = vm.currentProjectId
                        Text(if (proj != null) "项目:$proj" else "未选项目",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                        IconButton(onClick = { expanded = false }) { Text("✕") }
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(vm.history.value.size) {
                        if (vm.history.value.isNotEmpty())
                            listState.animateScrollToItem(vm.history.value.lastIndex)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(vm.history.value) { turn -> ChatBubbleLocal(turn) }
                        if (vm.isThinking) item { ThinkingBubbleLocal() }
                    }
                    var input by remember { mutableStateOf("") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("跟 AI 说：提取资产 / 给主角生成服装 / 入渲染…") },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                            shape = RoundedCornerShape(12.dp),
                        )
                        Button(onClick = {
                            if (input.isNotBlank()) { vm.sendUserMessage(input); input = "" }
                        }) { Text("发送") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleLocal(turn: DialogueTurn) {
    val isAi = turn.side == DialogueTurn.Side.AI
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End) {
        Surface(
            color = if (isAi) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(turn.content, Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAi) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun ThinkingBubbleLocal() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text("🤔 思考中…", Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
