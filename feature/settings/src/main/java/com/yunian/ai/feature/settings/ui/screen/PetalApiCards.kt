package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.icon.AppIcons
import androidx.compose.ui.graphics.vector.ImageVector
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass


import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.delay

internal val PetalBackgroundStart = Color(0xFFDEFCF9)
internal val PetalBackgroundEnd = Color(0xFFE8F6FC)
internal val PetalPrimary = Color(0xFF5A4A80)
internal val PetalPrimaryContainer = Color(0xFFCCA8E9)
internal val PetalOnPrimaryContainer = Color(0xFF3A2E52)
internal val PetalSurface = Color(0xFFFFFFFF)
internal val PetalSurfaceContainer = Color(0xFFE8F0F8)
internal val PetalSurfaceContainerLow = Color(0xFFF0F6FC)
internal val PetalSecondaryContainer = Color(0xFFCADEFC)
internal val PetalOnSurface = Color(0xFF2A2440)
internal val PetalOnSurfaceVariant = Color(0xFF524A66)
internal val PetalOutlineVariant = Color(0xFFC8D8F0)
internal val PetalError = Color(0xFFBA1A1A)
internal val PetalErrorContainer = Color(0xFFFFDAD6)
internal val PetalGreen = Color(0xFF10A37F)
internal val PetalGreenLight = Color(0xFFE8F5E9)
internal val PetalOrange = Color(0xFFFFA726)

private fun mapErrorCode(code: String): String = when (code) {
    "upstream_unreachable" -> "上游不通"
    "account_blocked" -> "已冻结"
    "key_disabled" -> "密钥已禁用"
    "network_error" -> "网络不通"
    "timeout" -> "超时"
    "cloud_service_disabled" -> "云端服务尚未开启"
    "app_key_mismatch" -> "应用凭证校验失败"
    else -> "失败"
}

@Composable
fun PetalStatChip(icon: ImageVector, text: String, color: Color, isDarkTheme: Boolean) {
    val bgColor = if (isDarkTheme) color.copy(alpha = 0.12f) else PetalSurfaceContainerLow

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PetalApiConfigEditDialog(
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
                SecureLog.d("PetalApiCards", "PARTNER auto-select model: chosen=$chosenModel, server=$serverModel, available=${availableModels.size}")
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

                if (isPartner) {
                    connectionResult.errorMessage?.let { error ->
                        Text(text = error, fontSize = 12.sp, color = PetalError)
                    }
                    if (connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED) {
                        Text(
                            text = "Clove API 已连接",
                            color = PetalGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    } else if (connectionResult.status == SettingsViewModel.ConnectionStatus.TESTING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = PetalPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在测试连接...", fontSize = 14.sp, color = textSecondaryColor)
                        }
                    } else {
                        Text(
                            text = "点击下方「测试」验证 Clove API 连接",
                            color = textSecondaryColor,
                            fontSize = 13.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (!isPartner) {

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
                            Text("如：YuNian专用API", color = textTertiary, fontSize = 12.sp)
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
                    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { modelFieldWidthPx = it.width }
                    ) {
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
                            val menuShape = RoundedCornerShape(16.dp)
                            val menuWidth = with(density) {
                                if (modelFieldWidthPx > 0) modelFieldWidthPx.toDp() else 0.dp
                            }
                            MaterialTheme(
                                shapes = MaterialTheme.shapes.copy(extraSmall = menuShape)
                            ) {
                                DropdownMenu(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false },
                                    modifier = Modifier
                                        .then(
                                            if (menuWidth > 0.dp) Modifier.width(menuWidth)
                                            else Modifier.fillMaxWidth()
                                        )
                                        .heightIn(max = 280.dp)
                                        .clip(menuShape)
                                        .background(cardBackground)
                                        .border(1.dp, dividerColor.copy(alpha = 0.85f), menuShape)
                                ) {
                                    availableModels.forEachIndexed { index, m ->
                                        val selected = m == model
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = m,
                                                    color = if (selected) PetalPrimary else textPrimaryColor,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                model = m
                                                showModelDropdown = false
                                            },
                                            modifier = Modifier.background(
                                                if (selected) PetalPrimary.copy(alpha = 0.08f) else Color.Transparent
                                            )
                                        )
                                        if (index < availableModels.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                thickness = 0.6.dp,
                                                color = dividerColor.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
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
                    val currentConfig = if (isPartner) config else config.copy(
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
                    Text(
                        if (isPartner) "验证 Clove API 连接" else "测试",
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }

            GlassButton(
                onClick = {
                    onSave(
                        if (isPartner) config else config.copy(
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

@Composable
fun PetalApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onTest: (ApiConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onSelectActive: () -> Unit,
    onFetchModels: (String, String, String) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    testedConfigs: Map<String, ApiConfig>,
    connectionKey: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.yunian.ai.network.AiService.BalanceInfo? = null,
    balanceQueryFailed: Boolean = false,
    onQueryBalance: (() -> Unit)? = null
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.15f) else PetalSurface

    var balanceQueried by remember { mutableStateOf(false) }
    val isConnected = connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED
    LaunchedEffect(isConnected) {
        if (isConnected && !balanceQueried) {
            onQueryBalance?.invoke()
            balanceQueried = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = cardColor)
            .animateContentSize()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name.ifBlank { config.provider.displayName },
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty() && config.name != config.provider.displayName) {
                        Text(
                            text = config.provider.displayName,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {

                    val statusColor = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> PetalGreen
                        SettingsViewModel.ConnectionStatus.FAILED -> PetalError
                        SettingsViewModel.ConnectionStatus.TESTING -> PetalOrange
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                    }
                    val statusText = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED ->
                            if (connectionResult.latencyMs > 0) "已连接 ${connectionResult.latencyMs}ms" else "已连接"
                        SettingsViewModel.ConnectionStatus.FAILED -> {
                            val err = connectionResult.errorCode
                            connectionResult.errorMessage?.takeIf { it.isNotBlank() }
                                ?: if (err != null) mapErrorCode(err) else "失败"
                        }
                        SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                    }
                    PetalStatChip(
                        icon = AppIcons.Circle,
                        text = statusText,
                        color = statusColor,
                        isDarkTheme = isDarkTheme
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                if (config.provider == ApiProvider.PARTNER) {
                    Text("模型: 自动分配", color = PetalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column {
                            Text("延迟: ${connectionResult.latencyMs}ms | 状态: 已连接",
                                color = PetalGreen, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.groupName?.let { gn ->
                                Text("组: $gn | 剩余: $${String.format("%.2f", connectionResult.remainingQuota)}",
                                    color = PetalGreen.copy(alpha = 0.8f), fontSize = 11.sp)
                            }
                            if (connectionResult.rpmLimit > 0) {
                                Text("RPM: ${connectionResult.rpmLimit} | 日限额: $${connectionResult.dailyLimit ?: "∞"}",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    } else if (connectionResult.status == SettingsViewModel.ConnectionStatus.FAILED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val errCode = connectionResult.errorCode ?: "unknown"
                        val errLabel = mapErrorCode(errCode)
                        Column {
                            Text("$errLabel | error=${errCode} | latency=${connectionResult.latencyMs}ms",
                                color = PetalError, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.errorMessage?.let { msg ->
                                Text(msg, color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (config.model.isNotEmpty()) {
                    Text(
                        text = "模型: ${config.model}",
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (config.baseUrl.isNotEmpty() && config.provider != ApiProvider.PARTNER) {
                    Text(
                        text = "地址: ${config.baseUrl}",
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (balanceInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalSurfaceContainerLow)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全服余额",
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        if (balanceInfo.remainingBalance != null) {
                            val bal = balanceInfo.remainingBalance
                            Text(
                                text = "$${"%.2f".format(bal)}",
                                color = PetalGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "查询中...",
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (balanceQueryFailed) {
                    Text(
                        text = "余额查询失败",
                        color = PetalError,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(
                        onClick = {
                            editingConfig = testedConfigs[connectionKey] ?: config
                        },
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("编辑", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                    GlassButton(
                        onClick = { onTest(config) },
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("测试", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                    GlassButton(
                        onClick = onSelectActive,
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("设为活跃", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    editingConfig?.let { editing ->
        val providerModels = fetchedModels[editing.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[editing.provider.name] ?: SettingsViewModel.ModelFetchState()
        PetalApiConfigEditDialog(
            config = editing,
            connectionResult = connectionResult,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            onTest = { testConfig: ApiConfig -> onTest(testConfig) },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onFetchModels = { baseUrl: String, apiKey: String ->
                onFetchModels(baseUrl, apiKey, editing.provider.name)
            },
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }
}

@Composable
fun PetalSavedApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onDelete: () -> Unit,
    onTest: (ApiConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onSelectActive: () -> Unit,
    onFetchModels: (String, String, String) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    testedConfigs: Map<String, ApiConfig>,
    connectionKey: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.yunian.ai.network.AiService.BalanceInfo? = null,
    balanceQueryFailed: Boolean = false,
    onQueryBalance: (() -> Unit)? = null
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.08f) else PetalSurfaceContainer

    var balanceQueried by remember { mutableStateOf(false) }
    val isConnected = connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED
    LaunchedEffect(isConnected) {
        if (isConnected && !balanceQueried) {
            onQueryBalance?.invoke()
            balanceQueried = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = cardColor)
            .animateContentSize()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name.ifBlank { config.provider.displayName },
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty() && config.name != config.provider.displayName) {
                        Text(
                            text = config.provider.displayName,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {

                    if (isActive) {
                        PetalStatChip(
                            icon = AppIcons.Check,
                            text = "活跃",
                            color = PetalGreen,
                            isDarkTheme = isDarkTheme
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    val statusColor = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> PetalGreen
                        SettingsViewModel.ConnectionStatus.FAILED -> PetalError
                        SettingsViewModel.ConnectionStatus.TESTING -> PetalOrange
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                    }
                    val statusText = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED ->
                            if (connectionResult.latencyMs > 0) "已连接 ${connectionResult.latencyMs}ms" else "已连接"
                        SettingsViewModel.ConnectionStatus.FAILED -> {
                            val err = connectionResult.errorCode
                            connectionResult.errorMessage?.takeIf { it.isNotBlank() }
                                ?: if (err != null) mapErrorCode(err) else "失败"
                        }
                        SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                    }
                    PetalStatChip(
                        icon = AppIcons.Circle,
                        text = statusText,
                        color = statusColor,
                        isDarkTheme = isDarkTheme
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                if (config.provider == ApiProvider.PARTNER) {
                    Text("模型: 自动分配", color = PetalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column {
                            Text("延迟: ${connectionResult.latencyMs}ms | 状态: 已连接",
                                color = PetalGreen, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.groupName?.let { gn ->
                                Text("组: $gn | 剩余: $${String.format("%.2f", connectionResult.remainingQuota)}",
                                    color = PetalGreen.copy(alpha = 0.8f), fontSize = 11.sp)
                            }
                            if (connectionResult.rpmLimit > 0) {
                                Text("RPM: ${connectionResult.rpmLimit} | 日限额: $${connectionResult.dailyLimit ?: "∞"}",
                                    color = PetalGreen.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    } else if (connectionResult.status == SettingsViewModel.ConnectionStatus.FAILED) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val errCode = connectionResult.errorCode ?: "unknown"
                        val errLabel = mapErrorCode(errCode)
                        Column {
                            Text("$errLabel | error=${errCode} | latency=${connectionResult.latencyMs}ms",
                                color = PetalError, fontSize = 12.sp)
                            connectionResult.clientId?.let { cid ->
                                Text("Client ID: $cid",
                                    color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            connectionResult.errorMessage?.let { msg ->
                                Text(msg, color = PetalError.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else if (config.model.isNotEmpty()) {
                    Text(
                        text = "模型: ${config.model}",
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (config.baseUrl.isNotEmpty() && config.provider != ApiProvider.PARTNER) {
                    Text(
                        text = "地址: ${config.baseUrl}",
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (balanceInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalSurfaceContainerLow)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "余额查询",
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        if (balanceInfo.remainingBalance != null) {
                            val bal = balanceInfo.remainingBalance
                            Text(
                                text = "$${"%.2f".format(bal)}",
                                color = PetalGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "查询中...",
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (balanceQueryFailed) {
                    Text(
                        text = "余额查询失败",
                        color = PetalError,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassButton(
                        onClick = onDelete,
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    GlassButton(
                        onClick = {
                            editingConfig = testedConfigs[connectionKey] ?: config
                        },
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("编辑", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                    GlassButton(
                        onClick = { onTest(config) },
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("测试", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                    GlassButton(
                        onClick = onSelectActive,
                        height = 34.dp,
                        horizontalPadding = 12.dp,
                        minTouchTarget = true
                    ) {
                        Text("设为活跃", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    editingConfig?.let { editing ->
        val providerModels = fetchedModels[editing.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[editing.provider.name] ?: SettingsViewModel.ModelFetchState()
        PetalApiConfigEditDialog(
            config = editing,
            connectionResult = connectionResult,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            onTest = { testConfig: ApiConfig -> onTest(testConfig) },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onFetchModels = { baseUrl: String, apiKey: String ->
                onFetchModels(baseUrl, apiKey, editing.provider.name)
            },
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }
}

@Composable
fun PetalAddApiButton(onClick: () -> Unit, isDarkTheme: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(backdrop = LocalPageBackdrop.current, shape = RoundedCornerShape(16.dp), surfaceColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.15f) else PetalSurface)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = PetalPrimary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "添加 API 设置",
                    color = AppTheme.colors.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "配置新的服务提供商、密钥与模型",
                    color = AppTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
