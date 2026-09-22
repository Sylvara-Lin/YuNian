package com.yunian.ai.feature.memory
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.memory.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.DiaryEntry
import com.yunian.ai.database.model.MemoryCategory
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: MemoryViewModel = viewModel()
) {
    val companions by viewModel.companions.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedCompanion by remember { mutableStateOf<CompanionEntity?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.memory_management),
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            if (companions.isEmpty()) {
                EmptyMemoryState()
            } else {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    companions.forEach { companion ->
                        CompanionChip(
                            companion = companion,
                            isSelected = selectedCompanion?.id == companion.id,
                            onClick = { selectedCompanion = companion }
                        )
                    }
                }

                selectedCompanion?.let { companion ->

                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = AppTheme.colors.primary.copy(alpha = 0.6f)
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Text(
                            stringResource(R.string.core_memory),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (selectedTab == 0) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Text(
                            stringResource(R.string.temp_memory),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (selectedTab == 1) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                            }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = {
                                Text(
                            stringResource(R.string.diary),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == 2) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (selectedTab == 2) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                            }
                        )
                    }

                    when (selectedTab) {
                        0 -> CoreMemoryTab(companionId = companion.id, viewModel = viewModel)
                        1 -> TempMemoryTab(companionId = companion.id, viewModel = viewModel)
                        2 -> DiaryTab(companionId = companion.id, viewModel = viewModel)
                    }
                } ?: run {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.select_companion_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompanionChip(
    companion: CompanionEntity,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(20.dp),
                surfaceColor = if (isSelected) {
                    AppTheme.colors.primary.copy(alpha = 0.8f)
                } else {
                    AppTheme.colors.surfaceVariant.copy(alpha = 0.6f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AppTheme.colors.primary.copy(alpha = 0.6f),
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.primary.copy(alpha = 0.8f)
                        )
                    )
                }
            }
            Text(
                text = companion.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                ),
                color = if (isSelected) AppTheme.colors.onPrimary else AppTheme.colors.onSurface
            )
        }
    }
}

@Composable
fun CoreMemoryTab(companionId: Long, viewModel: MemoryViewModel) {
    val memories by viewModel.getMemoriesForCompanion(companionId).collectAsStateWithLifecycle(initialValue = emptyList())
    val categories = remember { MemoryCategory.values().toList() }
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryRecord?>(null) }

    val filteredMemories = remember(memories, selectedCategory) {
        selectedCategory?.let { category ->
            memories.filter { it.toMemoryCategory() == category }
        } ?: memories
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.memory_local_only),
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    label = stringResource(R.string.all),
                    isSelected = selectedCategory == null,
                    onClick = { selectedCategory = null }
                )
                categories.forEach { category ->
                    FilterChip(
                        label = category.getDisplayName(),
                        isSelected = selectedCategory == category,
                        onClick = { selectedCategory = category }
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppTheme.colors.primary.copy(alpha = 0.1f))
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Plus,
                        contentDescription = "添加记忆",
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "添加",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = AppTheme.colors.primary
                    )
                }
            }
        }

        if (filteredMemories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_core_memory),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredMemories, key = { it.id }) { memory ->
                    MemoryItemCard(
                        memory = memory,
                        onDelete = { viewModel.deleteMemory(memory) },
                        onEdit = { editingMemory = it }
                    )
                }
            }
        }
    }

    if (showAddDialog || editingMemory != null) {
        MemoryEditDialog(
            companionId = companionId,
            existingMemory = editingMemory,
            categories = categories,
            onDismiss = {
                showAddDialog = false
                editingMemory = null
            },
            onSave = { memory ->
                if (editingMemory != null) {
                    viewModel.updateMemory(memory)
                } else {
                    viewModel.addManualMemory(
                        companionId = companionId,
                        content = memory.content,
                        category = memory.toMemoryCategory(),
                        importance = memory.importance,
                        context = memory.summary
                    )
                }
                showAddDialog = false
                editingMemory = null
            },
            onDelete = if (editingMemory != null) ({
                viewModel.deleteMemory(editingMemory!!)
                editingMemory = null
            }) else null
        )
    }
}

@Composable
fun TempMemoryTab(companionId: Long, viewModel: MemoryViewModel) {
    val tempMemories by viewModel.getTempMemoriesForCompanion(companionId).collectAsStateWithLifecycle(initialValue = emptyList())

    if (tempMemories.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.no_temp_memory),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tempMemories, key = { it.id }) { tempMemory ->
                TempMemoryItemCard(tempMemory = tempMemory)
            }
        }
    }
}

@Composable
fun DiaryTab(companionId: Long, viewModel: MemoryViewModel) {
    val diaries by viewModel.getDiariesForCompanion(companionId).collectAsStateWithLifecycle(initialValue = emptyList())
    val isGenerating by viewModel.isGeneratingDiary.collectAsStateWithLifecycle()
    var editingDiary by remember { mutableStateOf<DiaryEntry?>(null) }
    var pendingGenerated by remember { mutableStateOf<DiaryEntry?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val diaryGenerateFailedText = stringResource(R.string.diary_generate_failed)
    val diaryGeneratedTitle = stringResource(R.string.diary_generated_title)

    Column(modifier = Modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.diary_auto_tip),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
            TextButton(
                enabled = !isGenerating,
                onClick = {
                    viewModel.generateDiary(companionId) { generated ->
                        if (generated.isNullOrBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                diaryGenerateFailedText,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            pendingGenerated = DiaryEntry(
                                companionId = companionId,
                                title = diaryGeneratedTitle,
                                content = generated,
                                mood = 2,
                                date = System.currentTimeMillis(),
                                tags = "",
                                deviceId = ""
                            )
                        }
                    }
                }
            ) {
                Text(if (isGenerating) stringResource(R.string.diary_generating) else stringResource(R.string.ai_generate_diary))
            }
        }

        if (diaries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.no_diary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(diaries, key = { it.id }) { diary ->
                    DiaryItemCard(
                        diary = diary,
                        onEdit = { editingDiary = it },
                        onDelete = { viewModel.deleteDiary(diary) }
                    )
                }
            }
        }
    }

    if (editingDiary != null) {
        DiaryEditDialog(
            companionId = companionId,
            existingDiary = editingDiary,
            onDismiss = {
                editingDiary = null
            },
            onSave = { diary ->
                viewModel.updateDiary(diary)
                editingDiary = null
            },
            onDelete = {
                viewModel.deleteDiary(editingDiary!!)
                editingDiary = null
            },
        )
    }

    if (pendingGenerated != null) {
        DiaryEditDialog(
            companionId = companionId,
            existingDiary = pendingGenerated,
            onDismiss = { pendingGenerated = null },
            onSave = { diary ->
                viewModel.addDiary(
                    companionId = diary.companionId,
                    title = diary.title,
                    content = diary.content,
                    mood = diary.mood,
                    weather = diary.weather,
                    tags = diary.tags,
                    date = diary.date
                )
                pendingGenerated = null
            }
        )
    }
}

@Composable
fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) {
                    AppTheme.colors.primary.copy(alpha = 0.8f)
                } else {
                    AppTheme.colors.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (isSelected) AppTheme.colors.onPrimary else AppTheme.colors.onSurface
        )
    }
}

@Composable
fun MemoryItemCard(
    memory: MemoryRecord,
    onDelete: () -> Unit,
    onEdit: (MemoryRecord) -> Unit = {}
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val category = memory.toMemoryCategory()

    val cardShape = RoundedCornerShape(12.dp)
    AppListItemLayout(
        isStartAligned = true,
        startSlot = {},
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = cardShape,
                surfaceColor = AppTheme.colors.surface
            ),
        onClick = { onEdit(memory) },
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = category.getIcon(),
                        contentDescription = null,
                        tint = AppTheme.colors.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = category.getDisplayName(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = AppTheme.colors.primary.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                AppTheme.colors.primary.copy(alpha = memory.importance * 0.3f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.importance, (memory.importance * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = AppTheme.colors.primary.copy(alpha = 0.9f)
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = AppIcons.Trash2,
                        contentDescription = stringResource(R.string.memory_management),
                        tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = { onEdit(memory) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = AppIcons.Pencil,
                        contentDescription = "编辑记忆",
                        tint = AppTheme.colors.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = AppTheme.colors.onSurface.copy(alpha = 0.9f)
            )

            if (memory.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.context, memory.summary),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dateFormat.format(Date(memory.observedAt)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun TempMemoryItemCard(tempMemory: MemoryRecord) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val parsedMemory = remember(tempMemory.content) { parseTempMemoryContent(tempMemory.content) }
    val userText = parsedMemory.first
    val aiText = parsedMemory.second

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {},
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(12.dp),
                surfaceColor = AppTheme.colors.surface
            ),
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.History,
                    contentDescription = null,
                    tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = dateFormat.format(Date(tempMemory.createdAt)),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.you, userText.ifBlank { tempMemory.content }),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = AppTheme.colors.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (aiText.isNotBlank()) {
                Text(
                    text = "TA: $aiText",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = AppTheme.colors.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

private fun parseTempMemoryContent(content: String): Pair<String, String> {
    val parts = content.split(" | ", limit = 2)
    if (parts.size != 2) {
        return content.trim() to ""
    }

    // 用户标签写入端固定为「用户:」，直接剥离；剥离后为空则回退原串，避免气泡变空。
    val userText = parts[0].removePrefix("用户:").trim()
        .ifEmpty { parts[0].trim() }

    // AI 标签写作端可能是旧「AI:」或角色名（如「小梓:」），统一按通用说话人标签剥离。
    val aiRaw = parts[1].trim()
    val aiText = aiRaw.removeLeadingSpeakerLabel().ifEmpty { aiRaw }

    return userText to aiText
}

/**
 * 剥离形如 `<标签>: ` 的说话人前缀，兼容旧的 `AI: ` 与新的角色名标签（如 `小梓: `）。
 * 标签需不含空白与冒号、长度在 1..12 之间；不满足条件时原样返回。
 */
private fun String.removeLeadingSpeakerLabel(): String {
    val separatorIndex = indexOf(": ")
    if (separatorIndex <= 0) return this
    val label = substring(0, separatorIndex)
    val isValidLabel = label.length in 1..12 &&
        label.none { it.isWhitespace() || it == ':' }
    if (!isValidLabel) return this
    return substring(separatorIndex + 2).trim()
}

@Composable
fun DiaryItemCard(
    diary: DiaryEntry,
    onEdit: (DiaryEntry) -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {},
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(12.dp),
                surfaceColor = AppTheme.colors.surface
            ),
        onClick = { onEdit(diary) },
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Book,
                        contentDescription = null,
                        tint = AppTheme.colors.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = diary.moodDisplayName(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = AppTheme.colors.primary.copy(alpha = 0.8f)
                    )
                    if (diary.weather.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = diary.weather,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                Row {
                    IconButton(onClick = { onEdit(diary) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = AppIcons.Pencil,
                            contentDescription = stringResource(R.string.edit_diary),
                            tint = AppTheme.colors.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = AppIcons.Trash2,
                            contentDescription = stringResource(R.string.memory_management),
                            tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (diary.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = diary.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = AppTheme.colors.onSurface
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = diary.content,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = AppTheme.colors.onSurface.copy(alpha = 0.9f)
            )

            if (diary.tags.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    diary.tags.split(',').map { it.trim() }.filter { it.isNotBlank() }.take(4).forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppTheme.colors.primary.copy(alpha = 0.1f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = AppTheme.colors.primary.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = dateFormat.format(Date(diary.date)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DiaryEntry.moodDisplayName(): String = when (mood) {
    1 -> stringResource(R.string.mood_happy)
    3 -> stringResource(R.string.mood_sad)
    4 -> stringResource(R.string.mood_angry)
    5 -> stringResource(R.string.mood_touched)
    6 -> stringResource(R.string.mood_miss)
    else -> stringResource(R.string.mood_calm)
}

@Composable
fun EmptyMemoryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.MemoryStick,
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.no_companions),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.create_first),
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MemoryCategory.getDisplayName(): String = when (this) {
    MemoryCategory.FACT -> stringResource(R.string.fact)
    MemoryCategory.EMOTION -> stringResource(R.string.emotion)
    MemoryCategory.PREFERENCE -> stringResource(R.string.preference)
    MemoryCategory.EVENT -> stringResource(R.string.event)
    MemoryCategory.HABIT -> stringResource(R.string.habit)
    MemoryCategory.RELATIONSHIP -> stringResource(R.string.relationship)
}

fun MemoryCategory.getIcon(): ImageVector = when (this) {
    MemoryCategory.FACT -> AppIcons.MemoryStick
    MemoryCategory.EMOTION -> AppIcons.Star
    MemoryCategory.PREFERENCE -> AppIcons.Star
    MemoryCategory.EVENT -> AppIcons.History
    MemoryCategory.HABIT -> AppIcons.History
    MemoryCategory.RELATIONSHIP -> AppIcons.MemoryStick
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiaryEditDialog(
    companionId: Long,
    existingDiary: DiaryEntry?,
    onDismiss: () -> Unit,
    onSave: (DiaryEntry) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var title by remember { mutableStateOf(existingDiary?.title ?: "") }
    var content by remember { mutableStateOf(existingDiary?.content ?: "") }
    var mood by remember { mutableIntStateOf(existingDiary?.mood ?: 2) }
    var weather by remember { mutableStateOf(existingDiary?.weather ?: "") }
    var tags by remember { mutableStateOf(existingDiary?.tags ?: "") }
    val isEditing = existingDiary != null

    val moodOptions = listOf(
        1 to stringResource(R.string.mood_happy),
        2 to stringResource(R.string.mood_calm),
        3 to stringResource(R.string.mood_sad),
        4 to stringResource(R.string.mood_angry),
        5 to stringResource(R.string.mood_touched),
        6 to stringResource(R.string.mood_miss)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) stringResource(R.string.edit_diary) else stringResource(R.string.add_diary),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.diary_title)) },
                    placeholder = { Text(stringResource(R.string.diary_title_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.diary_content)) },
                    placeholder = { Text(stringResource(R.string.diary_content_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8
                )

                Text(
                    text = stringResource(R.string.diary_mood),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    moodOptions.forEach { (value, label) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (mood == value) {
                                        AppTheme.colors.primary.copy(alpha = 0.15f)
                                    } else {
                                        AppTheme.colors.surfaceVariant
                                    }
                                )
                                .clickable { mood = value }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if (mood == value) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = weather,
                    onValueChange = { weather = it },
                    label = { Text(stringResource(R.string.diary_weather)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.diary_tags)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) {
                        onSave(
                            existingDiary?.copy(
                                title = title.trim(),
                                content = content.trim(),
                                mood = mood,
                                weather = weather.trim(),
                                tags = tags.trim()
                            ) ?: DiaryEntry(
                                companionId = companionId,
                                title = title.trim(),
                                content = content.trim(),
                                mood = mood,
                                weather = weather.trim(),
                                tags = tags.trim()
                            )
                        )
                    }
                },
                enabled = content.isNotBlank()
            ) {
                Text(if (isEditing) "保存修改" else "添加")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = AppTheme.colors.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun MemoryEditDialog(
    companionId: Long,
    existingMemory: MemoryRecord?,
    categories: List<MemoryCategory>,
    onDismiss: () -> Unit,
    onSave: (MemoryRecord) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var content by remember { mutableStateOf(existingMemory?.content ?: "") }
    var selectedCategory by remember { mutableStateOf(existingMemory?.toMemoryCategory() ?: MemoryCategory.FACT) }
    var importance by remember { mutableStateOf(existingMemory?.importance ?: 0.7f) }
    var context by remember { mutableStateOf(existingMemory?.summary ?: "") }

    val isEditing = existingMemory != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isEditing) "编辑记忆" else "添加记忆",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("记忆内容") },
                    placeholder = { Text("例如：用户喜欢喝冰美式，每天早上都要买一杯") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { category ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selectedCategory == category) {
                                        AppTheme.colors.primary.copy(alpha = 0.15f)
                                    } else {
                                        AppTheme.colors.surfaceVariant
                                    }
                                )
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = category.getDisplayName(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = if (selectedCategory == category) {
                                    AppTheme.colors.primary
                                } else {
                                    AppTheme.colors.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "重要度: ${(importance * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("低", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
                    androidx.compose.material3.Slider(
                        value = importance,
                        onValueChange = { importance = it },
                        modifier = Modifier.weight(1f)
                    )
                    Text("高", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant)
                }

                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it },
                    label = { Text("补充说明（可选）") },
                    placeholder = { Text("记录该记忆的来源或背景信息") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) {
                        onSave(
                            existingMemory?.copy(
                                content = content.trim(),
                                memoryType = selectedCategory.toMemoryType(),
                                importance = importance,
                                summary = context.trim(),
                                tags = selectedCategory.name.lowercase(),
                                updatedAt = System.currentTimeMillis()
                            ) ?: MemoryRecord(
                                content = content.trim(),
                                memoryType = selectedCategory.toMemoryType(),
                                scope = com.yunian.ai.database.model.MemoryScope.COMPANION,
                                source = MemorySource.MANUAL,
                                sourceId = companionId,
                                importance = importance,
                                confidence = 1.0f,
                                summary = context.trim(),
                                tags = selectedCategory.name.lowercase()
                            )
                        )
                    }
                },
                enabled = content.isNotBlank()
            ) {
                Text(if (isEditing) "保存修改" else "添加")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = AppTheme.colors.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

private fun MemoryRecord.toMemoryCategory(): MemoryCategory = when (memoryType) {
    MemoryType.SEMANTIC -> MemoryCategory.FACT
    MemoryType.EPISODIC -> if (tags.contains("emotion", ignoreCase = true)) MemoryCategory.EMOTION else MemoryCategory.EVENT
    MemoryType.PREFERENCE -> MemoryCategory.PREFERENCE
    MemoryType.RELATIONSHIP -> MemoryCategory.RELATIONSHIP
    MemoryType.PROCEDURAL -> MemoryCategory.HABIT
    MemoryType.WORKING, MemoryType.FUZZY -> MemoryCategory.FACT
}

private fun MemoryCategory.toMemoryType(): MemoryType = when (this) {
    MemoryCategory.FACT -> MemoryType.SEMANTIC
    MemoryCategory.EMOTION -> MemoryType.EPISODIC
    MemoryCategory.PREFERENCE -> MemoryType.PREFERENCE
    MemoryCategory.EVENT -> MemoryType.EPISODIC
    MemoryCategory.HABIT -> MemoryType.PROCEDURAL
    MemoryCategory.RELATIONSHIP -> MemoryType.RELATIONSHIP
}
