package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.feature.settings.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentalFeaturesScreen(
    onNavigateBack: () -> Unit,
    onYandereModeClick: () -> Unit,
    onWorldbookClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onMcpClick: () -> Unit = {}
) {
    val colorScheme = AppTheme.colors

    val features = listOf(
        FeatureItem(
            icon = AppIcons.Book,
            title = stringResource(R.string.worldbook),
            description = stringResource(R.string.worldbook_desc),
            onClick = onWorldbookClick
        ),
        FeatureItem(
            icon = AppIcons.Sparkles,
            title = stringResource(R.string.skills_manager),
            description = stringResource(R.string.skills_manager_desc),
            onClick = onSkillsClick
        ),
        FeatureItem(
            icon = AppIcons.Network,
            title = stringResource(R.string.mcp_settings_title),
            description = stringResource(R.string.mcp_settings_desc),
            onClick = onMcpClick
        ),
        FeatureItem(
            icon = AppIcons.FlaskConical,
            title = stringResource(R.string.yandere_mode),
            description = stringResource(R.string.yandere_mode_desc),
            onClick = onYandereModeClick
        )
    )

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.experimental_features),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.experimental_features_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(features) { feature ->
                ExperimentalFeatureCard(feature = feature)
            }
        }
    }
}

@Composable
private fun ExperimentalFeatureCard(feature: FeatureItem) {
    val colorScheme = AppTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(16.dp)
    val surfaceColor = if (isPressed) {
        colorScheme.surfaceVariant.copy(alpha = 0.85f)
    } else {
        colorScheme.surfaceVariant
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
                onClick = feature.onClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = feature.icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feature.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = colorScheme.onSurface,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Icon(
            imageVector = AppIcons.ChevronRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

private data class FeatureItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val onClick: () -> Unit
)
