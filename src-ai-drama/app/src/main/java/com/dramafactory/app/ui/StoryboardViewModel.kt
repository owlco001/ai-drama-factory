package com.dramafactory.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dramafactory.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第十轮：分镜页 ViewModel——AI编剧+导演一键生成分镜。
 *
 * 流程：读本集剧本 → AiStoryboardDirector.generate（拆镜+视觉指令）→
 * 台词逐字粗校验 → 落 shots 表（visual_prompt/duration_seconds）→ UI 展示。
 * 重新生成会先清空本集旧镜（render_tasks 历史保留对账）。
 */
class StoryboardViewModel(private val episodeId: String) : ViewModel() {

    data class UiState(
        val shots: List<com.dramafactory.app.data.ShotEntity> = emptyList(),
        val generating: Boolean = false,
        val message: String? = null,
        /** 第十三轮：shotId → 已出产视频本地路径（COMPLETED且有文件） */
        val videoUris: Map<String, String> = emptyMap(),
        /** v1.7.17：assetId → 显示名，供分镜卡片展示「本镜引用了哪些资产」 */
        val assetNames: Map<String, String> = emptyMap(),
        /** v1.9.2：assetId → 缩略图 URI（remote_url 或本地 image_uri），分镜详情弹窗展示引用资产 */
        val assetThumbs: Map<String, String> = emptyMap(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val rows = runCatching { withContext(Dispatchers.IO) { AppGraph.dao.shotsOf(episodeId) } }
            .getOrDefault(emptyList())
        // 第十三轮：拉已出产视频（COMPLETED 且本地文件存在）
        val vids = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.renderStatesOf(episodeId) }
        }.getOrDefault(emptyList())
            .filter { it.state == "COMPLETED" && !it.localFileUri.isNullOrBlank() }
            .associate { it.shotId to it.localFileUri!! }
        // v1.7.17：构建 assetId → 显示名，让分镜页能直接看出每镜引用了哪些资产
        // v1.9.2：同时构建 assetId → 缩略图 URI，分镜详情弹窗展示引用资产长相
        val assetRows = runCatching {
            withContext(Dispatchers.IO) {
                val pid = episodeId.substringBeforeLast("_ep")
                AppGraph.dao.assetsAllOf(pid)
            }
        }.getOrDefault(emptyList())
        val names = assetRows.associate { it.asset_id to AssetCatalog.displayName(it.prompt) }
        val thumbs = assetRows.mapNotNull { e ->
            val uri = e.remote_url ?: e.image_uri
            if (uri.isNullOrBlank()) null else e.asset_id to uri
        }.toMap()
        _state.value = _state.value.copy(shots = rows, videoUris = vids,
            assetNames = names, assetThumbs = thumbs)
    }

    /** AI 一键生成分镜（LLM 不可用时提示） */
    fun generateWithAi() = viewModelScope.launch {
        _state.value = _state.value.copy(generating = true, message = "AI 编剧正在拆解剧本…")
        val script = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.episode(episodeId)?.script_json }
        }.getOrNull()
        if (script.isNullOrBlank()) {
            _state.value = _state.value.copy(generating = false,
                message = "本集没有剧本文本。请回项目导入剧本，或到资产页粘贴。")
            return@launch
        }
        // LLM 前置判断：引擎未就绪/key 未配置不发起网络
        val llmReady = runCatching {
            AppGraph.isInitialized && !AppGraph.keyVault.load(AppGraph.CONFIG_VIDEO).isNullOrBlank()
        }.getOrDefault(false)
        if (!llmReady) {
            _state.value = _state.value.copy(generating = false,
                message = "未配置 API Key，无法使用AI生成分镜。请到「设置」页配置后重试。")
            return@launch
        }

        // 第十五轮：拉项目资产注入 LLM 提示词，让分镜用 asset_id 引用真资产。
        // v1.7.17：改走 AssetCatalog.build —— 只给母卡、只给已有图的卡，
        // 否则 LLM 会引用 参考图子卡（侧脸/怒容）或未生图的空卡，渲染时静默失锁。
        val projectId = episodeId.substringBeforeLast("_ep")
        val assets = runCatching {
            withContext(Dispatchers.IO) { AppGraph.dao.assetsAllOf(projectId) }
        }.getOrDefault(emptyList())
        val catalog = AssetCatalog.build(assets)
        val result = runCatching {
            com.dramafactory.core.quality.AiStoryboardDirector.generate(
                script, chat = { req -> AppGraph.text.chat(req) }, assets = catalog)
        }.getOrElse {
            _state.value = _state.value.copy(generating = false,
                message = "AI 生成分镜失败：${it.message ?: it.javaClass.simpleName}")
            return@launch
        }
        if (result.shots.isEmpty()) {
            _state.value = _state.value.copy(generating = false, message = "AI未能从剧本拆出镜头，请检查剧本内容后重试")
            return@launch
        }

        // 落库：清旧镜 → 写新镜（assetIds 落 first_asset_ids JSON 数组，渲染时据此拉图入锁脸）
        withContext(Dispatchers.IO) { runCatching { AppGraph.dao.deleteShotsOf(episodeId) } }
        for (s in result.shots) {
            withContext(Dispatchers.IO) {
                runCatching { AppGraph.dao.upsertShot(com.dramafactory.app.data.ShotEntity(
                    shot_id = "${episodeId}_shot${s.shotNo}",
                    episode_id = episodeId, project_id = projectId, shot_no = s.shotNo,
                    dialogue = s.dialogue, narration = s.narration,
                    action = listOfNotNull(s.action, s.visualPrompt?.let { "［$it］" }).joinToString("；"),
                    beat_ref = s.beatRef, carry_over = s.carryOver,
                    first_asset_ids = AssetCatalog.encodeRefIds(s.assetIds),
                    last_asset_ids = "[]",
                    visual_prompt = s.visualPrompt, duration_seconds = s.durationSeconds,
                    sb_check = if (result.gateErrors[s.shotNo].isNullOrEmpty()) "pass"
                               else "error:${result.gateErrors[s.shotNo]!!.joinToString(",")}",
                )) }
            }
        }
        refresh()
        val errCount = result.gateErrors.size
        // v1.7.17：目录为空意味着这些分镜一镜都不会引用资产（渲染只能回退项目级前4张）。
        // 目录现在只收「已生图的母卡」，资产没生图就没有目录项，必须把原因直接说清楚，
        // 否则用户只会觉得"我有资产啊，怎么不引用"。
        val noAssetNote = if (catalog.isEmpty())
            "。⚠ 本项目还没有已生成图像的角色/场景资产，分镜未引用任何资产（渲染将回退项目级前4张）；请先到资产页把资产图生成出来，再重生成分镜" else ""
        _state.value = _state.value.copy(generating = false, message =
            "已生成${result.shots.size}镜" + (if (errCount > 0) "（其中${errCount}镜校验有误，见列表标记）" else "，全部通过校验✓") + noAssetNote)
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    // ---- 第十二轮：分镜可操作（编辑/删除/渲染）----

    /** 编辑单镜并落库；action与visual_prompt分开存，UI展示时合并 */
    fun updateShot(
        shotId: String, action: String, dialogue: String?, narration: String?,
        visualPrompt: String?, durationSeconds: Double,
    ) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                AppGraph.dao.shotKeyframes(shotId)?.let { row ->
                    AppGraph.dao.upsertShot(row.copy(
                        action = action.trim(),
                        dialogue = dialogue?.trim()?.ifBlank { null },
                        narration = narration?.trim()?.ifBlank { null },
                        visual_prompt = visualPrompt?.trim()?.ifBlank { null },
                        duration_seconds = durationSeconds.coerceIn(1.0, 60.0),
                    ))
                }
            }
        }
        refresh()
        _state.value = _state.value.copy(message = "已保存镜头修改✓")
    }

    /** 删除单镜 */
    fun deleteShot(shotId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { runCatching { AppGraph.dao.deleteShot(shotId) } }
        refresh()
    }

    /**
     * 第十二轮：把本集全部分镜入队渲染（复用渲染队列断点续传/预算熔断链路）。
     */
    fun enqueueRender(onQueued: (Int) -> Unit) = viewModelScope.launch {
        val shots = _state.value.shots
        if (shots.isEmpty()) { onQueued(0); return@launch }
        val queue = RenderRuntime.queueFor(episodeId)
        val metas: List<com.dramafactory.core.model.ShotMeta> = shots.map { row ->
            val visual: String = row.visual_prompt?.let { "[$it]" } ?: ""
            val dlg: String = row.dialogue?.let { "「$it」" } ?: ""
            com.dramafactory.core.model.ShotMeta(
                shotId = row.shot_id, episodeId = episodeId,
                prompt = "$dlg${row.action ?: ""}$visual")
        }
        runCatching { queue.enqueueEpisode(episodeId, metas) }
            .onSuccess { onQueued(metas.size) }
            .onFailure { _state.value = _state.value.copy(message = "入队失败：${it.message}") }
    }
}
