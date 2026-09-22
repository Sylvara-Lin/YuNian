@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.yunian.ai.feature.settings.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.settings.ui.viewmodel.ImageGenSettingsViewModel
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import com.yunian.ai.uicommon.theme.WeChatDarkDivider
import com.yunian.ai.uicommon.theme.WeChatDarkTextPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkTextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val MAX_MODELS_COLLAPSED = 12

/**
 * AI 生图接入设置页。
 *
 * 入口位于「API 设置」页的独立卡片，采用与视觉模型设置一致的玻璃卡片风格。
 * 所有配置持久化在 DataStore，无数据库变更。
 */
@Composable
fun ImageGenSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ImageGenSettingsViewModel = viewModel()
) {
    val enabled by viewModel.enabled.collectAsState()
    val connectionMode by viewModel.connectionMode.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val savedModels by viewModel.savedModels.collectAsState()
    val size by viewModel.size.collectAsState()
    val count by viewModel.count.collectAsState()
    val probability by viewModel.probability.collectAsState()
    val keywords by viewModel.keywords.collectAsState()
    val cooldownMinutes by viewModel.cooldownMinutes.collectAsState()
    val promptTemplate by viewModel.promptTemplate.collectAsState()
    val toastEnabled by viewModel.toastEnabled.collectAsState()
    val mainConfigName by viewModel.mainConfigName.collectAsState()
    val mainConfigReady by viewModel.mainConfigReady.collectAsState()
    val isFetchingModels by viewModel.isFetchingModels.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var keywordInput by remember { mutableStateOf("") }
    var showAllModels by remember { mutableStateOf(false) }
    var showModelList by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var templateDraft by remember { mutableStateOf(promptTemplate) }
    var probabilityDraft by remember { mutableFloatStateOf(probability.toFloat()) }

    val snackbarHostState = remember { SnackbarHostState() }

    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val textPrimaryColor = if (isDarkTheme) WeChatDarkTextPrimary else PetalOnSurface
    // 副标题 / 说明文字统一用高对比色：浅色主题纯黑（原 onSurfaceVariant/outlineVariant 太浅看不清），
    // 深色主题用亮灰（纯黑在深色玻璃卡片上不可见）。
    val textSecondaryColor = if (isDarkTheme) WeChatDarkTextSecondary else Color.Black
    val textTertiaryColor = if (isDarkTheme) WeChatDarkTextSecondary else Color.Black
    val cardSurface = if (isDarkTheme) WeChatDarkCard else PetalSurface
    val fieldContainer = if (isDarkTheme) WeChatDarkCard else com.yunian.ai.uicommon.theme.AppTheme.colors.staticWhite
    val backdrop = LocalPageBackdrop.current

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(promptTemplate) {
        if (templateDraft != promptTemplate) templateDraft = promptTemplate
    }
    LaunchedEffect(probability) {
        if (probabilityDraft.roundToInt() != probability) probabilityDraft = probability.toFloat()
    }

    // 优先用本次拉取结果，其次用上次持久化的结果，保证重进页面也能直接选
    val modelCandidates = if (fetchedModels.isNotEmpty()) fetchedModels else savedModels
    val visibleModels = if (showAllModels) modelCandidates else modelCandidates.take(MAX_MODELS_COLLAPSED)
    val warning = viewModel.configCheck()

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {
            // ---------------- 顶栏（椭圆液态玻璃） ----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .drawGlass(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        surfaceColor = cardSurface
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigateBack() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "AI 生图",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
                Box(modifier = Modifier.size(32.dp))
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
            ) {
                Text(
                    text = "接入 OpenAI 兼容协议的图像生成接口，支持聊天中自动配图",
                    fontSize = 13.sp,
                    color = textTertiaryColor,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---------------- 卡片 1：总开关 ----------------
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 100)) +
                    slideInVertically(tween(400, delayMillis = 100)) { it / 4 }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .drawGlass(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(24.dp),
                            surfaceColor = cardSurface
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Sparkles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "AI 生图功能", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimaryColor)
                            Text(
                                text = "启用后可在聊天中按概率或关键词自动生成配图",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "启用生图", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                            Text(
                                text = if (enabled) "已开启 · 配置完成后可在聊天中自动配图" else "已关闭 · 不影响任何现有聊天行为",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }

                    if (warning != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalOrange.copy(alpha = 0.10f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(AppIcons.TriangleAlert, null, Modifier.size(14.dp), tint = PetalOrange)
                            Spacer(Modifier.width(6.dp))
                            Text(text = warning, fontSize = 12.sp, color = PetalOrange)
                        }
                    }
                }
            }

            // ---------------- 卡片 2：连接配置 ----------------
            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 150)) +
                        slideInVertically(tween(400, delayMillis = 150)) { it / 4 }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .drawGlass(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(24.dp),
                                surfaceColor = cardSurface
                            )
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SectionTitle(icon = AppIcons.Settings, text = "连接配置", color = textPrimaryColor)

                        // 连接模式
                        val useCustomApi = connectionMode != "auto"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { viewModel.setConnectionMode(if (useCustomApi) "auto" else "CUSTOM") }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (useCustomApi) "独立配置（专用于生图）" else "跟随主 API 设置",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimaryColor
                                )
                                Text(
                                    text = if (useCustomApi) {
                                        "单独填写生图接口的地址与密钥"
                                    } else if (mainConfigReady) {
                                        "复用「${mainConfigName.ifBlank { "主 API" }}」的地址与密钥"
                                    } else {
                                        "尚未配置可用的主 API"
                                    },
                                    fontSize = 12.sp,
                                    color = textSecondaryColor,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Switch(
                                checked = useCustomApi,
                                onCheckedChange = { viewModel.setConnectionMode(if (it) "CUSTOM" else "auto") },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }

                        if (useCustomApi) {
                            HorizontalDivider(color = if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer)
                            GlassTextField(
                                value = baseUrl,
                                onValueChange = { viewModel.setBaseUrl(it) },
                                label = "API 地址 (Base URL) *",
                                placeholder = "https://api.openai.com/v1/",
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor,
                                textTertiaryColor = textTertiaryColor,
                                containerColor = fieldContainer,
                                keyboardType = KeyboardType.Uri
                            )
                            GlassTextField(
                                value = apiKey,
                                onValueChange = { viewModel.setApiKey(it) },
                                label = "API 密钥 (Key) *",
                                placeholder = "sk-...",
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor,
                                textTertiaryColor = textTertiaryColor,
                                containerColor = fieldContainer,
                                keyboardType = KeyboardType.Password,
                                mask = !apiKeyVisible,
                                trailing = {
                                    Icon(
                                        imageVector = if (apiKeyVisible) AppIcons.EyeOff else AppIcons.Eye,
                                        contentDescription = if (apiKeyVisible) "隐藏密钥" else "显示密钥",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable { apiKeyVisible = !apiKeyVisible },
                                        tint = textSecondaryColor
                                    )
                                }
                            )
                        }

                        // 模型：下拉箭头用于展开/收起已拉取的模型列表
                        GlassTextField(
                            value = model,
                            onValueChange = { viewModel.setModel(it) },
                            label = "生图模型 *",
                            placeholder = "如 flux-schnell / dall-e-3 / wanx-v1",
                            textPrimaryColor = textPrimaryColor,
                            textSecondaryColor = textSecondaryColor,
                            textTertiaryColor = textTertiaryColor,
                            containerColor = fieldContainer,
                            trailing = {
                                Icon(
                                    imageVector = if (showModelList) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                    contentDescription = if (showModelList) "收起模型列表" else "展开模型列表",
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable {
                                            if (!showModelList && modelCandidates.isEmpty()) {
                                                viewModel.fetchModels()
                                            }
                                            showModelList = !showModelList
                                        },
                                    tint = textSecondaryColor
                                )
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.Sparkles, null, Modifier.size(15.dp), tint = textPrimaryColor)
                                Spacer(Modifier.width(6.dp))
                                Text("可用生图模型", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .drawGlass(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(percent = 50),
                                        surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    )
                                    .clickable(enabled = !isFetchingModels) { viewModel.fetchModels() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isFetchingModels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("拉取中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Icon(AppIcons.RefreshCw, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("拉取模型列表", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        if (fetchedModels.isEmpty() && savedModels.isNotEmpty()) {
                            Text(
                                text = "以下为上次拉取的 ${savedModels.size} 个模型，可点「拉取模型列表」刷新",
                                fontSize = 12.sp,
                                color = textTertiaryColor
                            )
                        }

                        if (showModelList && modelCandidates.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                visibleModels.forEach { item ->
                                    GlassChip(
                                        label = item,
                                        selected = item == model,
                                        contentColor = textSecondaryColor,
                                        onClick = { viewModel.setModel(item) }
                                    )
                                }
                            }
                            if (modelCandidates.size > MAX_MODELS_COLLAPSED) {
                                GlassPill(
                                    label = if (showAllModels) {
                                        "收起"
                                    } else {
                                        "还有 ${modelCandidates.size - MAX_MODELS_COLLAPSED} 个，展开"
                                    },
                                    contentColor = MaterialTheme.colorScheme.primary,
                                    onClick = { showAllModels = !showAllModels }
                                )
                            }
                        }

                        // 尺寸
                        Text("出图尺寸", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AppSettingsStore.ImageGenDefaults.SIZE_OPTIONS.forEach { (value, label) ->
                                GlassChip(
                                    label = label,
                                    selected = value == size,
                                    contentColor = textSecondaryColor,
                                    onClick = { viewModel.setSize(value) }
                                )
                            }
                        }

                        // 张数
                        Text("每次生成张数", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            (1..4).forEach { n ->
                                GlassChip(
                                    label = "$n 张",
                                    selected = n == count,
                                    contentColor = textSecondaryColor,
                                    onClick = { viewModel.setCount(n) }
                                )
                            }
                        }

                        // 测试连接
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawGlass(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(12.dp),
                                    surfaceColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = !isTesting) { viewModel.testConnection() }
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("测试中…", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            } else {
                                Icon(AppIcons.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("测试 API 连接", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        testResult?.let { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (result.success) PetalGreen.copy(alpha = 0.08f)
                                        else PetalError.copy(alpha = 0.08f)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (result.success) "连接正常" else "连接失败",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (result.success) PetalGreen else PetalError
                                )
                                Text(
                                    text = result.message,
                                    fontSize = 12.sp,
                                    color = textSecondaryColor
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 卡片 3：触发规则 ----------------
            if (enabled) {
                Spacer(modifier = Modifier.height(16.dp))
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 200)) +
                        slideInVertically(tween(400, delayMillis = 200)) { it / 4 }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .drawGlass(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(24.dp),
                                surfaceColor = cardSurface
                            )
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        SectionTitle(icon = AppIcons.Zap, text = "触发规则", color = textPrimaryColor)

                        // 概率
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自动触发概率", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                            Text(
                                text = "${probabilityDraft.roundToInt()}%",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = probabilityDraft,
                            onValueChange = { probabilityDraft = it },
                            onValueChangeFinished = { viewModel.setProbability(probabilityDraft.roundToInt()) },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AppSettingsStore.ImageGenDefaults.PROBABILITY_PRESETS.forEach { (value, label) ->
                                GlassChip(
                                    label = label,
                                    selected = probability == value,
                                    contentColor = textSecondaryColor,
                                    onClick = {
                                        probabilityDraft = value.toFloat()
                                        viewModel.setProbability(value)
                                    }
                                )
                            }
                        }
                        Text(
                            text = "每一轮 AI 回复后按该概率决定是否配图（0% 表示只靠关键词触发）",
                            fontSize = 11.sp,
                            color = textTertiaryColor
                        )
                        if (probability >= 60) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.TriangleAlert, null, Modifier.size(12.dp), tint = PetalOrange)
                                Spacer(Modifier.width(4.dp))
                                Text("高概率会频繁消耗生图额度，请留意接口计费", fontSize = 11.sp, color = PetalOrange)
                            }
                        }

                        HorizontalDivider(color = if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer)

                        // 关键词
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自定义触发关键词", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                            GlassPill(
                                label = "恢复默认",
                                contentColor = MaterialTheme.colorScheme.primary,
                                onClick = { viewModel.resetKeywords() }
                            )
                        }
                        Text(
                            text = "命中任一关键词即无条件生成配图，不受概率限制",
                            fontSize = 11.sp,
                            color = textTertiaryColor
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            keywords.forEach { keyword ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .drawGlass(
                                            backdrop = backdrop,
                                            shape = RoundedCornerShape(percent = 50),
                                            surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        )
                                        .clickable { viewModel.removeKeyword(keyword) }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(keyword, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        imageVector = AppIcons.X,
                                        contentDescription = "删除 $keyword",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = keywordInput,
                                onValueChange = { keywordInput = it },
                                placeholder = { Text("输入关键词，如：画一张", color = textTertiaryColor, fontSize = 13.sp) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    focusedContainerColor = fieldContainer,
                                    unfocusedContainerColor = fieldContainer,
                                    focusedTextColor = textPrimaryColor,
                                    unfocusedTextColor = textPrimaryColor
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                            )
                            Spacer(Modifier.width(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .drawGlass(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(percent = 50),
                                        surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    )
                                    .clickable {
                                        val input = keywordInput.trim()
                                        if (input.isNotEmpty()) {
                                            viewModel.addKeyword(input)
                                            keywordInput = ""
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(AppIcons.Plus, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text("添加", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        HorizontalDivider(color = if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer)

                        // 冷却
                        Text("同一会话冷却时间", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(0, 1, 3, 5, 10, 30).forEach { minutes ->
                                GlassChip(
                                    label = if (minutes == 0) "不限制" else "${minutes} 分钟",
                                    selected = minutes == cooldownMinutes,
                                    contentColor = textSecondaryColor,
                                    onClick = { viewModel.setCooldownMinutes(minutes) }
                                )
                            }
                        }

                        // 高级
                        GlassPill(
                            label = if (showAdvanced) "收起高级设置" else "展开高级设置",
                            contentColor = MaterialTheme.colorScheme.primary,
                            onClick = { showAdvanced = !showAdvanced }
                        )
                        if (showAdvanced) {
                            GlassTextField(
                                value = templateDraft,
                                onValueChange = { templateDraft = it },
                                label = "Prompt 模板",
                                placeholder = "如：画一张：{content}，写实风格",
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor,
                                textTertiaryColor = textTertiaryColor,
                                containerColor = fieldContainer,
                                onCommit = { viewModel.setPromptTemplate(it) },
                                supporting = "{content} 会被替换为画面描述"
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("显示生成提示", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = textPrimaryColor)
                                    Text("生成中 / 成功 / 失败时给出提示", fontSize = 12.sp, color = textSecondaryColor)
                                }
                                Switch(
                                    checked = toastEnabled,
                                    onCheckedChange = { viewModel.setToastEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 卡片 4：说明 ----------------
            Spacer(modifier = Modifier.height(16.dp))
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 250)) +
                    slideInVertically(tween(400, delayMillis = 250)) { it / 4 }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .drawGlass(
                            backdrop = backdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = cardSurface
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Info, null, Modifier.size(16.dp), tint = textPrimaryColor)
                        Spacer(Modifier.width(6.dp))
                        Text("接入说明", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimaryColor)
                    }
                    InfoItem(
                        icon = AppIcons.RefreshCw,
                        title = "兼容标准协议",
                        content = "使用 OpenAI 标准的 /images/generations 与 /models 接口，主流厂商与中转站均可直接接入。",
                        textColor = textPrimaryColor,
                        secondaryColor = textSecondaryColor
                    )
                    InfoItem(
                        icon = AppIcons.Zap,
                        title = "关键词优先",
                        content = "关键词与概率是「或」的关系：命中关键词必定出图，未命中时再按概率抽签。",
                        textColor = textPrimaryColor,
                        secondaryColor = textSecondaryColor
                    )
                    InfoItem(
                        icon = AppIcons.Clock,
                        title = "冷却保护",
                        content = "同一会话在冷却时间内最多生成一张，避免高概率设置下连续消耗额度。",
                        textColor = textPrimaryColor,
                        secondaryColor = textSecondaryColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ------------------------------------------------------------------ 局部组件

@Composable
private fun SectionTitle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 小号胶囊按钮：选中态用主题色填充，未选中为液态玻璃底。 */
@Composable
private fun GlassChip(
    label: String,
    selected: Boolean,
    contentColor: Color,
    onClick: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp = 240.dp
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 更扁的小胶囊按钮，用于「恢复默认」「展开高级设置」这类次级动作。 */
@Composable
private fun GlassPill(
    label: String,
    contentColor: Color,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .clip(shape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textTertiaryColor: Color,
    containerColor: Color,
    keyboardType: KeyboardType = KeyboardType.Text,
    mask: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onCommit: ((String) -> Unit)? = null,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = textSecondaryColor) },
        placeholder = { Text(placeholder, color = textTertiaryColor, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        singleLine = true,
        visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit?.invoke(value) }),
        trailingIcon = trailing,
        supportingText = if (supporting != null) {
            { Text(supporting, fontSize = 11.sp, color = textTertiaryColor) }
        } else {
            null
        }
    )
}
