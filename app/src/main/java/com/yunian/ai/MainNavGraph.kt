package com.yunian.ai

import android.app.Activity
import com.yunian.ai.uicommon.utils.PageTransitions
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.yunian.ai.common.PerformanceTrace
import com.yunian.ai.common.YandereModeManager
import com.yunian.ai.domain.ServiceRegistry
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.yunian.ai.feature.automation.ui.AutomationListScreen
import com.yunian.ai.feature.backup.BackupScreen
import com.yunian.ai.feature.backup.BackupExportSelectScreen
import com.yunian.ai.feature.chat.ui.screen.ChatDetailScreen
import com.yunian.ai.feature.chat.ui.screen.ChatScreen
import com.yunian.ai.feature.chat.ui.screen.DndSettingsScreen
import com.yunian.ai.feature.chat.ui.screen.VoiceCallScreen
import com.yunian.ai.feature.coffee.ui.CoffeeOrderQueryScreen
import com.yunian.ai.feature.coffee.ui.CoffeeScreen
import com.yunian.ai.feature.coffee.ui.CoffeeSettingsScreen
import com.yunian.ai.feature.coffee.ui.CoffeeTokenInputScreen
import com.yunian.ai.feature.coffee.ui.ProductDetailScreen
import com.yunian.ai.feature.companion.ui.screen.ContactsScreen
import com.yunian.ai.feature.companion.ui.screen.CreateCompanionScreen
import com.yunian.ai.feature.groupchat.ui.CreateGroupScreen
import com.yunian.ai.feature.groupchat.ui.GroupChatScreen
import com.yunian.ai.feature.groupchat.ui.GroupDetailScreen
import com.yunian.ai.feature.memory.MemoryScreen
import com.yunian.ai.feature.profile.AboutScreen
import com.yunian.ai.feature.profile.AgreementViewScreen
import com.yunian.ai.feature.profile.AboutYuNianSettingsScreen
import com.yunian.ai.feature.profile.GeneralCategoryScreen
import com.yunian.ai.feature.profile.GeneralSettingsScreen
import com.yunian.ai.feature.profile.HomeScreen
import com.yunian.ai.feature.profile.OriginOSAdaptionScreen
import com.yunian.ai.feature.profile.PermissionsSettingsScreen
import com.yunian.ai.feature.profile.ToolsSettingsScreen
import com.yunian.ai.uicommon.component.BackgroundSettingsScreen
import com.yunian.ai.feature.profile.ProfileScreen
import com.yunian.ai.feature.profile.ProfileSettingsScreen
import com.yunian.ai.feature.profile.RoleManagerScreen
import com.yunian.ai.feature.profile.UserProfileReadonlyScreen
import com.yunian.ai.feature.profile.SupportScreen
import com.yunian.ai.feature.profile.TeamScreen
import com.yunian.ai.feature.profile.ThanksFullListScreen
import com.yunian.ai.feature.profile.ThanksScreen
import com.yunian.ai.feature.qqbot.ui.QQBotSettingsScreen
import com.yunian.ai.feature.settings.ui.screen.CheckUpdateScreen
import com.yunian.ai.feature.settings.ui.screen.ExperimentalFeaturesScreen
import com.yunian.ai.feature.settings.ui.screen.FrameRateScreen
import com.yunian.ai.feature.settings.ui.screen.LanguageScreen
import com.yunian.ai.feature.settings.ui.screen.McpSettingsScreen
import com.yunian.ai.feature.settings.ui.screen.SettingsScreen
import com.yunian.ai.feature.settings.ui.screen.ThemeScreen
import com.yunian.ai.feature.settings.ui.screen.TokenUsageScreen
import com.yunian.ai.feature.settings.ui.screen.TtsSettingsScreen
import com.yunian.ai.feature.settings.ui.screen.WorldbookScreen
import com.yunian.ai.feature.settings.ui.screen.WorldbookDetailScreen
import com.yunian.ai.feature.settings.ui.screen.YandereModeScreen
import com.yunian.ai.feature.wechat.ui.WeChatBindScreen
import com.yunian.ai.feature.wechat.ui.WeChatSettingsScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainNavHost(
    navController: NavHostController,
    pagerState: PagerState,
    mainActivity: Activity,
    isDarkTheme: Boolean,
    openCompanionChat: (Long) -> Unit,
    bottomNavItems: List<BottomNavItem>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    lastTabPage: Int,
    onLastTabPageChanged: (Int) -> Unit,
    backdrop: LayerBackdrop?
) {
    NavHost(
        navController = navController,
        startDestination = MainRoute.Home.route,
        enterTransition = { PageTransitions.enterTransition() },
        exitTransition = { PageTransitions.exitTransition() },
        popEnterTransition = { PageTransitions.popEnterTransition() },
        popExitTransition = { PageTransitions.popExitTransition() }
    ) {
        composable(MainRoute.Home.route) {
            MainTabScreen(
                pagerState = pagerState,
                navController = navController,
                openCompanionChat = openCompanionChat,
                bottomNavItems = bottomNavItems,
                coroutineScope = coroutineScope,
                onLastTabPageChanged = onLastTabPageChanged,
                backdrop = backdrop
            )
        }

        composable(MainRoute.CreateCompanion.route) {
            CreateCompanionScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            MainRoute.EditCompanion(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            CreateCompanionScreen(
                companionId = companionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            MainRoute.Chat(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            ChatScreen(
                companionId = companionId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { navController.navigate(MainRoute.ChatDetail(it).route) },
                onNavigateToUserProfile = { navController.navigate(MainRoute.UserProfileReadonly.route) },
                onNavigateToVoiceCall = { navController.navigate(MainRoute.VoiceCall(it).route) }
            )
        }
        composable(MainRoute.UserProfileReadonly.route) {
            UserProfileReadonlyScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            MainRoute.ChatDetail(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val detailCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            ChatDetailScreen(
                companionId = detailCompanionId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDndSettings = { navController.navigate(MainRoute.DndSettings(detailCompanionId).route) }
            )
        }
        composable(
            MainRoute.DndSettings(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val dndCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            DndSettingsScreen(
                companionId = dndCompanionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            MainRoute.VoiceCall(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val callCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            VoiceCallScreen(
                companionId = callCompanionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            MainRoute.GroupChat(0).route.replace("0", "{groupId}"),
            arguments = listOf(navArgument("groupId") { type = NavType.LongType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
            GroupChatScreen(
                groupId = groupId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { navController.navigate(MainRoute.GroupDetail(it).route) }
            )
        }
        composable(
            MainRoute.GroupDetail(0).route.replace("0", "{groupId}"),
            arguments = listOf(navArgument("groupId") { type = NavType.LongType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
            GroupDetailScreen(
                groupId = groupId,
                onNavigateBack = { navController.popBackStack() },
                onGroupDeleted = { navController.popBackStack(MainRoute.GroupChat(groupId).route, inclusive = true) }
            )
        }
        composable(MainRoute.CreateGroup.route) {
            CreateGroupScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(MainRoute.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.TtsSettings.route) {
            TtsSettingsScreen(onNavigateBack = { navController.popBackStack() }, isDarkTheme = isDarkTheme)
        }
        composable(MainRoute.TokenUsage.route) {
            TokenUsageScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Memory.route) {
            MemoryScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.RoleManager.route) {
            val roleManagerViewModel: com.yunian.ai.feature.profile.ProfileViewModel = viewModel()
            val managerCurrentRole by roleManagerViewModel.selectedRole.collectAsStateWithLifecycle()
            val managerSwitchState by roleManagerViewModel.switchState.collectAsStateWithLifecycle()
            RoleManagerScreen(
                currentRole = managerCurrentRole,
                switchState = managerSwitchState,
                onSwitchRole = { role -> roleManagerViewModel.switchRole(role) { navController.popBackStack() } },
                onNavigateBack = { navController.popBackStack() },
                onConsumeError = { roleManagerViewModel.consumeSwitchError() }
            )
        }
        composable(MainRoute.Theme.route) {
            ThemeScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.Language.route) {
            LanguageScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.BackgroundSettings.route) {
            BackgroundSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.CheckUpdate.route) {
            CheckUpdateScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.ProfileSettings.route) {
            ProfileSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.About.route) {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onNavigateBack = { navController.popBackStack() },
                onCheckUpdateClick = { navController.navigate(MainRoute.CheckUpdate.route) },
                onAgreementClick = { navController.navigate(MainRoute.AgreementView.route) },
                onTeamClick = { navController.navigate(MainRoute.Team.route) }
            )
        }
        composable(MainRoute.AgreementView.route) {
            AgreementViewScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.FrameRate.route) {
            FrameRateScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.YandereMode.route) {
            val manager = ServiceRegistry.get(YandereModeManager::class.java)
            if (manager != null) {
                YandereModeScreen(
                    onNavigateBack = { navController.popBackStack() },
                    yandereModeManager = manager
                )
            }
        }
        composable(MainRoute.Team.route) {
            TeamScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Support.route) {
            SupportScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Thanks.route) {
            ThanksScreen(
                onNavigateBack = { navController.popBackStack() },
                onViewFullList = { navController.navigate(MainRoute.ThanksFullList.route) }
            )
        }
        composable(MainRoute.ThanksFullList.route) {
            ThanksFullListScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.OriginOSAdaption.route) {
            OriginOSAdaptionScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.ExperimentalFeatures.route) {
            ExperimentalFeaturesScreen(
                onNavigateBack = { navController.popBackStack() },
                onYandereModeClick = { navController.navigate(MainRoute.YandereMode.route) },
                onWorldbookClick = { navController.navigate(MainRoute.Worldbook.route) },
                onSkillsClick = { navController.navigate(MainRoute.Skills.route) },
                onMcpClick = { navController.navigate(MainRoute.McpSettings.route) }
            )
        }
        composable(MainRoute.Worldbook.route) {
            WorldbookScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { navController.navigate(MainRoute.WorldbookDetail(it).route) }
            )
        }
        composable(MainRoute.WorldbookDetail(0).route.replace("0", "{worldbookId}"), arguments = listOf(navArgument("worldbookId") { type = NavType.LongType })) {
            val id = it.arguments?.getLong("worldbookId") ?: 0L
            WorldbookDetailScreen(worldbookId = id, onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Skills.route) {
            com.yunian.ai.feature.skills.ui.SkillsCenterScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.McpSettings.route) {
            McpSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.GeneralSettings.route) {
            GeneralSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onExperimentalFeaturesClick = { navController.navigate(MainRoute.ExperimentalFeatures.route) },
                onGeneralCategoryClick = { navController.navigate(MainRoute.SettingsGeneralCategory.route) },
                onToolsClick = { navController.navigate(MainRoute.SettingsTools.route) },
                onPermissionsClick = { navController.navigate(MainRoute.SettingsPermissions.route) },
                onAboutYuNianClick = { navController.navigate(MainRoute.SettingsAboutYuNian.route) }
            )
        }
        composable(MainRoute.SettingsGeneralCategory.route) {
            GeneralCategoryScreen(
                onNavigateBack = { navController.popBackStack() },
                onLanguageClick = { navController.navigate(MainRoute.Language.route) },
                onFrameRateClick = { navController.navigate(MainRoute.FrameRate.route) },
                onTtsSettingsClick = { navController.navigate(MainRoute.TtsSettings.route) },
                onTokenUsageClick = { navController.navigate(MainRoute.TokenUsage.route) },
                onWeChatClick = { navController.navigate(MainRoute.WeChatSettings.route) },
                onQQBotClick = { navController.navigate(MainRoute.QQBotSettings.route) },
                onDataBackupClick = { navController.navigate(MainRoute.DataBackup.route) },
                onOriginOSAdaptionClick = { navController.navigate(MainRoute.OriginOSAdaption.route) }
            )
        }
        composable(MainRoute.SettingsTools.route) {
            ToolsSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onCoffeeClick = { navController.navigate(MainRoute.Coffee.route) },
                onAutomationClick = { navController.navigate(MainRoute.Automation.route) }
            )
        }
        composable(MainRoute.SettingsPermissions.route) {
            PermissionsSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.SettingsAboutYuNian.route) {
            AboutYuNianSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onCheckUpdateClick = { navController.navigate(MainRoute.CheckUpdate.route) }
            )
        }
        composable(MainRoute.WeChatSettings.route) {
            WeChatSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBindClick = { navController.navigate(MainRoute.WeChatBind.route) }
            )
        }
        composable(MainRoute.WeChatBind.route) {
            WeChatBindScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.QQBotSettings.route) {
            QQBotSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.DataBackup.route) {
            BackupScreen(
                onNavigateBack = { navController.popBackStack() },
                onExportSelect = { navController.navigate(MainRoute.BackupExportSelect.route) }
            )
        }
        composable(MainRoute.BackupExportSelect.route) {
            BackupExportSelectScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Coffee.route) {
            CoffeeScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { deptId, productId ->
                    navController.navigate(MainRoute.CoffeeProduct(deptId, productId).route)
                },
                onSettingsClick = { navController.navigate(MainRoute.CoffeeSettings.route) },
                onOrderQueryClick = { navController.navigate(MainRoute.CoffeeOrderQuery.route) }
            )
        }
        composable(
            MainRoute.CoffeeProduct(0, 0).route.replace("0", "{deptId}/{productId}"),
            arguments = listOf(
                navArgument("deptId") { type = NavType.LongType },
                navArgument("productId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val deptId = backStackEntry.arguments?.getLong("deptId") ?: 0L
            val productId = backStackEntry.arguments?.getLong("productId") ?: 0L
            ProductDetailScreen(
                deptId = deptId,
                productId = productId,
                onBack = { navController.popBackStack() },
                onAddedToCart = { navController.popBackStack() }
            )
        }
        composable(MainRoute.CoffeeSettings.route) {
            CoffeeSettingsScreen(
                onBack = { navController.popBackStack() },
                onReplaceToken = { navController.navigate(MainRoute.CoffeeToken.route) },
                onQueryOrder = { orderId -> navController.navigate(MainRoute.CoffeeOrderQueryWithId(orderId).route) }
            )
        }
        composable(MainRoute.CoffeeToken.route) {
            CoffeeTokenInputScreen(onBack = { navController.popBackStack() })
        }
        composable(MainRoute.CoffeeOrderQuery.route) {
            CoffeeOrderQueryScreen(onBack = { navController.popBackStack() })
        }
        composable(
            MainRoute.CoffeeOrderQueryWithId("placeholder").route.replace("placeholder", "{orderId}"),
            arguments = listOf(navArgument("orderId") { type = NavType.StringType; nullable = false })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId").orEmpty()
            CoffeeOrderQueryScreen(
                initialOrderId = orderId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(MainRoute.Automation.route) {
            AutomationListScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabPager(
    pagerState: PagerState,
    navController: NavHostController,
    openCompanionChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // O-A 冷启动优化：首帧不预组合不可见的第 2 页（ContactsScreen 首次组合成本高），
    // 首帧后的空闲窗口恢复预组合（且用户未在滑动时），恢复后横滑体验与改动前一致。
    var preloadNeighborPage by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        withFrameNanos { }
        delay(300)
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        preloadNeighborPage = true
    }
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = if (preloadNeighborPage) 1 else 0,
        modifier = modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> HomeScreen(
                onCompanionClick = openCompanionChat,
                onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
            )
            1 -> {
                // O-A 验证打点：page1 被组合的时刻（冷启动时不应早于首帧 dump）
                PerformanceTrace.markStartupStage("tab_pager_page1_composed")
                android.util.Log.i("YuNianPerf", "tab_pager_page1_composed fired")
                ContactsScreen(
                    onCompanionClick = openCompanionChat,
                    onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                    onEditClick = { navController.navigate(MainRoute.EditCompanion(it).route) },
                    onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                    onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
                )
            }
            2 -> ProfileScreen(
                onMemoryClick = { navController.navigate(MainRoute.Memory.route) },
                onSettingsClick = { navController.navigate(MainRoute.Settings.route) },
                onThemeClick = { navController.navigate(MainRoute.Theme.route) },
                onBackgroundSettingsClick = { navController.navigate(MainRoute.BackgroundSettings.route) },
                onGeneralSettingsClick = { navController.navigate(MainRoute.GeneralSettings.route) },
                onRoleManagerClick = { navController.navigate(MainRoute.RoleManager.route) },
                onTeamClick = { navController.navigate(MainRoute.Team.route) },
                onSupportClick = { navController.navigate(MainRoute.Support.route) },
                onThanksClick = { navController.navigate(MainRoute.Thanks.route) },
                onAboutClick = { navController.navigate(MainRoute.About.route) },
                onProfileSettingsClick = { navController.navigate(MainRoute.ProfileSettings.route) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabScreen(
    pagerState: PagerState,
    navController: NavHostController,
    openCompanionChat: (Long) -> Unit,
    bottomNavItems: List<BottomNavItem>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onLastTabPageChanged: (Int) -> Unit,
    backdrop: LayerBackdrop?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                if (pagerState.settledPage == 1) PerformanceTrace.markContactsDrawn()
            }
    ) {
        MainTabPager(
            pagerState = pagerState,
            navController = navController,
            openCompanionChat = openCompanionChat,
            modifier = Modifier
        )
        FloatingGlassBottomNav(
            items = bottomNavItems,
            currentIndex = pagerState.currentPage,
            onItemClick = { index ->
                onLastTabPageChanged(index)
                if (index == 1) {
                    PerformanceTrace.startContacts()
                }
                coroutineScope.launch {
                    pagerState.scrollToPage(index)
                }
            },
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}