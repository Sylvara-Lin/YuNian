package com.yunian.ai.feature.wechat.ui
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons

import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.collectAsState
import com.yunian.ai.domain.wechat.WeChatChannelHealthSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.SettingItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeChatSettingsScreen(
    onNavigateBack: () -> Unit,
    onBindClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: WeChatViewModel = viewModel(factory = remember { WeChatViewModelFactory(context.applicationContext as android.app.Application) })
    val uiState by viewModel.uiState.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showCompanionDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddMappingDialog by remember { mutableStateOf(false) }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "微信设置",
                onBack = onNavigateBack
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            WeChatStatusCard(
                isLoggedIn = uiState.isLoggedIn,
                accountId = uiState.account?.ilinkBotId,
                customName = uiState.customBotName,
                onBindClick = onBindClick,
                onUnbindClick = { showLogoutDialog = true },
                onRenameClick = { showRenameDialog = true }
            )

            if (uiState.isLoggedIn) {
                Spacer(modifier = Modifier.height(12.dp))
                ChannelHealthCard(
                    health = uiState.channelHealth,
                    onRefresh = { viewModel.refreshChannelHealth() },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "功能设置",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            @Suppress("DEPRECATION")
            SettingItem(
                icon = AppIcons.MessageCircle,
                title = "消息通知",
                subtitle = "微信消息到达时推送通知",
                trailing = {
                    Switch(
                        checked = uiState.notifyEnabled,
                        onCheckedChange = { viewModel.toggleNotifyEnabled(it) }
                    )
                }
            )

            HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))

            SettingItem(
                icon = AppIcons.Link,
                title = "自动回复",
                subtitle = "收到微信消息后自动调用 AI 回复",
                trailing = {
                    Switch(
                        checked = uiState.autoReply,
                        onCheckedChange = { viewModel.toggleAutoReply(it) }
                    )
                }
            )

            HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))

            @Suppress("DEPRECATION")
            SettingItem(
                icon = AppIcons.MessageCircle,
                title = "消息转发",
                subtitle = "将 AI 消息同步发送到微信",
                trailing = {
                    Switch(
                        checked = uiState.forwardEnabled,
                        onCheckedChange = { viewModel.toggleForwardEnabled(it) }
                    )
                }
            )

            if (uiState.availableCompanions.isNotEmpty()) {
                HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))

                @Suppress("DEPRECATION")
                SettingItem(
                    icon = AppIcons.MessageCircle,
                    title = "默认 AI 伴侣",
                    subtitle = uiState.availableCompanions.find { it.id == uiState.defaultCompanionId }?.name
                        ?: "未选择（使用第一个）",
                    trailing = {
                        TextButton(onClick = { showCompanionDialog = true }) {
                            Text("选择")
                        }
                    }
                )
            }

            if (uiState.isLoggedIn && uiState.availableCompanions.isNotEmpty()) {
                HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "微信用户人设分配",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddMappingDialog = true }) {
                        Icon(
                            imageVector = AppIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("添加")
                    }
                }

                val mappings = uiState.userMappings.ifEmpty {
                    uiState.userCompanionMappings.map { (uid, cid) ->
                        com.yunian.ai.domain.wechat.WeChatUserMapping(uid, cid)
                    }
                }

                if (mappings.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surfaceVariant
                            )
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "暂无映射",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppTheme.colors.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "用户首次发消息时会自动绑定默认伴侣；也可手动添加微信用户 ID。",
                            fontSize = 12.sp,
                            color = AppTheme.colors.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                } else {
                    mappings.forEachIndexed { index, mapping ->
                        var showMappingDialog by remember(mapping.wechatUserId) { mutableStateOf(false) }
                        var showDeleteConfirm by remember(mapping.wechatUserId) { mutableStateOf(false) }
                        val wechatUserId = mapping.wechatUserId
                        val companionId = mapping.companionId
                        val mappedCompanionName = uiState.availableCompanions.find { it.id == companionId }?.name
                            ?: "未知 (ID: $companionId)"

                        SettingItem(
                            icon = AppIcons.Link,
                            title = "用户 $wechatUserId",
                            subtitle = "人设: $mappedCompanionName",
                            trailing = {
                                Row {
                                    TextButton(onClick = { showMappingDialog = true }) {
                                        Text("切换")
                                    }
                                    IconButton(onClick = { showDeleteConfirm = true }) {
                                        Icon(
                                            imageVector = AppIcons.Trash2,
                                            contentDescription = "删除映射",
                                            tint = AppTheme.colors.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        )

                        if (index < mappings.size - 1) {
                            HorizontalDivider(
                                color = AppTheme.colors.outline,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }

                        if (showMappingDialog) {
                            AlertDialog(
                                onDismissRequest = { showMappingDialog = false },
                                title = { Text("为用户 $wechatUserId 选择 AI 伴侣") },
                                text = {
                                    Column {
                                        uiState.availableCompanions.forEach { companion ->
                                            TextButton(
                                                onClick = {
                                                    viewModel.setUserCompanionMapping(wechatUserId, companion.id)
                                                    showMappingDialog = false
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    companion.name,
                                                    color = if (companion.id == companionId)
                                                        AppTheme.colors.primary else AppTheme.colors.onSurface
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    TextButton(onClick = { showMappingDialog = false }) {
                                        Text("取消")
                                    }
                                }
                            )
                        }

                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("删除映射") },
                                text = { Text("确定要删除用户 $wechatUserId 的人设映射吗？删除后将使用默认 AI 伴侣。") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.removeUserCompanionMapping(wechatUserId)
                                        showDeleteConfirm = false
                                    }) {
                                        Text("删除", color = AppTheme.colors.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) {
                                        Text("取消")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "说明",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surfaceVariant
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = "• 基于腾讯 ilink 协议，仅支持私聊\n" +
                           "• 绑定后请用微信给机器人发消息以激活会话\n" +
                           "• Token 有效期约数天，过期后需重新绑定",
                    fontSize = 13.sp,
                    color = AppTheme.colors.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("解除绑定") },
            text = { Text("确定要解除微信绑定吗？解除后将无法通过微信接收消息。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    showLogoutDialog = false
                }) {
                    Text("确定", color = AppTheme.colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCompanionDialog) {
        AlertDialog(
            onDismissRequest = { showCompanionDialog = false },
            title = { Text("选择默认 AI 伴侣") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.setDefaultCompanionId(null)
                            showCompanionDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "自动分配（使用第一个）",
                            color = if (uiState.defaultCompanionId == null) AppTheme.colors.primary else AppTheme.colors.onSurface
                        )
                    }
                    uiState.availableCompanions.forEach { companion ->
                        TextButton(
                            onClick = {
                                viewModel.setDefaultCompanionId(companion.id)
                                showCompanionDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                companion.name,
                                color = if (companion.id == uiState.defaultCompanionId) AppTheme.colors.primary else AppTheme.colors.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCompanionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(uiState.customBotName ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("设置机器人名字") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCustomBotName(newName.takeIf { it.isNotBlank() })
                    showRenameDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showAddMappingDialog) {
        var userIdInput by remember { mutableStateOf("") }
        var selectedCompanionId by remember {
            mutableStateOf(
                uiState.defaultCompanionId
                    ?: uiState.availableCompanions.firstOrNull()?.id
                    ?: 0L,
            )
        }
        AlertDialog(
            onDismissRequest = { showAddMappingDialog = false },
            title = { Text("添加用户映射") },
            text = {
                Column {
                    OutlinedTextField(
                        value = userIdInput,
                        onValueChange = { userIdInput = it },
                        label = { Text("微信用户 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "选择 AI 伴侣",
                        fontSize = 13.sp,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    uiState.availableCompanions.forEach { companion ->
                        TextButton(
                            onClick = { selectedCompanionId = companion.id },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                companion.name,
                                color = if (companion.id == selectedCompanionId) {
                                    AppTheme.colors.primary
                                } else {
                                    AppTheme.colors.onSurface
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uid = userIdInput.trim()
                        if (uid.isNotBlank() && selectedCompanionId > 0) {
                            viewModel.addUserCompanionMapping(uid, selectedCompanionId)
                            showAddMappingDialog = false
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMappingDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun WeChatStatusCard(
    isLoggedIn: Boolean,
    accountId: String?,
    customName: String?,
    onBindClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLoggedIn) AppTheme.colors.success.copy(alpha = 0.1f) else AppTheme.colors.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isLoggedIn) AppIcons.CircleCheckBig else AppIcons.TriangleAlert,
            contentDescription = null,
            tint = if (isLoggedIn) AppTheme.colors.success else AppTheme.colors.warning,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isLoggedIn) {
                customName ?: "微信已绑定"
            } else {
                "未绑定微信"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.onSurface
        )
        if (isLoggedIn && !accountId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "ID: $accountId",
                fontSize = 12.sp,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn) {
                TextButton(
                    onClick = onRenameClick,
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = AppTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = "改名",
                        fontSize = 13.sp,
                        color = AppTheme.colors.primary
                    )
                }
                TextButton(
                    onClick = onUnbindClick,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "解除绑定",
                        fontSize = 13.sp,
                        color = AppTheme.colors.error
                    )
                }
            } else {
                TextButton(
                    onClick = onBindClick,
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "立即绑定",
                        fontSize = 13.sp,
                        color = AppTheme.colors.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelHealthCard(
    health: WeChatChannelHealthSnapshot,
    onRefresh: () -> Unit,
) {
    val pollerLabel = if (health.primaryPollerActive) "主轮询运行中" else "主轮询未持有（Worker 兜底）"
    val pollerColor = if (health.primaryPollerActive) AppTheme.colors.success else AppTheme.colors.warning
    val lastPoll = formatEpochMs(health.lastPollAtMs)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(12.dp),
                surfaceColor = AppTheme.colors.surfaceVariant
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "通道状态",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = AppIcons.RefreshCw,
                    contentDescription = "刷新",
                    tint = AppTheme.colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = pollerLabel, fontSize = 13.sp, color = pollerColor)
        Text(
            text = "连续失败 ${health.consecutiveFailures} · Outbox 打开 ${health.openOutboxCount}" +
                "（待发 ${health.pendingOutboxCount} / 发送中 ${health.sendingOutboxCount} / 失败 ${health.failedOutboxCount}）",
            fontSize = 12.sp,
            color = AppTheme.colors.onSurfaceVariant,
            lineHeight = 18.sp,
        )
        Text(
            text = "最近轮询: $lastPoll",
            fontSize = 12.sp,
            color = AppTheme.colors.onSurfaceVariant,
        )
        if (health.watchdogStallCount > 0) {
            Text(
                text = "看门狗停摆 ${health.watchdogStallCount} 次（最长 ${health.lastWatchdogStallMs / 1000}s）",
                fontSize = 12.sp,
                color = AppTheme.colors.warning,
            )
        }
    }
}

private fun formatEpochMs(ms: Long): String {
    if (ms <= 0L) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
