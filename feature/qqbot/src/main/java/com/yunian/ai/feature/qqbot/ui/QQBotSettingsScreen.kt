package com.yunian.ai.feature.qqbot.ui
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons

import com.yunian.ai.uicommon.theme.AppTheme
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.qqbot.data.network.BindStatus
import com.yunian.ai.feature.qqbot.data.network.QQBotWebSocketClient
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.SettingItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QQBotSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: QQBotViewModel = viewModel(factory = remember { QQBotViewModelFactory(context.applicationContext as android.app.Application) })
    val uiState by viewModel.uiState.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showCompanionDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var bindMode by remember { mutableStateOf<BindMode>(BindMode.QR) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QQBotEvent.LoginSuccess -> Toast.makeText(context, QQBotStrings.BIND_SUCCESS, Toast.LENGTH_SHORT).show()
                is QQBotEvent.LoginFailed -> Toast.makeText(context, QQBotStrings.bindFailed(event.error), Toast.LENGTH_LONG).show()
                is QQBotEvent.LoggedOut -> Toast.makeText(context, QQBotStrings.UNBOUND, Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = QQBotStrings.SETTINGS_TITLE,
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
            QQBotStatusCard(
                isLoggedIn = uiState.isLoggedIn,
                accountId = uiState.account?.appId,
                customName = uiState.customBotName,
                connectionState = uiState.connectionState,
                onBindClick = {  },
                onUnbindClick = { showLogoutDialog = true },
                onRenameClick = { showRenameDialog = true }
            )

            if (!uiState.isLoggedIn) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = bindMode == BindMode.QR,
                        onClick = { bindMode = BindMode.QR },
                        label = { Text(QQBotStrings.QR_BIND) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppTheme.colors.primaryContainer
                        )
                    )
                    FilterChip(
                        selected = bindMode == BindMode.MANUAL,
                        onClick = { bindMode = BindMode.MANUAL },
                        label = { Text(QQBotStrings.MANUAL_BIND) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppTheme.colors.primaryContainer
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (bindMode) {
                    BindMode.QR -> QQBotQrBindSection(uiState = uiState, viewModel = viewModel)
                    BindMode.MANUAL -> {
                        QQBotBindForm(
                            isLoading = uiState.isLoading,
                            error = uiState.error,
                            onBind = { appId, secret, name ->
                                viewModel.saveAccount(appId, secret, name)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = QQBotStrings.FEATURE_SETTINGS,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            @Suppress("DEPRECATION")
            SettingItem(
                icon = AppIcons.MessageCircle,
                title = QQBotStrings.MSG_NOTIFY,
                subtitle = QQBotStrings.MSG_NOTIFY_SUB,
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
                title = QQBotStrings.AUTO_REPLY,
                subtitle = QQBotStrings.AUTO_REPLY_SUB,
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
                title = QQBotStrings.MSG_FORWARD,
                subtitle = QQBotStrings.MSG_FORWARD_SUB,
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
                    title = QQBotStrings.DEFAULT_COMPANION,
                    subtitle = uiState.availableCompanions.find { it.id == uiState.defaultCompanionId }?.name
                        ?: QQBotStrings.NOT_SELECTED,
                    trailing = {
                        TextButton(onClick = { showCompanionDialog = true }) {
                            Text(QQBotStrings.SELECT)
                        }
                    }
                )
            }

            if (uiState.userCompanionMappings.isNotEmpty() && uiState.availableCompanions.isNotEmpty()) {
                HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))

                Text(
                    text = QQBotStrings.USER_MAPPING_TITLE,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                uiState.userCompanionMappings.entries.forEachIndexed { index, (qqUserId, companionId) ->
                    var showMappingDialog by remember { mutableStateOf(false) }
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    val mappedCompanionName = uiState.availableCompanions.find { it.id == companionId }?.name
                        ?: QQBotStrings.unknownCompanion(companionId)

                    SettingItem(
                        icon = AppIcons.Link,
                        title = QQBotStrings.userLabel(qqUserId),
                        subtitle = QQBotStrings.personaLabel(mappedCompanionName),
                        trailing = {
                            Row {
                                TextButton(onClick = { showMappingDialog = true }) {
                                    Text(QQBotStrings.SWITCH)
                                }
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(
                                        imageVector = AppIcons.Trash2,
                                        contentDescription = QQBotStrings.DELETE_MAPPING,
                                        tint = AppTheme.colors.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    )

                    if (index < uiState.userCompanionMappings.size - 1) {
                        HorizontalDivider(color = AppTheme.colors.outline, modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    if (showMappingDialog) {
                        AlertDialog(
                            onDismissRequest = { showMappingDialog = false },
                            title = { Text(QQBotStrings.selectCompanionFor(qqUserId)) },
                            text = {
                                Column {
                                    uiState.availableCompanions.forEach { companion ->
                                        TextButton(
                                            onClick = {
                                                viewModel.setUserCompanionMapping(qqUserId, companion.id)
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
                                    Text(QQBotStrings.CANCEL)
                                }
                            }
                        )
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text(QQBotStrings.DELETE_MAPPING) },
                            text = { Text(QQBotStrings.deleteMappingConfirm(qqUserId)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.removeUserCompanionMapping(qqUserId)
                                    showDeleteConfirm = false
                                }) {
                                    Text(QQBotStrings.DELETE_LABEL, color = AppTheme.colors.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text(QQBotStrings.CANCEL)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = QQBotStrings.NOTES_TITLE,
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
                    text = QQBotStrings.NOTES_BODY,
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
            title = { Text(QQBotStrings.UNBIND) },
            text = { Text(QQBotStrings.UNBIND_CONFIRM) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    showLogoutDialog = false
                }) {
                    Text(QQBotStrings.CONFIRM, color = AppTheme.colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(QQBotStrings.CANCEL)
                }
            }
        )
    }

    if (showCompanionDialog) {
        AlertDialog(
            onDismissRequest = { showCompanionDialog = false },
            title = { Text(QQBotStrings.SELECT_DEFAULT_COMPANION) },
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
                            QQBotStrings.AUTO_ASSIGN,
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
                    Text(QQBotStrings.CANCEL)
                }
            }
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(uiState.customBotName ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(QQBotStrings.SET_BOT_NAME) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(QQBotStrings.NAME) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCustomBotName(newName.takeIf { it.isNotBlank() })
                    showRenameDialog = false
                }) {
                    Text(QQBotStrings.SAVE)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(QQBotStrings.CANCEL)
                }
            }
        )
    }
}

private enum class BindMode {
    QR, MANUAL
}

@Composable
private fun QQBotQrBindSection(
    uiState: QQBotUiState,
    viewModel: QQBotViewModel
) {

    if (uiState.qrBitmap != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = AppTheme.colors.surfaceVariant
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                bitmap = uiState.qrBitmap.asImageBitmap(),
                contentDescription = QQBotStrings.QR_CONTENT_DESC,
                modifier = Modifier
                    .size(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.colors.staticWhite)
                    .padding(8.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            when (uiState.bindStatus) {
                BindStatus.PENDING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = QQBotStrings.WAITING_SCAN,
                            fontSize = 14.sp,
                            color = AppTheme.colors.primary
                        )
                    }
                }
                BindStatus.COMPLETED -> {
                    Text(
                        text = QQBotStrings.BIND_OK,
                        fontSize = 14.sp,
                        color = AppTheme.colors.success
                    )
                }
                BindStatus.EXPIRED -> {
                    Text(
                        text = QQBotStrings.QR_EXPIRED,
                        fontSize = 14.sp,
                        color = AppTheme.colors.error
                    )
                }
                else -> {}
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { viewModel.cancelQrBind() }) {
                Text(QQBotStrings.CANCEL, fontSize = 13.sp, color = AppTheme.colors.error)
            }
        }
    } else if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(320.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = AppTheme.colors.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(QQBotStrings.GENERATING_QR, fontSize = 14.sp, color = AppTheme.colors.onSurfaceVariant)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = AppTheme.colors.surfaceVariant
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = AppIcons.QrCode,
                contentDescription = null,
                tint = AppTheme.colors.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = QQBotStrings.USE_QQ_SCAN,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = QQBotStrings.SCAN_HINT,
                fontSize = 13.sp,
                color = AppTheme.colors.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = { viewModel.startQrBind() }) {
                Text(QQBotStrings.GENERATE_QR, fontSize = 14.sp)
            }
        }
    }

    if (!uiState.bindError.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiState.bindError,
            fontSize = 13.sp,
            color = AppTheme.colors.error,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = QQBotStrings.QR_FOOTER,
        fontSize = 12.sp,
        color = AppTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
        lineHeight = 18.sp
    )
}

@Composable
private fun QQBotBindForm(
    isLoading: Boolean,
    error: String?,
    onBind: (String, String, String?) -> Unit
) {
    var appId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = AppTheme.colors.surfaceVariant
            )
            .padding(16.dp)
    ) {
        Text(
            text = QQBotStrings.BIND_QQBOT,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("ClientSecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text(QQBotStrings.BOT_NAME_OPTIONAL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontSize = 13.sp,
                color = AppTheme.colors.error
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = { onBind(appId, clientSecret, customName.takeIf { it.isNotBlank() }) },
            enabled = !isLoading && appId.isNotBlank() && clientSecret.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(if (isLoading) QQBotStrings.BINDING else QQBotStrings.BIND)
        }
    }
}

@Composable
private fun QQBotStatusCard(
    isLoggedIn: Boolean,
    accountId: String?,
    customName: String?,
    connectionState: QQBotWebSocketClient.ConnectionState,
    onBindClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    val (statusText, statusColor) = when {
        !isLoggedIn -> QQBotStrings.NOT_BOUND to AppTheme.colors.warning
        connectionState == QQBotWebSocketClient.ConnectionState.CONNECTED -> QQBotStrings.ONLINE to AppTheme.colors.success
        connectionState == QQBotWebSocketClient.ConnectionState.CONNECTING -> QQBotStrings.CONNECTING to AppTheme.colors.warning
        connectionState == QQBotWebSocketClient.ConnectionState.RECONNECTING -> QQBotStrings.RECONNECTING to AppTheme.colors.warning
        connectionState == QQBotWebSocketClient.ConnectionState.AUTH_FAILED -> QQBotStrings.AUTH_FAILED to AppTheme.colors.danger
        else -> QQBotStrings.OFFLINE to Color(0xFF9E9E9E)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLoggedIn && connectionState == QQBotWebSocketClient.ConnectionState.CONNECTED)
                AppTheme.colors.success.copy(alpha = 0.1f) else AppTheme.colors.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isLoggedIn && connectionState == QQBotWebSocketClient.ConnectionState.CONNECTED)
                AppIcons.CircleCheckBig else AppIcons.TriangleAlert,
            contentDescription = null,
            tint = if (isLoggedIn && connectionState == QQBotWebSocketClient.ConnectionState.CONNECTED)
                AppTheme.colors.success else AppTheme.colors.warning,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isLoggedIn) customName ?: QQBotStrings.BOUND else QQBotStrings.NOT_BOUND_QQBOT,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (isLoggedIn) statusText else QQBotStrings.BIND_HINT,
            fontSize = 13.sp,
            color = if (isLoggedIn) statusColor else AppTheme.colors.onSurfaceVariant
        )
        if (isLoggedIn && !accountId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "AppID: $accountId",
                fontSize = 12.sp,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn) {
                TextButton(onClick = onRenameClick, modifier = Modifier.height(32.dp)) {
                    Icon(
                        imageVector = AppIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = AppTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(QQBotStrings.RENAME, fontSize = 13.sp, color = AppTheme.colors.primary)
                }
                TextButton(onClick = onUnbindClick, modifier = Modifier.height(32.dp)) {
                    Text(QQBotStrings.UNBIND, fontSize = 13.sp, color = AppTheme.colors.error)
                }
            } else {
                TextButton(onClick = onBindClick, modifier = Modifier.height(32.dp)) {
                    Text(QQBotStrings.BIND_NOW, fontSize = 13.sp, color = AppTheme.colors.primary)
                }
            }
        }
    }
}
