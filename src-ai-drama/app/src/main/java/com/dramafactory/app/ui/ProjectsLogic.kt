package com.dramafactory.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 项目列表页ViewModel逻辑——与Android解耦，JVM可单测。
 *
 * 职责：新建项目（名称+导入小说TXT/MD）、进入项目、删除。
 * 小说导入解析约束（F02）：支持.txt/.md纯文本；非空校验；长度截断上限200万字防OOM。
 */
class ProjectsLogic {

    data class ProjectItem(
        val projectId: String,
        val name: String,
        val novelChars: Int = 0,       // 已导入小说字数
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class UiState(
        val projects: List<ProjectItem> = emptyList(),
        val newName: String = "",
        val importedNovel: String? = null,     // 待绑定文本（小说或剧本）
        val importedFileName: String? = null,
        val importMode: ImportMode = ImportMode.NOVEL,   // 导入文档类型（第四轮）
        val importedPasted: Boolean = false,   // true=粘贴导入
        val sceneHint: Int = 0,                // 剧本场次提示数
        /** 剧本模式：跳过资产文本分析，直接进分镜编辑（项目级标志） */
        val scriptMode: Boolean = false,
        val pasteInput: String = "",           // 粘贴导入输入框
        val importError: String? = null,
        val creating: Boolean = false,
    )

    companion object {
        const val MAX_NOVEL_CHARS = 2_000_000   // 防OOM截断上限（约一部长篇）
        val SUPPORTED_EXTENSIONS = listOf("txt", "md")
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> get() = _state

    /** 项目持久化/读取回调：App层注入Room DAO实现 */
    var persistProject: suspend (name: String, novelText: String?) -> String = { _, _ -> "" }
    var loadProjects: suspend () -> List<ProjectItem> = { emptyList() }
    var deleteProjectRow: suspend (projectId: String) -> Unit = {}

    /** 进入页面刷新项目列表 */
    suspend fun refresh() {
        _state.value = _state.value.copy(projects = runCatching { loadProjects() }.getOrDefault(emptyList()))
    }

    fun onNameChanged(text: String) { _state.value = _state.value.copy(newName = text) }

    fun onPasteInputChanged(text: String) { _state.value = _state.value.copy(pasteInput = text) }

    /** 切换小说/剧本模式（预选导入类型） */
    fun selectMode(mode: ImportMode) { _state.value = _state.value.copy(importMode = mode) }

    /**
     * 导入小说文件内容（URI读流后传入text）。扩展名校验+空文件校验+超长截断。
     * @return 是否导入成功
     */
    fun importNovel(fileName: String?, text: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase()
        when {
            fileName == null || text == null ->
                _state.value = _state.value.copy(importError = "未选择文件")
            ext !in SUPPORTED_EXTENSIONS ->
                _state.value = _state.value.copy(importError = "仅支持 TXT / MD 文件")
            text.isBlank() ->
                _state.value = _state.value.copy(importError = "文件内容为空")
            else -> {
                val truncated = if (text.length > MAX_NOVEL_CHARS) text.take(MAX_NOVEL_CHARS) else text
                _state.value = _state.value.copy(importedNovel = truncated, importedFileName = fileName,
                    importError = if (text.length > MAX_NOVEL_CHARS) "小说超长，已截断至${MAX_NOVEL_CHARS}字" else null)
                return true
            }
        }
        return false
    }

    fun clearImportError() { _state.value = _state.value.copy(importError = null) }

    /**
     * 新建项目：名称必填；已导入小说则一并落库。
     * @return 新项目id（失败返回null）
     */
    suspend fun createProject(): String? {
        val name = _state.value.newName.trim()
        if (name.isEmpty()) return false.let { _state.value = _state.value.copy(importError = "请填写项目名"); null }
        _state.value = _state.value.copy(creating = true)
        val id = runCatching { persistProject(name, _state.value.importedNovel) }.getOrNull()
        _state.value = _state.value.copy(creating = false, newName = "", importedNovel = null,
            importedFileName = null, pasteInput = "",
            scriptMode = _state.value.importMode == ImportMode.SCRIPT,
            projects = if (id != null) runCatching { loadProjects() }.getOrDefault(_state.value.projects) else _state.value.projects)
        return id?.takeIf { it.isNotEmpty() }
    }

    /** 删除项目（级联由外键ON DELETE CASCADE保证） */
    suspend fun deleteProject(projectId: String) {
        runCatching { deleteProjectRow(projectId) }
        refresh()
    }

    // ==================== 剧本导入（第四轮新增） ====================
    //
    // 区分「小说模式」与「剧本模式」：
    // - 小说模式：导入后走文本分析→资产生成→分镜（原流程）；
    // - 剧本模式：跳过资产生成的文本分析，直接进入分镜编辑。
    // 两种方式：粘贴文本 / 文件导入（复用文件选择器）。

    enum class ImportMode(val label: String) {
        NOVEL("小说"), SCRIPT("剧本");
    }

    data class ImportedDoc(
        val mode: ImportMode,
        val fileName: String,
        val text: String,
        val pasted: Boolean,       // true=粘贴文本导入
        val sceneCount: Int = 0,   // 剧本模式解析出的场次提示数
    )

    /**
     * 导入文档（小说或剧本）。剧本模式额外做轻量场次解析：
     * 统计「第X场 / 场景N / SCENE N / 内景·外景」等场次标题行数。
     */
    fun importDocument(mode: ImportMode, fileName: String?, text: String?, pasted: Boolean): Boolean {
        if (fileName == null || text == null) {
            _state.value = _state.value.copy(importError = "未选择文件")
            return false
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (!pasted && ext !in SUPPORTED_EXTENSIONS) {
            _state.value = _state.value.copy(importError = "仅支持 TXT / MD 文件")
            return false
        }
        if (text.isBlank()) {
            _state.value = _state.value.copy(importError = "内容为空")
            return false
        }
        val truncated = if (text.length > MAX_NOVEL_CHARS) text.take(MAX_NOVEL_CHARS) else text
        val scenes = if (mode == ImportMode.SCRIPT) SceneParser.countScenes(truncated) else 0
        _state.value = _state.value.copy(
            importedNovel = truncated, importedFileName = fileName, importMode = mode,
            importedPasted = pasted, sceneHint = scenes,
            importError = if (text.length > MAX_NOVEL_CHARS) "超长，已截断至${MAX_NOVEL_CHARS}字" else null)
        return true
    }

    object SceneParser {
        private val SCENE_LINE = Regex(
            """^\s*(?:第[0-9一二三四五六七八九十百]+场|场景\s*\d+|SCENE\s*\d+|内景|外景|INT\.|EXT\.)""", RegexOption.IGNORE_CASE)

        /** 轻量场次统计：匹配常见中文/英文剧本场次标题行 */
        fun countScenes(text: String): Int =
            text.lines().count { SCENE_LINE.containsMatchIn(it) }

        /** 启发式判断文本更像剧本还是小说（供自动预选模式） */
        fun guessMode(text: String): ImportMode =
            if (countScenes(text.take(5000)) >= 2) ImportMode.SCRIPT else ImportMode.NOVEL
    }
}
