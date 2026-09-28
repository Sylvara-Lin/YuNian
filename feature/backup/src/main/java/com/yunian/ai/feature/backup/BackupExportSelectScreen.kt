package com.yunian.ai.feature.backup
import com.yunian.ai.uicommon.icon.AppIcons


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.uicommon.component.CompanionAvatar
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.AppThemeColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupExportSelectScreen(onNavigateBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    val viewModel: BackupViewModel = viewModel()

    val stats by viewModel.companionStats.collectAsState()
    val statsLoading by viewModel.statsLoading.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.loadCompanionStats()
        kotlinx.coroutines.delay(80)
        isVisible = true
    }

    val exportSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { dest ->
            pendingExportBytes?.let { bytes ->
                scope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        }
                        viewModel.onExportComplete()
                    } catch (e: Exception) {
                        viewModel.onError("保存文件失败: ${e.localizedMessage}")
                    }
                    pendingExportBytes = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { encryptedBytes ->
            pendingExportBytes = encryptedBytes
            exportSaveLauncher.launch("lianyu_backup_${dateString()}.lybk")
        }
    }

    if (showPasswordDialog) {
        PasswordDialog(
            mode = PasswordMode.EXPORT,
            isLoading = uiState is BackupViewModel.UiState.Exporting,
            onConfirm = { password ->
                viewModel.export(password, selectedIds)
                showPasswordDialog = false
            },
            onDismiss = {
                showPasswordDialog = false
                if (uiState !is BackupViewModel.UiState.Exporting) viewModel.resetState()
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is BackupViewModel.UiState.Success -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            is BackupViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            else -> {}
        }
    }

    val allSelected = stats.isNotEmpty() && stats.all { it.companionId in selectedIds }

    GlassPageScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = "联系人",
                onBack = onNavigateBack,
                actions = {
                    if (stats.isNotEmpty()) {
                        TextButton(onClick = {
                            selectedIds = if (allSelected) emptySet()
                            else stats.map { it.companionId }.toSet()
                        }) {
                            Text(
                                if (allSelected) "取消全选" else "全选",
                                color = colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {

            Surface(
                color = colorScheme.background,
                shadowElevation = 8.dp
            ) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            if (selectedIds.isNotEmpty()) showPasswordDialog = true
                        },
                        enabled = selectedIds.isNotEmpty() &&
                            uiState !is BackupViewModel.UiState.Exporting,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedIds.isNotEmpty()) AppTheme.colors.success
                            else colorScheme.surfaceVariant
                        )
                    ) {
                        if (uiState is BackupViewModel.UiState.Exporting) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = AppTheme.colors.staticWhite, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (uiState is BackupViewModel.UiState.Exporting) "正在导出..."
                            else "导出(${selectedIds.size})",
                            color = AppTheme.colors.staticWhite,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "选择你要导出的数据，可保存至本地或传输到其他设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        when {
            statsLoading && stats.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("正在统计联系人数据...", color = colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            stats.isEmpty() -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无联系人", color = colorScheme.onSurfaceVariant, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("创建联系人与 AI 对话后即可导出", color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            else -> {
                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 6 }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(stats, key = { it.companionId }) { stat ->
                            ContactSelectCard(
                                stat = stat,
                                selected = stat.companionId in selectedIds,
                                onClick = {
                                    selectedIds = if (stat.companionId in selectedIds) {
                                        selectedIds - stat.companionId
                                    } else {
                                        selectedIds + stat.companionId
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSelectCard(
    stat: CompanionExportStat,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colorScheme.primaryContainer.copy(alpha = 0.35f) else colorScheme.surfaceVariant)
            .then(
                if (selected) Modifier.border(1.5.dp, colorScheme.primary, shape)
                else Modifier.border(1.dp, colorScheme.outlineVariant, shape)
            )
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CompanionAvatar(
                avatarUrl = stat.avatarUrl,
                name = stat.name,
                size = 56.dp
            )

            Icon(
                imageVector = if (selected) AppIcons.CircleCheckBig else AppIcons.Circle,
                contentDescription = if (selected) "已选择" else "未选择",
                tint = if (selected) colorScheme.primary else colorScheme.outline,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stat.name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))

        StatLine(label = "聊天数据", value = "${stat.messageCount} 条", colorScheme = colorScheme)
        StatLine(label = "占用大小", value = formatSize(stat.totalSizeBytes), colorScheme = colorScheme)
        StatLine(label = "最后数据", value = formatLastTime(stat.lastTimestamp), colorScheme = colorScheme)
    }
}

@Composable
private fun StatLine(
    label: String,
    value: String,
    colorScheme: AppThemeColors
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatLastTime(timestamp: Long): String {
    if (timestamp <= 0) return "暂无"
    val now = System.currentTimeMillis()
    val sdf = if (now - timestamp < 24 * 3600 * 1000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    } else if (now - timestamp < 365 * 24 * 3600 * 1000L) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    } else {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    return sdf.format(Date(timestamp))
}
