package com.dramafactory.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import com.dramafactory.app.ui.theme.DramaColor
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import com.dramafactory.app.ui.components.DramaSnackbarController
import com.dramafactory.app.ui.components.DramaSnackbarHost
import com.dramafactory.app.ui.components.LocalDramaSnackbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.dramafactory.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dramafactory.app.ui.AiAssistantFloating
import com.dramafactory.app.ui.AiAssistantViewModel
import java.io.File
import kotlinx.coroutines.launch

/**
 * 七阶段主导航（架构§4.1）：
 * 项目列表(S1) → 小说导入(S2，并入项目页) → 资产库(S3/S4) → 分镜编辑(S5) →
 * 渲染队列(S6) → 成片库(S7) → 设置。
 * MVP用底部导航6项+设置；「当前项目」经简单状态传递（不引navigation库，减依赖面）。
 */
enum class Page(val label: String) {
    PROJECTS("项目"), EPISODES("剧集"), ASSETS("资产"), STORYBOARD("分镜"),
    QUEUE("渲染"), LIBRARY("成片"), SETTINGS("设置");

    /** 是否在底栏常驻（v1.7.6：收敛为5项；剧集/设置降级为子页） */
    val onBar: Boolean get() = this in listOf(PROJECTS, ASSETS, STORYBOARD, QUEUE, LIBRARY)
    /** 子页归属的主标签（底栏高亮映射） */
    val ownerMain: Page get() = when (this) {
        EPISODES -> PROJECTS
        SETTINGS -> lastMainCache
        else -> this
    }
}

/** 记录进入设置前的上一个主页面（设置子页返回用） */
private var lastMainCache: Page = Page.PROJECTS

/** 全局UI状态：当前选中项目/集（简化跨页上下文） */
class AppNavState {
    var currentProjectId by mutableStateOf<String?>(null)
    var currentProjectName by mutableStateOf<String?>(null)
    var currentEpisodeId by mutableStateOf("default")
}

/** 第十一轮：开屏动画显示时长（ms） */
private const val SPLASH_MS = 2600L
private const val SPLASH_FADE_MS = 280L

/** Pure timing/seam for the splash exit; kept independent of Compose for JVM tests. */
internal data class SplashTiming(val fadeStartMs: Long, val fadeDurationMs: Long, val totalMs: Long)

internal fun splashTiming(totalMs: Long = SPLASH_MS, fadeDurationMs: Long = SPLASH_FADE_MS): SplashTiming {
    require(totalMs >= 0L) { "totalMs must not be negative" }
    require(fadeDurationMs >= 0L) { "fadeDurationMs must not be negative" }
    return SplashTiming(
        fadeStartMs = (totalMs - fadeDurationMs).coerceAtLeast(0L),
        fadeDurationMs = fadeDurationMs.coerceAtMost(totalMs),
        totalMs = totalMs,
    )
}

/** Idempotent completion gate: recomposition/cancellation cannot finish twice. */
internal class SplashCompletionGate {
    private var completed = false

    fun complete(): Boolean = if (completed) false else {
        completed = true
        true
    }
}

/**
 * 第十一轮：开屏动画——「一枝独秀不是春，百花齐放更添香。开源你的梦境」。
 * 五瓣花错峰绽放 + 三行文案渐显，2.6 秒后自动进入主界面。
 */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    val rootAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    val ambientAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val ambientScale = remember { androidx.compose.animation.core.Animatable(.82f) }
    val glowAlpha = remember { androidx.compose.animation.core.Animatable(.05f) }
    val glowScale = remember { androidx.compose.animation.core.Animatable(.82f) }
    val flowerAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val flowerScale = remember { androidx.compose.animation.core.Animatable(.82f) }
    val lineAlphas = remember {
        listOf(
            androidx.compose.animation.core.Animatable(0f),
            androidx.compose.animation.core.Animatable(0f),
            androidx.compose.animation.core.Animatable(0f),
        )
    }
    val completionGate = remember { SplashCompletionGate() }
    val timing = splashTiming()

    // One lifecycle-bound timeline keeps the exit callback out of composition.
    LaunchedEffect(Unit) {
        launch {
            ambientAlpha.animateTo(.9f, androidx.compose.animation.core.tween(180))
            ambientScale.animateTo(1f, androidx.compose.animation.core.tween(720))
        }
        launch {
            kotlinx.coroutines.delay(150L)
            flowerAlpha.animateTo(1f, androidx.compose.animation.core.tween(750))
            flowerScale.animateTo(1f, androidx.compose.animation.core.tween(750))
        }
        launch {
            kotlinx.coroutines.delay(260L)
            glowAlpha.animateTo(.18f, androidx.compose.animation.core.tween(700))
            glowScale.animateTo(1.08f, androidx.compose.animation.core.tween(700))
            glowScale.animateTo(1.16f, androidx.compose.animation.core.tween(300))
            glowScale.animateTo(1.08f, androidx.compose.animation.core.tween(300))
        }
        lineAlphas.forEachIndexed { index, alpha ->
            launch {
                kotlinx.coroutines.delay(1050L + index * 380L)
                alpha.animateTo(1f, androidx.compose.animation.core.tween(450))
            }
        }
        kotlinx.coroutines.delay(timing.fadeStartMs)
        rootAlpha.animateTo(0f, androidx.compose.animation.core.tween(timing.fadeDurationMs.toInt()))
        if (completionGate.complete()) {
            onDone()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer { alpha = rootAlpha.value }
            .background(Brush.verticalGradient(
                // 开屏底色：由表面高层级渐变到基底，走设计系统令牌（原为硬编码 0xFF1A1030/0xFF0D0A1A）
                listOf(DramaColor.SurfaceContainerHigh, DramaColor.Background))),
        contentAlignment = Alignment.Center,
    ) {
        // Keep the fusion layers local to the artwork instead of brightening the full screen.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(280.dp)
                .graphicsLayer {
                    alpha = ambientAlpha.value
                    scaleX = ambientScale.value
                    scaleY = ambientScale.value
                }
                .background(
                    Brush.radialGradient(
                        0f to DramaColor.Primary.copy(alpha = .22f),
                        .42f to DramaColor.Secondary.copy(alpha = .10f),
                        1f to Color.Transparent,
                    )
                )
        )
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            alpha = glowAlpha.value
                            scaleX = glowScale.value
                            scaleY = glowScale.value
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    DramaColor.Secondary.copy(alpha = .20f),
                                    DramaColor.Primary.copy(alpha = .08f),
                                    Color.Transparent,
                                )
                            )
                        )
                )
                Image(
                    painter = painterResource(R.drawable.ui_splash_flower),
                contentDescription = "AI短剧工厂",
                modifier = Modifier.size(96.dp)
                    .alpha(flowerAlpha.value)
                    .scale(flowerScale.value)
                )
            }
            Spacer(Modifier.size(18.dp))
            val lines = listOf("一枝独秀不是春，", "百花齐放更添香。", "开源你的梦境 · AI短剧工厂")
            for ((i, line) in lines.withIndex()) {
                Text(line,
                    style = MaterialTheme.typography.titleMedium,
                    color = DramaColor.OnPrimaryContainer.copy(alpha = lineAlphas[i].value),
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
@androidx.compose.material3.ExperimentalMaterial3Api
fun DramaApp() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mode_prefs", 0) }
    var splashDone by remember { mutableStateOf(false) }
    if (!splashDone) { SplashScreen(onDone = { splashDone = true }); return }
    val nav = remember { AppNavState() }
    // 退出重进恢复：记住上次的项目上下文，避免"什么都没有"
    LaunchedEffect(Unit) {
        val lastPid = prefs.getString("last_pid", null)
        val lastEp = prefs.getString("last_ep", null)
        if (lastPid != null) {
            nav.currentProjectId = lastPid
            nav.currentEpisodeId = lastEp ?: "${lastPid}_ep1"
        }
    }
    var page by remember { mutableStateOf(Page.PROJECTS) }
    // 记录当前主标签（用于设置子页返回映射）
    val lastMain = remember { mutableStateOf(Page.PROJECTS) }
    if (page.onBar) lastMain.value = page
    // 全局 AI 助手（悬浮球）：贯穿整个 App，所有标签页共享同一对话与 agent
    val aiVm: AiAssistantViewModel = viewModel()

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

    // v1.8.5：统一瞬时反馈通道。SnackbarHost 挂在 Scaffold 上（唯一宿主，视觉走 DramaFactory 规格），
    // controller 经 CompositionLocal 注入，页面任意位置 LocalDramaSnackbar.current.show(...) 即可触发。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val snackbarController = remember(snackbarHostState, snackbarScope) {
        DramaSnackbarController(snackbarHostState, snackbarScope)
    }
    CompositionLocalProvider(LocalDramaSnackbar provides snackbarController) {
    Scaffold(
        snackbarHost = { DramaSnackbarHost(snackbarHostState) },
        topBar = {
            // 子页（剧集/设置）显示返回箭头；主页面显示设置齿轮
            TopAppBar(
                title = { Text(page.label, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (!page.onBar) IconButton(onClick = {
                        page = if (page == Page.SETTINGS) lastMain.value else page.ownerMain
                    }) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (page.onBar) IconButton(onClick = { lastMain.value = page; page = Page.SETTINGS }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                for (p in Page.entries.filter { it.onBar }) {
                    NavigationBarItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = {
                            val vec = pageIcon(p)
                            if (vec != null) Icon(vec, contentDescription = p.label)
                            else Icon(pagePainter(p), contentDescription = p.label)
                        },
                        label = { Text(p.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            Modifier.padding(padding).fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                    when (page) {
                        // 第十轮：进入项目先到剧集列表，再点选具体集进入资产页
                        Page.PROJECTS -> ProjectsPage(
                            onEnterProject = { id ->
                                nav.currentProjectId = id
                                nav.currentProjectName = null
                                page = Page.EPISODES
                            },
                            onOpenSettings = {
                                lastMain.value = Page.PROJECTS
                                page = Page.SETTINGS
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
            // ★布局修复（MuMu 真机验证发现）：AiAssistantFloating 的 Box(fillMaxSize()) 原先直接
            // 放在上述 Column 内——非 weight 子项按 maxHeight 优先测量，悬浮球会抢走全部高度，
            // 把内容区 weight(1f) 挤成 0，导致「看不到任何页面、标签切换看似无效」。
            // 现改为放在外层 Box 中：与内容区重叠，真正"悬浮"覆盖，不参与 Column 测量。
            // 进入不同项目自动切换 AI 上下文：用 setProject（内部检测项目 id 变化才重置）
            aiVm.setProject(nav.currentProjectId)
            aiVm.currentEpisodeId = nav.currentEpisodeId
            aiVm.onGoto = { target ->
                val p = when (target.lowercase()) {
                    "projects", "项目" -> Page.PROJECTS
                    "episodes", "剧集" -> Page.EPISODES
                    "assets", "资产" -> Page.ASSETS
                    "storyboard", "分镜" -> Page.STORYBOARD
                    "queue", "渲染" -> Page.QUEUE
                    "library", "成片", "成片库" -> Page.LIBRARY
                    "settings", "设置" -> Page.SETTINGS
                    else -> null
                }
                p?.let { page = it }
            }
            AiAssistantFloating(aiVm)
        }
    }
    }
}

private fun pageIcon(p: Page): androidx.compose.ui.graphics.vector.ImageVector? = when (p) {
    Page.PROJECTS -> null
    Page.EPISODES -> null
    Page.ASSETS -> null
    Page.STORYBOARD -> Icons.Filled.List
    Page.QUEUE -> null
    Page.LIBRARY -> null
    Page.SETTINGS -> Icons.Filled.Settings
}

/** 走查P0-2：底栏语义图标（folder/image/cpu/film）走 XML vector drawable，避免依赖 material-icons-extended */
@androidx.compose.runtime.Composable
private fun pagePainter(p: Page): androidx.compose.ui.graphics.painter.Painter = androidx.compose.ui.res.painterResource(
    when (p) {
        Page.PROJECTS, Page.EPISODES -> R.drawable.ic_folder
        Page.ASSETS -> R.drawable.ic_image
        Page.QUEUE -> R.drawable.ic_cpu
        Page.LIBRARY -> R.drawable.ic_movie
        else -> R.drawable.ic_folder
    }
)
