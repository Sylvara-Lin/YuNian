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
internal fun TtsToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "启用 TTS 语音",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimaryColor
            )
            Text(
                text = "开启后 AI 回复将使用语音播放",
                fontSize = 12.sp,
                color = textSecondaryColor
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.colors.onPrimary,
                checkedTrackColor = AppTheme.colors.primaryContainer,
                uncheckedThumbColor = AppTheme.colors.outline,
                uncheckedTrackColor = AppTheme.colors.surfaceVariant
            )
        )
    }
}

@Composable
internal fun ProviderSelectionCard(
    selectedProvider: TtsProvider,
    onProviderSelect: (TtsProvider) -> Unit,
    showDropdown: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
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
            text = "语音提供商",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = "选择合成引擎，不同提供商音色与配置不同",
            fontSize = 12.sp,
            color = textSecondaryColor,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProvider.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = selectedProvider.description,
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (showDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(AppTheme.colors.surface)
            ) {
                TtsProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = provider.displayName,
                                    fontSize = 14.sp,
                                    color = textPrimaryColor
                                )
                                Text(
                                    text = provider.description,
                                    fontSize = 12.sp,
                                    color = textSecondaryColor
                                )
                            }
                        },
                        onClick = {
                            onProviderSelect(provider)
                            onDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (provider == selectedProvider) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    tint = PetalGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun VoiceSelectionCard(
    voices: List<TtsVoice>,
    selectedVoiceId: String,
    onVoiceSelect: (String) -> Unit,
    showDropdown: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val selectedVoice = voices.find { it.id == selectedVoiceId }
    val triggerEnabled = voices.isNotEmpty()

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
            text = "选择音色",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = if (voices.isEmpty()) "当前提供商暂无可用音色" else "从下拉列表选择合成音色",
            fontSize = 12.sp,
            color = textSecondaryColor,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (triggerEnabled) AppTheme.colors.surface
                        else AppTheme.colors.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .clickable(enabled = triggerEnabled) { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedVoice?.let { "${it.name}（${it.gender}）" }
                            ?: if (voices.isEmpty()) "暂无可用音色" else "请选择音色",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedVoice != null) textPrimaryColor else textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = selectedVoice?.let { voice ->
                        buildString {
                            if (voice.language.isNotBlank()) append(voice.language)
                            if (voice.description.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(voice.description)
                            }
                        }
                    }.orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            fontSize = 12.sp,
                            color = textSecondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (showDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown && triggerEnabled,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(AppTheme.colors.surface)
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "${voice.name}（${voice.gender}）",
                                    fontSize = 14.sp,
                                    color = textPrimaryColor
                                )
                                val detail = buildString {
                                    if (voice.language.isNotBlank()) append(voice.language)
                                    if (voice.description.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(voice.description)
                                    }
                                }
                                if (detail.isNotBlank()) {
                                    Text(
                                        text = detail,
                                        fontSize = 12.sp,
                                        color = textSecondaryColor
                                    )
                                }
                            }
                        },
                        onClick = {
                            onVoiceSelect(voice.id)
                            onDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (voice.id == selectedVoiceId) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    tint = PetalGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

