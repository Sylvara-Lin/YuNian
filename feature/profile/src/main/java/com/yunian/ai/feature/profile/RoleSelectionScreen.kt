package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.common.CompanionRole
import kotlinx.coroutines.delay

@Composable
fun RoleSelectionScreen(
    onRoleSelected: (CompanionRole) -> Unit,
    onSkip: (() -> Unit)? = null
) {
    var selectedRole by remember { mutableStateOf<CompanionRole?>(null) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 5 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.role_selection_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = AppTheme.colors.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.role_selection_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(500, delayMillis = 120)) + slideInVertically(tween(500, delayMillis = 120)) { it / 5 }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RoleOptionCard(
                    role = CompanionRole.GIRLFRIEND,
                    icon = AppIcons.Heart,
                    title = stringResource(R.string.role_girlfriend_title),
                    name = stringResource(R.string.role_girlfriend_name),
                    description = stringResource(R.string.role_girlfriend_desc),
                    traits = listOf(
                        stringResource(R.string.role_girlfriend_trait_1),
                        stringResource(R.string.role_girlfriend_trait_2),
                        stringResource(R.string.role_girlfriend_trait_3)
                    ),
                    accentColor = Color(0xFFFF6B9D),
                    isSelected = selectedRole == CompanionRole.GIRLFRIEND,
                    onClick = { selectedRole = CompanionRole.GIRLFRIEND }
                )

                RoleOptionCard(
                    role = CompanionRole.BOYFRIEND,
                    icon = AppIcons.Shield,
                    title = stringResource(R.string.role_boyfriend_title),
                    name = stringResource(R.string.role_boyfriend_name),
                    description = stringResource(R.string.role_boyfriend_desc),
                    traits = listOf(
                        stringResource(R.string.role_boyfriend_trait_1),
                        stringResource(R.string.role_boyfriend_trait_2),
                        stringResource(R.string.role_boyfriend_trait_3)
                    ),
                    accentColor = Color(0xFF4A90E2),
                    isSelected = selectedRole == CompanionRole.BOYFRIEND,
                    onClick = { selectedRole = CompanionRole.BOYFRIEND }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(500, delayMillis = 240)) + slideInVertically(tween(500, delayMillis = 240)) { it / 5 }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { selectedRole?.let(onRoleSelected) },
                    enabled = selectedRole != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.colors.primary,
                        disabledContainerColor = AppTheme.colors.surfaceVariant,
                        disabledContentColor = AppTheme.colors.onSurfaceVariant
                    )
                ) {
                    Text(
                        text = stringResource(R.string.role_selection_confirm),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (onSkip != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.role_selection_skip),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSkip)
                            .padding(vertical = 8.dp),
                        color = AppTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun RoleOptionCard(
    role: CompanionRole,
    icon: ImageVector,
    title: String,
    name: String,
    description: String,
    traits: List<String>,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    val borderColor = if (isSelected) accentColor else colorScheme.outline.copy(alpha = 0.3f)
    val backgroundColor = if (isSelected) accentColor.copy(alpha = 0.08f) else colorScheme.surfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(20.dp)
    val surfaceColor = if (isPressed || isSelected) {
        backgroundColor.copy(alpha = (backgroundColor.alpha * 0.92f).coerceAtLeast(0.08f))
    } else {
        backgroundColor
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = surfaceColor
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.size(16.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    ),
                    color = colorScheme.onSurface
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            lineHeight = 22.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            traits.forEach { trait ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = trait,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
