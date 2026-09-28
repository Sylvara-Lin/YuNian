package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.app.Activity
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.settings.R
import com.yunian.ai.common.FrameRateManager
import com.yunian.ai.feature.settings.ui.viewmodel.FrameRateViewModel
import com.yunian.ai.uicommon.theme.PinkPrimary
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrameRateScreen(
    onNavigateBack: () -> Unit,
    activity: Activity
) {
    val context = LocalContext.current
    val themeViewModel: ThemeViewModel = viewModel()
    val frameRateViewModel: FrameRateViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val selectedRate by frameRateViewModel.frameRate.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = AppTheme.colors
    val bgColor = colorScheme.background
    val cardColor = colorScheme.surfaceVariant
    val textPrimary = colorScheme.onSurface
    val textSecondary = colorScheme.onSurfaceVariant
    val dividerColor = colorScheme.outline

    val supportedRates = remember { FrameRateManager.getSupportedFrameRates(context) }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.framerate_title),
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
                )
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.screen_refresh_rate),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp
                ),
                color = textSecondary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = cardColor
                    )
            ) {
                supportedRates.forEachIndexed { index, rate ->
                    FrameRateOptionItem(
                        rate = rate,
                        isSelected = selectedRate == rate,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary,
                        onClick = {
                            frameRateViewModel.setFrameRate(rate)
                            FrameRateManager.applyFrameRate(activity.window, rate)
                        }
                    )
                    if (index < supportedRates.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = dividerColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.framerate_hint),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun FrameRateOptionItem(
    rate: FrameRateManager.FrameRate,
    isSelected: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = rate.label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            color = textPrimary
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(PinkPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = AppTheme.colors.staticBlack,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
