@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import com.yunian.ai.uicommon.theme.WeChatDarkDivider
import com.yunian.ai.uicommon.theme.WeChatDarkTextPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

@Composable
fun DiaryModelSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val diaryEnabled by viewModel.diaryEnabled.collectAsState()
    val diaryModel by viewModel.diaryModel.collectAsState()
    val diaryBaseUrl by viewModel.diaryBaseUrl.collectAsState()
    val diaryApiKey by viewModel.diaryApiKey.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showTestResultDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    val backgroundColor = if (isDarkTheme) WeChatDarkBackground else PetalBackgroundStart
    val textPrimaryColor = if (isDarkTheme) WeChatDarkTextPrimary else PetalOnSurface
    // 副标题 / 说明文字统一用高对比色：浅色主题纯黑（原 onSurfaceVariant/outlineVariant 太浅看不清），
    // 深色主题用亮灰（纯黑在深色玻璃卡片上不可见）。
    val textSecondaryColor = if (isDarkTheme) WeChatDarkTextSecondary else Color.Black
    val textTertiaryColor = if (isDarkTheme) WeChatDarkTextSecondary else Color.Black

    GlassPageScaffold(
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
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(percent = 50),
                        surfaceColor = if (isDarkTheme) WeChatDarkCard else PetalSurface
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
                    text = "日记模型设置",
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
                    text = "为「AI 生成日记」单独配置 API 模型，不占用主聊天 API 额度",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = textTertiaryColor,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

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
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(24.dp),
                            surfaceColor = if (isDarkTheme) WeChatDarkCard else PetalSurface
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
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
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Book,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "AI 日记生成", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textPrimaryColor)
                            Text(text = "手动或自动生成聊天日记时，使用这里配置的模型", fontSize = 12.sp, color = textSecondaryColor)
                        }
                    }

                    var useCustomApi by remember { mutableStateOf(diaryEnabled) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = if (useCustomApi) PetalPrimary.copy(alpha = 0.08f) else Color.Gray.copy(alpha = 0.05f)
                            )
                            .clickable {
                                useCustomApi = !useCustomApi
                                viewModel.setDiaryEnabled(useCustomApi)
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (useCustomApi) AppIcons.Settings else AppIcons.RefreshCw,
                                    contentDescription = null,
                                    tint = textPrimaryColor,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = if (useCustomApi) "自定义API配置（独立）" else "跟随主API设置",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimaryColor
                                )
                            }
                            Text(
                                text = if (useCustomApi) "为日记生成单独配置API地址、密钥和模型名" else "直接使用主聊天API的连接信息",
                                fontSize = 12.sp,
                                color = textSecondaryColor,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Switch(
                            checked = useCustomApi,
                            onCheckedChange = { newValue ->
                                useCustomApi = newValue
                                viewModel.setDiaryEnabled(newValue)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = if (useCustomApi) PetalPrimary else Color.Gray,
                                checkedTrackColor = if (useCustomApi) PetalPrimaryContainer.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f),
                                uncheckedThumbColor = AppTheme.colors.staticWhite,
                                uncheckedTrackColor = Color.Gray.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }

                    if (useCustomApi) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                            androidx.compose.material3.HorizontalDivider(color = if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer)

                            OutlinedTextField(
                                value = diaryModel,
                                onValueChange = { viewModel.setDiaryModel(it) },
                                label = { Text("模型名称 *", color = textSecondaryColor) },
                                placeholder = { Text("如: deepseek-chat, gpt-4o-mini, kimi-k2.6", color = textTertiaryColor, fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (diaryModel.isNotBlank()) PetalGreen else PetalPrimary,
                                    unfocusedBorderColor = if (diaryModel.isNotBlank()) PetalGreen else if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer,
                                    focusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    unfocusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    focusedTextColor = textPrimaryColor,
                                    unfocusedTextColor = textPrimaryColor
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                supportingText = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (diaryModel.isBlank()) AppIcons.TriangleAlert else AppIcons.Check,
                                            contentDescription = null,
                                            tint = if (diaryModel.isBlank()) PetalOrange else PetalGreen,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = if (diaryModel.isBlank()) "必填：请输入支持文本对话的模型名称" else "已输入: $diaryModel",
                                            color = if (diaryModel.isBlank()) PetalOrange else PetalGreen,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            )

                            OutlinedTextField(
                                value = diaryBaseUrl,
                                onValueChange = { viewModel.setDiaryBaseUrl(it) },
                                label = { Text("API 地址 (Base URL) *", color = textSecondaryColor) },
                                placeholder = { Text("https://api.openai.com/v1/ 或中转站地址", color = textTertiaryColor, fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (diaryBaseUrl.isNotBlank()) PetalGreen else PetalPrimary,
                                    unfocusedBorderColor = if (diaryBaseUrl.isNotBlank()) PetalGreen else if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer,
                                    focusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    unfocusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    focusedTextColor = textPrimaryColor,
                                    unfocusedTextColor = textPrimaryColor
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                            )

                            OutlinedTextField(
                                value = diaryApiKey,
                                onValueChange = { viewModel.setDiaryApiKey(it) },
                                label = { Text("API 密钥 (Key) *", color = textSecondaryColor) },
                                placeholder = { Text("sk-... 或其他格式的密钥", color = textTertiaryColor, fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = if (diaryApiKey.isNotBlank()) PetalGreen else PetalPrimary,
                                    unfocusedBorderColor = if (diaryApiKey.isNotBlank()) PetalGreen else if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer,
                                    focusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    unfocusedContainerColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite,
                                    focusedTextColor = textPrimaryColor,
                                    unfocusedTextColor = textPrimaryColor
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                trailingIcon = {
                                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                        Icon(
                                            imageVector = if (apiKeyVisible) AppIcons.Check else AppIcons.ArrowLeft,
                                            contentDescription = if (apiKeyVisible) "隐藏密钥" else "显示密钥",
                                            tint = textSecondaryColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .drawGlass(
                                        backdrop = LocalPageBackdrop.current,
                                        shape = RoundedCornerShape(12.dp),
                                        surfaceColor = PetalPrimary.copy(alpha = 0.08f)
                                    )
                                    .clickable(enabled = !isTestingConnection) {
                                        scope.launch {
                                            isTestingConnection = true
                                            testResult = null
                                            try {
                                                val baseUrl = diaryBaseUrl.trim().removeSuffix("/")
                                                val testKey = diaryApiKey.trim()
                                                val testModel = diaryModel.trim()

                                                if (baseUrl.isBlank() || testKey.isBlank() || testModel.isBlank()) {
                                                    testResult = "✗ 请填写模型名称、API 地址和密钥"
                                                    isTestingConnection = false
                                                    return@launch
                                                }

                                                val result = viewModel.testDiaryConnection(
                                                    baseUrl = baseUrl,
                                                    apiKey = testKey,
                                                    model = testModel
                                                )

                                                result.onSuccess { message ->
                                                    testResult = "✓ $message"
                                                }.onFailure { error ->
                                                    testResult = "✗ 连接失败: ${error.message}"
                                                }

                                                showTestResultDialog = true
                                            } catch (e: Exception) {
                                                testResult = "✗ 测试异常: ${e.message ?: e.javaClass.simpleName}"
                                                showTestResultDialog = true
                                            } finally {
                                                isTestingConnection = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 14.dp, horizontal = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isTestingConnection) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = PetalPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "测试连接中...", color = PetalPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                } else {
                                    Icon(imageVector = AppIcons.Check, contentDescription = null, tint = PetalPrimary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "测试 API 连接", color = PetalPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            if (diaryEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(PetalGreen.copy(alpha = 0.05f))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Check,
                                            contentDescription = null,
                                            tint = PetalGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(text = "当前使用独立API配置", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PetalGreen)
                                    }
                                    Text(text = "模型: ${diaryModel.ifBlank { "未填写" }} | 地址: ${diaryBaseUrl.ifBlank { "未填写" }}", fontSize = 11.sp, color = textSecondaryColor)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

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
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = if (isDarkTheme) WeChatDarkCard else PetalSurface
                        )
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Copy,
                            contentDescription = null,
                            tint = textPrimaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(text = "配置说明", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textPrimaryColor)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoItem(icon = AppIcons.RefreshCw, title = "跟随主API（推荐）", content = "选择「跟随主API设置」时，将自动使用您在API设置页面配置的连接信息，无需重复填写。", textColor = textPrimaryColor, secondaryColor = textSecondaryColor)
                        InfoItem(icon = AppIcons.Settings, title = "独立配置", content = "如果日记生成频繁失败或想用更便宜的模型，请在此处单独填写模型名、URL 和 Key。", textColor = textPrimaryColor, secondaryColor = textSecondaryColor)
                        InfoItem(icon = AppIcons.TriangleAlert, title = "注意事项", content = "确保模型名与 API 提供商匹配！例如：DeepSeek 必须搭配 DeepSeek 的 Key 和 URL。", textColor = textPrimaryColor, secondaryColor = textSecondaryColor)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showTestResultDialog && testResult != null) {
        DiaryTestResultDialog(
            testResult = testResult,
            isDarkTheme = isDarkTheme,
            onDismiss = { showTestResultDialog = false }
        )
    }
}

@Composable
private fun DiaryTestResultDialog(
    testResult: String?,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit
) {
    if (testResult == null) return

    val isSuccess = testResult.startsWith("✓")

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isSuccess) AppIcons.Check else AppIcons.X,
                contentDescription = null,
                tint = if (isSuccess) PetalGreen else Color.Red,
                modifier = Modifier.size(40.dp)
            )
        },
        title = {
            Text(
                text = if (isSuccess) "连接成功！" else "连接失败",
                color = if (isSuccess) PetalGreen else Color.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isSuccess) "日记API配置正确，可以正常生成日记" else "无法连接到API服务器，请检查以下配置：",
                    color = if (isDarkTheme) WeChatDarkTextPrimary else PetalOnSurface,
                    fontSize = 14.sp
                )

                if (!isSuccess) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Red.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = testResult.removePrefix("✗ ").removePrefix("✓ "),
                            color = Color.Red,
                            fontSize = 13.sp
                        )
                    }

                    Text(
                        text = "常见问题排查：\n• API 地址是否正确（注意末尾的 /v1）\n• API Key 是否有效且未过期\n• 模型名称是否与厂商匹配\n• 网络连接是否正常",
                        color = if (isDarkTheme) WeChatDarkTextSecondary else PetalOnSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalGreen.copy(alpha = 0.08f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = testResult.removePrefix("✓ "),
                            color = PetalGreen,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("知道了", color = PetalPrimary, fontWeight = FontWeight.Medium)
            }
        }
    )
}
