@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen

import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.network.tts.TtsConfig
import com.yunian.ai.network.tts.TtsProvider
import com.yunian.ai.network.tts.TtsVoice
import com.yunian.ai.network.tts.MiMoTtsProvider
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.network.tts.LocalTtsCatalog
import com.yunian.ai.network.tts.LocalTtsUiState
import com.yunian.ai.network.tts.LocalTtsUiStatus
import com.yunian.ai.feature.settings.ui.viewmodel.TtsSettingsViewModel
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.PetalPrimaryContainer
import com.yunian.ai.uicommon.theme.PetalOnPrimaryContainer
import com.yunian.ai.uicommon.theme.PetalGreen
import com.yunian.ai.uicommon.theme.PetalError
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@Composable
fun TtsSettingsScreen(
    onNavigateBack: () -> Unit,
    isDarkTheme: Boolean = false,
    settingsViewModel: TtsSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ttsService = remember { settingsViewModel.getTtsService() }

    var isVisible by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(TtsProvider.entries.first()) }
    var selectedVoiceId by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showVoiceDropdown by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewAudioPath by remember { mutableStateOf<String?>(null) }

    var previewCacheKey by remember { mutableStateOf("") }

    fun stopPreview() {
        previewPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        previewPlayer = null
        isPreviewPlaying = false
    }

    fun playPreview(path: String) {
        stopPreview()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener {
                    stopPreview()
                    testResult = "播放完成"
                }
                setOnErrorListener { _, _, _ ->
                    stopPreview()
                    testResult = "播放中断"
                    true
                }
                prepare()
            }
            previewPlayer = player
            player.start()
            isPreviewPlaying = true

            val durationMs = runCatching { player.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
            scope.launch {
                delay(durationMs + 1500L)
                if (previewPlayer === player) {
                    stopPreview()
                    testResult = "播放完成"
                }
            }
        } catch (e: Exception) {
            stopPreview()
            val reason = e.message ?: e.javaClass.simpleName
            testResult = "播放失败：$reason"
            scope.launch { snackbarHostState.showSnackbar(testResult!!) }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    val config = remember {
        TtsConfig.fromSharedPreferences(context)
    }

    var aliyunKey by remember { mutableStateOf(config.aliyunKeyId) }
    var aliyunSecret by remember { mutableStateOf(config.aliyunKeySecret) }
    var aliyunAppKey by remember { mutableStateOf(config.aliyunAppKey) }
    var baiduKey by remember { mutableStateOf(config.baiduApiKey) }
    var baiduSecret by remember { mutableStateOf(config.baiduSecretKey) }
    var xunfeiAppId by remember { mutableStateOf(config.xunfeiAppId) }
    var xunfeiKey by remember { mutableStateOf(config.xunfeiApiKey) }
    var xunfeiSecret by remember { mutableStateOf(config.xunfeiApiSecret) }
    var azureKey by remember { mutableStateOf(config.azureSubscriptionKey) }
    var azureRegion by remember { mutableStateOf(config.azureRegion) }
    var volcengineAppId by remember { mutableStateOf(config.volcengineAppId) }
    var volcengineToken by remember { mutableStateOf(config.volcengineToken) }
    var volcengineCluster by remember { mutableStateOf(config.volcengineCluster) }
    var sfApiKey by remember { mutableStateOf(config.siliconflowApiKey) }
    var sfCustomVoiceId by remember { mutableStateOf(config.siliconflowCustomVoiceId) }
    var sfUseGlobalKey by remember { mutableStateOf(config.siliconflowUseGlobalKey) }
    var sfTtsModel by remember { mutableStateOf(config.siliconflowTtsModel) }
    var sfSpeed by remember { mutableStateOf(config.siliconflowSpeed) }
    var sfGain by remember { mutableStateOf(config.siliconflowGain) }
    var sfSampleRate by remember { mutableStateOf(config.siliconflowSampleRate) }
    var customTtsUrl by remember { mutableStateOf(config.customTtsUrl) }
    var customTtsApiKey by remember { mutableStateOf(config.customTtsApiKey) }
    var customTtsModel by remember { mutableStateOf(config.customTtsModel) }
    var customTtsVoiceId by remember { mutableStateOf(config.customTtsVoiceId) }
    var customTtsResponseFormat by remember { mutableStateOf(config.customTtsResponseFormat) }
    var mimoApiKey by remember { mutableStateOf(config.mimoApiKey) }
    var mimoBaseUrl by remember { mutableStateOf(config.mimoBaseUrl) }
    var mimoModel by remember { mutableStateOf(config.mimoModel) }
    var mimoVoiceId by remember { mutableStateOf(config.mimoVoiceId) }
    var mimoVoiceDesignPrompt by remember { mutableStateOf(config.mimoVoiceDesignPrompt) }
    var mimoVoiceClonePath by remember { mutableStateOf(config.mimoVoiceClonePath) }
    var mimoOptimizeTextPreview by remember { mutableStateOf(config.mimoOptimizeTextPreview) }
    var showSfModelDropdown by remember { mutableStateOf(false) }
    var showSfRateDropdown by remember { mutableStateOf(false) }
    var showCustomFormatDropdown by remember { mutableStateOf(false) }
    var showMimoModelDropdown by remember { mutableStateOf(false) }

    var localTtsSpeed by remember { mutableStateOf(config.localTtsSpeed) }
    var localTtsSid by remember { mutableStateOf(config.localTtsSid) }
    val localTtsManager = remember { ttsService.localTtsManager }
    val localTtsState by localTtsManager.state.collectAsState()
    var showLocalTtsModelDropdown by remember { mutableStateOf(false) }

    val chatTtsCfg = remember { ChatTtsConfig.fromSharedPreferences(context) }
    var chatTtsMode by remember { mutableStateOf(chatTtsCfg.mode) }
    var skipParentheses by remember { mutableStateOf(chatTtsCfg.skipParentheses) }
    var chatTtsAutoDedup by remember { mutableStateOf(chatTtsCfg.autoDedup) }
    var chatTtsBeautify by remember { mutableStateOf(chatTtsCfg.beautify) }
    var showChatTtsModeDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        ttsEnabled = prefs.getBoolean("tts_enabled", true)
        val providerName = prefs.getString("tts_provider", null)

        selectedProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.entries.first()

        val legacyCustom = prefs.getBoolean("sf_use_custom_tts", false)
        if (
            selectedProvider == TtsProvider.SILICONFLOW &&
            legacyCustom &&
            customTtsUrl.isNotBlank()
        ) {
            selectedProvider = TtsProvider.OPENAI_COMPAT
            prefs.edit()
                .putString("tts_provider", TtsProvider.OPENAI_COMPAT.name)
                .putBoolean("sf_use_custom_tts", false)
                .apply()
        }
        selectedVoiceId = prefs.getString("tts_voice_${selectedProvider.name}", "") ?: ""

        delay(100)
        isVisible = true
    }

    val voices = remember(selectedProvider) {
        ttsService.getVoices(selectedProvider)
    }

    val colorScheme = AppTheme.colors
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val textTertiaryColor = colorScheme.outlineVariant
    val cardBg = colorScheme.surfaceVariant

    fun saveSettings() {
        val newConfig = TtsConfig(
            aliyunKeyId = aliyunKey,
            aliyunKeySecret = aliyunSecret,
            aliyunAppKey = aliyunAppKey,
            baiduApiKey = baiduKey,
            baiduSecretKey = baiduSecret,
            xunfeiAppId = xunfeiAppId,
            xunfeiApiKey = xunfeiKey,
            xunfeiApiSecret = xunfeiSecret,
            azureSubscriptionKey = azureKey,
            azureRegion = azureRegion,
            volcengineAppId = volcengineAppId,
            volcengineToken = volcengineToken,
            volcengineCluster = volcengineCluster,
            siliconflowApiKey = sfApiKey,
            siliconflowCustomVoiceId = sfCustomVoiceId,
            siliconflowUseGlobalKey = sfUseGlobalKey,
            siliconflowTtsModel = sfTtsModel,
            siliconflowSpeed = sfSpeed,
            siliconflowGain = sfGain,
            siliconflowSampleRate = sfSampleRate,
            customTtsUrl = customTtsUrl,
            customTtsApiKey = customTtsApiKey,
            customTtsModel = customTtsModel,
            customTtsVoiceId = customTtsVoiceId,
            customTtsResponseFormat = customTtsResponseFormat,
            mimoApiKey = mimoApiKey,
            mimoBaseUrl = mimoBaseUrl,
            mimoModel = mimoModel,
            mimoVoiceId = mimoVoiceId,
            mimoVoiceDesignPrompt = mimoVoiceDesignPrompt,
            mimoVoiceClonePath = mimoVoiceClonePath,
            mimoOptimizeTextPreview = mimoOptimizeTextPreview,
            localTtsSpeed = localTtsSpeed,
            localTtsSid = localTtsSid
        )
        settingsViewModel.saveSettings(
            config = newConfig,
            ttsEnabled = ttsEnabled,
            provider = selectedProvider,
            voiceId = selectedVoiceId
        )
    }

    fun synthesizeAndPlay(hint: String) {
        scope.launch {
            isSynthesizing = true
            testResult = null
            saveSettings()

            val audioPath = ttsService.testWithSampleText(
                selectedProvider,
                "你好，这是一个语音合成测试。",
                selectedVoiceId
            )
            isSynthesizing = false
            if (audioPath != null) {
                previewAudioPath = audioPath
                previewCacheKey = "${selectedProvider.name}|$selectedVoiceId|$sfCustomVoiceId|$mimoModel|$mimoVoiceClonePath|$mimoVoiceDesignPrompt|$mimoOptimizeTextPreview"
                testResult = hint
                snackbarHostState.showSnackbar(testResult!!)
                playPreview(audioPath)
            } else {
                testResult = "合成失败：${ttsService.lastSynthesisError ?: "未知原因"}"
                snackbarHostState.showSnackbar(testResult!!)
            }
        }
    }

    fun saveChatTtsSettings() {
        val cfg = ChatTtsConfig(
            mode = chatTtsMode,
            skipParentheses = skipParentheses,
            autoDedup = chatTtsAutoDedup,
            beautify = chatTtsBeautify
        )
        settingsViewModel.saveChatTtsSettings(cfg)
    }

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "TTS 语音设置",
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TtsToggleCard(
                        enabled = ttsEnabled,
                        onToggle = {
                            ttsEnabled = it
                            saveSettings()
                        },
                        isDarkTheme = isDarkTheme,
                        cardBg = cardBg,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    AnimatedVisibility(
                        visible = ttsEnabled,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProviderSelectionCard(
                                selectedProvider = selectedProvider,
                                onProviderSelect = {
                                    selectedProvider = it
                                    selectedVoiceId = ""
                                    showVoiceDropdown = false
                                    saveSettings()
                                },
                                showDropdown = showProviderDropdown,
                                onDropdownToggle = {
                                    showProviderDropdown = it
                                    if (it) showVoiceDropdown = false
                                },
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )

                            if (selectedProvider != TtsProvider.MIMO ||
                                MiMoTtsProvider.normalizeModel(mimoModel) == MiMoTtsProvider.MODEL_TTS
                            ) {
                                VoiceSelectionCard(
                                    voices = voices,
                                    selectedVoiceId = selectedVoiceId,
                                    onVoiceSelect = {
                                        selectedVoiceId = it
                                        saveSettings()
                                    },
                                    showDropdown = showVoiceDropdown,
                                    onDropdownToggle = {
                                        showVoiceDropdown = it
                                        if (it) showProviderDropdown = false
                                    },
                                    cardBg = cardBg,
                                    textPrimaryColor = textPrimaryColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                            }

                            if (selectedProvider == TtsProvider.SHERPA_LOCAL) {

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
                                        .padding(20.dp)
                                ) {
                                    Text(
                                        text = "${selectedProvider.displayName} 配置",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimaryColor
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LocalTtsConfigContent(
                                        localTtsState = localTtsState,
                                        localTtsSpeed = localTtsSpeed,
                                        onLocalTtsSpeedChange = { localTtsSpeed = it; saveSettings() },
                                        localTtsSid = localTtsSid,
                                        onLocalTtsSidChange = { localTtsSid = it; saveSettings() },
                                        showModelDropdown = showLocalTtsModelDropdown,
                                        onShowModelDropdown = { showLocalTtsModelDropdown = it },
                                        onSelectModel = {
                                            scope.launch { localTtsManager.selectModel(it) }
                                        },
                                        onDownload = {
                                            scope.launch {
                                                localTtsManager.startDownload(localTtsState.modelId)
                                            }
                                        },
                                        onCancelDownload = {
                                            scope.launch { localTtsManager.cancelDownload() }
                                        },
                                        onEnable = {
                                            scope.launch { localTtsManager.enable() }
                                        },
                                        onDisable = {
                                            scope.launch { localTtsManager.disable() }
                                        },
                                        onDelete = {
                                            scope.launch { localTtsManager.deleteDownloadedModel() }
                                        },
                                        isDarkTheme = isDarkTheme,
                                        cardBg = cardBg,
                                        textPrimaryColor = textPrimaryColor,
                                        textSecondaryColor = textSecondaryColor,
                                        context = context
                                    )
                                }
                            } else {
                                ApiKeyConfigCard(
                                    provider = selectedProvider,
                                    aliyunKey = aliyunKey,
                                    onAliyunKeyChange = { aliyunKey = it },
                                    aliyunSecret = aliyunSecret,
                                    onAliyunSecretChange = { aliyunSecret = it },
                                    aliyunAppKey = aliyunAppKey,
                                    onAliyunAppKeyChange = { aliyunAppKey = it },
                                    baiduKey = baiduKey,
                                    onBaiduKeyChange = { baiduKey = it },
                                    baiduSecret = baiduSecret,
                                    onBaiduSecretChange = { baiduSecret = it },
                                    xunfeiAppId = xunfeiAppId,
                                    onXunfeiAppIdChange = { xunfeiAppId = it },
                                    xunfeiKey = xunfeiKey,
                                    onXunfeiKeyChange = { xunfeiKey = it },
                                    xunfeiSecret = xunfeiSecret,
                                    onXunfeiSecretChange = { xunfeiSecret = it },
                                    azureKey = azureKey,
                                    onAzureKeyChange = { azureKey = it },
                                    azureRegion = azureRegion,
                                    onAzureRegionChange = { azureRegion = it },
                                    volcengineAppId = volcengineAppId,
                                    onVolcengineAppIdChange = { volcengineAppId = it },
                                    volcengineToken = volcengineToken,
                                    onVolcengineTokenChange = { volcengineToken = it },
                                    volcengineCluster = volcengineCluster,
                                    onVolcengineClusterChange = { volcengineCluster = it },
                                    sfApiKey = sfApiKey,
                                    onSfApiKeyChange = { sfApiKey = it },
                                    sfCustomVoiceId = sfCustomVoiceId,
                                    onSfCustomVoiceIdChange = { sfCustomVoiceId = it },
                                    sfUseGlobalKey = sfUseGlobalKey,
                                    onSfUseGlobalKeyChange = { sfUseGlobalKey = it },
                                    sfTtsModel = sfTtsModel,
                                    onSfTtsModelChange = { sfTtsModel = it },
                                    sfSpeed = sfSpeed,
                                    onSfSpeedChange = { sfSpeed = it },
                                    sfGain = sfGain,
                                    onSfGainChange = { sfGain = it },
                                    sfSampleRate = sfSampleRate,
                                    onSfSampleRateChange = { sfSampleRate = it },
                                    customTtsUrl = customTtsUrl,
                                    onCustomTtsUrlChange = { customTtsUrl = it },
                                    customTtsApiKey = customTtsApiKey,
                                    onCustomTtsApiKeyChange = { customTtsApiKey = it },
                                    customTtsModel = customTtsModel,
                                    onCustomTtsModelChange = { customTtsModel = it },
                                    customTtsVoiceId = customTtsVoiceId,
                                    onCustomTtsVoiceIdChange = { customTtsVoiceId = it },
                                    customTtsResponseFormat = customTtsResponseFormat,
                                    onCustomTtsResponseFormatChange = { customTtsResponseFormat = it },
                                    mimoApiKey = mimoApiKey,
                                    onMimoApiKeyChange = { mimoApiKey = it },
                                    mimoBaseUrl = mimoBaseUrl,
                                    onMimoBaseUrlChange = { mimoBaseUrl = it },
                                    mimoModel = mimoModel,
                                    onMimoModelChange = { mimoModel = it },
                                    mimoVoiceId = mimoVoiceId,
                                    onMimoVoiceIdChange = { mimoVoiceId = it },
                                    mimoVoiceDesignPrompt = mimoVoiceDesignPrompt,
                                    onMimoVoiceDesignPromptChange = { mimoVoiceDesignPrompt = it },
                                    mimoVoiceClonePath = mimoVoiceClonePath,
                                    onMimoVoiceClonePathChange = { mimoVoiceClonePath = it; saveSettings() },
                                    mimoOptimizeTextPreview = mimoOptimizeTextPreview,
                                    onMimoOptimizeTextPreviewChange = { mimoOptimizeTextPreview = it; saveSettings() },
                                    showMimoModelDropdown = showMimoModelDropdown,
                                    onShowMimoModelDropdown = { showMimoModelDropdown = it },
                                    showSfModelDropdown = showSfModelDropdown,
                                    onShowSfModelDropdown = { showSfModelDropdown = it },
                                    showSfRateDropdown = showSfRateDropdown,
                                    onShowSfRateDropdown = { showSfRateDropdown = it },
                                    showCustomFormatDropdown = showCustomFormatDropdown,
                                    onShowCustomFormatDropdown = { showCustomFormatDropdown = it },
                                    isDarkTheme = isDarkTheme,
                                    cardBg = cardBg,
                                    textPrimaryColor = textPrimaryColor,
                                    textSecondaryColor = textSecondaryColor,
                                    textTertiaryColor = textTertiaryColor
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            saveSettings()
                                            val result = ttsService.testProvider(selectedProvider)
                                            isTesting = false
                                            testResult = if (result) "连接成功" else "连接失败：${ttsService.lastSynthesisError ?: "请检查配置"}"
                                            snackbarHostState.showSnackbar(testResult!!)
                                        }
                                    },
                                    enabled = !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalGreen.copy(alpha = 0.15f),
                                        contentColor = PetalGreen
                                    )
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PetalGreen,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.RefreshCw,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("测试连接", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }

                                Button(
                                    onClick = {

                                        if (isPreviewPlaying) {
                                            stopPreview()
                                            return@Button
                                        }

                                        val cacheKey = "${selectedProvider.name}|$selectedVoiceId|$sfCustomVoiceId|$mimoModel|$mimoVoiceClonePath|$mimoVoiceDesignPrompt|$mimoOptimizeTextPreview"
                                        if (previewAudioPath != null && previewCacheKey == cacheKey) {
                                            testResult = "重播中…"
                                            scope.launch { snackbarHostState.showSnackbar(testResult!!) }
                                            playPreview(previewAudioPath!!)
                                            return@Button
                                        }
                                        synthesizeAndPlay("合成成功，播放中…")
                                    },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalPrimaryContainer,
                                        contentColor = PetalOnPrimaryContainer
                                    )
                                ) {
                                    when {
                                        isSynthesizing -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = PetalOnPrimaryContainer,
                                                strokeWidth = 2.dp
                                            )
                                        }
                                        isPreviewPlaying -> {
                                            Icon(
                                                imageVector = AppIcons.Square,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("停止", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                        else -> {
                                            Icon(
                                                imageVector = AppIcons.Play,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("试听", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                }

                                Button(
                                    onClick = { synthesizeAndPlay("重新合成，播放中…") },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalPrimary.copy(alpha = 0.10f),
                                        contentColor = PetalPrimary
                                    )
                                ) {
                                    if (isSynthesizing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PetalPrimary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.RefreshCw,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("重新合成", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                            }

                            testResult?.let {
                                Text(
                                    text = it,
                                    fontSize = 13.sp,
                                    color = if (it.contains("成功")) PetalGreen else PetalError,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            ChatReadAloudSettingsCard(
                                chatTtsMode = chatTtsMode,
                                onModeSelect = { chatTtsMode = it; saveChatTtsSettings() },
                                showModeDropdown = showChatTtsModeDropdown,
                                onModeDropdownToggle = { showChatTtsModeDropdown = it },
                                skipParentheses = skipParentheses,
                                onSkipParenthesesChange = { skipParentheses = it; saveChatTtsSettings() },
                                autoDedup = chatTtsAutoDedup,
                                onAutoDedupChange = { chatTtsAutoDedup = it; saveChatTtsSettings() },
                                beautify = chatTtsBeautify,
                                onBeautifyChange = { chatTtsBeautify = it; saveChatTtsSettings() },
                                isDarkTheme = isDarkTheme,
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

