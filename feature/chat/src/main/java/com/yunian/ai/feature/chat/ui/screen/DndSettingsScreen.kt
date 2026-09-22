package com.yunian.ai.feature.chat.ui.screen

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatDetailSettingsViewModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatDetailSettingsViewModelFactory
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme
import java.util.Locale
import com.yunian.ai.uicommon.component.SettingsRow
import com.yunian.ai.uicommon.component.SettingsToggleRow

internal fun formatMinutesToTime(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0) % (24 * 60)
    return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
}

@Composable
fun DndSettingsScreen(
    companionId: Long,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext as Application }
    val settingsViewModel: ChatDetailSettingsViewModel = viewModel(
        factory = ChatDetailSettingsViewModelFactory(appContext, companionId)
    )
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val colors = AppTheme.colors
    val scrollState = rememberScrollState()

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "免打扰设置",
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {

            Spacer(modifier = Modifier.height(12.dp))

            DndSectionTitle("免打扰")
            DndSettingsCard {
                SettingsToggleRow(
                    title = "开启免打扰",
                    subtitle = "开启后，AI 将在设定时间段内暂停主动消息",
                    checked = settings.doNotDisturbEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(doNotDisturbEnabled = checked) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DndSectionTitle("时间段")
            DndSettingsCard {
                SettingsRow(
                    title = "开始时间",
                    subtitle = formatMinutesToTime(settings.dndStartMinutes)
                ) {
                    showStartPicker = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsRow(
                    title = "结束时间",
                    subtitle = formatMinutesToTime(settings.dndEndMinutes)
                ) {
                    showEndPicker = true
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "免打扰仅影响 AI 主动消息，不影响你主动聊天。\n" +
                    "若开始时间晚于结束时间（如 23:00 → 08:00），表示跨午夜生效；开始等于结束时表示不生效。",
                fontSize = 12.sp,
                color = colors.metadataContent,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showStartPicker) {
        DndTimePickerDialog(
            title = "选择开始时间",
            initialMinutes = settings.dndStartMinutes,
            onDismiss = { showStartPicker = false },
            onConfirm = { minutes ->
                settingsViewModel.updateSettings { it.copy(dndStartMinutes = minutes) }
                showStartPicker = false
            }
        )
    }
    if (showEndPicker) {
        DndTimePickerDialog(
            title = "选择结束时间",
            initialMinutes = settings.dndEndMinutes,
            onDismiss = { showEndPicker = false },
            onConfirm = { minutes ->
                settingsViewModel.updateSettings { it.copy(dndEndMinutes = minutes) }
                showEndPicker = false
            }
        )
    }
}

@Composable
private fun DndSectionTitle(text: String) {
    val colors = AppTheme.colors

    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.metadataContent,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun DndSettingsCard(content: @Composable () -> Unit) {
    val colors = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(12.dp),
                surfaceColor = colors.surface
            )
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DndTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = AppTheme.colors
    val timeState = rememberTimePickerState(
        initialHour = (initialMinutes / 60) % 24,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface
            )
        },
        text = {
            TimePicker(
                state = timeState,
                colors = TimePickerDefaults.colors(
                    clockDialColor = colors.surfaceVariant,
                    clockDialSelectedContentColor = colors.onPrimary,
                    clockDialUnselectedContentColor = colors.onSurfaceVariant,
                    selectorColor = colors.primary,
                    timeSelectorSelectedContainerColor = colors.primary,
                    timeSelectorUnselectedContainerColor = colors.surfaceVariant,
                    timeSelectorSelectedContentColor = colors.onPrimary,
                    timeSelectorUnselectedContentColor = colors.onSurfaceVariant,
                    containerColor = colors.surface
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }
            ) { Text("确定", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.metadataContent) }
        },
        containerColor = colors.surface
    )
}
