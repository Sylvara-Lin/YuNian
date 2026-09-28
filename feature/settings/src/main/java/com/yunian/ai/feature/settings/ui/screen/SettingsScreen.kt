@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.icon.AppIcons

import com.yunian.ai.uicommon.theme.AppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ApiProviderPreset
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.delay
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val configs by viewModel.configs.collectAsState(initial = emptyList())
    val providerPresets by viewModel.providerPresets.collectAsState(initial = emptyList())
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val modelFetchStates by viewModel.modelFetchStates.collectAsState()
    val balanceInfo by viewModel.balanceInfo.collectAsState()
    val balanceQueryFailed by viewModel.balanceQueryFailed.collectAsState()

    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val testedConfigs by viewModel.testedConfigs.collectAsState()
    val visionEnabled by viewModel.visionEnabled.collectAsState()
    val visionModel by viewModel.visionModel.collectAsState()
    val diaryEnabled by viewModel.diaryEnabled.collectAsState()
    val diaryModel by viewModel.diaryModel.collectAsState()
    var expandedProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var newConfigDialog by remember { mutableStateOf<ApiConfig?>(null) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showVisionModelSettings by remember { mutableStateOf(false) }
    var showDiaryModelSettings by remember { mutableStateOf(false) }
    var showImageGenSettings by remember { mutableStateOf(false) }

    var showApiTestDialog by remember { mutableStateOf(false) }
    var apiTestResult by remember { mutableStateOf<ApiTestDialogData?>(null) }
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saveResult.collect { result ->
            when (result) {
                is SettingsViewModel.SaveResult.Success -> snackbarHostState.showSnackbar(result.message)
                is SettingsViewModel.SaveResult.Error -> snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    val colorScheme = AppTheme.colors
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val textTertiaryColor = colorScheme.outlineVariant
    val dividerColor = colorScheme.outline
    val cardBackground = colorScheme.surfaceVariant
    val pageBackdrop = LocalPageBackdrop.current

    LaunchedEffect(Unit) {
        viewModel.refreshConnectionStatus()
        viewModel.refreshPartnerQuota()
        delay(100)
        isVisible = true
    }

    LaunchedEffect(Unit) {
        viewModel.testCompletionEvent.collect { event ->
            apiTestResult = ApiTestDialogData(
                isSuccess = event.isSuccess,
                providerName = event.providerName,
                latencyMs = event.latencyMs,
                errorMessage = event.errorMessage
            )
            showApiTestDialog = true
        }
    }

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "API 设置",
                onBack = onNavigateBack,
                actions = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Heart,
                            contentDescription = "Favorite",
                            tint = PetalPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()

                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            ApiCardsSection(
                isVisible = isVisible,
                configs = configs,
                providerPresets = providerPresets,
                viewModel = viewModel,
                expandedProvider = expandedProvider,
                onExpandedProviderChange = { expandedProvider = it },
                fetchedModels = fetchedModels,
                modelFetchStates = modelFetchStates,
                isDarkTheme = isDarkTheme,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                balanceInfo = balanceInfo,
                balanceQueryFailed = balanceQueryFailed,
                showProviderPicker = showProviderPicker,
                onShowProviderPickerChange = { showProviderPicker = it },

                connectionStatus = connectionStatus,
                testedConfigs = testedConfigs
            )

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) +
                        slideInVertically(tween(400, delayMillis = 200)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showVisionModelSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Eye,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "视觉模型设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = if (visionEnabled) "已启用 - ${AppSettingsStore.VisionModels.getVisionModelDisplayName(visionModel)}" else "点击配置图片识别",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 250)) +
                        slideInVertically(tween(400, delayMillis = 250)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showDiaryModelSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Book,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "日记模型设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = if (diaryEnabled) "已启用 - ${diaryModel.ifBlank { "自定义模型" }}" else "AI 生成日记使用主 API",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 300)) +
                        slideInVertically(tween(400, delayMillis = 300)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showImageGenSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Sparkles,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "AI 生图",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = "接入主流生图接口，支持概率与关键词自动配图",
                                fontSize = 12.sp,
                                color = if (isDarkTheme) textSecondaryColor else Color.Black
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }


            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    newConfigDialog?.let { newConfig ->
        val providerModels = fetchedModels[newConfig.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[newConfig.provider.name] ?: SettingsViewModel.ModelFetchState()
        ApiConfigEditDialog(
            config = newConfig,
            connectionResult = SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN),
            onDismiss = { newConfigDialog = null },
            onSave = { config: ApiConfig ->
                viewModel.saveConfig(config)
                newConfigDialog = null
            },
            onTest = { testConfig: ApiConfig -> viewModel.testConnection(testConfig) },
            onFetchModels = { baseUrl: String, apiKey: String ->
                viewModel.fetchModels(baseUrl, apiKey, newConfig.provider.name)
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }

    if (showProviderPicker) {
        val visiblePresets = providerPresets.filter { it.provider != ApiProvider.PARTNER }
        val cardBackground = AppTheme.colors.surfaceVariant
        val dividerColor = AppTheme.colors.outline

        GlassEditDialog(
            onDismissRequest = { showProviderPicker = false },
            title = "选择 API 提供商",
            titleColor = textPrimaryColor,
            actions = {
                GlassButton(
                    onClick = { showProviderPicker = false },
                    height = 44.dp,
                    horizontalPadding = 16.dp
                ) {
                    Text("取消", color = textSecondaryColor)
                }
            }
        ) {
            // 注意：这里不要再加 verticalScroll —— GlassEditDialog 内部内容区已经是滚动容器，
            // 嵌套同方向滚动会让内层拿到无限高约束，抛 IllegalStateException 闪退
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visiblePresets.forEach { preset ->
                    GlassButton(
                        onClick = {
                            showProviderPicker = false
                            newConfigDialog = ApiConfig(
                                name = preset.displayName,
                                provider = preset.provider,
                                apiKey = "",
                                baseUrl = preset.baseUrl,
                                model = preset.model,
                                formatHint = preset.formatHint
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 60.dp,
                        horizontalPadding = 16.dp
                    ) {
                        // GlassButton 内部 Row 是"水平居中"排列，列表项需要占满宽度左对齐，
                        // 所以这里再包一层 fillMaxWidth 的 Row 承载图标与文字
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderLogo(
                                provider = preset.provider,
                                size = 36.dp,
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.displayName,
                                    color = textPrimaryColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = preset.baseUrl,
                                    color = textSecondaryColor,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 集中拦截系统返回键 / 返回手势：当任一子页面打开时，优先关闭子页面，
    // 避免直接弹出整个设置页回到主页面（仅当子页面可见时才启用，保证设置页自身返回行为不受影响）。
    BackHandler(
        enabled = showVisionModelSettings || showDiaryModelSettings || showImageGenSettings
    ) {
        when {
            showVisionModelSettings -> showVisionModelSettings = false
            showDiaryModelSettings -> showDiaryModelSettings = false
            showImageGenSettings -> showImageGenSettings = false
        }
    }

    // 三个子页面为全屏覆盖层，用 AnimatedVisibility 做「从右侧滑入 / 退回右侧」的过渡动画，
    // 符合「进入下一级 / 返回上一级」的心理模型；容器补 fillMaxSize 避免动画期间尺寸跳动或被裁剪。
    AnimatedVisibility(
        visible = showVisionModelSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        VisionModelSettingsScreen(
            onNavigateBack = { showVisionModelSettings = false },
            viewModel = viewModel
        )
    }

    AnimatedVisibility(
        visible = showDiaryModelSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        DiaryModelSettingsScreen(
            onNavigateBack = { showDiaryModelSettings = false },
            viewModel = viewModel
        )
    }

    AnimatedVisibility(
        visible = showImageGenSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        ImageGenSettingsScreen(
            onNavigateBack = { showImageGenSettings = false }
        )
    }

    if (showApiTestDialog && apiTestResult != null) {
        ApiTestResultDialog(
            data = apiTestResult!!,
            isDarkTheme = isDarkTheme,
            onDismiss = { showApiTestDialog = false }
        )
    }
}

@Composable
private fun ApiCardsSection(
    isVisible: Boolean,
    configs: List<ApiConfig>,
    providerPresets: List<ApiProviderPreset>,
    viewModel: SettingsViewModel,
    expandedProvider: ApiProvider?,
    onExpandedProviderChange: (ApiProvider?) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.yunian.ai.network.AiService.BalanceInfo?,
    balanceQueryFailed: Boolean,
    showProviderPicker: Boolean,
    onShowProviderPickerChange: (Boolean) -> Unit,

    connectionStatus: Map<String, SettingsViewModel.ConnectionResult>,
    testedConfigs: Map<String, ApiConfig>
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = 150)) +
                slideInVertically(tween(400, delayMillis = 150)) { it / 4 }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val partnerPreset = providerPresets.firstOrNull { it.provider == ApiProvider.PARTNER }
            val partnerConfig = configs.firstOrNull { it.provider == ApiProvider.PARTNER }
                ?: ApiConfig(
                    provider = ApiProvider.PARTNER,
                    apiKey = "",
                    extraApiKeys = "",
                    baseUrl = partnerPreset?.baseUrl ?: ApiProvider.PARTNER.defaultBaseUrl,
                    model = partnerPreset?.model ?: ApiProvider.PARTNER.defaultModel,
                    formatHint = partnerPreset?.formatHint ?: "openai"
                )

            val partnerExpanded = expandedProvider == ApiProvider.PARTNER

            PetalApiCard(
                config = partnerConfig,
                connectionResult = connectionStatus[viewModel.connectionKey(partnerConfig)]
                    ?: SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN),
                isExpanded = partnerExpanded,
                isActive = partnerConfig.isEnabled,
                onExpandToggle = {
                    onExpandedProviderChange(if (partnerExpanded) null else ApiProvider.PARTNER)
                },
                onEdit = { selectedConfig: ApiConfig ->
                    viewModel.saveConfig(selectedConfig)
                },
                onTest = { selectedConfig: ApiConfig ->
                    viewModel.testConnection(selectedConfig)
                },
                onToggleEnabled = { viewModel.toggleConfigEnabled(partnerConfig) },
                onSelectActive = { viewModel.selectActiveConfig(partnerConfig) },
                onFetchModels = { baseUrl: String, apiKey: String, provider: String ->
                    viewModel.fetchModels(baseUrl, apiKey, provider)
                },
                fetchedModels = fetchedModels,
                modelFetchStates = modelFetchStates,
                testedConfigs = testedConfigs,
                connectionKey = viewModel.connectionKey(partnerConfig),
                isDarkTheme = isDarkTheme,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                balanceInfo = null,
                balanceQueryFailed = false,
                onQueryBalance = null
            )

            configs.filter { it.provider != ApiProvider.PARTNER }.forEach { config ->
                val result = connectionStatus[viewModel.connectionKey(config)]
                    ?: SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN)
                val isExpanded = expandedProvider == config.provider

                PetalSavedApiCard(
                    config = config,
                    connectionResult = result,
                    isExpanded = isExpanded,
                    isActive = config.isEnabled,
                    onExpandToggle = {
                        onExpandedProviderChange(if (isExpanded) null else config.provider)
                    },
                    onEdit = { it: ApiConfig -> viewModel.saveConfig(it) },
                    onDelete = { viewModel.deleteConfig(config) },
                    onTest = { viewModel.testConnection(config) },
                    onToggleEnabled = { viewModel.toggleConfigEnabled(config) },
                    onSelectActive = { viewModel.selectActiveConfig(config) },
                    onFetchModels = { baseUrl: String, apiKey: String, provider: String ->
                        viewModel.fetchModels(baseUrl, apiKey, provider)
                    },
                    fetchedModels = fetchedModels,
                    modelFetchStates = modelFetchStates,
                    testedConfigs = testedConfigs,
                    connectionKey = viewModel.connectionKey(config),
                    isDarkTheme = isDarkTheme,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            PetalAddApiButton(
                onClick = {
                    onShowProviderPickerChange(true)
                },
                isDarkTheme = isDarkTheme
            )
        }
    }
}
