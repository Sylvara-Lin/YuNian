package com.yunian.ai.feature.chat.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.feature.chat.data.CompanionChatDetailSettings
import com.yunian.ai.feature.chat.ui.viewmodel.ChatDetailSettingsViewModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatDetailSettingsViewModelFactory
import com.yunian.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.yunian.ai.uicommon.component.ChatBackgroundPickerDialog
import com.yunian.ai.uicommon.component.chatBackgroundOptions
import com.yunian.ai.uicommon.component.getChatBackgroundKey
import com.yunian.ai.uicommon.component.isCustomBackground
import com.yunian.ai.uicommon.component.listCustomSolidColors
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.parseColorBackground
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.SettingsRow
import com.yunian.ai.uicommon.component.SettingsToggleRow

@Composable
fun ChatDetailScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDndSettings: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext as Application }

    val dialogScope = rememberCoroutineScope()
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(appContext, companionId)
    )
    val settingsViewModel: ChatDetailSettingsViewModel = viewModel(
        factory = ChatDetailSettingsViewModelFactory(appContext, companionId)
    )
    val companionData by viewModel.companionData.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val innerThoughtEnabled by settingsViewModel.innerThoughtEnabled.collectAsStateWithLifecycle()
    val globalImageGenEnabled by settingsViewModel.globalImageGenEnabled.collectAsStateWithLifecycle()

    var showBgPicker by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showFollowUpIntervalDialog by remember { mutableStateOf(false) }
    var showFollowUpMaxTimesDialog by remember { mutableStateOf(false) }
    var imageGenKeywordsDraft by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()
    val colors = AppTheme.colors

    GlassPageScaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.ArrowLeft, contentDescription = "返回", tint = colors.onBackground)
                }
                Text(
                    text = "聊天详情",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = colors.surface
                    )
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (companionData?.avatarUrl != null) {
                        AsyncImage(
                            model = companionData?.avatarUrl,
                            contentDescription = companionData?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = companionData?.name?.firstOrNull()?.toString() ?: "?",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.metadataContent
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = companionData?.name ?: "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                if (!companionData?.personality.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = companionData?.personality ?: "",
                        fontSize = 13.sp,
                        color = colors.metadataContent,
                        modifier = Modifier.padding(horizontal = 24.dp),
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (settings.blocked) {
                        StatusTag("已拉黑", colors.danger)
                    } else if (settings.doNotDisturbEnabled) {
                        StatusTag("免打扰", colors.warning)
                    } else if (!settings.proactiveEnabled) {
                        StatusTag("主动消息关闭", colors.metadataContent)
                    } else {
                        StatusTag("正常", colors.success)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("聊天外观")
            SettingsCard {
                val displayBgKey = if (settings.useGlobalBackground) {
                    getChatBackgroundKey(context)
                } else {
                    settings.backgroundKey
                }
                val bgSubtitle = if (settings.useGlobalBackground) {
                    "全局 · ${backgroundName(displayBgKey, context)}"
                } else {
                    backgroundName(displayBgKey, context)
                }
                SettingsRow(title = "当前聊天背景", subtitle = bgSubtitle) {
                    showBgPicker = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "使用全局背景",
                    checked = settings.useGlobalBackground,
                    onCheckedChange = { checked ->
                        settingsViewModel.setUseGlobalBackground(checked)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("主动消息")
            SettingsCard {
                SettingsToggleRow(
                    title = "允许主动发消息",
                    checked = settings.proactiveEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(proactiveEnabled = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsRow(title = "主动消息间隔", subtitle = intervalLabel(settings.proactiveIntervalMinutes)) {
                    showIntervalDialog = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "允许主动开启新话题",
                    subtitle = "关闭后更倾向延续近期话题；话题已完结时仍可自然收束，不会硬续",
                    checked = settings.allowNewTopic,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(allowNewTopic = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "允许深夜消息",
                    checked = settings.allowLateNightMessage,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(allowLateNightMessage = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "允许连续追问",
                    subtitle = "关闭后尽量少连环追问，仍服从角色性格",
                    checked = settings.allowFollowUpMessage,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(allowFollowUpMessage = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "未回复追问提醒",
                    subtitle = "AI 发消息后你长时间未回，会按间隔追问催促",
                    checked = settings.followUpReminderEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(followUpReminderEnabled = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsRow(
                    title = "追问间隔",
                    subtitle = intervalLabel(settings.followUpReminderIntervalMinutes),
                    enabled = settings.followUpReminderEnabled
                ) {
                    showFollowUpIntervalDialog = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsRow(
                    title = "追问次数上限",
                    subtitle = "${settings.followUpReminderMaxTimes} 次",
                    enabled = settings.followUpReminderEnabled
                ) {
                    showFollowUpMaxTimesDialog = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "显示心理活动",
                    subtitle = "AI回复中包含（脸红）（开心）等内心描写",
                    checked = innerThoughtEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.setInnerThoughtEnabled(checked)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsToggleRow(
                    title = "精确时间感知",
                    subtitle = "通过NTP网络校时获取精确时间，避免设备时钟不准",
                    checked = settings.ntpTimeEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(ntpTimeEnabled = checked) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("表情包")
            SettingsCard {
                SettingsSliderRow(
                    title = "AI发送表情包概率",
                    subtitle = "${settings.stickerProbability}%",
                    value = settings.stickerProbability / 100f,
                    onValueChange = { value ->
                        settingsViewModel.updateSettings {
                            it.copy(stickerProbability = (value * 100).toInt())
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("AI 生图")
            SettingsCard {
                SettingsToggleRow(
                    title = "本会话单独配置生图",
                    subtitle = when {
                        !globalImageGenEnabled -> "全局生图未开启，请先到「API 设置 → AI 生图」启用"
                        settings.imageGenOverrideEnabled -> "已覆盖全局设置"
                        else -> "当前跟随全局设置"
                    },
                    checked = settings.imageGenOverrideEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.setImageGenOverrideEnabled(checked)
                    }
                )
                if (settings.imageGenOverrideEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                    SettingsSliderRow(
                        title = "本会话生图概率",
                        subtitle = "${settings.imageGenTriggerProbability}%",
                        value = settings.imageGenTriggerProbability / 100f,
                        onValueChange = { value ->
                            settingsViewModel.setImageGenProbability((value * 100).toInt())
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "触发关键词",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "多个关键词用换行或逗号分隔，命中即无条件出图",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = imageGenKeywordsDraft ?: settings.imageGenKeywords,
                            onValueChange = { imageGenKeywordsDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) {
                                        imageGenKeywordsDraft?.let { draft ->
                                            settingsViewModel.setImageGenKeywords(draft)
                                        }
                                        imageGenKeywordsDraft = null
                                    }
                                },
                            placeholder = {
                                Text("如：画一张\n给我画", color = colors.onSurfaceVariant, fontSize = 13.sp)
                            },
                            minLines = 2,
                            maxLines = 5,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("免打扰")
            SettingsCard {
                SettingsToggleRow(
                    title = "免打扰",
                    checked = settings.doNotDisturbEnabled,
                    onCheckedChange = { checked ->
                        settingsViewModel.updateSettings { it.copy(doNotDisturbEnabled = checked) }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                SettingsRow(title = "免打扰时间段", subtitle = dndRangeLabel(settings)) {
                    onNavigateToDndSettings()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SectionTitle("关系与隐私")
            SettingsCard {
                if (settings.blocked) {
                    DangerRow(title = "取消拉黑", color = colors.success) {
                        settingsViewModel.updateSettings { it.copy(blocked = false) }
                    }
                } else {
                    DangerRow(title = "拉黑", color = colors.danger) {
                        showBlockConfirm = true
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                DangerRow(title = "清空聊天记录", color = colors.danger) {
                    showClearConfirm = true
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp), color = colors.outlineVariant)
                DangerRow(title = "重置聊天设置", color = colors.warning) {
                    showResetConfirm = true
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showBgPicker) {
        val currentKey = if (settings.useGlobalBackground) {
            getChatBackgroundKey(context)
        } else {
            settings.backgroundKey ?: "default"
        }
        ChatBackgroundPickerDialog(
            currentKey = currentKey,
            onDismiss = { showBgPicker = false },
            onSelect = { key ->
                dialogScope.launch {
                    settingsViewModel.selectBackground(key).join()
                    showBgPicker = false
                }
            }
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("确认拉黑") },
            text = { Text("拉黑后对方将不再主动发消息。你仍可以手动发送消息。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogScope.launch {
                            settingsViewModel.updateSettings { it.copy(blocked = true) }.join()
                            showBlockConfirm = false
                        }
                    }
                ) { Text("拉黑", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空") },
            text = { Text("清空后将无法恢复，确定要清空聊天记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {

                        viewModel.clearChatHistory()
                        showClearConfirm = false
                    }
                ) { Text("清空", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("确认重置") },
            text = { Text("所有聊天设置将恢复默认值，包括背景、主动消息、免打扰等。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogScope.launch {
                            settingsViewModel.resetSettings().join()
                            showResetConfirm = false
                        }
                    }
                ) { Text("重置", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showIntervalDialog) {
        IntervalInputDialog(
            currentMinutes = settings.proactiveIntervalMinutes,
            minMinutes = 1,
            maxMinutes = 1440,
            onDismiss = { showIntervalDialog = false },
            onConfirm = { minutes ->
                dialogScope.launch {
                    settingsViewModel.updateSettings {
                        it.copy(proactiveIntervalMinutes = minutes)
                    }.join()
                    showIntervalDialog = false
                }
            }
        )
    }

    if (showFollowUpIntervalDialog) {
        IntervalInputDialog(
            title = "追问间隔",
            hint = "设置AI发消息后你未回复时的追问间隔时间（分钟）",
            currentMinutes = settings.followUpReminderIntervalMinutes,
            minMinutes = 1,
            maxMinutes = 120,
            onDismiss = { showFollowUpIntervalDialog = false },
            onConfirm = { minutes ->
                dialogScope.launch {
                    settingsViewModel.updateSettings {
                        it.copy(followUpReminderIntervalMinutes = minutes)
                    }.join()
                    showFollowUpIntervalDialog = false
                }
            }
        )
    }

    if (showFollowUpMaxTimesDialog) {
        MaxTimesInputDialog(
            currentTimes = settings.followUpReminderMaxTimes,
            onDismiss = { showFollowUpMaxTimesDialog = false },
            onConfirm = { times ->
                dialogScope.launch {
                    settingsViewModel.updateSettings {
                        it.copy(followUpReminderMaxTimes = times)
                    }.join()
                    showFollowUpMaxTimesDialog = false
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
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
private fun SettingsCard(content: @Composable () -> Unit) {
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

@Composable
private fun SettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val colors = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontSize = 15.sp, color = colors.onSurface)
            Text(text = subtitle, fontSize = 14.sp, color = colors.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..1f,
            steps = 9,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.surfaceVariant
            )
        )
    }
}

@Composable
private fun DangerRow(title: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun backgroundName(key: String?, context: android.content.Context): String {
    if (key.isNullOrBlank() || key == "default") return "默认"
    chatBackgroundOptions(context).firstOrNull { it.key == key }?.let { return it.name }
    listCustomSolidColors(context).firstOrNull { it.key == key }?.let { return it.name }
    if (isCustomBackground(key)) return "自定义图片"
    if (parseColorBackground(key) != null) return "自定义纯色"
    return "自定义"
}

private fun intervalLabel(minutes: Int): String {
    return when {
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}

private fun dndRangeLabel(settings: CompanionChatDetailSettings): String {
    if (settings.dndStartMinutes == 0 && settings.dndEndMinutes == 0) return "未设置，点击设置"
    val label = "${formatMinutesToTime(settings.dndStartMinutes)} - ${formatMinutesToTime(settings.dndEndMinutes)}"
    return if (settings.dndStartMinutes > settings.dndEndMinutes) "$label（跨夜）" else label
}

@Composable
private fun IntervalInputDialog(
    title: String = "主动消息间隔",
    hint: String = "设置AI主动发消息的最小间隔时间（分钟）",
    currentMinutes: Int,
    minMinutes: Int,
    maxMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = AppTheme.colors

    var inputText by remember { mutableStateOf(currentMinutes.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }

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
            Column {
                Text(
                    text = hint,
                    fontSize = 13.sp,
                    color = colors.metadataContent
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { input ->
                        inputText = input
                        errorText = null
                    },
                    label = { Text("间隔（分钟）") },
                    suffix = { Text("分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it, color = colors.danger) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "范围：$minMinutes~$maxMinutes 分钟，当前：${intervalLabel(currentMinutes)}",
                    fontSize = 12.sp,
                    color = colors.metadataContent
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val minutes = inputText.toIntOrNull()
                    if (minutes == null) {
                        errorText = "请输入有效数字"
                    } else if (minutes < minMinutes) {
                        errorText = "最小间隔${minMinutes}分钟"
                    } else if (minutes > maxMinutes) {
                        errorText = "最大间隔$maxMinutes 分钟"
                    } else {
                        onConfirm(minutes)
                    }
                }
            ) { Text("确定", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.metadataContent) }
        },
        containerColor = colors.surface
    )
}

@Composable
private fun MaxTimesInputDialog(
    currentTimes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = AppTheme.colors

    var inputText by remember { mutableStateOf(currentTimes.toString()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "追问次数上限",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface
            )
        },
        text = {
            Column {
                Text(
                    text = "每条AI消息未回复时最多追问几次，达到上限后不再追问",
                    fontSize = 13.sp,
                    color = colors.metadataContent
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { input ->
                        inputText = input
                        errorText = null
                    },
                    label = { Text("次数") },
                    suffix = { Text("次") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorText != null,
                    supportingText = errorText?.let { { Text(it, color = colors.danger) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "范围：1~10 次，当前：$currentTimes 次",
                    fontSize = 12.sp,
                    color = colors.metadataContent
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val times = inputText.toIntOrNull()
                    if (times == null) {
                        errorText = "请输入有效数字"
                    } else if (times < 1) {
                        errorText = "最少1次"
                    } else if (times > 10) {
                        errorText = "最多10次"
                    } else {
                        onConfirm(times)
                    }
                }
            ) { Text("确定", color = colors.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.metadataContent) }
        },
        containerColor = colors.surface
    )
}
