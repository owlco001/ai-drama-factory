package com.dramafactory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 *
 * 设计要点（按老王要求"自然语言处理，大脑调控所有功能"）：
 * - 用户侧是纯自然语言对话，AI 自己理解意图、调用 App 能力。
 * - [ACT] 只是 AI 内部 → App 的调用协议（用户永不可见），由 AiAgent 在回复里附带。
 * - 系统 prompt 把 AI 定位为"能操控这个短剧 App 的助手"，它可调用：建项目、传剧本、提取资产、
 *   生成图、生成分镜、入渲染、合成成片、切标签等全部功能。
 * - 全局单例语义：DramaApp 顶层持有，所有标签页共享同一对话与 agent，且知道"当前项目"。
 * - AI 指令执行结果落 dao = 生成对应的手动操作记录，与手动操作并存、可手改/撤回（融合）。
 */
class AiAssistantViewModel : ViewModel() {
    private val _history = MutableStateFlow<List<DialogueTurn>>(emptyList())
    val history: StateFlow<List<DialogueTurn>> = _history

    var isThinking by mutableStateOf(false)
        private set

    /** 当前项目上下文（由 DramaApp 注入，随底部导航选中项目变化） */
    var currentProjectId: String? = null
    var currentEpisodeId: String? = null

    /** 上次注入的项目 id，用于检测"切换项目"事件（避免每次重组重复重置） */
    private var _lastProjectId: String? = null

    /**
     * DramaApp 在导航项目变化时调用。若项目 id 真的变化（且非空），
     * 重置 AI 上下文（丢弃旧 agent 的剧本草稿/对话历史 + 清空气泡），
     * 让 AI 在新项目里从零开始——实现"进入不同项目自动切换上下文"。
     */
    fun setProject(pid: String?) {
        if (pid == null) return
        if (pid == _lastProjectId) return
        _lastProjectId = pid
        currentProjectId = pid
        // 丢弃旧 agent（其 scriptDraft/_history/_messages 都是 mutable 内部状态，无 reset 接口，
        // 直接置空，下次 say 时 ensureAgent 重建干净实例）
        agent = null
        _history.value = listOf(
            DialogueTurn(
                DialogueTurn.Side.AI,
                "📁 已切换到项目：$pid。上下文已重置，我们在这个新项目里继续。",
                System.currentTimeMillis()
            )
        )
    }

    /** AI 请求前端跳转标签（如"打开资产页看看"） */
    var onGoto: ((String) -> Unit)? = null

    private var agent: AiAgent? = null
    private var _building = false

    init {
        _history.value = listOf(
            DialogueTurn(DialogueTurn.Side.AI,
                "嗨，我是你的短剧创作搭档 🎬 你用大白话跟我说就行——比如「给这个项目提取资产」「把主角改成红衣」「生成分镜」「跑完整流程出成片」「打开资产页看看」。我直接动手，你随时也能手动改。")
        )
    }

    private suspend fun ensureAgent() {
        if (agent != null || _building) return
        _building = true
        runCatching {
            val router = AppGraph.textModelRouter
            val modelId = router.activeTextModelId()
            val provider = AppGraph.textProviderFor()
            agent = AiAgent(
                textProvider = provider,
                modelId = modelId,
                actionHandler = { act, onNotice -> handleAction(act, onNotice) },
                currentProjectHint = currentProjectId,
            )
        }
        _building = false
    }

    /**
     * AI 大脑指令 → 调用 App 能力（端侧执行，返回回显文案；null=无法执行）。
     * verb 覆盖全部功能，由 AiAgent 的 LLM 按自然语言意图自行选择。
     */
    private suspend fun handleAction(act: ActionIntent, onNotice: (String) -> Unit = {}): String? {
        val dao = AppGraph.dao
        val ctx = AppGraph.appContext()
        val projectId = currentProjectId
        val epId = currentEpisodeId ?: projectId?.let { "${it}_ep1" }

        return when (act.verb) {
            // ===== 项目 / 剧本 =====
            "new_project" -> {
                val name = act.param("name") ?: act.param("title") ?: "AI项目"
                val id = withContext(Dispatchers.IO) {
                    val pid = "p_${System.currentTimeMillis()}"
                    dao.upsertProject(com.dramafactory.app.data.ProjectEntity(
                        project_id = pid, name = name, created_at = System.currentTimeMillis()))
                    pid
                }
                currentProjectId = id
                currentEpisodeId = "${id}_ep1"
                "已建项目：$name（id=$id）"
            }
            "set_script" -> {
                val pid = projectId ?: return "（请先建或打开一个项目）"
                val text = act.param("text") ?: act.param("script") ?: return "（请给我剧本文本，例如 text=…）"
                val eid = "${pid}_ep1"
                withContext(Dispatchers.IO) {
                    val cur = dao.episode(eid)
                    if (cur != null) dao.upsertEpisode(cur.copy(script_json = text.take(100_000)))
                    else dao.upsertEpisode(com.dramafactory.app.data.EpisodeEntity(
                        episode_id = eid, project_id = pid, ep_no = 1, script_json = text.take(100_000)))
                }
                "已把剧本存入项目（${text.length}字）"
            }
            "open_project" -> {
                val id = act.param("id") ?: act.param("projectId") ?: return null
                currentProjectId = id
                currentEpisodeId = "${id}_ep1"
                "已切换到项目：$id"
            }
            // ===== 资产 =====
            "extract_assets" -> {
                val pid = projectId ?: return "（请先建或打开一个项目）"
                val e = epId ?: "${pid}_ep1"
                val script = withContext(Dispatchers.IO) { dao.episode(e)?.script_json } ?: ""
                if (script.isBlank()) return "（当前集还没有剧本/小说文本，先『上传剧本』或跟我说『剧本是：…』）"
                onNotice("🔍 正在从剧本提取角色/场景/道具资产…")
                val assets = runCatching { AppGraph.extractAssetsFor(script) }.getOrElse { emptyList() }
                withContext(Dispatchers.IO) {
                    assets.forEach { a ->
                        dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                            asset_id = a.assetId, project_id = e, kind = a.kind,
                            prompt = a.name + "：" + a.prompt, updated_at = System.currentTimeMillis()))
                    }
                }
                "已提取 ${assets.size} 张资产卡（去「资产」标签可看/手改）"
            }
            "generate" -> {
                val id = act.param("assetId") ?: return null
                AssetsLogic().generate(id)
                "已触发重新生成：$id"
            }
            "stop_generate" -> {
                val id = act.param("assetId") ?: return null
                AssetsLogic().stopGenerate(id)
                "已停止生成：$id"
            }
            "remove_asset" -> {
                val id = act.param("assetId") ?: return null
                val ids = AssetsLogic().removeAssetsCascade(listOf(id))
                withContext(Dispatchers.IO) { for (i in ids) runCatching { dao.deleteAsset(i) } }
                "已删除资产：$id${if (ids.size > 1) "（含 ${ids.size - 1} 张子卡）" else ""}"
            }
            "edit_asset" -> {
                val id = act.param("assetId") ?: return null
                val newPrompt = act.param("prompt") ?: return "（请告诉我新的描述，例如 prompt=穿红衣的少女）"
                val pid = projectId ?: return "（请先打开项目）"
                withContext(Dispatchers.IO) {
                    val cur = dao.assetsAllOf(pid).firstOrNull { it.asset_id == id }
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
            "set_cross_era" -> {
                val e = epId ?: return "（请先打开项目）"
                val allowed = act.paramList("allowed")
                if (allowed.isEmpty()) return "（请告诉我放开的器物，例如 allowed=手机,眼镜）"
                withContext(Dispatchers.IO) {
                    dao.setEpisodeAllowedCrossEra(e, "[" + allowed.joinToString(",") { "\"$it\"" } + "]")
                }
                "已放开跨时代器物：${allowed.joinToString("、")}"
            }
            "list_assets" -> {
                val pid = projectId ?: return "（请先打开项目）"
                val assets = withContext(Dispatchers.IO) { dao.assetsAllOf(pid) }
                if (assets.isEmpty()) "（当前项目还没有资产，先让我「提取资产」吧）"
                else "当前项目共 ${assets.size} 个资产：\n" +
                    assets.joinToString("\n") { "· ${it.kind}（id=${it.asset_id}，描述：${it.prompt.take(20)}…）" }
            }
            // ===== 分镜 / 渲染 / 成片 =====
            "gen_shots" -> {
                val pid = projectId ?: return "（请先建或打开一个项目）"
                val e = epId ?: "${pid}_ep1"
                val script = withContext(Dispatchers.IO) { dao.episode(e)?.script_json } ?: ""
                if (script.isBlank()) return "（当前集还没有剧本文本，先上传剧本）"
                onNotice("🎬 正在生成分镜…")
                val shots = runCatching { AppGraph.genShotsFor(script) }.getOrElse { emptyList() }
                withContext(Dispatchers.IO) {
                    shots.forEach { s ->
                        dao.upsertShot(com.dramafactory.app.data.ShotEntity(
                            shot_id = "${e}_shot${s.shotNo}", episode_id = e, project_id = pid,
                            shot_no = s.shotNo, action = s.action, dialogue = s.dialogue))
                    }
                }
                "已生成 ${shots.size} 条分镜（去「分镜」标签查看）"
            }
            "render" -> {
                val pid = projectId ?: return "（请先打开项目）"
                val e = epId ?: "${pid}_ep1"
                val shots = withContext(Dispatchers.IO) { dao.shotsOf(e) }
                if (shots.isEmpty()) return "（还没有分镜，先让我「生成分镜」）"
                onNotice("🎞️ 正在入队渲染（含锁脸资产注入/补生成）…")
                val aiShots = shots.map { com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiShot(it.shot_no, it.action ?: "", it.dialogue) }
                val n = AppGraph.enqueueRenderFor(e, aiShots)
                "已入渲染队 $n 条（去「渲染」标签看进度）"
            }
            "compose_film" -> {
                val e = epId ?: return "（请先打开项目并跑渲染）"
                onNotice("🎞️ 正在合成成片…")
                val f = if (ctx != null) AppGraph.composeFilmFor(e, ctx) else null
                if (f != null) "已成片：${f.absolutePath}（去「成片」标签播放）" else "（渲染还没完成，暂时无法合成成片）"
            }
            "run_pipeline" -> {
                val pid = projectId ?: return "（请先建或打开一个项目）"
                val e = epId ?: "${pid}_ep1"
                val script = withContext(Dispatchers.IO) { dao.episode(e)?.script_json } ?: ""
                if (script.isBlank()) return "（当前集还没有剧本文本，先上传剧本或跟我说『剧本是：…』）"
                onNotice("🚀 启动完整流水线：提取→生图→审计→分镜→渲染…")
                val res = AppGraph.runFullPipeline(script) { onNotice("· $it") }
                if (res.isSuccess) {
                    currentEpisodeId = AppGraph.aiOrchestrator.currentEpisodeId.value ?: e
                    "已启动完整流水线（提取→图→分镜→渲染），跑完去「成片」标签看成片"
                } else "（流水线启动失败：${res.exceptionOrNull()?.message?.take(80)}）"
            }
            // ===== 导航 =====
            "goto" -> {
                val target = act.param("page") ?: act.param("tab") ?: return null
                onGoto?.invoke(target)
                "已切换到：$target"
            }
            else -> null
        }
    }

    /** 用户自然语言发一句话 */
    fun sendUserMessage(text: String) {
        if (text.isBlank() || isThinking) return
        viewModelScope.launch {
            isThinking = true
            // 前置检查：没有任何文本模型 key 时，直接引导去设置页，避免无意义的模型调用失败
            if (!AppGraph.hasAnyTextKey()) {
                _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI,
                    "⚠️ 还没配置文本模型 Key（DeepSeek / Agnes）。请点底部「设置」→ 文本模型，选一个模型并填入 Key、点「测试连通」保存。配好后再跟我说就行。")
                isThinking = false
                return@launch
            }
            val a = agent ?: run {
                runCatching { ensureAgent() }.getOrNull().also { built ->
                    if (built == null) {
                        _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, "⚠️ 智能体初始化失败，请检查文本模型 Key 配置")
                        isThinking = false
                        return@launch
                    }
                }
                agent!!
            }
            runCatching { a.say(text) { notice ->
                // v1.7.3：AI 执行长任务时实时汇报进度（非流式，阶段提示逐条追加）
                _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, "🔄 $notice")
            } }
                .onSuccess { _history.value = a.history }
                .onFailure { e -> _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, "⚠️ 调用模型失败：${e.javaClass.simpleName}：${e.message?.take(300) ?: ""}") }
            isThinking = false
        }
    }
}

/** 右下角全局悬浮球 + 聊天面板（覆盖在当前页上） */
@Composable
fun AiAssistantFloating(vm: AiAssistantViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Text("💬", style = MaterialTheme.typography.titleLarge) }

        if (expanded) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.95f).fillMaxHeight(0.8f).padding(8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("🤖", Modifier.padding(6.dp), style = MaterialTheme.typography.titleMedium) }
                        Text("AI 助手", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        val proj = vm.currentProjectId
                        Text(if (proj != null) "项目:$proj" else "未选项目",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        IconButton(onClick = { expanded = false }) { Text("✕") }
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(vm.history.value.size) {
                        if (vm.history.value.isNotEmpty()) listState.animateScrollToItem(vm.history.value.lastIndex)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // v1.6.3 修对话闪退：之前 items 没传 key，_history.value 整体替换后
                        // LazyColumn 不知道每个 item 的稳定标识，触发原生 crash（用户消息+AI回复序列重组时）。
                        itemsIndexed(vm.history.value, key = { i, _ -> i }) { _, turn -> ChatBubbleLocal(turn) }
                        if (vm.isThinking) item("thinking") { ThinkingBubbleLocal() }
                    }
                    var input by remember { mutableStateOf("") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("跟 AI 说：提取资产 / 改主角红衣 / 生成分镜 / 跑完整流程出成片…") },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                            shape = RoundedCornerShape(12.dp),
                        )
                        Button(onClick = { if (input.isNotBlank()) { vm.sendUserMessage(input); input = "" } }) { Text("发送") }
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
        Surface(color = if (isAi) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.85f)) {
            Text(turn.content, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium,
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
