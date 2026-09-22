package com.yunian.ai.feature.automation.ui
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import com.yunian.ai.uicommon.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(onNavigateBack: () -> Unit) {
    val viewModel: AutomationListViewModel = viewModel()
    val automations by viewModel.automations.collectAsStateWithLifecycle()
    val colorScheme = AppTheme.colors
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<Automation?>(null) }
    var editTarget by remember { mutableStateOf<Automation?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    GlassPageScaffold(
        modifier = Modifier
            .fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "自动化",
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        if (automations.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有自动化任务\n在对话里告诉 AI「每天早8点提醒我喝水」或「创建一个吃醋巡检工作流」试试吧",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(automations, key = { it.id }) { automation ->
                    AutomationRow(
                        automation = automation,
                        onToggle = { viewModel.toggleEnabled(automation.id, it) },
                        onDelete = { deleteTarget = automation },
                        onEdit = { editTarget = automation },
                        onFire = { viewModel.fire(automation.id) }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除自动化") },
            text = { Text("确定删除「${target.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    editTarget?.let { target ->
        AutomationEditDialog(
            automation = target,
            onDismiss = { editTarget = null },
            onSave = { updated ->
                viewModel.update(updated)
                editTarget = null
            }
        )
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onFire: () -> Unit
) {
    val colorScheme = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    automation.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    triggerText(automation),
                    fontSize = 13.sp,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    statsText(automation),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                if (automation.stats.lastMessage.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "上次内容：${automation.stats.lastMessage}",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
            Switch(checked = automation.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onFire) {
                Icon(AppIcons.Play, null, tint = colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("立即执行", fontSize = 12.sp, color = colorScheme.primary)
            }
            IconButton(onClick = onEdit) {
                Icon(AppIcons.Pencil, "编辑", tint = colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(AppIcons.Trash2, "删除", tint = colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditDialog(
    automation: Automation,
    onDismiss: () -> Unit,
    onSave: (Automation) -> Unit
) {
    val colorScheme = AppTheme.colors
    var title by remember { mutableStateOf(automation.title) }
    var hour by remember { mutableStateOf(automation.hourOfDay.toString()) }
    var minute by remember { mutableStateOf(automation.minuteOfHour.toString()) }
    var message by remember { mutableStateOf(automation.message) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑自动化") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (automation.type != AutomationType.ONCE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { hour = it.filter(Char::isDigit).take(2) },
                            label = { Text("时") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { minute = it.filter(Char::isDigit).take(2) },
                            label = { Text("分") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (!automation.isWorkflow) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("到点消息（伴侣发的文案）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (automation.isWorkflow)
                        "工作流节点与连线不支持在此编辑；修改后定时触发按新时间执行。"
                    else "修改保存后定时调度会自动重建。",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: automation.hourOfDay
                val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: automation.minuteOfHour
                onSave(
                    automation.copy(
                        title = title.ifBlank { automation.title },
                        hourOfDay = h,
                        minuteOfHour = m,
                        message = message.ifBlank { automation.message }
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun triggerText(a: Automation): String {
    val repeat = when (a.type) {
        AutomationType.ONCE -> "一次"
        AutomationType.DAILY -> "每天"
        AutomationType.WEEKLY -> {
            val name = when (a.dayOfWeek) {
                2 -> "周一"; 3 -> "周二"; 4 -> "周三"; 5 -> "周四"; 6 -> "周五"; 7 -> "周六"; 1 -> "周日"; else -> "每周"
            }
            name
        }
    }
    val time = if (a.type == AutomationType.ONCE) {
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(a.triggerAtMillis))
    } else {
        "${a.hourOfDay.toString().padStart(2, '0')}:${a.minuteOfHour.toString().padStart(2, '0')}"
    }
    val workflowMeta = if (a.isWorkflow) {
        "${a.nodes.size} 节点 · ${a.edges.size} 连线 · "
    } else "";
    return "$workflowMeta$repeat · $time · ${if (a.enabled) "启用" else "停用"}"
}

private fun statsText(a: Automation): String {
    val s = a.stats
    val base = if (a.isWorkflow) {
        "已执行 ${s.fireCount} 次 · 成功 ${s.successCount} · 失败 ${s.failCount}"
    } else {
        if (s.fireCount > 0) "已执行 ${s.fireCount} 次" else "尚未执行"
    }
    val last = if (s.lastFiredAt > 0) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(s.lastFiredAt))
        if (s.lastResult == "success") "上次成功 · $time" else "上次失败 · $time"
    } else {
        "未执行过"
    }
    return "$base · $last"
}
