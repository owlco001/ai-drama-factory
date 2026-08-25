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
        val importedNovel: String? = null,     // 待绑定小说文本
        val importedFileName: String? = null,
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
            importedFileName = null,
            projects = if (id != null) runCatching { loadProjects() }.getOrDefault(_state.value.projects) else _state.value.projects)
        return id?.takeIf { it.isNotEmpty() }
    }

    /** 删除项目（级联由外键ON DELETE CASCADE保证） */
    suspend fun deleteProject(projectId: String) {
        runCatching { deleteProjectRow(projectId) }
        refresh()
    }
}
