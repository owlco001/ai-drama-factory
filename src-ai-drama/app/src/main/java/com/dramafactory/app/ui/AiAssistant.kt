package com.dramafactory.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import com.dramafactory.app.ui.theme.DramaGradient
import com.dramafactory.app.ui.theme.DramaColor
import com.dramafactory.app.ui.theme.BubbleAiShape
import com.dramafactory.app.ui.theme.BubbleUserShape
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
import com.dramafactory.app.R
import com.dramafactory.core.model.ProviderError
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

    /**
     * v1.9.2：AI 助手内直接切换文本模型（面板头部下拉）。
     * 落盘激活模型（与设置页同一持久化通道）→ 丢弃旧 agent（下次 say 按新模型重建）→
     * 气泡确认。切换即时生效，无需重启对话。
     */
    fun switchTextModel(providerId: String) = viewModelScope.launch {
        val entry = runCatching { AppGraph.textModelRouter.registeredTextModels() }
            .getOrDefault(emptyList()).firstOrNull { it.providerId == providerId }
        val r = runCatching { AppGraph.textModelRouter.setActiveTextModel(providerId) }
        if (r.isSuccess) {
            agent = null   // 旧 agent 绑定旧 provider/model，置空后 ensureAgent 按新激活重建
            _history.value = _history.value + DialogueTurn(
                DialogueTurn.Side.AI,
                "已切换文本模型：${entry?.label ?: providerId}。下一条消息起由它接管。")
        } else {
            _history.value = _history.value + DialogueTurn(
                DialogueTurn.Side.AI,
                "切换失败：${r.exceptionOrNull()?.message ?: "未知错误"}")
        }
    }

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
            // v1.9.5 修复：activeTextModelId() 返回的是 **providerId**（agnes / deepseek），
            // 并非模型名。此前把它当 modelId 传给 AiAgent，最终作为 ChatRequest.model 发往网关
            // （"agnes" 不是合法模型 ID）→ 官方网关拒绝，表现为「测试连通成功但聊天失败」。
            // 激活模型已由 textProviderFor() 决定走哪个 provider，具体模型名交由 provider 自身
            // 选型（Agnes 按输入规模自动选模 / DeepSeek 固定 deepseek-chat），故这里传空。
            val provider = AppGraph.textProviderFor()
            agent = AiAgent(
                textProvider = provider,
                modelId = "",
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
                onNotice("正在从剧本提取角色/场景/道具资产…")
                val assets = runCatching { AppGraph.extractAssetsFor(script) }.getOrElse { emptyList() }
                withContext(Dispatchers.IO) {
                    assets.forEach { a ->
                        dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                            asset_id = a.assetId, project_id = pid, kind = a.kind,
                            prompt = a.name + "：" + a.prompt, updated_at = System.currentTimeMillis()))
                    }
                }
                "已提取 ${assets.size} 张资产卡（去「资产」标签可看/手改）"
            }
            // v1.7.18 修复：以下动作此前 new AssetsLogic() 后直接调 generate/review 等——
            // 但那个新实例的 generateHandler/reviewPersist 从未接线（默认抛异常/空实现），
            // 且内存资产列表为空，导致「AI 说已生成/已删除」实际什么都没发生（假成功）。
            // 全部改为直接操作 dao（与手动操作同一落库通道，可对账可撤回）。
            "generate" -> {
                val id = act.param("assetId") ?: return null
                val pid = projectId ?: return "（请先打开项目）"
                onNotice("正在重新生成资产图：$id …")
                val row = withContext(Dispatchers.IO) {
                    dao.assetsAllOf(pid).firstOrNull { it.asset_id == id }
                } ?: return "（找不到资产 $id，先让我 list_assets 看看有哪些）"
                val url = runCatching {
                    com.dramafactory.app.ui.AssetImageGenerator.generate(
                        provider = AppGraph.image, kind = row.kind,
                        basePrompt = row.prompt, preset = AppGraph.currentPreset())
                }.getOrNull() ?: return "（图像生成失败：请确认图像/视频模型 Key 与网络）"
                withContext(Dispatchers.IO) { runCatching { dao.setAssetRemoteUrl(id, url, System.currentTimeMillis()) } }
                "已重新生成资产 $id 的图像"
            }
            "stop_generate" -> {
                val id = act.param("assetId") ?: return null
                "（已取消：$id 当前没有进行中的生成任务）"
            }
            "remove_asset" -> {
                val id = act.param("assetId") ?: return null
                val pid = projectId ?: return "（请先打开项目）"
                val removed = withContext(Dispatchers.IO) {
                    val all = dao.assetsAllOf(pid)
                    val cascade = all.filter { it.asset_id == id || it.parent_id == id }.map { it.asset_id }
                    cascade.forEach { runCatching { dao.deleteAsset(it) } }
                    cascade.size
                }
                if (removed == 0) "（找不到资产 $id）"
                else "已删除资产 $id${if (removed > 1) "（含 ${removed - 1} 张参考图子卡）" else ""}"
            }
            "edit_asset" -> {
                val id = act.param("assetId") ?: return null
                val newPrompt = act.param("prompt") ?: return "（请告诉我新的描述，例如 prompt=穿红衣的少女）"
                val pid = projectId ?: return "（请先打开项目）"
                val ok = withContext(Dispatchers.IO) {
                    val cur = dao.assetsAllOf(pid).firstOrNull { it.asset_id == id }
                    if (cur != null) {
                        dao.updateAssetLocal(id, cur.source, cur.image_uri, cur.video_uri,
                            cur.reference_image_uri, newPrompt, System.currentTimeMillis())
                        true
                    } else false
                }
                if (ok) "已更新资产描述：$id → $newPrompt" else "（找不到资产 $id）"
            }
            "review_pass" -> {
                val id = act.param("assetId") ?: return null
                withContext(Dispatchers.IO) { runCatching { dao.setReviewState(id, "keep") } }
                "已标记通过评审：$id"
            }
            "review_all_pass" -> {
                val pid = projectId ?: return "（请先打开项目）"
                val n = withContext(Dispatchers.IO) {
                    val all = dao.assetsAllOf(pid)
                    all.forEach { runCatching { dao.setReviewState(it.asset_id, "keep") } }
                    all.size
                }
                "已全部通过评审（$n 个资产）"
            }
            "build_pose_pack" -> {
                val cid = act.param("characterId") ?: act.param("assetId") ?: return null
                val pid = projectId ?: return "（请先打开项目）"
                val parent = withContext(Dispatchers.IO) {
                    dao.assetsAllOf(pid).firstOrNull { it.asset_id == cid }
                } ?: return "（找不到角色 $cid）"
                if (parent.kind != "character") return "（$cid 不是角色资产，参考图只给角色建）"
                val preset = com.dramafactory.core.quality.StylePreset.HAN_DEFAULT
                val n = withContext(Dispatchers.IO) {
                    var added = 0
                    for (shot in preset.referenceShots) {
                        val subId = "ref_${System.currentTimeMillis()}_${System.nanoTime()}"
                        val subPrompt = com.dramafactory.core.quality.AssetPromptBuilder
                            .finalReferencePrompt(preset, parent.prompt, shot)
                        runCatching {
                            dao.upsertAsset(com.dramafactory.app.data.AssetEntity(
                                asset_id = subId, project_id = pid, kind = "character",
                                parent_id = cid, pose_role = shot.key, prompt = subPrompt,
                                updated_at = System.currentTimeMillis()))
                            added++
                        }
                    }
                    added
                }
                "已为角色 $cid 生成 $n 张独立参考图（基准正面半身/45°右前/正侧面/正面全身，各自成图不拼图）"
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
                onNotice("正在生成分镜（编剧+导演）…")
                // v1.7.18：对齐 StoryboardViewModel 的 v1.7.17 链路——目录只给已生图的母卡，
                // first_asset_ids 落标准 JSON，渲染据此注入参考图锁脸。
                val catalog = withContext(Dispatchers.IO) { AssetCatalog.build(dao.assetsAllOf(pid)) }
                val result = runCatching {
                    com.dramafactory.core.quality.AiStoryboardDirector.generate(
                        script, chat = { req -> AppGraph.text.chat(req) }, assets = catalog)
                }.getOrElse { return "（分镜生成失败：${it.message?.take(80)}）" }
                if (result.shots.isEmpty()) return "（AI 未能从剧本拆出镜头，请检查剧本内容后重试）"
                withContext(Dispatchers.IO) {
                    runCatching { dao.deleteShotsOf(e) }
                    for (s in result.shots) {
                        runCatching {
                            dao.upsertShot(com.dramafactory.app.data.ShotEntity(
                                shot_id = "${e}_shot${s.shotNo}", episode_id = e, project_id = pid,
                                shot_no = s.shotNo, dialogue = s.dialogue, narration = s.narration,
                                action = listOfNotNull(s.action, s.visualPrompt?.let { "［$it］" }).joinToString("；"),
                                beat_ref = s.beatRef, carry_over = s.carryOver,
                                first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds),
                                last_asset_ids = "[]",
                                visual_prompt = s.visualPrompt, duration_seconds = s.durationSeconds,
                                sb_check = if (result.gateErrors[s.shotNo].isNullOrEmpty()) "pass"
                                           else "error:${result.gateErrors[s.shotNo]!!.joinToString(",")}"))
                        }
                    }
                }
                val noAssetNote = if (catalog.isEmpty())
                    "；⚠ 本项目还没有已生成图像的角色/场景资产，分镜未引用任何资产（渲染将回退项目级前4张），请先到资产页把资产图生成出来再重生成" else ""
                "已生成 ${result.shots.size} 条分镜$noAssetNote（去「分镜」标签查看）"
            }
            "render" -> {
                val pid = projectId ?: return "（请先打开项目）"
                val e = epId ?: "${pid}_ep1"
                val shots = withContext(Dispatchers.IO) { dao.shotsOf(e) }
                if (shots.isEmpty()) return "（还没有分镜，先让我「生成分镜」）"
                onNotice("正在入队渲染（含锁脸资产注入/补生成）…")
                val aiShots = shots.map { com.dramafactory.core.orchestrate.DefaultAiOrchestrator.AiShot(it.shot_no, it.action ?: "", it.dialogue) }
                val n = AppGraph.enqueueRenderFor(e, aiShots)
                "已入渲染队 $n 条（去「渲染」标签看进度）"
            }
            "compose_film" -> {
                val e = epId ?: return "（请先打开项目并跑渲染）"
                onNotice("正在合成成片…")
                val f = if (ctx != null) AppGraph.composeFilmFor(e, ctx) else null
                if (f != null) "已成片：${f.absolutePath}（去「成片」标签播放）" else "（渲染还没完成，暂时无法合成成片）"
            }
            "run_pipeline" -> {
                val pid = projectId ?: return "（请先建或打开一个项目）"
                val e = epId ?: "${pid}_ep1"
                val script = withContext(Dispatchers.IO) { dao.episode(e)?.script_json } ?: ""
                if (script.isBlank()) return "（当前集还没有剧本文本，先上传剧本或跟我说『剧本是：…』）"
                onNotice("启动完整流水线：提取→生图→审计→分镜→渲染…")
                val res = AppGraph.runFullPipeline(script) { onNotice("· $it") }
                if (res.isSuccess) {
                    currentEpisodeId = AppGraph.aiOrchestrator.currentEpisodeId.value ?: e
                    "已启动完整流水线（提取→图→分镜→渲染），跑完去「成片」标签看成片"
                } else "（流水线启动失败：${res.exceptionOrNull()?.message?.take(80)}）"
            }
            // ===== v1.7.18：渲染控制 / 状态查询 / 模型配置 =====
            "render_status" -> {
                val e = epId ?: return "（请先打开项目）"
                val rows = withContext(Dispatchers.IO) {
                    runCatching { dao.renderTasksOfEpOrdered(e) }.getOrDefault(emptyList())
                }
                if (rows.isEmpty()) "（本集还没有渲染任务，先生成分镜再渲染）"
                else {
                    val byState = rows.groupingBy { it.state }.eachCount()
                    val order = listOf("PENDING", "SUBMITTING", "SUBMITTED", "COMPLETED", "FAILED", "BLOCKED")
                    "本集渲染进度：共 ${rows.size} 镜 → " +
                        order.filter { byState.containsKey(it) }
                            .joinToString(" · ") { "$it ${byState[it]}" } +
                        "（渲染队列当前${if (com.dramafactory.app.ui.RenderRuntime.queueFor(e).isPaused) "已暂停" else "运行中"}）"
                }
            }
            "render_pause" -> {
                val e = epId ?: return "（请先打开项目）"
                com.dramafactory.app.ui.RenderRuntime.queueFor(e).pause()
                "已暂停渲染队列（已提交的镜头会继续轮询取回）"
            }
            "render_resume" -> {
                val e = epId ?: return "（请先打开项目）"
                com.dramafactory.app.ui.RenderRuntime.queueFor(e).resume(confirmedByUser = false)
                "已恢复渲染队列"
            }
            "model_status" -> {
                val hasVideo = runCatching { !AppGraph.keyVault.load(AppGraph.CONFIG_VIDEO).isNullOrBlank() }.getOrDefault(false)
                val hasText = runCatching { AppGraph.hasAnyTextKey() }.getOrDefault(false)
                val hasImage = runCatching { !AppGraph.keyVault.load(AppGraph.CONFIG_IMAGE).isNullOrBlank() }.getOrDefault(false)
                val videoCfg = runCatching { AppGraph.dao.verifiedConfig(AppGraph.CONFIG_VIDEO) }.getOrNull()
                buildString {
                    append("模型配置状态：\n")
                    append("· 文本：${if (hasText) "已配置" else "未配置"}（AI 对话/编剧用）\n")
                    append("· 视频：${if (hasVideo) "已配置" else "未配置"}" +
                        if (videoCfg?.provider_id == "custom") "（自定义：${videoCfg.model}）" else "" + "\n")
                    append("· 图像：${if (hasImage) "已配置" else "未配置（默认共用视频 Key）"}\n")
                    append("缺配置的话，跟我说『打开设置』就能去补 Key。")
                }
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
            // ensureAgent() 内部已用 runCatching 吞掉异常，其「正常返回」并不代表构建成功，
            // 所以原写法 `runCatching { ensureAgent() }.getOrNull()` 恒为非 null，
            // 构建失败时照样走到 agent!! —— 必抛 NPE 崩溃。改为构建后显式判空。
            if (agent == null) ensureAgent()
            val a = agent
            if (a == null) {
                _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, "⚠️ 智能体初始化失败，请检查文本模型 Key 配置")
                isThinking = false
                return@launch
            }
            runCatching { a.say(text) { notice ->
                // v1.7.3：AI 执行长任务时实时汇报进度（非流式，阶段提示逐条追加）
                _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, notice)
            } }
                .onSuccess { _history.value = a.history }
                .onFailure { e ->
                    // 这是 say() 自身抛出的兜底路径；正常模型异常已被 say() 捕获并返回友好文案
                    val friendly = when (e) {
                        is ProviderError.TransientError -> if (e.retryable) "AI 服务暂时繁忙，请稍后再试。" else "AI 服务暂时不可用。"
                        is ProviderError.QuotaError -> "API 调用配额已用完，请稍后再试。"
                        is ProviderError.AuthError -> "API Key 无效或已过期，请去设置页检查。"
                        else -> "调用模型失败：${e.message?.take(120) ?: ""}"
                    }
                    _history.value = _history.value + DialogueTurn(DialogueTurn.Side.AI, "⚠️ $friendly")
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
        // 走查P0-3：AI 悬浮球 = 56dp 正圆 + 紫→品红渐变 + 外发光 + 脉冲环 + sparkle
        val pulse = rememberInfiniteTransition()
        val ringScale by pulse.animateFloat(
            initialValue = 1f, targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = keyframes { durationMillis = 1500; 1.6f at 1500 },
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart))
        val ringAlpha by pulse.animateFloat(
            initialValue = 0.35f, targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes { durationMillis = 1500; 0f at 1500 },
                repeatMode = androidx.compose.animation.core.RepeatMode.Restart))
        // 脉冲外环
        Box(Modifier
            .align(Alignment.BottomEnd).padding(16.dp)
            .size(56.dp)
            .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
            .clip(CircleShape)
            .background(DramaColor.Tertiary.copy(alpha = 1f)))
        // 主球
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd).padding(16.dp)
                .size(56.dp)
                .shadow(elevation = 8.dp, spotColor = DramaColor.GlowShadow, shape = CircleShape)
                .clip(CircleShape)
                .background(DramaGradient.ai())
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_sparkle), contentDescription = "AI 助手",
                tint = DramaColor.OnPrimary, modifier = Modifier.size(26.dp))
        }

        if (expanded) {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.95f).fillMaxHeight(0.8f).padding(8.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Default.Face, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(6.dp).size(22.dp))
                        }
                        Text("AI 助手", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        // ---- v1.9.2：文本模型切换（下拉菜单，与设置页同一持久化通道）----
                        val router = remember { runCatching { AppGraph.textModelRouter }.getOrNull() }
                        val entries = remember { router?.registeredTextModels().orEmpty() }
                        var activeModel by remember {
                            mutableStateOf(router?.activeTextModelId() ?: "agnes")
                        }
                        var modelMenu by remember { mutableStateOf(false) }
                        if (router != null && entries.isNotEmpty()) {
                            Box {
                                AssistChip(
                                    onClick = { modelMenu = true },
                                    label = {
                                        Text(
                                            entries.firstOrNull { it.providerId == activeModel }?.label ?: activeModel,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换模型",
                                            modifier = Modifier.size(14.dp))
                                    },
                                    modifier = Modifier.heightIn(max = 32.dp),
                                )
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    for (e in entries) {
                                        DropdownMenuItem(
                                            text = { Text(e.label, style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                modelMenu = false
                                                if (e.providerId != activeModel) {
                                                    activeModel = e.providerId
                                                    vm.switchTextModel(e.providerId)
                                                }
                                            },
                                            trailingIcon = if (e.providerId == activeModel) {
                                                { Icon(Icons.Default.Check, contentDescription = "当前",
                                                    modifier = Modifier.size(16.dp)) }
                                            } else null,
                                        )
                                    }
                                }
                            }
                        }
                        val proj = vm.currentProjectId
                        Text(if (proj != null) "项目:$proj" else "未选项目",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 6.dp))
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
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
                            shape = MaterialTheme.shapes.medium,
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
        // 设计规格 .bub-ai / .bub-me：AI 气泡 surface-highest + 左下尖角；
        // 用户气泡 hero 渐变 + 右上尖角，文字取 onPrimary
        val shape = if (isAi) BubbleAiShape else BubbleUserShape
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(shape)
                .background(
                    if (isAi) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                .then(if (isAi) Modifier else Modifier.background(DramaGradient.hero())),
        ) {
            Text(turn.content, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium,
                color = if (isAi) MaterialTheme.colorScheme.onSurface else DramaColor.OnPrimary)
        }
    }
}

@Composable
private fun ThinkingBubbleLocal() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = BubbleAiShape) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("思考中…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
