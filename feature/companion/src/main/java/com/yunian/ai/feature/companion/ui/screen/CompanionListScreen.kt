package com.yunian.ai.feature.companion.ui.screen
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.viewmodel.CompanionListViewModel
import com.yunian.ai.uicommon.component.AppListItemLayout
import kotlinx.coroutines.delay
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionListScreen(
    onCompanionClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: CompanionListViewModel = viewModel()
) {
    val companions by viewModel.companions.collectAsState(initial = emptyList())
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.my_girlfriends),
                surfaceColor = AppTheme.colors.primary,
                titleColor = AppTheme.colors.staticWhite,
                actions = {
                    IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(R.string.cancel),
                            tint = AppTheme.colors.staticWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isVisible,
                enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)),
                exit = scaleOut()
            ) {
                FloatingActionButton(
                    onClick = onAddClick,
                    containerColor = AppTheme.colors.primary,
                    contentColor = AppTheme.colors.staticWhite,
                    shape = CircleShape,
                    modifier = Modifier.shadow(8.dp, CircleShape)
                ) {
                    Icon(
                        imageVector = AppIcons.Plus,
                        contentDescription = stringResource(R.string.add_girlfriend),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
                .padding(paddingValues)
        ) {
            if (companions.isEmpty()) {
                EmptyStateWithAnimation()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(companions) { index, companion ->
                        AnimatedVisibility(
                            visible = isVisible,
                            enter = fadeIn(
                                animationSpec = tween(300, delayMillis = index * 100)
                            ) + slideInVertically(
                                animationSpec = tween(300, delayMillis = index * 100),
                                initialOffsetY = { it / 2 }
                            ),
                            exit = fadeOut() + slideOutVertically()
                        ) {
                            CompanionCard(
                                companion = companion,
                                onClick = { onCompanionClick(companion.id) },
                                onDelete = { viewModel.deleteCompanion(companion) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompanionCard(
    companion: CompanionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "scale"
    )
    val cardShape = RoundedCornerShape(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(cardShape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = cardShape,
                surfaceColor = AppTheme.colors.surface
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            AppListItemLayout(
                isStartAligned = true,
                startSlot = {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .shadow(8.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        AppTheme.colors.primary.copy(alpha = 0.6f),
                                        AppTheme.colors.primary.copy(alpha = 0.8f),
                                        AppTheme.colors.primary.copy(alpha = 0.4f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (companion.avatarUrl != null) {
                            AsyncImage(
                                model = companion.avatarUrl,
                                contentDescription = companion.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = companion.name.firstOrNull()?.toString() ?: "?",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.staticWhite
                                )
                            )
                        }
                    }
                },
                endSlot = {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.alpha(0.6f)
                    ) {
                        Icon(
                            imageVector = AppIcons.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            tint = Color(0xFFFF6B6B),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                slotGap = 16.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = companion.name,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color(0xFF2D1F23)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = companion.personality,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5D4F53),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val tags = companion.tags
                    if (tags != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            tags.split(",").take(3).forEach { tag ->
                                val trimmedTag = tag.trim()
                                if (trimmedTag.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                AppTheme.colors.primary.copy(alpha = 0.6f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = trimmedTag,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Medium
                                            ),
                                            color = AppTheme.colors.staticWhite
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateWithAnimation() {
    var started by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 0.8f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "scale"
    )
    val alphaAnim by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(600),
        label = "alpha"
    )

    LaunchedEffect(Unit) { started = true }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alphaAnim
                }
        ) {

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .shadow(20.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AppTheme.colors.primary.copy(alpha = 0.8f),
                                AppTheme.colors.primary.copy(alpha = 0.6f),
                                AppTheme.colors.primary.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "\uD83D\uDC95",
                    fontSize = 48.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.no_companions),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = Color(0xFF5D4F53)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.create_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF8D7F83)
            )
        }
    }
}
