package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
    PROJECTS("项目"), EPISODES("剧集"), ASSETS("资产"), STORYBOARD("分镜"),
    QUEUE("渲染"), LIBRARY("成片"), SETTINGS("设置");
}

/** 全局UI状态：当前选中项目/集（简化跨页上下文） */
class AppNavState {
    var currentProjectId by mutableStateOf<String?>(null)
    var currentProjectName by mutableStateOf<String?>(null)
    var currentEpisodeId by mutableStateOf("default")
}

/** 第十一轮：开屏动画显示时长（ms） */
private const val SPLASH_MS = 2600L

/**
 * 第十一轮：开屏动画——「一枝独秀不是春，百花齐放更添香。开源你的梦境」。
 * 五瓣花错峰绽放 + 三行文案渐显，2.6 秒后自动进入主界面。
 */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(SPLASH_MS)
        visible = false
    }
    if (!visible) { onDone(); return }
    Box(
        modifier = Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(androidx.compose.ui.graphics.Color(0xFF1A1030), androidx.compose.ui.graphics.Color(0xFF0D0A1A)))),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                val delays = listOf(150, 350, 550, 750, 950)
                for ((i, d) in delays.withIndex()) {
                    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
                    val scale = remember { androidx.compose.animation.core.Animatable(0.3f) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(d.toLong())
                        alpha.animateTo(1f, androidx.compose.animation.core.tween(500))
                        scale.animateTo(1f, androidx.compose.animation.core.tween(500))
                    }
                    Text("🌸",
                        fontSize = androidx.compose.ui.unit.TextUnit(26f + i * 4f, androidx.compose.ui.unit.TextUnitType.Sp),
                        modifier = Modifier.padding(horizontal = 3.dp)
                            .alpha(alpha.value)
                            .scale(scale.value))
                }
            }
            Spacer(Modifier.size(18.dp))
            val lines = listOf("一枝独秀不是春，", "百花齐放更添香。", "开源你的梦境 · AI短剧工厂")
            for ((i, line) in lines.withIndex()) {
                val ta = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1100L + i * 380L)
                    ta.animateTo(1f, androidx.compose.animation.core.tween(450))
                }
                Text(line,
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color(0xFFE8DDF5).copy(alpha = ta.value),
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun DramaApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mode_prefs", 0) }
    var splashDone by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "manual") ?: "manual") }
    if (!splashDone) { SplashScreen(onDone = { splashDone = true }); return }
    val nav = remember { AppNavState() }
    // 退出重进恢复：记住上次 AI/手动模式的项目上下文，避免"什么都没有"
    LaunchedEffect(Unit) {
        val lastPid = prefs.getString("last_pid", null)
        val lastEp = prefs.getString("last_ep", null)
        if (lastPid != null) {
            nav.currentProjectId = lastPid
            nav.currentEpisodeId = lastEp ?: "${lastPid}_ep1"
        }
    }
    var page by remember { mutableStateOf(Page.PROJECTS) }

    // ★第五轮修复：Android 13+ 渲染触发前必须授予POST_NOTIFICATIONS，否则前台服务通知
    // 发不出且部分ROM直接拒启FGS导致崩溃。进入App时静默请求一次（拒绝也不阻断使用）。
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
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
                        selected = (mode != "ai") && page == p,
                        onClick = {
                            // AI 模式下点底部标签：退出 AI 模式并跳转（解决切不了标签）
                            mode = "manual"
                            prefs.edit().putString("mode", "manual").apply()
                            page = p
                        },
                        icon = { Icon(pageIcon(p), contentDescription = p.label) },
                        label = { Text(p.label) },
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 第十三轮 P0-1：首页双模式入口（全局记忆）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            mode = "ai"
                            prefs.edit().putString("mode", "ai").apply()
                            page = Page.PROJECTS
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (mode == "ai") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) { Text("🤖 AI 全托管") }
                    Button(
                        onClick = {
                            mode = "manual"
                            prefs.edit().putString("mode", "manual").apply()
                            page = Page.PROJECTS
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (mode == "manual") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) { Text("✍️ 人工模式") }
                }

                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    when (mode) {
                        "ai" -> AiPipelinePage(
                            onBack = {
                                mode = "manual"
                                prefs.edit().putString("mode", "manual").apply()
                                page = Page.PROJECTS
                            },
                            onNavigate = { target ->
                                mode = "manual"
                                prefs.edit().putString("mode", "manual").apply()
                                // 记住上下文，退出重进可恢复
                                nav.currentProjectId?.let { prefs.edit().putString("last_pid", it).apply() }
                                prefs.edit().putString("last_ep", nav.currentEpisodeId).apply()
                                page = target
                            },
                            onAutoCreated = { pid, epId ->
                                nav.currentProjectId = pid
                                nav.currentProjectName = null
                                nav.currentEpisodeId = epId
                                prefs.edit().putString("last_pid", pid).apply()
                                prefs.edit().putString("last_ep", epId).apply()
                            },
                        )
                        else -> when (page) {
                            // 第十轮：进入项目先到剧集列表，再点选具体集进入资产页
                            Page.PROJECTS -> ProjectsPage(
                                onEnterProject = { id ->
                                    nav.currentProjectId = id
                                    nav.currentProjectName = null
                                    page = Page.EPISODES
                                })
                            Page.EPISODES -> nav.currentProjectId?.let { pid ->
                                EpisodePage(
                                    projectId = pid,
                                    projectName = nav.currentProjectName,
                                    onOpenEpisode = { epId ->
                                        nav.currentEpisodeId = epId
                                        page = Page.ASSETS
                                    })
                            }
                            Page.ASSETS -> AssetsPage(projectId = nav.currentEpisodeId,
                                onContinue = { page = Page.QUEUE })
                            Page.STORYBOARD -> StoryboardPage(episodeId = nav.currentEpisodeId)
                            Page.QUEUE -> QueuePage(episodeId = nav.currentEpisodeId)
                            Page.LIBRARY -> LibraryPage()
                            Page.SETTINGS -> SettingsPage()
                        }
                    }
                }
            }
        }
    }
}

private fun pageIcon(p: Page) = when (p) {
    Page.PROJECTS, Page.EPISODES -> Icons.Filled.Home
    Page.ASSETS -> Icons.Filled.PlayArrow
    Page.STORYBOARD -> Icons.Filled.List
    Page.QUEUE -> Icons.Filled.Star
    Page.LIBRARY -> Icons.Filled.Info
    Page.SETTINGS -> Icons.Filled.Settings
}
