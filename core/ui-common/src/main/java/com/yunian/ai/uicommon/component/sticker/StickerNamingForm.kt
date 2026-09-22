package com.yunian.ai.uicommon.component.sticker

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 输入框液态玻璃底圆角（替代 Material 默认 4dp 小圆角） */
private val GlassFieldShape = RoundedCornerShape(14.dp)

/**
 * 命名 + 语义 + 别名表单：StickerImportOverlay（首次导入）与「重命名」共用。
 * 唯一性约束：isNameTaken 实时校验，重复 → error 文案「已有同名表情」并禁用确认（修 P4）。
 * 语义描述选填，用占位文案引导（主理人拍板：示例「如：委屈、崩溃时的猫猫头」）。
 *
 * 输入框为液态玻璃观感：透明玻璃底 + 顶部微高光 + 细描边 + 大圆角，
 * 与外层玻璃卡片材质一致；focus 聚焦时描边过渡到主题主色，error 时描红。
 */
@Composable
fun StickerNamingForm(
    suggestedName: String,
    isNameTaken: (String) -> Boolean,
    onConfirm: (name: String, semantic: String, aliases: List<String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "为这个表情起个名字",
    confirmText: String = "保存",
    initialSemantic: String = "",
    initialAliases: List<String> = emptyList(),
    /** 重命名场景：原名本身不算重复（传当前表情名） */
    currentName: String? = null,
) {
    // 键控 suggestedName：预填名异步解析完成后（空串 → 真名）自动回填；用户输入期间值不变不会打断
    var name by remember(suggestedName) { mutableStateOf(suggestedName) }
    var semantic by remember { mutableStateOf(initialSemantic) }
    var aliasesText by remember { mutableStateOf(initialAliases.joinToString("、")) }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // 名称重复实时校验（trim 后比较：给原名加个空格也算「保持原名」，不应误报重复）
    val nameError = when {
        name.isBlank() -> null
        currentName != null && name.trim() == currentName.trim() -> null
        isNameTaken(name.trim()) -> "已有同名表情"
        else -> null
    }
    val confirmEnabled = name.isNotBlank() && nameError == null

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        GlassOutlinedField(
            value = name,
            onValueChange = { name = it.take(20) },
            placeholderText = "如：裂开、mua",
            isError = nameError != null,
            errorText = nameError
        )
        GlassOutlinedField(
            value = semantic,
            onValueChange = { semantic = it.take(40) },
            placeholderText = "如：委屈、崩溃时的猫猫头（选填）"
        )
        GlassOutlinedField(
            value = aliasesText,
            onValueChange = { aliasesText = it.take(60) },
            placeholderText = "别名用、分隔（选填），如：绷不住了、泪目"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text(text = "取消")
            }
            Button(
                onClick = {
                    onConfirm(name.trim(), semantic.trim(), parseAliasInput(aliasesText))
                },
                enabled = confirmEnabled
            ) {
                Text(text = confirmText)
            }
        }
    }
}

/**
 * 液态玻璃输入框：透明玻璃底（顶部微高光渐变）+ 细描边 + 大圆角。
 * focus 聚焦描边 → 主题主色；error → 主题错误色；均以 160ms 过渡。
 */
@Composable
private fun GlassOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    errorText: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface

    // 静息：半透明白描边；聚焦：主色；错误：错误色
    val idleBorder = Color.White.copy(alpha = if (isDark) 0.18f else 0.32f)
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> errorColor
            focused -> primaryColor
            else -> idleBorder
        },
        animationSpec = tween(160),
        label = "glassFieldBorder"
    )
    // 玻璃底：聚焦 / 错误时底色略提亮，给出「点亮」反馈
    val glassBaseAlpha by animateFloatAsState(
        targetValue = when {
            isError -> 0.16f
            focused -> 0.14f
            else -> 0.08f
        },
        animationSpec = tween(160),
        label = "glassFieldBase"
    )
    val glassBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = glassBaseAlpha + if (isDark) 0.02f else 0.08f),
        1f to Color.White.copy(alpha = glassBaseAlpha * 0.3f)
    )
    val textColor = onSurface.copy(alpha = 0.95f)
    val placeholderColor = onSurface.copy(alpha = 0.42f)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(GlassFieldShape)
            .background(glassBrush)
            .onFocusChanged { focused = it.isFocused },
        placeholder = { Text(text = placeholderText, fontSize = 13.sp) },
        singleLine = true,
        isError = isError,
        supportingText = if (errorText != null) {
            { Text(text = errorText, fontSize = 12.sp, color = errorColor) }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = textColor,
            unfocusedTextColor = textColor,
            errorTextColor = textColor,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            cursorColor = primaryColor,
            errorCursorColor = errorColor,
            focusedBorderColor = borderColor,
            unfocusedBorderColor = borderColor,
            errorBorderColor = borderColor,
            focusedPlaceholderColor = placeholderColor,
            unfocusedPlaceholderColor = placeholderColor,
            errorPlaceholderColor = placeholderColor,
            focusedSupportingTextColor = errorColor,
            errorSupportingTextColor = errorColor
        ),
        shape = GlassFieldShape
    )
}

/** 别名输入解析：按「、，,」拆分，去空白与方括号，去重，≤5 个每个 ≤12 字 */
internal fun parseAliasInput(raw: String): List<String> {
    return raw
        .split('、', '，', ',')
        .map { it.trim().replace(Regex("[\\[\\]\\n\\r]"), "") }
        .filter { it.isNotBlank() }
        .distinct()
        .take(5)
        .map { it.take(12) }
}