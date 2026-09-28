@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen

import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ApiProviderPreset
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.delay
import androidx.compose.foundation.isSystemInDarkTheme

data class ApiTestDialogData(
    val isSuccess: Boolean,
    val providerName: String,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

@Composable
internal fun ApiTestResultDialog(
    data: ApiTestDialogData,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit
) {
    GlassEditDialog(
        onDismissRequest = onDismiss,
        title = if (data.isSuccess) "连接成功！" else "连接失败",
        titleColor = if (data.isSuccess) PetalGreen else PetalError,
        actions = {
            GlassButton(
                onClick = onDismiss,
                height = 44.dp,
                horizontalPadding = 20.dp
            ) {
                Text(
                    "知道了",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (data.isSuccess) AppIcons.Check else AppIcons.X,
                contentDescription = null,
                tint = if (data.isSuccess) PetalGreen else PetalError,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = if (data.isSuccess) "${data.providerName} API 连接测试通过" else "${data.providerName} API 无法连接，请检查配置",
                color = AppTheme.colors.onSurface,
                fontSize = 14.sp
            )

            if (data.isSuccess && data.latencyMs > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PetalGreen.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Clock, null, Modifier.size(14.dp), tint = PetalGreen)
                        Spacer(Modifier.width(4.dp))
                        Text(text = "响应延迟: ${data.latencyMs}ms", color = PetalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    val (latencyIcon, latencyText) = when {
                        data.latencyMs < 500 -> AppIcons.Zap to "延迟优秀，连接速度很快"
                        data.latencyMs < 1500 -> AppIcons.Check to "延迟正常，可以正常使用"
                        else -> AppIcons.TriangleAlert to "延迟较高，可能影响体验"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(latencyIcon, null, Modifier.size(14.dp), tint = AppTheme.colors.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(latencyText, color = AppTheme.colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            if (!data.isSuccess && data.errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PetalErrorContainer.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = data.errorMessage, color = PetalError, fontSize = 13.sp)
                }

                Text(
                    text = "常见问题：\n• API Key 是否正确\n• Base URL 是否填到 /chat/completions 的父层级（如 https://api.openai.com/v1）\n• 网络连接是否正常\n• 该服务商是否支持当前模型",
                    color = AppTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun ApiConfigEditDialog(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    onDismiss: () -> Unit,
    onSave: (ApiConfig) -> Unit,
    onTest: (ApiConfig) -> Unit,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onFetchModels: ((String, String) -> Unit)? = null,
    availableModels: List<String> = emptyList(),
    modelFetchState: SettingsViewModel.ModelFetchState = SettingsViewModel.ModelFetchState()
) {
    val cardBackground = AppTheme.colors.surfaceVariant
    val dividerColor = AppTheme.colors.outline
    val textTertiary = AppTheme.colors.outlineVariant
    // 玻璃底上的输入框用半透明容器色，保证文字可读
    val fieldContainerColor = cardBackground.copy(alpha = 0.62f)

    var apiKey by remember { mutableStateOf(config.apiKey) }
    var apiName by remember { mutableStateOf(config.name) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var temperature by remember { mutableFloatStateOf(config.temperature) }
    var maxTokens by remember { mutableStateOf(config.maxTokens?.toString() ?: "") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var formatHint by remember { mutableStateOf(config.formatHint) }
    var lastFetchedParams by remember { mutableStateOf("") }
    val context = LocalContext.current

    val isPartner = config.provider == ApiProvider.PARTNER
    val isCustom = config.provider == ApiProvider.CUSTOM
    val isCustomAnthropic = isCustom && formatHint == "anthropic"
    val canFetchOpenAiModels = onFetchModels != null && !isCustomAnthropic && baseUrl.isNotBlank() && (isPartner || apiKey.isNotBlank())
    val isValid = apiKey.isNotBlank() || baseUrl.isNotBlank() || isPartner
    val hasModels = availableModels.isNotEmpty()

    val selectedModelText = when {
        modelFetchState.isLoading -> "正在获取模型列表..."
        hasModels -> model.ifEmpty { "请选择模型" }
        isPartner && apiKey.isBlank() -> "密钥将从服务器自动获取，直接点击测试"
        else -> "填写密钥后自动拉取模型"
    }

    LaunchedEffect(apiKey, baseUrl, config.provider, formatHint) {
        val fetchParams = baseUrl.trim() + "|" + apiKey.trim()

        val shouldFetch = canFetchOpenAiModels && fetchParams != lastFetchedParams
        if (shouldFetch) {
            delay(600)
            lastFetchedParams = fetchParams

            val keyToUse = apiKey.trim()
            onFetchModels?.invoke(baseUrl, keyToUse)
        }
    }

    LaunchedEffect(availableModels, config.provider) {
        if ((isPartner || isCustom) && availableModels.isNotEmpty()) {
            model = if (isPartner) {

                val serverModel = com.yunian.ai.common.RemoteKeyProvider.getRandomModel(context)
                    ?.takeIf { it.isNotBlank() && availableModels.contains(it) }
                val chosenModel = if (availableModels.size > 1) {
                    com.yunian.ai.network.AiService.familyBalancedRandom(availableModels)
                } else {
                    serverModel ?: availableModels.first()
                }
                SecureLog.d("SettingsScreen", "PARTNER auto-select model: chosen=$chosenModel, server=$serverModel, available=${availableModels.size}")
                chosenModel
            } else {

                if (model.isBlank()) availableModels.first() else model
            }
        }
    }

    GlassEditDialog(
        onDismissRequest = onDismiss,
        title = "${config.name.ifBlank { config.provider.displayName }} 配置",
        titleColor = textPrimaryColor,
        content = {

                if (isCustom) {
                    OutlinedTextField(
                        value = apiName,
                        onValueChange = { apiName = it },
                        label = { Text("API 名称", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                        placeholder = {
                            Text("如：我的DeepSeek、公司代理API", color = textTertiary, fontSize = 12.sp)
                        }
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "填到能拼 /chat/completions 的那一层，如 https://api.openai.com/v1",
                            color = textTertiary,
                            fontSize = 11.sp
                        )
                    }
                )

                if (isCustom) {
                    var showFormatDropdown by remember { mutableStateOf(false) }
                    val formatOptions = mapOf(
                        "openai" to "OpenAI 兼容",
                        "anthropic" to "Anthropic 兼容"
                    )
                    val selectedFormatText = formatOptions[formatHint] ?: "OpenAI 兼容"

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedFormatText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("API 格式", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFormatDropdown = !showFormatDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = {
                                Icon(
                                    imageVector = if (showFormatDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                    contentDescription = "展开",
                                    modifier = Modifier.clickable { showFormatDropdown = !showFormatDropdown },
                                    tint = textSecondaryColor
                                )
                            },
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = showFormatDropdown,
                            onDismissRequest = { showFormatDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            formatOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = textPrimaryColor, fontSize = 14.sp) },
                                    onClick = {
                                        formatHint = key
                                        showFormatDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (isCustomAnthropic) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true
                    )
                    Text(
                        text = "Anthropic 兼容模式通常不支持自动拉取模型，请手动填写模型名",
                        fontSize = 12.sp,
                        color = textSecondaryColor
                    )
                } else if (onFetchModels != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = if (hasModels) selectedModelText else model,
                            onValueChange = { if (!hasModels) model = it },
                            readOnly = hasModels,
                            label = { Text("Model", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasModels) { showModelDropdown = !showModelDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = if (hasModels) {
                                {
                                    Icon(
                                        imageVector = if (showModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                        contentDescription = "展开",
                                        modifier = Modifier.clickable { showModelDropdown = !showModelDropdown },
                                        tint = textSecondaryColor
                                    )
                                }
                            } else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            singleLine = true
                        )
                        if (hasModels && !modelFetchState.isLoading) {
                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                availableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, color = textPrimaryColor, fontSize = 14.sp) },
                                        onClick = {
                                            model = m
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (modelFetchState.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PetalPrimary)
                        Text("正在获取模型列表...", fontSize = 12.sp, color = textSecondaryColor)
                    }

                    modelFetchState.errorMessage?.let { error ->
                        Text(text = error, fontSize = 12.sp, color = PetalError)
                    }

                    GlassButton(
                        onClick = {
                            model = ""
                            lastFetchedParams = ""
                            onFetchModels.invoke(baseUrl, apiKey.trim())
                        },
                        enabled = canFetchOpenAiModels && !modelFetchState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        height = 44.dp
                    ) {
                        Text(
                            if (hasModels) "重新拉取模型" else "一键拉取模型",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true
                    )
                }

                Text(
                    text = "Temperature: ${String.format("%.1f", temperature)}",
                    color = textPrimaryColor,
                    fontSize = 14.sp
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = PetalPrimary,
                        activeTrackColor = PetalPrimary
                    )
                )

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                    label = { Text("Max Tokens", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true
                )
        },
        actions = {
            GlassButton(
                onClick = onDismiss,
                height = 44.dp,
                horizontalPadding = 16.dp
            ) {
                Text("取消", color = textSecondaryColor)
            }

            GlassButton(
                onClick = {
                    val currentConfig = config.copy(
                        name = apiName.trim(),
                        apiKey = apiKey.trim(),
                        extraApiKeys = "",
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        temperature = temperature,
                        maxTokens = maxTokens.toIntOrNull(),
                        formatHint = formatHint
                    )
                    onTest(currentConfig)
                },
                enabled = isValid && connectionResult.status != SettingsViewModel.ConnectionStatus.TESTING,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                horizontalPadding = 12.dp
            ) {
                if (connectionResult.status == SettingsViewModel.ConnectionStatus.TESTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("测试", color = MaterialTheme.colorScheme.onSurface)
                }
            }

            GlassButton(
                onClick = {
                    onSave(
                        config.copy(
                            name = apiName.trim(),
                            apiKey = apiKey.trim(),
                            extraApiKeys = "",
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            temperature = temperature,
                            maxTokens = maxTokens.toIntOrNull(),
                            formatHint = formatHint
                        )
                    )
                },
                enabled = isValid,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                horizontalPadding = 12.dp
            ) {
                Text("保存", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}

