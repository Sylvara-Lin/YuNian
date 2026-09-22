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
import android.provider.OpenableColumns
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
import com.yunian.ai.network.tts.MimoVoiceSampleFormat
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
internal fun ApiKeyConfigCard(
    provider: TtsProvider,
    aliyunKey: String,
    onAliyunKeyChange: (String) -> Unit,
    aliyunSecret: String,
    onAliyunSecretChange: (String) -> Unit,
    aliyunAppKey: String,
    onAliyunAppKeyChange: (String) -> Unit,
    baiduKey: String,
    onBaiduKeyChange: (String) -> Unit,
    baiduSecret: String,
    onBaiduSecretChange: (String) -> Unit,
    xunfeiAppId: String,
    onXunfeiAppIdChange: (String) -> Unit,
    xunfeiKey: String,
    onXunfeiKeyChange: (String) -> Unit,
    xunfeiSecret: String,
    onXunfeiSecretChange: (String) -> Unit,
    azureKey: String,
    onAzureKeyChange: (String) -> Unit,
    azureRegion: String,
    onAzureRegionChange: (String) -> Unit,
    volcengineAppId: String,
    onVolcengineAppIdChange: (String) -> Unit,
    volcengineToken: String,
    onVolcengineTokenChange: (String) -> Unit,
    volcengineCluster: String,
    onVolcengineClusterChange: (String) -> Unit,
    sfApiKey: String,
    onSfApiKeyChange: (String) -> Unit,
    sfCustomVoiceId: String,
    onSfCustomVoiceIdChange: (String) -> Unit,
    sfUseGlobalKey: Boolean,
    onSfUseGlobalKeyChange: (Boolean) -> Unit,
    sfTtsModel: String,
    onSfTtsModelChange: (String) -> Unit,
    sfSpeed: String,
    onSfSpeedChange: (String) -> Unit,
    sfGain: String,
    onSfGainChange: (String) -> Unit,
    sfSampleRate: Int,
    onSfSampleRateChange: (Int) -> Unit,
    customTtsUrl: String,
    onCustomTtsUrlChange: (String) -> Unit,
    customTtsApiKey: String,
    onCustomTtsApiKeyChange: (String) -> Unit,
    customTtsModel: String,
    onCustomTtsModelChange: (String) -> Unit,
    customTtsVoiceId: String,
    onCustomTtsVoiceIdChange: (String) -> Unit,
    customTtsResponseFormat: String,
    onCustomTtsResponseFormatChange: (String) -> Unit,
    mimoApiKey: String,
    onMimoApiKeyChange: (String) -> Unit,
    mimoBaseUrl: String,
    onMimoBaseUrlChange: (String) -> Unit,
    mimoModel: String,
    onMimoModelChange: (String) -> Unit,
    mimoVoiceId: String,
    onMimoVoiceIdChange: (String) -> Unit,
    mimoVoiceDesignPrompt: String,
    onMimoVoiceDesignPromptChange: (String) -> Unit,
    mimoVoiceClonePath: String,
    onMimoVoiceClonePathChange: (String) -> Unit,
    mimoOptimizeTextPreview: Boolean,
    onMimoOptimizeTextPreviewChange: (Boolean) -> Unit,
    showMimoModelDropdown: Boolean,
    onShowMimoModelDropdown: (Boolean) -> Unit,
    showSfModelDropdown: Boolean,
    onShowSfModelDropdown: (Boolean) -> Unit,
    showSfRateDropdown: Boolean,
    onShowSfRateDropdown: (Boolean) -> Unit,
    showCustomFormatDropdown: Boolean,
    onShowCustomFormatDropdown: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textTertiaryColor: Color
) {
    val dividerColor = AppTheme.colors.outline
    val context = LocalContext.current

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
            text = "${provider.displayName} 配置",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (provider) {
            TtsProvider.ALIYUN -> {
                TtsTextField(value = aliyunKey, onValueChange = onAliyunKeyChange, label = "Access Key ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = aliyunSecret, onValueChange = onAliyunSecretChange, label = "Access Key Secret", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = aliyunAppKey, onValueChange = onAliyunAppKeyChange, label = "App Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.BAIDU -> {
                TtsTextField(value = baiduKey, onValueChange = onBaiduKeyChange, label = "API Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = baiduSecret, onValueChange = onBaiduSecretChange, label = "Secret Key", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.XUNFEI -> {
                TtsTextField(value = xunfeiAppId, onValueChange = onXunfeiAppIdChange, label = "App ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = xunfeiKey, onValueChange = onXunfeiKeyChange, label = "API Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = xunfeiSecret, onValueChange = onXunfeiSecretChange, label = "API Secret", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.MICROSOFT -> {
                TtsTextField(value = azureKey, onValueChange = onAzureKeyChange, label = "Subscription Key", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = azureRegion, onValueChange = onAzureRegionChange, label = "Region (如: eastasia)", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.VOLCENGINE -> {
                TtsTextField(value = volcengineAppId, onValueChange = onVolcengineAppIdChange, label = "App ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = volcengineToken, onValueChange = onVolcengineTokenChange, label = "Token", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = volcengineCluster, onValueChange = onVolcengineClusterChange, label = "Cluster (可选)", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.SILICONFLOW -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("复用全局 SiliconFlow API Key", fontSize = 13.sp, color = textPrimaryColor)
                    Switch(checked = sfUseGlobalKey, onCheckedChange = onSfUseGlobalKeyChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = PetalPrimary))
                }
                if (!sfUseGlobalKey) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TtsTextField(value = sfApiKey, onValueChange = onSfApiKeyChange,
                        label = "API Key", isPassword = true, isDarkTheme = isDarkTheme,
                        dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("TTS 模型", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowSfModelDropdown(!showSfModelDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sfTtsModel.ifBlank { "未选择" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showSfModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showSfModelDropdown, onDismissRequest = { onShowSfModelDropdown(false) }) {
                        listOf(
                            "FunAudioLLM/CosyVoice2-0.5B" to "CosyVoice2 (推荐)",
                            "fnlp/MOSS-TTSD-v0.5" to "MOSS-TTSD"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                onSfTtsModelChange(value); onShowSfModelDropdown(false)
                            })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("采样率", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowSfRateDropdown(!showSfRateDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${sfSampleRate} Hz", fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showSfRateDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showSfRateDropdown, onDismissRequest = { onShowSfRateDropdown(false) }) {
                        listOf(8000, 16000, 22050, 44100).forEach { rate ->
                            DropdownMenuItem(text = { Text("$rate Hz") }, onClick = {
                                onSfSampleRateChange(rate); onShowSfRateDropdown(false)
                            })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("语速: ${sfSpeed}", fontSize = 13.sp, color = textSecondaryColor)
                Slider(
                    value = sfSpeed.toFloatOrNull() ?: 1.0f,
                    onValueChange = { onSfSpeedChange(String.format(Locale.US, "%.1f", it)) },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("增益: ${sfGain} dB", fontSize = 13.sp, color = textSecondaryColor)
                Slider(
                    value = sfGain.toFloatOrNull() ?: 0f,
                    onValueChange = { onSfGainChange(String.format("%.0f", it)) },
                    valueRange = -10f..10f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
                )

                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = sfCustomVoiceId, onValueChange = onSfCustomVoiceIdChange,
                    label = "自定义音色 (名称或 speech: URI)", isDarkTheme = isDarkTheme, dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "克隆音色：可填音色名称（如 dp_42824，自动解析 URI）或直接粘贴完整 URI（speech:...）",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("没有自定义音色？", fontSize = 12.sp, color = textSecondaryColor)
                    Text("前往添加>>", fontSize = 12.sp, color = PetalPrimary,
                        modifier = Modifier.clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            intent.data = android.net.Uri.parse("https://voice.gbkgov.cn/")
                            context.startActivity(intent)
                        })
                }
            }
            TtsProvider.MIMO -> {
                Text(
                    text = "MiMo TTS 走 /v1/chat/completions + audio 字段（非 OpenAI speech）",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = mimoBaseUrl,
                    onValueChange = onMimoBaseUrlChange,
                    label = "Base URL (https://api.xiaomimimo.com/v1)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = mimoApiKey,
                    onValueChange = onMimoApiKeyChange,
                    label = "API Key",
                    isPassword = true,
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("模型", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowMimoModelDropdown(!showMimoModelDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mimoModel.ifBlank { "未选择" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showMimoModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMimoModelDropdown,
                        onDismissRequest = { onShowMimoModelDropdown(false) },
                        modifier = Modifier.background(AppTheme.colors.surface)
                    ) {
                        listOf(
                            MiMoTtsProvider.MODEL_TTS to "预置精品音色 · 支持唱歌模式",
                            MiMoTtsProvider.MODEL_TTS_VOICEDESIGN to "文本描述定制音色",
                            MiMoTtsProvider.MODEL_TTS_VOICECLONE to "音频样本复刻音色"
                        ).forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    onMimoModelChange(id)
                                    onShowMimoModelDropdown(false)
                                },
                                leadingIcon = if (id == mimoModel) {
                                    { Icon(AppIcons.Check, null, tint = PetalGreen, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val mimoModelNote = when (MiMoTtsProvider.normalizeModel(mimoModel)) {
                    MiMoTtsProvider.MODEL_TTS_VOICEDESIGN ->
                        "通过文本描述定制音色；不支持唱歌模式、预置音色与音色复刻。"
                    MiMoTtsProvider.MODEL_TTS_VOICECLONE ->
                        "基于音频样本复刻音色；不支持唱歌模式、预置音色与音色设计。"
                    else ->
                        "支持唱歌模式（文本开头加 (唱歌) 标签）；不支持音色设计与音色复刻。"
                }
                Text(mimoModelNote, fontSize = 12.sp, color = textSecondaryColor)

                when (MiMoTtsProvider.normalizeModel(mimoModel)) {
                    MiMoTtsProvider.MODEL_TTS_VOICEDESIGN -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mimoVoiceDesignPrompt,
                            onValueChange = onMimoVoiceDesignPromptChange,
                            label = { Text("音色描述（必填，1-4 句）", color = textSecondaryColor) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = AppTheme.colors.surface,
                                unfocusedContainerColor = AppTheme.colors.surface,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            minLines = 3,
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "描述越具体越生动：性别年龄、音色质感、情绪语气、语速节奏等，支持中英文。不要写混响/回声等后期效果词，避免矛盾特征（如稚嫩童声 + 总裁气场）。",
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("智能润色合成文本", fontSize = 13.sp, color = textPrimaryColor)
                                Text("optimize_text_preview：让模型润色目标播报文本", fontSize = 11.sp, color = textSecondaryColor)
                            }
                            Switch(
                                checked = mimoOptimizeTextPreview,
                                onCheckedChange = onMimoOptimizeTextPreviewChange,
                                colors = SwitchDefaults.colors(checkedTrackColor = PetalPrimary)
                            )
                        }
                    }
                    MiMoTtsProvider.MODEL_TTS_VOICECLONE -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        var clonePickError by remember { mutableStateOf<String?>(null) }
                        var cloneSampleWarning by remember { mutableStateOf<String?>(null) }
                        var cloneSampleInfo by remember { mutableStateOf<MimoVoiceSampleFormat.WavInfo?>(null) }
                        val cloneScope = rememberCoroutineScope()
                        val cloneLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                cloneScope.launch {
                                    // 清理前仍在被引用的旧路径（读取于导入/持久化之前）。
                                    val previousPath = mimoVoiceClonePath
                                    val outcome = withContext(Dispatchers.IO) {
                                        importMimoCloneSample(context, uri)
                                    }
                                    when (outcome) {
                                        is MimoCloneImportResult.Success -> {
                                            // 先持久化新路径，再清理旧样本；并**保留 previousPath 指向的文件**。
                                            // 这样即便 saveSettings() 尚未落盘就崩溃，prefs 指向的旧文件依然存在，
                                            // 从根本上消除"prefs 指向已删文件"的时序窗口（不依赖落盘时序）。
                                            onMimoVoiceClonePathChange(outcome.path)
                                            withContext(Dispatchers.IO) {
                                                val newFile = File(outcome.path)
                                                val dir = newFile.parentFile ?: return@withContext
                                                cleanupOldCloneSamples(
                                                    dir = dir,
                                                    keepNames = setOfNotNull(
                                                        newFile.name,
                                                        previousPath.takeIf { it.isNotBlank() }?.let { File(it).name }
                                                    )
                                                )
                                            }
                                            clonePickError = null
                                        }
                                        is MimoCloneImportResult.Failure -> {
                                            clonePickError = outcome.message
                                        }
                                    }
                                }
                            }
                        }
                        // 路径变化（含冷启动读取已保存路径）时解析一次样本信息 / 非 PCM 校验。
                        LaunchedEffect(mimoVoiceClonePath) {
                            val path = mimoVoiceClonePath
                            if (path.isBlank()) {
                                cloneSampleInfo = null
                                cloneSampleWarning = null
                                return@LaunchedEffect
                            }
                            withContext(Dispatchers.IO) {
                                val file = File(path)
                                if (!file.extension.equals(MimoVoiceSampleFormat.WAV_EXT, ignoreCase = true)) {
                                    cloneSampleInfo = null
                                    cloneSampleWarning = null
                                    return@withContext
                                }
                                val header = runCatching {
                                    file.inputStream().use { readHeaderBytes(it, WAV_HEADER_PROBE_BYTES) }
                                }.getOrNull()
                                val info = header?.let { MimoVoiceSampleFormat.parseWav(it) }
                                cloneSampleInfo = info
                                cloneSampleWarning = when {
                                    info == null -> "无法解析样本信息（文件可能已损坏），仍可尝试合成"
                                    info.pcmStatus == MimoVoiceSampleFormat.WavInfo.PcmStatus.NOT_PCM ->
                                        "该 wav 使用非 PCM 编码（${info.formatName}），MiMo 可能无法识别，" +
                                            "建议用音频软件另存为 16 位 PCM wav 后重试"
                                    else -> null
                                }
                            }
                        }
                        Button(
                            onClick = { cloneLauncher.launch(arrayOf("audio/*", "application/octet-stream")) },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PetalPrimaryContainer,
                                contentColor = PetalOnPrimaryContainer
                            )
                        ) {
                            Text(
                                if (mimoVoiceClonePath.isBlank()) "选择音频样本" else "更换音频样本",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (mimoVoiceClonePath.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${File(mimoVoiceClonePath).name}",
                                    fontSize = 13.sp,
                                    color = PetalGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    deleteCloneSample(mimoVoiceClonePath)
                                    onMimoVoiceClonePathChange("")
                                    clonePickError = null
                                }) {
                                    Text("移除", fontSize = 12.sp, color = PetalError)
                                }
                            }
                            cloneSampleInfo?.let { info ->
                                Text(
                                    text = "样本：${info.describe()}",
                                    fontSize = 11.sp,
                                    color = textSecondaryColor
                                )
                            }
                        }
                        cloneSampleWarning?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 11.sp, color = PetalError)
                        }
                        clonePickError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 12.sp, color = PetalError)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "支持 mp3 / wav，样本编码后不超过 10MB（原始约 7.5MB），建议 30 秒以内的清晰语音。合成时读取该文件发送给 MiMo 复刻音色。",
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }
                    else -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        TtsTextField(
                            value = mimoVoiceId,
                            onValueChange = onMimoVoiceIdChange,
                            label = "自定义 voice (可选，留空使用上方预置音色)",
                            isDarkTheme = isDarkTheme,
                            dividerColor = dividerColor,
                            textPrimaryColor = textPrimaryColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }
            }
            TtsProvider.OPENAI_COMPAT -> {
                Text(
                    text = "兼容 OpenAI Audio Speech：POST /v1/audio/speech\n可填 Base URL（如 https://xxx/v1）或完整 speech 地址\n局域网自建服务可用 http://192.168.x.x:port/v1",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsUrl,
                    onValueChange = onCustomTtsUrlChange,
                    label = "Base URL / Speech URL",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsApiKey,
                    onValueChange = onCustomTtsApiKeyChange,
                    label = "API Key",
                    isPassword = true,
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsModel,
                    onValueChange = onCustomTtsModelChange,
                    label = "模型 (如 tts-1 / tts-1-hd)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsVoiceId,
                    onValueChange = onCustomTtsVoiceIdChange,
                    label = "voice (如 alloy / nova)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("response_format", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowCustomFormatDropdown(!showCustomFormatDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(customTtsResponseFormat.ifBlank { "mp3" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showCustomFormatDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showCustomFormatDropdown,
                        onDismissRequest = { onShowCustomFormatDropdown(false) }
                    ) {
                        listOf("mp3", "opus", "aac", "flac", "wav", "pcm").forEach { format ->
                            DropdownMenuItem(text = { Text(format) }, onClick = {
                                onCustomTtsResponseFormatChange(format)
                                onShowCustomFormatDropdown(false)
                            })
                        }
                    }
                }
            }
            TtsProvider.SHERPA_LOCAL -> {

            }
        }
    }
}

@Composable
internal fun TtsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    isDarkTheme: Boolean,
    dividerColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = textSecondaryColor) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PetalPrimary,
            unfocusedBorderColor = dividerColor,
            focusedContainerColor = AppTheme.colors.surface,
            unfocusedContainerColor = AppTheme.colors.surface,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true
    )
}

/** 读取 WAV 头部用于解析 fmt/data chunk 的最大探测字节数。 */
private const val WAV_HEADER_PROBE_BYTES = 64 * 1024

/** MiMo 音色复刻样本导入结果。 */
private sealed interface MimoCloneImportResult {
    data class Success(val path: String) : MimoCloneImportResult
    data class Failure(val message: String) : MimoCloneImportResult
}

/** 被选文件的元信息（来自 ContentResolver 查询，可能缺失）。 */
private data class MimoPickedFileMeta(val displayName: String?, val size: Long?)

/**
 * 导入 MiMo 音色复刻样本为应用私有文件。
 *
 * 流程：查询声明 MIME / 文件名 → 边复制边按体积上限限流 → 读取落盘文件头做魔数嗅探
 * （与声明的 MIME/扩展名冲突时以魔数为准）→ 按真实格式保存为带版本名的 `mimo_clone_sample_<epochMillis>.<ext>`。
 *
 * 注意：本函数**不做清理**。旧样本的清理由调用方在持久化新路径之后执行
 * （见 `cleanupOldCloneSamples`），以保证 `mimo_voice_clone_path` 始终指向存在的文件。
 */
private fun importMimoCloneSample(context: Context, uri: Uri): MimoCloneImportResult {
    val meta = queryFileMeta(context, uri)
    val declaredMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    // 元信息可用时先行拒绝超大文件，避免无谓复制。
    if (meta.size != null && meta.size > 0L && MimoVoiceSampleFormat.exceedsCloneLimit(meta.size)) {
        return MimoCloneImportResult.Failure(MimoVoiceSampleFormat.tooLargeMessage(meta.size))
    }

    val targetDir = File(context.filesDir, "tts_mimo_clone").apply { mkdirs() }
    // tmp 复用 mimo_clone_sample 前缀（不同扩展名），使崩溃残留也能被 cleanupOldCloneSamples 命中；不会与真实样本冲突。
    val tmpFile = File(targetDir, "mimo_clone_sample.tmp")
    var total = 0L
    var tooLarge = false
    try {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return MimoCloneImportResult.Failure("无法读取所选文件，请换一个音频样本")
        stream.use { input ->
            tmpFile.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (MimoVoiceSampleFormat.exceedsCloneLimit(total)) {
                        tooLarge = true
                        break
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
    } catch (e: Exception) {
        tmpFile.delete()
        return MimoCloneImportResult.Failure("复制样本失败：${e.message ?: e.javaClass.simpleName}")
    }

    if (tooLarge) {
        tmpFile.delete()
        return MimoCloneImportResult.Failure(MimoVoiceSampleFormat.tooLargeMessage(meta.size ?: total))
    }
    if (total <= 0L) {
        tmpFile.delete()
        return MimoCloneImportResult.Failure("音频样本为空")
    }

    // 落盘后读取文件头，以魔数为准复核真实格式。
    val header = runCatching {
        tmpFile.inputStream().use { readHeaderBytes(it, MimoVoiceSampleFormat.SNIFF_BYTES) }
    }.getOrNull() ?: ByteArray(0)
    val format = MimoVoiceSampleFormat.detect(header, declaredMime, meta.displayName)
    if (format == null) {
        tmpFile.delete()
        return MimoCloneImportResult.Failure(
            MimoVoiceSampleFormat.unsupportedMessage(declaredMime, meta.displayName)
        )
    }

    // 带版本文件名：同格式替换时路径也会变化，从而刷新 UI 信息与试听缓存，并顺带避免孤儿样本。
    val outFile = File(targetDir, "${MIMO_CLONE_SAMPLE_PREFIX}_${System.currentTimeMillis()}.${format.ext}")
    outFile.delete()
    if (!tmpFile.renameTo(outFile)) {
        val copied = runCatching {
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }.isSuccess
        if (!copied || !outFile.exists()) {
            tmpFile.delete()
            outFile.delete()
            return MimoCloneImportResult.Failure("保存音频样本失败")
        }
    }
    return MimoCloneImportResult.Success(outFile.absolutePath)
}

/** MiMo 复刻样本文件名前缀（旧固定名、新版本名与崩溃残留 tmp 共用此前缀，便于清理）。 */
private const val MIMO_CLONE_SAMPLE_PREFIX = "mimo_clone_sample"

/**
 * 删除同目录下不再需要的 MiMo 复刻样本（`mimo_clone_sample*`，含崩溃残留的 `*.tmp`）。
 *
 * best-effort，绝不抛异常。
 *
 * @param keepNames 必须**保留**的文件名集合：应包含新样本名，以及在清理发生时
 *   `mimo_voice_clone_path` 仍可能指向的旧样本名。调用方须在**持久化新路径之后**调用本函数，
 *   并保证 [keepNames] 覆盖所有可能被 prefs 引用的文件，从而不出现"prefs 指向已删文件"的窗口。
 */
internal fun cleanupOldCloneSamples(dir: File, keepNames: Set<String>) {
    runCatching {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keepNames && file.name.startsWith(MIMO_CLONE_SAMPLE_PREFIX)) {
                runCatching { file.delete() }
            }
        }
    }
}

/** 删除指定样本文件本体（best-effort，绝不抛异常）。 */
private fun deleteCloneSample(path: String) {
    if (path.isBlank()) return
    runCatching {
        val file = File(path)
        if (file.exists()) file.delete()
    }
}

/** 查询被选文件的显示名与大小；query 返回 null 等异常情况回退到 [Uri.lastPathSegment]。 */
private fun queryFileMeta(context: Context, uri: Uri): MimoPickedFileMeta {
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val fromResolver = runCatching {
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst()) {
                null
            } else {
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
                MimoPickedFileMeta(name, size)
            }
        }
    }.getOrNull()
    return fromResolver ?: MimoPickedFileMeta(uri.lastPathSegment, null)
}

/** 读取最多 [maxBytes] 字节头部（用于魔数嗅探 / WAV 信息解析）。 */
private fun readHeaderBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var read = 0
    while (read < maxBytes) {
        val n = input.read(buffer, read, maxBytes - read)
        if (n <= 0) break
        read += n
    }
    return buffer.copyOf(read)
}

