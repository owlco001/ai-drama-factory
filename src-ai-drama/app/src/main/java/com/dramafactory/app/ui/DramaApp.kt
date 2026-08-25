package com.dramafactory.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

/**
 * 七阶段主导航（架构§4.1）：
 * 项目列表(S1) → 小说导入(S2，并入项目页) → 资产库(S3/S4) → 分镜编辑(S5) →
 * 渲染队列(S6) → 成片库(S7) → 设置。
 * MVP用底部导航6项+设置；「当前项目」经简单状态传递（不引navigation库，减依赖面）。
 */
enum class Page(val label: String) {
    PROJECTS("项目"), ASSETS("资产"), STORYBOARD("分镜"),
    QUEUE("渲染"), LIBRARY("成片"), SETTINGS("设置");
}

/** 全局UI状态：当前选中项目/集（简化跨页上下文） */
class AppNavState {
    var currentProjectId by mutableStateOf<String?>(null)
    var currentEpisodeId by mutableStateOf("default")
}

@Composable
fun DramaApp() {
    val nav = remember { AppNavState() }
    var page by remember { mutableStateOf(Page.PROJECTS) }

    // ★第五轮修复：Android 13+ 渲染触发前必须授予POST_NOTIFICATIONS，否则前台服务通知
    // 发不出且部分ROM直接拒启FGS导致崩溃。进入App时静默请求一次（拒绝也不阻断使用）。
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (p in Page.entries) {
                    NavigationBarItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = { Icon(pageIcon(p), contentDescription = p.label) },
                        label = { Text(p.label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (page) {
                Page.PROJECTS -> ProjectsPage(
                    onEnterProject = { id -> nav.currentProjectId = id; page = Page.ASSETS })
                Page.ASSETS -> AssetsPage(projectId = nav.currentProjectId,
                    onContinue = { page = Page.QUEUE })
                Page.STORYBOARD -> StoryboardPage(episodeId = nav.currentEpisodeId)
                Page.QUEUE -> QueuePage()
                Page.LIBRARY -> LibraryPage()
                Page.SETTINGS -> SettingsPage()
            }
        }
    }
}

private fun pageIcon(p: Page) = when (p) {
    Page.PROJECTS -> Icons.Filled.Home
    Page.ASSETS -> Icons.Filled.PlayArrow
    Page.STORYBOARD -> Icons.Filled.List
    Page.QUEUE -> Icons.Filled.Star
    Page.LIBRARY -> Icons.Filled.Info
    Page.SETTINGS -> Icons.Filled.Settings
}
