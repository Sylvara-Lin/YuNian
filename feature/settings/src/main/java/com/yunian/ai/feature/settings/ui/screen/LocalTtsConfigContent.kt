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
internal fun LocalTtsConfigContent(
    localTtsState: LocalTtsUiState,
    localTtsSpeed: Float,
    onLocalTtsSpeedChange: (Float) -> Unit,
    localTtsSid: Int,
    onLocalTtsSidChange: (Int) -> Unit,
    showModelDropdown: Boolean,
    onShowModelDropdown: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    context: Context
) {
    val model = localTtsState.model
    val status = localTtsState.status
    val isDownloading = status == LocalTtsUiStatus.DOWNLOADING
    val isReady = status == LocalTtsUiStatus.READY
    val isEnabled = status == LocalTtsUiStatus.ENABLED
    val canDownload = model.files.any { it.downloadUrl.isNotBlank() } && !isDownloading && !isEnabled

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Text("本地模型", fontSize = 13.sp, color = textSecondaryColor)
        Box {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onShowModelDropdown(!showModelDropdown) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(model.displayName, fontSize = 14.sp, color = textPrimaryColor)
                Icon(
                    if (showModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = showModelDropdown, onDismissRequest = { onShowModelDropdown(false) }) {
                LocalTtsCatalog.all.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.displayName, fontSize = 14.sp) },
                        onClick = { onSelectModel(m.id); onShowModelDropdown(false) },
                        leadingIcon = if (m.id == model.id) {
                            { Icon(AppIcons.Check, null, tint = PetalGreen, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }

        val statusText = when (status) {
            LocalTtsUiStatus.NOT_DOWNLOADED -> "未下载"
            LocalTtsUiStatus.DOWNLOADING -> {
                val pct = localTtsState.progressPercent
                val fi = localTtsState.currentFileIndex + 1
                val tot = localTtsState.totalFiles
                "下载中 $pct% ($fi/$tot)"
            }
            LocalTtsUiStatus.READY -> "就绪（点击启用）"
            LocalTtsUiStatus.ENABLED -> "已启用"
            LocalTtsUiStatus.FAILED -> "失败: ${localTtsState.errorMessage ?: "未知"}"
        }
        val statusColor = when (status) {
            LocalTtsUiStatus.ENABLED -> PetalGreen
            LocalTtsUiStatus.READY -> PetalPrimary
            LocalTtsUiStatus.FAILED -> PetalError
            LocalTtsUiStatus.DOWNLOADING -> textSecondaryColor
            LocalTtsUiStatus.NOT_DOWNLOADED -> textSecondaryColor
        }
        Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canDownload) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalPrimaryContainer, contentColor = PetalOnPrimaryContainer)
                ) { Text("下载", fontSize = 13.sp) }
            }
            if (isDownloading) {
                Button(
                    onClick = onCancelDownload,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalError.copy(alpha = 0.15f), contentColor = PetalError)
                ) { Text("取消", fontSize = 13.sp) }
            }
            if (isReady) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalGreen.copy(alpha = 0.15f), contentColor = PetalGreen)
                ) { Text("启用", fontSize = 13.sp) }
            }
            if (isEnabled) {
                Button(
                    onClick = onDisable,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceVariant, contentColor = textPrimaryColor)
                ) { Text("禁用", fontSize = 13.sp) }
            }
            if (status != LocalTtsUiStatus.NOT_DOWNLOADED && !isDownloading) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalError.copy(alpha = 0.1f), contentColor = PetalError)
                ) { Text("删除", fontSize = 13.sp) }
            }
        }

        if (status == LocalTtsUiStatus.NOT_DOWNLOADED && model.files.all { it.downloadUrl.isBlank() }) {
            Text(
                text = "未配置下载源。请手动将模型文件放入：\n${model.modelDir(context).absolutePath}",
                fontSize = 12.sp, color = textSecondaryColor
            )
        }

        if (model.numSpeakers > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("音色 sid: $localTtsSid / ${model.numSpeakers - 1}", fontSize = 13.sp, color = textSecondaryColor)
            Slider(
                value = localTtsSid.toFloat(),
                onValueChange = { onLocalTtsSidChange(it.toInt()) },
                valueRange = 0f..(model.numSpeakers - 1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("语速: ${String.format(Locale.US, "%.1f", localTtsSpeed)}", fontSize = 13.sp, color = textSecondaryColor)
        Slider(
            value = localTtsSpeed,
            onValueChange = { onLocalTtsSpeedChange(String.format(Locale.US, "%.1f", it).toFloat()) },
            valueRange = 0.5f..2.0f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "sherpa-onnx 端上推理，无需联网。\n模型文件需放入上述目录后点击「启用」。",
            fontSize = 12.sp, color = textSecondaryColor
        )
    }
}

