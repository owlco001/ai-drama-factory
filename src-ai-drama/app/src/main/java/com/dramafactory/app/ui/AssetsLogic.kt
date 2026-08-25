package com.dramafactory.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 资产库页ViewModel逻辑——与Android解耦，JVM可单测。
 *
 * 职责：角色/场景/道具分组卡片流 + 资产生成（调Text/ImageProvider）+ 人工评审勾选（保留/重生成，F04）。
 * 评审语义：keep→review_state='keep'；regen→'regen'并重新触发生成。全部资产keep后评审门（GateReport.reviewPassed）才可放行。
 */
class AssetsLogic {

    enum class Kind(val label: String) { CHARACTER("角色"), SCENE("场景"), PROP("道具"), LOCAL("本地") }

    /** 资产卡片数据 */
    data class AssetCard(
        val assetId: String,
        val kind: Kind,
        val prompt: String,
        val remoteUrl: String? = null,     // 生成结果URL/data uri
        val reviewState: String = "none",  // none/keep/regen
        val generating: Boolean = false,   // 生成中转圈
        // ---- 第六轮：本地上传 / 图生图 / 视频参考 扩展 ----
        /** 来源：generated（引擎生成）/ local（用户本地上传） */
        val source: String = "generated",
        /** 本地上传图片URI（MediaStore或app内部存储） */
        val imageUri: String? = null,
        /** 本地上传视频URI（相册/拍摄） */
        val videoUri: String? = null,
        /** 图生图参考图URI：生成图像时作为 input_images 传给图像API */
        val referenceImageUri: String? = null,
    )

    private val _assets = MutableStateFlow<List<AssetCard>>(emptyList())
    val assets: StateFlow<List<AssetCard>> get() = _assets

    /**
     * 剧本→资产卡提取器（纯函数，JVM可单测；MVP关键词/结构化解析，不依赖LLM）。
     *
     * 产品语义修正（v0.4 bugfix）：剧本模式只是跳过「文本分析自动建卡」，
     * 用户仍需从剧本手动/一键生成角色/场景/道具图像资产（分镜渲染依赖资产ID）。
     * 提取规则：
     * - 「角色：A、B」/「场景：…」/「道具：…」清单行 → 按类拆分为多张卡；
     * - 场次标题行（第X场 / 场X / 场景N / SCENE N / 内景 / 外景 / INT./EXT.）→ 场景卡；
     *   prompt 取去除场号后的环境描述（如「场1 漠北草原·日·外」→「漠北草原·日·外」）；
     * - 第七轮：兼容 Markdown 加粗/标题装饰（**场1 漠北草原·日·外** / **角色：王莽** 等），
     *   先剥掉 **、#、` 与首尾空白再按上述规则匹配；
     * - 对白标签行首「角色名（OS/动作状态）：」且角色名2-4个汉字 → 角色卡（如 蒲奴（OS）：/ 张二（…）：）；
     * - 去重保序；无任何命中返回空列表（UI据此提示改用手动添加）。
     */
    object ScriptAssetExtractor {

        data class Extracted(val kind: Kind, val name: String)

        private val SCENE_LINE = Regex(
            """^\s*(?:第[0-9一二三四五六七八九十百]+场|场景\s*\d+|SCENE\s*\d+|内景|外景|INT\.|EXT\.)""",
            RegexOption.IGNORE_CASE)

        /** 场次标题：捕获场号前缀 + 环境描述。支持 第X场 / 场X / 场景X / SCENE X */
        private val SCENE_HEADING = Regex(
            """^(第[0-9一二三四五六七八九十百]+场|场\s*\d+|场景\s*\d+|SCENE\s*\d+)\s*(.*)$""",
            RegexOption.IGNORE_CASE)

        /** 纯环境标识行（无场号，整行即描述） */
        private val SCENE_PLAIN = Regex("""^(?:内景|外景|INT\.|EXT\.)""", RegexOption.IGNORE_CASE)

        /** 对白标签行首：角色名（OS/动作状态）：，角色名2-4个汉字（误判如「追兵声」可接受） */
        private val DIALOGUE_LABEL = Regex("""^([\u4e00-\u9fa5]{2,4})[（(][^）)]*[）)]\s*[:：]""")

        /** 剥掉Markdown装饰（**加粗** / # 标题 / `代码`）与首尾空白后返回纯文本行 */
        private fun plain(line: String): String =
            line.trim().trim('*', '`', '#').trim()

        /** 场次标题 → 场景卡prompt：去除场号后的环境描述；无描述则用标题本身（如「场1」/「第1场」） */
        private fun scenePrompt(line: String): String? {
            if (SCENE_PLAIN.containsMatchIn(line)) return line.take(60)   // 内景/外景/INT./EXT.：整行作描述
            val m = SCENE_HEADING.matchEntire(line) ?: return null
            val desc = m.groupValues[2].trim().trimStart('-', '—', '·', '：', ':').trim()
            return if (desc.isNotEmpty()) desc.take(60) else m.groupValues[1].trim()
        }

        /** 解析「标签：项1、项2／项3」清单行，按分隔符拆分 */
        private fun parseListLine(line: String): Pair<Kind, List<String>>? {
            val label = when {
                line.startsWith("角色") || line.startsWith("人物") -> Kind.CHARACTER
                line.startsWith("场景") && !SCENE_LINE.containsMatchIn(line) -> Kind.SCENE
                line.startsWith("道具") -> Kind.PROP
                else -> return null
            }
            val body = line.substringAfter('：', "").ifBlank { line.substringAfter(':', "") }
            if (body.isBlank()) return null
            val items = body.split('、', '，', ',', '/', '／')
                .map { it.trim().trimEnd('。', '.'); }.filter { it.isNotBlank() }
            return if (items.isEmpty()) null else label to items
        }

        fun extract(script: String): List<Extracted> {
            val out = LinkedHashMap<String, Extracted>()
            fun put(e: Extracted) { out.putIfAbsent("${e.kind.name}:${e.name}", e) }
            for (raw in script.lines()) {
                val line = plain(raw)
                if (line.isEmpty()) continue
                val listed = parseListLine(line)
                if (listed != null) {
                    for (name in listed.second) put(Extracted(listed.first, name))
                    continue
                }
                val scene = scenePrompt(line)
                if (scene != null) { put(Extracted(Kind.SCENE, scene)); continue }
                dialogueCharacter(line)?.let { put(Extracted(Kind.CHARACTER, it)) }
            }
            return out.values.toList()
        }

        /** 对白标签「角色名（OS/动作状态）：」→ 角色卡（如 蒲奴（OS）：/ 张二（嫌弃地擦灰…）：） */
        private fun dialogueCharacter(line: String): String? =
            DIALOGUE_LABEL.matchAt(line, 0)?.groupValues?.get(1)

        /** stage_flags JSON → 是否剧本模式（宽松解析，避免引JSON库） */
        fun isScriptMode(stageFlags: String?): Boolean =
            stageFlags?.contains(Regex("\"script_mode\"\\s*:\\s*true")) == true
    }

    /**
     * 一键从剧本文本提取资产卡：仅新增不存在的卡片（按kind+prompt判重），返回新增数量。
     * idGen由调用方注入（App层用时间戳保证唯一）。
     */
    fun extractFromScript(script: String, idGen: () -> String): Int {
        val existing = _assets.value.map { "${it.kind.name}:${it.prompt}" }.toHashSet()
        var added = 0
        for (e in ScriptAssetExtractor.extract(script)) {
            val key = "${e.kind.name}:${e.name}"
            if (key in existing) continue
            existing += key
            _assets.value += AssetCard(assetId = idGen(), kind = e.kind, prompt = e.name)
            added++
        }
        return added
    }

    /** 某分类下尚未生成图像的卡片id列表（供「逐类生成图像」入口） */
    fun pendingIdsOfKind(kind: Kind): List<String> =
        _assets.value.filter { it.kind == kind && it.remoteUrl == null && !it.generating }.map { it.assetId }

    /** 生成回调：App层注入真实ImageProvider调用；返回(url或null)与错误信息 */
    var generateHandler: suspend (card: AssetCard) -> Result<String> = { Result.failure(IllegalStateException("未接线")) }
    /** 评审落库回调：App层注入Room UPDATE assets SET review_state */
    var reviewPersist: suspend (assetId: String, state: String) -> Unit = { _, _ -> }

    fun setAssets(list: List<AssetCard>) { _assets.value = list }

    /** 新增资产（输入prompt后点「添加」） */
    fun addAsset(assetId: String, kind: Kind, prompt: String) {
        if (prompt.isBlank()) return
        _assets.value += AssetCard(assetId = assetId, kind = kind, prompt = prompt.trim())
    }

    /**
     * 第六轮：本地上传资产。三类来源（拍摄/相册图/相册视频）统一落库为 source=local 卡片。
     * 图片→kind=LOCAL + imageUri；视频→kind=LOCAL + videoUri；若同时带prompt则存prompt。
     * 返回该资产id（供UI后续设参考图/评审）。
     */
    fun addLocalAsset(
        assetId: String,
        imageUri: String? = null,
        videoUri: String? = null,
        prompt: String = "",
    ): String {
        val uri = imageUri ?: videoUri
        if (uri.isNullOrBlank()) return ""   // 无URI不上传
        _assets.value += AssetCard(
            assetId = assetId,
            kind = Kind.LOCAL,
            prompt = prompt.trim().ifBlank { (if (videoUri != null) "本地视频" else "本地图片") },
            source = "local",
            imageUri = imageUri,
            videoUri = videoUri,
        )
        return assetId
    }

    /** 设置/清除某资产的图生图参考图URI（UI「用参考图生成」入口） */
    fun setReferenceImage(assetId: String, uri: String?) {
        update(assetId) { it.copy(referenceImageUri = uri) }
    }

    /** 删除资产卡片 */
    fun removeAsset(assetId: String) {
        _assets.value = _assets.value.filterNot { it.assetId == assetId }
    }

    /**
     * 生成单资产：置generating转圈 → 调handler → 成功存URL / 失败还原并保留错误提示位。
     * 单卡失败不拖垮其他卡片（PRD崩溃率约束的UI面）。
     * 第六轮：图生图——若 asset.referenceImageUri 非空，将其作为 input_images 传入 handler。
     */
    suspend fun generate(assetId: String) {
        update(assetId) { it.copy(generating = true) }
        val card = _assets.value.firstOrNull { it.assetId == assetId } ?: return
        val result = runCatching { generateHandler(card) }.getOrElse { Result.failure(it) }
        update(assetId) {
            when {
                result.isSuccess -> it.copy(remoteUrl = result.getOrThrow(), generating = false)
                else -> it.copy(generating = false)   // 失败：URL保持旧值，UI按!success显示重试
            }
        }
    }

    /** 评审勾选：keep=保留 / regen=重生成（重生成同时触发再生成） */
    suspend fun review(assetId: String, keep: Boolean) {
        val newState = if (keep) "keep" else "regen"
        update(assetId) { it.copy(reviewState = newState) }
        reviewPersist(assetId, newState)
        if (!keep) generate(assetId)
    }

    /** 评审门判定：全部资产均已「保留」→ GateReport.reviewPassed=true 放行渲染 */
    fun reviewAllPassed(): Boolean =
        _assets.value.isNotEmpty() && _assets.value.all { it.reviewState == "keep" }

    private fun update(assetId: String, transform: (AssetCard) -> AssetCard) {
        _assets.value = _assets.value.map { if (it.assetId == assetId) transform(it) else it }
    }
}
