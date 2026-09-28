package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.common.FrameRateManager
import com.yunian.ai.uicommon.component.bounceVerticalScroll
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onNavigateBack: () -> Unit,
    onLanguageClick: () -> Unit = {},
    onFrameRateClick: () -> Unit = {},
    onTtsSettingsClick: () -> Unit = {},
    onTokenUsageClick: () -> Unit = {},
    onCheckUpdateClick: () -> Unit = {},
    onWeChatClick: () -> Unit = {},
    onQQBotClick: () -> Unit = {},
    onDataBackupClick: () -> Unit = {},
    onOriginOSAdaptionClick: () -> Unit = {},
    onCoffeeClick: () -> Unit = {},
    onExperimentalFeaturesClick: () -> Unit = {},
    onGeneralCategoryClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onAboutYuNianClick: () -> Unit = {},
    onToolsClick: () -> Unit = {}
) {
    val colorScheme = AppTheme.colors

    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.general_settings),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 128f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SettingsCategoryList(
                items = listOf(
                    MenuItemData(
                        AppIcons.Settings,
                        stringResource(R.string.settings_category_general),
                        stringResource(R.string.settings_category_general_desc),
                        onGeneralCategoryClick
                    ),
                    MenuItemData(
                        AppIcons.FlaskConical,
                        stringResource(R.string.experimental_features),
                        stringResource(R.string.experimental_features_desc),
                        onExperimentalFeaturesClick
                    ),
                    MenuItemData(
                        AppIcons.Coffee,
                        stringResource(R.string.settings_category_tools),
                        stringResource(R.string.settings_category_tools_desc),
                        onToolsClick
                    ),
                    MenuItemData(
                        AppIcons.ShieldCheck,
                        stringResource(R.string.settings_category_permissions),
                        stringResource(R.string.settings_category_permissions_desc),
                        onPermissionsClick
                    ),
                    MenuItemData(
                        AppIcons.Info,
                        stringResource(R.string.settings_category_about),
                        stringResource(R.string.settings_category_about_desc),
                        onAboutYuNianClick
                    )
                )
            )

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralCategoryScreen(
    onNavigateBack: () -> Unit,
    onLanguageClick: () -> Unit,
    onFrameRateClick: () -> Unit,
    onTtsSettingsClick: () -> Unit,
    onTokenUsageClick: () -> Unit,
    onWeChatClick: () -> Unit,
    onQQBotClick: () -> Unit,
    onDataBackupClick: () -> Unit,
    onOriginOSAdaptionClick: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors
    val currentFrameRate = FrameRateManager.getSavedFrameRate(context)

    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.settings_category_general),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 128f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsCategoryList(
                items = listOf(
                    MenuItemData(
                        AppIcons.Languages,
                        stringResource(R.string.language),
                        stringResource(R.string.language_desc),
                        onLanguageClick
                    ),
                    MenuItemData(
                        AppIcons.RefreshCw,
                        stringResource(R.string.framerate),
                        currentFrameRate.label,
                        onFrameRateClick
                    ),
                    ThinkingSettingsEntry(),
                    MenuItemData(
                        AppIcons.MicVocal,
                        stringResource(R.string.tts_settings),
                        stringResource(R.string.tts_settings_desc),
                        onTtsSettingsClick
                    ),
                    MenuItemData(
                        AppIcons.Download,
                        stringResource(R.string.data_backup),
                        stringResource(R.string.data_backup_desc),
                        onDataBackupClick
                    ),
                    MenuItemData(
                        AppIcons.MessageCircle,
                        stringResource(R.string.wechat_settings),
                        stringResource(R.string.wechat_settings_desc),
                        onWeChatClick
                    ),
                    MenuItemData(
                        AppIcons.MessageCircle,
                        stringResource(R.string.qqbot_settings),
                        stringResource(R.string.qqbot_settings_desc),
                        onQQBotClick
                    ),
                    MenuItemData(
                        AppIcons.KeyRound,
                        stringResource(R.string.token_usage),
                        stringResource(R.string.token_usage_desc),
                        onTokenUsageClick
                    ),
                    MenuItemData(
                        AppIcons.SlidersHorizontal,
                        stringResource(R.string.originos_adaption),
                        stringResource(R.string.originos_adaption_desc),
                        onOriginOSAdaptionClick
                    )
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            TypingSpinnerSettingCard()
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun TypingSpinnerSettingCard() {
    val viewModel: TypingSpinnerSettingsViewModel = viewModel()
    val showTypingSpinner by viewModel.showTypingSpinner.collectAsStateWithLifecycle()
    val colorScheme = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "回复时显示转圈动画",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface
                )
                Text(
                    "AI回复期间顶栏显示转圈；关闭后仅显示“对方正在输入”",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = showTypingSpinner,
                onCheckedChange = viewModel::setShowTypingSpinner
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsSettingsScreen(
    onNavigateBack: () -> Unit,
    onCoffeeClick: () -> Unit,
    onAutomationClick: () -> Unit = {}
) {
    val colorScheme = AppTheme.colors
    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.settings_category_tools),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 96f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsCategoryList(
                items = listOf(
                    MenuItemData(
                        AppIcons.Coffee,
                        stringResource(R.string.coffee_title),
                        stringResource(R.string.coffee_desc),
                        onCoffeeClick
                    ),
                    MenuItemData(
                        AppIcons.AlarmClock,
                        "自动化",
                        "AI 设置的定时提醒与自动化任务",
                        onAutomationClick
                    )
                )
            )
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val colorScheme = AppTheme.colors
    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.settings_category_permissions),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 96f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            PermissionSettingsCard()
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutYuNianSettingsScreen(
    onNavigateBack: () -> Unit,
    onCheckUpdateClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.settings_category_about),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.28f, maxOverscrollDp = 96f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsCategoryList(
                items = listOf(
                    MenuItemData(
                        AppIcons.RefreshCw,
                        stringResource(R.string.check_new_version),
                        stringResource(R.string.check_new_version_desc),
                        onCheckUpdateClick
                    )
                )
            )
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
internal fun SettingsCategoryList(items: List<MenuItemData>) {
    val cs = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = cs.surfaceVariant
            )
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        items.forEachIndexed { index, item ->
            SolidMenuItem(
                icon = item.icon,
                title = item.title,
                subtitle = item.subtitle,
                onClick = item.onClick,
                showDivider = index < items.lastIndex
            )
        }
    }
}

@Composable
private fun ThinkingSettingsEntry(): MenuItemData {

    val viewModel: ThinkingSettingsViewModel = viewModel()
    val showReasoning by viewModel.showReasoning.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        ThinkingSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showDialog = false }
        )
    }

    return MenuItemData(
        icon = AppIcons.Brain,
        title = stringResource(R.string.thinking_settings),
        subtitle = if (showReasoning) {
            stringResource(R.string.thinking_enabled)
        } else {
            stringResource(R.string.thinking_disabled)
        },
        onClick = { showDialog = true }
    )
}

@Composable
private fun ThinkingSettingsDialog(
    viewModel: ThinkingSettingsViewModel,
    onDismiss: () -> Unit
) {

    val scope = rememberCoroutineScope()
    val showReasoning by viewModel.showReasoning.collectAsStateWithLifecycle()
    val sendReasoning by viewModel.sendReasoning.collectAsStateWithLifecycle()
    val autoCollapse by viewModel.autoCollapseReasoning.collectAsStateWithLifecycle()
    val respField by viewModel.responseField.collectAsStateWithLifecycle()
    val reqField by viewModel.requestField.collectAsStateWithLifecycle()

    var localShow by remember { mutableStateOf(showReasoning) }
    var localSend by remember { mutableStateOf(sendReasoning) }
    var localCollapse by remember { mutableStateOf(autoCollapse) }
    var localResp by remember { mutableStateOf(respField) }
    var localReq by remember { mutableStateOf(reqField) }
    var isSaving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            if (!isSaving) onDismiss()
        },
        title = { Text(stringResource(R.string.thinking_settings)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                            viewModel.saveThinkingSettings(
                                showReasoning = localShow,
                                autoCollapseReasoning = localCollapse,
                                responseField = localResp,
                                requestField = localReq,
                                sendReasoning = localSend
                            ).join()
                            onDismiss()
                        } finally {
                            isSaving = false
                        }
                    }
                }
            ) { Text(if (isSaving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(
                enabled = !isSaving,
                onClick = onDismiss
            ) { Text("取消") }
        }
    )
}

@Composable
internal fun PermissionSettingsCard() {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        PermissionItem(stringResource(R.string.auto_start), stringResource(R.string.auto_start_desc)) {
            com.yunian.ai.common.BatteryOptimizationHelper.openAutoStartSettings(context)
        }
        PermissionDivider()
        PermissionItem(
            stringResource(R.string.battery_whitelist),
            stringResource(R.string.battery_whitelist_desc)
        ) {

            com.yunian.ai.common.BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
        }
        PermissionDivider()
        PermissionItem(
            stringResource(R.string.notification_permission),
            stringResource(R.string.notification_permission_desc)
        ) {
            com.yunian.ai.common.BatteryOptimizationHelper.openNotificationSettings(context)
        }
    }
}

@Composable
private fun PermissionItem(title: String, subtitle: String, onClick: () -> Unit) {
    val colorScheme = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(AppIcons.Power, title, Modifier.size(24.dp), tint = AppTheme.colors.onSurface)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                ),
                color = colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colorScheme.onSurfaceVariant
            )
        }
        Icon(
            AppIcons.ChevronRight,
            null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun PermissionDivider() {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp),
        thickness = 0.5.dp,
        color = AppTheme.colors.outline
    )
}
