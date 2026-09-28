package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.common.FrameRateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.BackgroundPermissionsCard
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import com.yunian.ai.uicommon.theme.WeChatDarkDivider
import com.yunian.ai.uicommon.theme.WeChatDarkTextPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkTextSecondary
import com.yunian.ai.uicommon.theme.WeChatLightBackground
import com.yunian.ai.uicommon.theme.WeChatLightDivider
import com.yunian.ai.uicommon.theme.WeChatLightTextPrimary
import com.yunian.ai.uicommon.theme.WeChatLightTextSecondary
import androidx.compose.foundation.isSystemInDarkTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

data class SettingsItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    onNavigateBack: () -> Unit,
    onLanguageClick: () -> Unit,
    onFrameRateClick: () -> Unit,
    onCheckUpdateClick: () -> Unit,
    onTtsSettingsClick: () -> Unit = {},
    onTokenUsageClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val backgroundColor = if (isDark) WeChatDarkBackground else WeChatLightBackground
    val cardColor = if (isDark) WeChatDarkCard else AppTheme.colors.staticWhite
    val textPrimary = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDark) WeChatDarkTextSecondary else WeChatLightTextSecondary
    val dividerColor = if (isDark) WeChatDarkDivider else WeChatLightDivider

    val currentRate = FrameRateManager.getSavedFrameRate(context)
    val thinkingViewModel: ThinkingSettingsViewModel = viewModel()
    val scope = rememberCoroutineScope()

    val showReasoning by thinkingViewModel.showReasoning.collectAsStateWithLifecycle()
    val sendReasoning by thinkingViewModel.sendReasoning.collectAsStateWithLifecycle()
    val autoCollapse by thinkingViewModel.autoCollapseReasoning.collectAsStateWithLifecycle()
    val respField by thinkingViewModel.responseField.collectAsStateWithLifecycle()
    val reqField by thinkingViewModel.requestField.collectAsStateWithLifecycle()

    var showReasoningDialog by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.app_settings_title),
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            SettingsGroup(
                items = listOf(
                    SettingsItemData(
                        icon = AppIcons.Languages,
                        title = stringResource(R.string.language),
                        subtitle = stringResource(R.string.language_desc),
                        onClick = onLanguageClick
                    ),
                    SettingsItemData(
                        icon = AppIcons.RefreshCw,
                        title = stringResource(R.string.framerate),
                        subtitle = currentRate.label,
                        onClick = onFrameRateClick
                    ),
                    SettingsItemData(
                        icon = AppIcons.MicVocal,
                        title = "TTS 语音设置",
                        subtitle = "配置语音合成服务",
                        onClick = onTtsSettingsClick
                    ),
                    SettingsItemData(
                        icon = AppIcons.KeyRound,
                        title = "Token 使用统计",
                        subtitle = "查看AI对话Token消耗情况",
                        onClick = onTokenUsageClick
                    )
                ),
                isDark = isDark,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                dividerColor = dividerColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsGroup(
                items = listOf(
                    SettingsItemData(
                        icon = AppIcons.Brain,
                        title = "思考设置",
                        subtitle = if (showReasoning) "已启用思考过程显示" else "思考过程显示已关闭",
                        onClick = { showReasoningDialog = true }
                    )
                ),
                isDark = isDark,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                dividerColor = dividerColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            SettingsGroup(
                items = listOf(
                    SettingsItemData(
                        icon = AppIcons.RefreshCw,
                        title = stringResource(R.string.check_new_version),
                        subtitle = stringResource(R.string.check_new_version_desc),
                        onClick = onCheckUpdateClick
                    )
                ),
                isDark = isDark,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                dividerColor = dividerColor
            )

            Spacer(modifier = Modifier.height(16.dp))

            BackgroundPermissionsCard(
                isVisible = isVisible,
                textPrimaryColor = textPrimary,
                textSecondaryColor = textSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showReasoningDialog) {
        var localShow by remember { mutableStateOf(showReasoning) }
        var localSend by remember { mutableStateOf(sendReasoning) }
        var localCollapse by remember { mutableStateOf(autoCollapse) }
        var localResp by remember { mutableStateOf(respField) }
        var localReq by remember { mutableStateOf(reqField) }
        var isSaving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isSaving) showReasoningDialog = false
            },
            title = { Text("思考设置") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启用思考过程显示")
                        Switch(
                            checked = localShow,
                            onCheckedChange = { localShow = it },
                            enabled = !isSaving
                        )
                    }
                    if (localShow) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("思考完成时自动折叠")
                            Switch(
                                checked = localCollapse,
                                onCheckedChange = { localCollapse = it },
                                enabled = !isSaving
                            )
                        }
                        OutlinedTextField(
                            value = localResp,
                            onValueChange = { localResp = it },
                            label = { Text("响应字段名") },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                        OutlinedTextField(
                            value = localReq,
                            onValueChange = { localReq = it },
                            label = { Text("请求字段名") },
                            enabled = !isSaving,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("发送思考内容")
                            Switch(
                                checked = localSend,
                                onCheckedChange = { localSend = it },
                                enabled = !isSaving
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        if (isSaving) return@TextButton
                        isSaving = true
                        scope.launch {
                            try {

                                thinkingViewModel.saveThinkingSettings(
                                    showReasoning = localShow,
                                    autoCollapseReasoning = localCollapse,
                                    responseField = localResp,
                                    requestField = localReq,
                                    sendReasoning = localSend
                                ).join()
                                showReasoningDialog = false
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                ) {
                    Text(if (isSaving) "保存中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = { showReasoningDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun SettingsGroup(
    items: List<SettingsItemData>,
    isDark: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    dividerColor: Color
) {
    val cardColor = if (isDark) WeChatDarkCard else AppTheme.colors.staticWhite
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = item.onClick)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isDark) Color(0xFF3A3A3C) else Color(0xFFF2F2F7)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFF8E8E93) else Color(0xFF8E8E93),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        ),
                        color = textPrimary
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = textSecondary
                    )
                }

                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (index < items.size - 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 64.dp)
                        .height(0.5.dp)
                        .background(dividerColor)
                )
            }
        }
    }
}
