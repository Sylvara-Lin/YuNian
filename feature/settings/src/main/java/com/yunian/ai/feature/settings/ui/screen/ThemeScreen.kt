package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.settings.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(
    onNavigateBack: () -> Unit,
    activity: android.app.Activity,
    viewModel: ThemeViewModel = viewModel()
) {
    val currentTheme by viewModel.themeMode.collectAsState()
    val isDarkTheme = when (currentTheme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = AppTheme.colors
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val dividerColor = colorScheme.outline
    val cardColor = colorScheme.surfaceVariant

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.theme_title),
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

            ThemeOptionCard(
                icon = AppIcons.Sun,
                title = stringResource(R.string.theme_light),
                subtitle = stringResource(R.string.theme_light_desc),
                isSelected = currentTheme == ThemeMode.LIGHT,
                previewColor = Color(0xFFFFF8FA),
                cardColor = cardColor,
                textPrimaryColor = textPrimaryColor,
                dividerColor = dividerColor,
                onClick = {
                    if (currentTheme != ThemeMode.LIGHT) {
                        viewModel.setThemeMode(ThemeMode.LIGHT)
                        viewModel.applyTheme(activity)
                    }
                }
            )

            ThemeOptionCard(
                icon = AppIcons.Moon,
                title = stringResource(R.string.theme_dark),
                subtitle = stringResource(R.string.theme_dark_desc),
                isSelected = currentTheme == ThemeMode.DARK,
                previewColor = Color(0xFF1A1A2E),
                cardColor = cardColor,
                textPrimaryColor = textPrimaryColor,
                dividerColor = dividerColor,
                onClick = {
                    if (currentTheme != ThemeMode.DARK) {
                        viewModel.setThemeMode(ThemeMode.DARK)
                        viewModel.applyTheme(activity)
                    }
                }
            )

            ThemeOptionCard(
                icon = AppIcons.Settings2,
                title = stringResource(R.string.theme_system),
                subtitle = stringResource(R.string.theme_system_desc),
                isSelected = currentTheme == ThemeMode.SYSTEM,
                previewColor = Color(0xFF2D2D44),
                cardColor = cardColor,
                textPrimaryColor = textPrimaryColor,
                dividerColor = dividerColor,
                onClick = {
                    if (currentTheme != ThemeMode.SYSTEM) {
                        viewModel.setThemeMode(ThemeMode.SYSTEM)
                        viewModel.applyTheme(activity)
                    }
                }
            )
        }
    }
}

@Composable
fun ThemeOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    previewColor: Color,
    cardColor: Color,
    textPrimaryColor: Color,
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

    Row(
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
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textPrimaryColor,
            modifier = Modifier.size(26.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                ),
                color = textPrimaryColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = dividerColor
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
                    tint = AppTheme.colors.staticWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}
