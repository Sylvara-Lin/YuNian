package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.settings.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.settings.ui.viewmodel.LanguageViewModel
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme

data class LanguageOption(
    val code: String,
    val name: String,
    val nativeName: String
)

val languages = listOf(
    LanguageOption("zh-CN", "简体中文", "简体中文"),
    LanguageOption("zh-TW", "繁體中文", "繁體中文"),
    LanguageOption("en", "English", "English"),
    LanguageOption("ja", "日本語", "日本語"),
    LanguageOption("ko", "한국어", "한국어")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    onNavigateBack: () -> Unit,
    activity: android.app.Activity,
    viewModel: LanguageViewModel = viewModel()
) {
    val currentLanguage by viewModel.language.collectAsState()
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = AppTheme.colors
    val textPrimaryColor = colorScheme.onSurface
    val backgroundColor = colorScheme.background
    val dividerColor = colorScheme.outline
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val cardColor = colorScheme.surfaceVariant

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.language_title),
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = paddingValues.calculateTopPadding(),
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            languages.forEach { language ->
                LanguageOptionCard(
                    language = language,
                    isSelected = currentLanguage == language.code,
                    isDarkTheme = isDarkTheme,
                    cardColor = cardColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor,
                    dividerColor = dividerColor,
                    onClick = {
                        if (currentLanguage != language.code) {
                            viewModel.setLanguage(language.code)
                            viewModel.applyLanguage(activity)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun LanguageOptionCard(
    language: LanguageOption,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    cardColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    dividerColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val surfaceColor = if (isPressed || isSelected) {
        cardColor.copy(alpha = 0.85f)
    } else {
        cardColor
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = surfaceColor
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                dividerColor.copy(alpha = 0.3f),
                                dividerColor.copy(alpha = 0.15f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = language.code.uppercase(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = textSecondaryColor.copy(alpha = 0.9f)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = textPrimaryColor
                )
                Text(
                    text = language.nativeName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = textSecondaryColor
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                            colors = listOf(
                                AppTheme.colors.primary.copy(alpha = 0.9f),
                                AppTheme.colors.primary.copy(alpha = 0.7f)
                            )
                        )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = AppTheme.colors.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
                }
            }
        }
    }
}
