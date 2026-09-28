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
internal fun ChatReadAloudSettingsCard(
    chatTtsMode: ChatTtsMode,
    onModeSelect: (ChatTtsMode) -> Unit,
    showModeDropdown: Boolean,
    onModeDropdownToggle: (Boolean) -> Unit,
    skipParentheses: Boolean,
    onSkipParenthesesChange: (Boolean) -> Unit,
    autoDedup: Boolean,
    onAutoDedupChange: (Boolean) -> Unit,
    beautify: Boolean,
    onBeautifyChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {

    @Suppress("UNUSED_PARAMETER")
    val unusedAutoDedup = autoDedup
    @Suppress("UNUSED_PARAMETER")
    val unusedOnAutoDedup = onAutoDedupChange

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "聊天页语音",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = "语音条模式会在 AI 回复入库时合成音频，单条消息同时显示语音条和文字；重进聊天不会重复合成",
            fontSize = 12.sp,
            color = textSecondaryColor
        )

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onModeDropdownToggle(!showModeDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "语音模式",
                        fontSize = 14.sp,
                        color = textPrimaryColor
                    )
                    Text(
                        text = chatTtsMode.displayName + " · " + chatTtsMode.description,
                        fontSize = 12.sp,
                        color = textSecondaryColor
                    )
                }
                Icon(
                    imageVector = if (showModeDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showModeDropdown,
                onDismissRequest = { onModeDropdownToggle(false) },
                modifier = Modifier.background(AppTheme.colors.surface)
            ) {
                ChatTtsMode.selectableModes.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(mode.displayName, fontSize = 14.sp, color = textPrimaryColor)
                                Text(mode.description, fontSize = 12.sp, color = textSecondaryColor)
                            }
                        },
                        onClick = { onModeSelect(mode) },
                        leadingIcon = {
                            if (mode == chatTtsMode) {
                                Icon(AppIcons.Check, contentDescription = null, tint = PetalGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("跳过括号内心戏", fontSize = 14.sp, color = textPrimaryColor)
                Text("合成时跳过 <...> (...) （...） 内的内容", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = skipParentheses,
                onCheckedChange = onSkipParenthesesChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppTheme.colors.onPrimary,
                    checkedTrackColor = AppTheme.colors.primaryContainer,
                    uncheckedThumbColor = AppTheme.colors.outline,
                    uncheckedTrackColor = AppTheme.colors.surfaceVariant
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("音频美化", fontSize = 14.sp, color = textPrimaryColor)
                Text("点击语音条播放时使用均衡器预设（部分设备不支持）", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = beautify,
                onCheckedChange = onBeautifyChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppTheme.colors.onPrimary,
                    checkedTrackColor = AppTheme.colors.primaryContainer,
                    uncheckedThumbColor = AppTheme.colors.outline,
                    uncheckedTrackColor = AppTheme.colors.surfaceVariant
                )
            )
        }
    }
}

