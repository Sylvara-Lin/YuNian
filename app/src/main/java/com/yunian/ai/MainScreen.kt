package com.yunian.ai
import com.yunian.ai.uicommon.icon.AppIcons


import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yunian.ai.common.ApplicationScopeProvider
import com.yunian.ai.common.BatteryOptimizationHelper
import com.yunian.ai.common.HardwareInfo
import com.yunian.ai.common.PerformanceTrace
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.common.update.UpdateMode
import com.yunian.ai.feature.update.AppUpdateManager
import com.yunian.ai.uicommon.component.ChatBackgroundCache
import com.yunian.ai.uicommon.component.UpdateDialog
import com.yunian.ai.uicommon.component.BackgroundSettingsViewModel
import com.yunian.ai.uicommon.component.WindowMainBackground
import com.yunian.ai.uicommon.component.getMainBackgroundKey
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import com.yunian.ai.uicommon.component.PageBackgroundContent
import com.yunian.ai.uicommon.component.resolveEffectiveChatBackgroundKey
import com.yunian.ai.uicommon.theme.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.yunian.ai.uicommon.component.glass.ProvidePageBackdrop

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainActivity: Activity) {
    // 首页首次组合（幂等；勿放 draw，只在组合体首行记录一次）。
    remember { PerformanceTrace.markStartupStage("home_first_compose"); true }
    // T04′ · 首帧细分打点的一次性守卫（纯内存 CAS，零 IO，避免每帧重复写并发 map）。
    val firstLayoutMarked = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val firstDrawMarked = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val valActivity = context as ComponentActivity

    val updateManager = remember {
        (valActivity as? MainActivity)?.updateManager ?: AppUpdateManager(context)
    }
    val showUpdateDialog by updateManager.showUpdateDialog.collectAsState()
    val updateInfo by updateManager.updateInfo.collectAsState()
    val downloadProgress by updateManager.downloadProgress.collectAsState()

    var lastNavTime by remember { mutableStateOf(0L) }

    fun openCompanionChat(companionId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 500) return
        lastNavTime = now
        PerformanceTrace.startChat()
        LastOpenedCompanionStore.save(context, companionId)
        // T03 · 角色级预热（零观感、不阻塞导航）：后台预热目标角色的设置快照与自定义背景位图，
        // 使 ChatScreen 首帧即得正确背景 key / 命中位图缓存，消除 R4 的窗口内背景层重建。
        warmUpCompanionChat(context.applicationContext, companionId)
        navController.navigate(MainRoute.Chat(companionId).route)
    }

    LaunchedEffect(Unit) {
        // T02 · 兜底预热硬件档位（应用启动已在 bgScope 预热，这里幂等再确保一次，仅后台执行）。
        withContext(Dispatchers.IO) { HardwareInfo.warmUp() }
    }

    LaunchedEffect(Unit) {
        val intent = valActivity.intent
        if (intent.getBooleanExtra("open_chat", false)) {
            val companionId = intent.getLongExtra("companion_id", -1L)
            if (companionId != -1L) {
                openCompanionChat(companionId)
                intent.removeExtra("open_chat")
                intent.removeExtra("companion_id")
                return@LaunchedEffect
            }
        }
    }

    MainScreenDialogs(
        onAutoStartSettings = { BatteryOptimizationHelper.openAutoStartSettings(context) },
        onBatterySettings = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) }
    )

    val bottomNavItems = listOf(
        BottomNavItem(stringResource(R.string.nav_love), AppIcons.MessageCircle, AppIcons.MessageCircle, MainRoute.Home.route),
        BottomNavItem(stringResource(R.string.nav_contacts), AppIcons.Users, AppIcons.Users, MainRoute.Contacts.route),
        BottomNavItem(stringResource(R.string.nav_profile), AppIcons.User, AppIcons.UserRound, MainRoute.Profile.route)
    )

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    var lastTabPage by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(currentRoute) {
        if (!MainRoute.isMainTabRoute(currentRoute)) {

            lastTabPage = pagerState.currentPage
            return@LaunchedEffect
        }

        val targetPage = when (currentRoute) {
            MainRoute.Contacts.route -> 1
            MainRoute.Profile.route -> 2
            else -> lastTabPage
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val themeViewModel: ThemeViewModel = viewModel()
    val isDark by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()

    var bgTick by remember { mutableIntStateOf(0) }
    fun syncWindowMainBackground() {
        val key = getMainBackgroundKey(context)
        // 传入组合作用域：自定义背景的解码/裁剪在后台完成，绝不在主线程解码（B1）。
        WindowMainBackground.apply(mainActivity.window, context, key, isDark, coroutineScope)
        bgTick++
    }

    LaunchedEffect(isDark) {
        if (MainRoute.isMainTabRoute(currentRoute)) {
            syncWindowMainBackground()
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        withFrameNanos {}
        bgTick++
    }
    val backgroundViewModel: BackgroundSettingsViewModel = viewModel()
    val bgKey by backgroundViewModel.mainBgKey.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isDark) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncWindowMainBackground()
                // 兜底：返回主页时强制重读背景 key，不依赖 SP 监听器
                backgroundViewModel.refreshFromPrefs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 背景内容统一由 PageBackgroundContent 渲染（预设/自定义纯色/自定义图片），
    // backdrop 只负责捕获该背景层供液态玻璃折射
    val mainBackdrop = key(bgKey, isDark) {
        rememberLayerBackdrop {
            drawContent()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // T04′ · 首帧布局完成（一次性；placement 回调，早于 draw，纯内存 CAS、零 IO）。
            .onGloballyPositioned {
                if (firstLayoutMarked.compareAndSet(false, true)) {
                    PerformanceTrace.markStartupStage("first_layout")
                }
            }
            .drawWithContent {
                drawContent()
                // T04′ · 首帧绘制（一次性；先于 first_frame 记录，用于切分 home_first_compose→first_frame）。
                if (firstDrawMarked.compareAndSet(false, true)) {
                    PerformanceTrace.markStartupStage("first_draw")
                }
                PerformanceTrace.markStartupDrawn()
                PerformanceTrace.markStartupStage("first_frame")
                // 一次性 dump（内部 AtomicBoolean 保证整个启动周期只输出一次，避免每帧刷屏）。
                PerformanceTrace.dumpStartupStages()
            }
            .testTag("app_main_ready")
    ) {
        ProvidePageBackdrop(mainBackdrop) {
            Box(modifier = Modifier.fillMaxSize()) {

                PageBackgroundContent(
                    bgKey = bgKey,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize().layerBackdrop(mainBackdrop),
                    onPainterReady = {
                        // T04′ · 背景画家就绪（复用 PageBackgroundContent 现成钩子；幂等）。
                        PerformanceTrace.markStartupStage("background_painter_ready")
                        bgTick++
                    }
                )
                // T04′ · 导航容器（NavHost）首次组合处。
                remember { PerformanceTrace.markStartupStage("mainscreen_nav_compose"); true }
                MainNavHost(
                    navController = navController,
                    pagerState = pagerState,
                    mainActivity = mainActivity,
                    isDarkTheme = isDark,
                    openCompanionChat = ::openCompanionChat,
                    bottomNavItems = bottomNavItems,
                    coroutineScope = coroutineScope,
                    lastTabPage = lastTabPage,
                    onLastTabPageChanged = { lastTabPage = it },
                    backdrop = mainBackdrop
                )
            }
        }

        val activeUpdate = updateInfo
        if (showUpdateDialog && activeUpdate != null) {
            UpdateDialog(
                updateInfo = activeUpdate,
                downloadProgress = downloadProgress,
                onUpdate = {
                    if (!updateManager.checkInstallPermission()) {
                        updateManager.requestInstallPermission(valActivity)
                    } else {
                        updateManager.startDownload(activeUpdate, UpdateMode.IMMEDIATE)
                    }
                },
                onBackgroundUpdate = {
                    updateManager.startDownload(activeUpdate, UpdateMode.BACKGROUND)
                },
                onNotNow = { updateManager.ignoreThisVersion() },
                onDismiss = { updateManager.dismissUpdate() }
            )
        }
    }
}

@Suppress("unused")
@Composable
private fun TestRedBackgroundLayer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Red.copy(alpha = 0.6f)))
}

/**
 * T03 · 角色级预热：在导航进入聊天页之前，后台预热目标角色的
 *
 * 1. **detail settings 进程级内存快照**（B5）：读取会经由 [ChatDetailSettingsStore.settingsMapFlow]
 *    同步刷新快照，使 ChatScreen 首帧即用快照解析出正确的背景 key（消除 R4 窗口内翻转）；
 * 2. **自定义背景位图**（B2）：命中 [ChatBackgroundCache]，避免首帧回落退底 / 转场中段整层重建。
 *
 * **架构约束**：per-companion 背景解析依赖 `feature:chat` 的类型（[ChatDetailSettingsStore]），
 * 故预热编排放在 `:app` 模块；`core:ui-common` 只提供按 key 的通用预热（[ChatBackgroundCache.preload]）。
 *
 * 背景 key 的解析复用 ChatScreen 的同一函数 [resolveEffectiveChatBackgroundKey] + [getChatBackgroundKey]，
 * 保证预热 key 与首帧实际渲染 key 严格一致。全程在后台 IO 作用域执行，**绝不阻塞导航**。
 */
private fun warmUpCompanionChat(appContext: Context, companionId: Long) {
    ApplicationScopeProvider.scope.launch {
        runCatching {
            val settings = ChatDetailSettingsStore(appContext).getSettings(companionId)
            val effectiveKey = resolveEffectiveChatBackgroundKey(
                useGlobalBackground = settings.useGlobalBackground,
                companionBackgroundKey = settings.backgroundKey,
                globalBackgroundKey = getChatBackgroundKey(appContext)
            )
            ChatBackgroundCache.preload(appContext, effectiveKey)
        }
    }
}
