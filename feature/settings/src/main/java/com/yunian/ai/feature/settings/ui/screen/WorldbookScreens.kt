@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.yunian.ai.domain.InjectionPosition
import com.yunian.ai.domain.EntryRole
import com.yunian.ai.domain.Lorebook
import com.yunian.ai.domain.LorebookEntry
import com.yunian.ai.domain.LorebookProvider
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.launch
import com.yunian.ai.feature.settings.worldbook.WorldbookEntryDto
import com.yunian.ai.feature.settings.worldbook.WorldbookExportDto
import com.yunian.ai.feature.settings.worldbook.WorldbookTransfer
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import androidx.compose.ui.graphics.Color

@Composable
fun WorldbookScreen(onNavigateBack: () -> Unit, onNavigateToDetail: (Long) -> Unit = {}) {
    val colorScheme = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lorebooks by remember { mutableStateOf<List<Lorebook>>(emptyList()) }
    var entryCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Lorebook?>(null) }

    fun refresh() {
        scope.launch {
            val provider = ServiceRegistry.get(LorebookProvider::class.java)
            if (provider != null) {
                val books = provider.getAllLorebooks()
                lorebooks = books
                // 并行统计每本世界书的条目数
                entryCounts = books.associate { book ->
                    book.id to provider.getEntries(book.id).size
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 导入：选 JSON 文件 → 解析 → 新建世界书（重名自动加后缀）+ 批量落库条目
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取文件")
                }
                // 阶段 5g §5.10：自动识别 ST World Info JSON 与旧 lianyu-worldbook 格式
                val dto = WorldbookTransfer.parseAny(text).getOrThrow()
                val provider = ServiceRegistry.get(LorebookProvider::class.java)
                    ?: throw IllegalStateException("服务未初始化")
                val taken = provider.getAllLorebooks().map { it.name }.toSet()
                var name = dto.name
                var suffix = 2
                while (name in taken) {
                    name = "${dto.name} ($suffix)"
                    suffix++
                }
                val now = System.currentTimeMillis()
                val newId = provider.createLorebook(
                    Lorebook(id = 0, name = name, description = dto.description, companionId = null, enabled = true, createdAt = now, updatedAt = now),
                    emptyList()
                )
                dto.entries.forEachIndexed { index, e ->
                    provider.upsertEntry(
                        LorebookEntry(
                            id = 0, lorebookId = newId, keywords = e.keywords, content = e.content,
                            injectionPosition = runCatching { InjectionPosition.valueOf(e.injectionPosition) }
                                .getOrDefault(InjectionPosition.AFTER_SYSTEM_PROMPT),
                            priority = e.priority, injectDepth = e.injectDepth,
                            role = runCatching { EntryRole.valueOf(e.role) }.getOrDefault(EntryRole.SYSTEM),
                            caseSensitive = e.caseSensitive, useRegex = e.useRegex,
                            scanDepth = e.scanDepth, constantActive = e.constantActive,
                            enabled = e.enabled, sortOrder = e.sortOrder,
                            createdAt = now, updatedAt = now
                        ).let { entry -> if (e.sortOrder == 0) entry.copy(sortOrder = index) else entry }
                    )
                }
                Toast.makeText(context, "已导入「$name」（${dto.entries.size} 个条目）", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败：${e.message ?: "无效文件"}", Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    if (showCreateDialog) {
        CreateLorebookDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc ->
                showCreateDialog = false
                scope.launch {
                    val provider = ServiceRegistry.get(LorebookProvider::class.java)
                    if (provider != null) {
                        val now = System.currentTimeMillis()
                        provider.createLorebook(
                            Lorebook(id = 0, name = name, description = desc, companionId = null, enabled = true, createdAt = now, updatedAt = now),
                            emptyList()
                        )
                    }
                    refresh()
                }
            }
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除世界书", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除「${book.name}」吗？其下所有条目将一并删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = book
                    pendingDelete = null
                    scope.launch {
                        ServiceRegistry.get(LorebookProvider::class.java)?.deleteLorebook(target.id)
                        refresh()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "世界书（知识库）",
                onBack = onNavigateBack,
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(AppIcons.Download, "导入世界书", tint = colorScheme.onSurface)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(AppIcons.Plus, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (lorebooks.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(AppIcons.Settings, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Text("暂无世界书", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Text("点击右下角 + 新建，或右上角导入 JSON", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(lorebooks, key = { it.id }) { lorebook ->
                    LorebookCard(
                        lorebook = lorebook,
                        entryCount = entryCounts[lorebook.id] ?: 0,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                ServiceRegistry.get(LorebookProvider::class.java)?.setLorebookEnabled(lorebook.id, enabled)
                                refresh()
                            }
                        },
                        onDelete = { pendingDelete = lorebook },
                        onClick = { onNavigateToDetail(lorebook.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LorebookCard(
    lorebook: Lorebook,
    entryCount: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lorebook.name,
                        fontWeight = FontWeight.Medium, fontSize = 16.sp,
                        color = colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (lorebook.description.isNotBlank()) {
                        Text(
                            lorebook.description, color = colorScheme.onSurfaceVariant, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Text(
                        "$entryCount 个条目" + if (!lorebook.enabled) " · 已停用" else "",
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(checked = lorebook.enabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Trash2, "删除", tint = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CreateLorebookDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建世界书", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("描述（可选）") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), desc.trim()) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun WorldbookDetailScreen(worldbookId: Long, onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LorebookEntry>>(emptyList()) }
    var worldbookName by remember { mutableStateOf("世界书") }
    var loading by remember { mutableStateOf(true) }
    var editingEntry by remember { mutableStateOf<LorebookEntry?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LorebookEntry?>(null) }

    fun refresh() {
        scope.launch {
            val provider = ServiceRegistry.get(LorebookProvider::class.java)
            if (provider != null) {
                val detail = provider.getLorebookWithEntries(worldbookId)
                worldbookName = detail?.lorebook?.name ?: "世界书"
                // 全量条目（含禁用），禁用条目仍需可见以便重新启用
                entries = detail?.entries ?: emptyList()
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    // 导出：SAF CreateDocument，写出当前书全部条目为 JSON
    var exporting by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val payload = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dto = WorldbookExportDto(
                        name = worldbookName,
                        description = "",
                        entries = entries.map { e ->
                            WorldbookEntryDto(
                                keywords = e.keywords, content = e.content,
                                injectionPosition = e.injectionPosition.name, role = e.role.name,
                                priority = e.priority, injectDepth = e.injectDepth,
                                scanDepth = e.scanDepth, caseSensitive = e.caseSensitive,
                                useRegex = e.useRegex, constantActive = e.constantActive,
                                enabled = e.enabled, sortOrder = e.sortOrder
                            )
                        }
                    )
                    // 阶段 5g §5.10：导出为 ST World Info JSON（map 格式 entries），
                    // 既可无损回导，也可直接投喂 SillyTavern / Rust 引擎
                    WorldbookTransfer.serializeSt(dto)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法写入文件")
                }
                Toast.makeText(context, "已导出 ${entries.size} 个条目", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 拖拽排序：长按条目拖动，松手后按当前顺序批量持久化 sortOrder
    val listState = rememberLazyListState()
    val dragDropState = rememberDragDropState(listState) { from, to ->
        entries = entries.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun persistOrder() {
        scope.launch {
            val provider = ServiceRegistry.get(LorebookProvider::class.java) ?: return@launch
            val now = System.currentTimeMillis()
            entries.forEachIndexed { index, entry ->
                if (entry.sortOrder != index) {
                    provider.upsertEntry(entry.copy(sortOrder = index, updatedAt = now))
                }
            }
            refresh()
        }
    }

    // 编辑/新建条目对话框：新建时传入正确的 worldbookId（修复 lorebookId=0 的致命 bug）
    editingEntry?.let { entry ->
        EntryEditDialog(
            worldbookId = worldbookId,
            initial = entry,
            onDismiss = { editingEntry = null },
            onConfirm = { saved ->
                editingEntry = null
                scope.launch {
                    ServiceRegistry.get(LorebookProvider::class.java)?.upsertEntry(saved)
                    refresh()
                }
            }
        )
    }

    if (showCreateDialog) {
        EntryEditDialog(
            worldbookId = worldbookId,
            initial = null,
            nextSortOrder = entries.size,
            onDismiss = { showCreateDialog = false },
            onConfirm = { saved ->
                showCreateDialog = false
                scope.launch {
                    ServiceRegistry.get(LorebookProvider::class.java)?.upsertEntry(saved)
                    refresh()
                }
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除条目", fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除该条目吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    pendingDelete = null
                    scope.launch {
                        ServiceRegistry.get(LorebookProvider::class.java)?.deleteEntry(target.id)
                        refresh()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = worldbookName,
                onBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = {
                            exporting = true
                            exportLauncher.launch(WorldbookTransfer.defaultFileName(worldbookName))
                        },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(AppIcons.ExternalLink, "导出 JSON", tint = colorScheme.onSurface)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(AppIcons.Plus, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无条目", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Text("点击右下角 + 添加条目", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    val index = entries.indexOfFirst { it.id == entry.id }
                    val dragging = index >= 0 && index == dragDropState.draggingItemIndex
                    EntryCard(
                        entry = entry,
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragDropState.draggingItemOffset else 0f }
                            .then(dragDropState.dragModifierFor(index) { persistOrder() }),
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                ServiceRegistry.get(LorebookProvider::class.java)?.setEntryEnabled(entry.id, enabled)
                                refresh()
                            }
                        },
                        onEdit = { editingEntry = entry },
                        onDelete = { pendingDelete = entry }
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: LorebookEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = AppTheme.colors
    Card(
        modifier = modifier.drawGlass(
            backdrop = LocalPageBackdrop.current,
            shape = RoundedCornerShape(16.dp),
            surfaceColor = colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (entry.keywords.isNotEmpty()) {
                        Text(
                            entry.keywords.joinToString(" / "),
                            color = colorScheme.primary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            if (entry.constantActive) "常驻激活" else "（无关键词）",
                            color = colorScheme.onSurfaceVariant, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        entry.content,
                        color = colorScheme.onSurfaceVariant, fontSize = 13.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        buildString {
                            append("位置：${positionLabel(entry.injectionPosition)}")
                            if (entry.injectionPosition == InjectionPosition.AT_DEPTH) append(" · 深度 ${entry.injectDepth ?: 4}")
                            append("  角色：${roleLabel(entry.role)}")
                            append("  优先级：${entry.priority}")
                            if (entry.useRegex) append(" · 正则")
                            if (entry.caseSensitive) append(" · 区分大小写")
                            if (entry.constantActive) append(" · 常驻")
                            if (!entry.enabled) append(" · 已停用")
                        },
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Switch(checked = entry.enabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = onEdit) {
                    Icon(AppIcons.Pencil, "编辑", tint = colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) {
                    Icon(AppIcons.Trash2, "删除", tint = colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 条目编辑对话框：创建与编辑共用
 * - initial 为 null 时是新建（lorebookId 取 worldbookId，此前硬编码 0 导致条目无法回显/生效）
 * - initial 非 null 时是编辑，保留 id/lorebookId/createdAt
 */
@Composable
private fun EntryEditDialog(
    worldbookId: Long,
    initial: LorebookEntry?,
    onDismiss: () -> Unit,
    onConfirm: (LorebookEntry) -> Unit,
    nextSortOrder: Int = 0
) {
    val isEdit = initial != null
    var keywords by remember { mutableStateOf(initial?.keywords?.joinToString("，") ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var position by remember { mutableStateOf(initial?.injectionPosition ?: InjectionPosition.AFTER_SYSTEM_PROMPT) }
    var role by remember { mutableStateOf(initial?.role ?: EntryRole.SYSTEM) }
    var priority by remember { mutableStateOf((initial?.priority ?: 0).toString()) }
    var injectDepth by remember { mutableStateOf((initial?.injectDepth ?: 4).toString()) }
    var scanDepth by remember { mutableStateOf((initial?.scanDepth ?: 10).toString()) }
    var caseSensitive by remember { mutableStateOf(initial?.caseSensitive ?: false) }
    var useRegex by remember { mutableStateOf(initial?.useRegex ?: false) }
    var constantActive by remember { mutableStateOf(initial?.constantActive ?: false) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var showPositionMenu by remember { mutableStateOf(false) }
    var showRoleMenu by remember { mutableStateOf(false) }

    val kwList = keywords.split(Regex("[，,、/\\s]+")).filter { it.isNotBlank() }
    val canSave = (kwList.isNotEmpty() || constantActive) && content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑条目" else "添加条目", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("关键词（逗号分隔，常驻激活时可不填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("注入内容（将按注入位置写入提示词）") },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(expanded = showPositionMenu, onExpandedChange = { showPositionMenu = it }) {
                    OutlinedTextField(value = positionLabel(position), onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(), label = { Text("注入位置") })
                    ExposedDropdownMenu(expanded = showPositionMenu, onDismissRequest = { showPositionMenu = false }) {
                        InjectionPosition.values().forEach { p ->
                            DropdownMenuItem(text = { Text(positionLabel(p)) }, onClick = { position = p; showPositionMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 仅独立消息位置需要选择注入角色（系统提示词位置固定合并进 system）
                if (position == InjectionPosition.TOP_OF_CHAT || position == InjectionPosition.BOTTOM_OF_CHAT || position == InjectionPosition.AT_DEPTH) {
                    ExposedDropdownMenuBox(expanded = showRoleMenu, onExpandedChange = { showRoleMenu = it }) {
                        OutlinedTextField(value = roleLabel(role), onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(), label = { Text("注入角色") })
                        ExposedDropdownMenu(expanded = showRoleMenu, onDismissRequest = { showRoleMenu = false }) {
                            EntryRole.values().forEach { r ->
                                DropdownMenuItem(text = { Text(roleLabel(r)) }, onClick = { role = r; showRoleMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (position == InjectionPosition.AT_DEPTH) {
                    OutlinedTextField(
                        value = injectDepth,
                        onValueChange = { injectDepth = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("注入深度（1=最后一条消息前，2=倒数第二条前…）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text("优先级（越大越优先注入）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = scanDepth,
                    onValueChange = { scanDepth = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("扫描深度（扫描最近 N 条消息匹配关键词）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                LabeledSwitch("启用条目", enabled) { enabled = it }
                LabeledSwitch("常驻激活（无需关键词，始终注入）", constantActive) { constantActive = it }
                LabeledSwitch("使用正则匹配关键词", useRegex) { useRegex = it }
                LabeledSwitch("区分大小写", caseSensitive) { caseSensitive = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        val now = System.currentTimeMillis()
                        onConfirm(
                            LorebookEntry(
                                id = initial?.id ?: 0,
                                // 修复：新建条目必须挂到当前世界书，此前硬编码 0 导致条目孤立、无法回显与生效
                                lorebookId = initial?.lorebookId ?: worldbookId,
                                keywords = if (constantActive && kwList.isEmpty()) emptyList() else kwList,
                                content = content.trim(),
                                injectionPosition = position,
                                priority = priority.toIntOrNull() ?: 0,
                                injectDepth = if (position == InjectionPosition.AT_DEPTH) (injectDepth.toIntOrNull() ?: 4) else null,
                                role = role,
                                caseSensitive = caseSensitive,
                                useRegex = useRegex,
                                scanDepth = scanDepth.toIntOrNull()?.coerceIn(1, 100) ?: 10,
                                constantActive = constantActive,
                                enabled = enabled,
                                sortOrder = initial?.sortOrder ?: nextSortOrder,
                                createdAt = initial?.createdAt ?: now,
                                updatedAt = now
                            )
                        )
                    }
                },
                enabled = canSave
            ) { Text(if (isEdit) "保存" else "添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * LazyColumn 长按拖拽排序状态（社区通用实现）
 * 拖动中实时换位，松手后由调用方持久化新顺序
 */
private class DragDropState(
    val lazyListState: LazyListState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onMove: (Int, Int) -> Unit
) {
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set
    var draggingItemOffset by mutableFloatStateOf(0f)
        private set
    private var draggedDistance = 0f
    private var autoScrollDelta = 0f

    internal fun consumeScrollFrame(): Float {
        val d = autoScrollDelta
        autoScrollDelta = 0f
        return d
    }

    fun dragModifierFor(index: Int, onDragEnd: () -> Unit): Modifier = Modifier.pointerInput(index) {
        detectDragGesturesAfterLongPress(
            onDragStart = { _ ->
                if (lazyListState.layoutInfo.visibleItemsInfo.any { it.index == index }) {
                    draggingItemIndex = index
                    draggedDistance = 0f
                    draggingItemOffset = 0f
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                val current = draggingItemIndex ?: return@detectDragGesturesAfterLongPress
                draggedDistance += dragAmount.y

                val layout = lazyListState.layoutInfo
                val currentInfo = layout.visibleItemsInfo.firstOrNull { it.index == current }
                if (currentInfo == null) return@detectDragGesturesAfterLongPress

                // 换位检测：被拖项中心越入其他可见项范围
                val draggedCenter = currentInfo.offset + draggedDistance + currentInfo.size / 2f
                val target = layout.visibleItemsInfo.firstOrNull { info ->
                    info.index != current && draggedCenter > info.offset && draggedCenter < info.offset + info.size
                }
                if (target != null) {
                    val shift = target.offset - currentInfo.offset
                    onMove(current, target.index)
                    draggingItemIndex = target.index
                    draggedDistance -= shift
                }
                draggingItemOffset = draggedDistance

                // 边缘自动滚动
                val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                val nearTop = draggedCenter - layout.viewportStartOffset < layout.visibleItemsInfo.firstOrNull()?.size?.times(0.6f) ?: 0f
                val nearBottom = layout.viewportEndOffset - draggedCenter < layout.visibleItemsInfo.firstOrNull()?.size?.times(0.6f) ?: 0f
                autoScrollDelta = when {
                    nearTop -> -viewport * 0.03f
                    nearBottom -> viewport * 0.03f
                    else -> 0f
                }
            },
            onDragEnd = {
                val wasDragging = draggingItemIndex != null
                draggingItemIndex = null
                draggingItemOffset = 0f
                autoScrollDelta = 0f
                if (wasDragging) onDragEnd()
            },
            onDragCancel = {
                draggingItemIndex = null
                draggingItemOffset = 0f
                autoScrollDelta = 0f
            }
        )
    }
}

@Composable
private fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (Int, Int) -> Unit
): DragDropState {
    val scope = rememberCoroutineScope()
    val state = remember(lazyListState) { DragDropState(lazyListState, scope, onMove) }
    LaunchedEffect(state) {
        while (true) {
            val delta = state.consumeScrollFrame()
            if (delta != 0f) {
                lazyListState.dispatchRawDelta(delta)
            } else {
                kotlinx.coroutines.delay(16)
            }
        }
    }
    return state
}

private fun positionLabel(p: InjectionPosition): String = when (p) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> "系统提示词之前"
    InjectionPosition.AFTER_SYSTEM_PROMPT -> "系统提示词之后"
    InjectionPosition.TOP_OF_CHAT -> "对话历史顶部"
    InjectionPosition.BOTTOM_OF_CHAT -> "对话历史底部"
    InjectionPosition.AT_DEPTH -> "指定深度"
}

private fun roleLabel(r: EntryRole): String = when (r) {
    EntryRole.SYSTEM -> "系统"
    EntryRole.USER -> "用户"
    EntryRole.ASSISTANT -> "助手"
}
