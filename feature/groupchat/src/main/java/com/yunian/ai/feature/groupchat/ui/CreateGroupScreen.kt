package com.yunian.ai.feature.groupchat.ui
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.groupchat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.viewmodel.ChatGroupViewModel
import com.yunian.ai.database.viewmodel.CompanionListViewModel
import kotlinx.coroutines.delay
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onNavigateBack: () -> Unit,
    companionListViewModel: CompanionListViewModel = viewModel(),
    groupViewModel: ChatGroupViewModel = viewModel()
) {
    val companions by companionListViewModel.companions.collectAsState(initial = emptyList())
    var groupName by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.create_group),
                onBack = onNavigateBack,
                actions = {
                    val canCreate = groupName.isNotBlank() && selectedIds.isNotEmpty()
                    Text(
                        text = stringResource(R.string.create),
                        color = if (canCreate) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(enabled = canCreate) {
                                groupViewModel.createGroup(groupName.trim(), selectedIds.toList())
                                onNavigateBack()
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }
            ) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.group_name), color = AppTheme.colors.onSurfaceVariant) },
                    placeholder = { Text(stringResource(R.string.group_name_hint), color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.colors.primary,
                        unfocusedBorderColor = AppTheme.colors.outline,
                        focusedContainerColor = AppTheme.colors.surface,
                        unfocusedContainerColor = AppTheme.colors.surface,
                        focusedTextColor = AppTheme.colors.onSurface,
                        unfocusedTextColor = AppTheme.colors.onSurface
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.select_companions, selectedIds.size, companions.size),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (companions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_companions),
                        color = AppTheme.colors.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(companions) { companion ->
                        val isSelected = selectedIds.contains(companion.id)
                        CompanionSelectItem(
                            companion = companion,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    selectedIds.remove(companion.id)
                                } else {
                                    selectedIds.add(companion.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompanionSelectItem(
    companion: CompanionEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(12.dp),
                    surfaceColor = AppTheme.colors.surface
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceVariant),
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
                    Icon(
                        imageVector = AppIcons.User,
                        contentDescription = null,
                        tint = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = companion.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = AppTheme.colors.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(AppTheme.colors.surfaceVariant)
                )
            }
        }
    }
}
