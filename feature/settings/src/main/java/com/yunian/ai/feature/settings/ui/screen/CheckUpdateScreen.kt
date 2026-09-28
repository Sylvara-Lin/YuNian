package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.update.AppUpdateManager
import com.yunian.ai.common.update.DownloadStatus
import com.yunian.ai.common.update.UpdateCheckState
import com.yunian.ai.common.update.UpdateMode
import com.yunian.ai.uicommon.component.UpdateDialog
import com.yunian.ai.uicommon.theme.WeChatDarkCard
import com.yunian.ai.uicommon.theme.WeChatDarkTextPrimary
import com.yunian.ai.uicommon.theme.WeChatLightTextPrimary
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckUpdateScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val updateManager = remember { AppUpdateManager(context) }
    val checkState by updateManager.updateCheckState.collectAsState()
    val updateInfo by updateManager.updateInfo.collectAsState()
    val scope = rememberCoroutineScope()
    val downloadProgress by updateManager.downloadProgress.collectAsState()
    val showUpdateDialog by updateManager.showUpdateDialog.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val currentVersion = remember { updateManager.getCurrentVersionName() }
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val cardColor = if (isDarkTheme) WeChatDarkCard else AppTheme.colors.staticWhite

    // 已是最新版本 → 页面底部跳出提示
    LaunchedEffect(checkState) {
        if (checkState == UpdateCheckState.LATEST) {
            snackbarHostState.showSnackbar(context.getString(R.string.software_latest_version))
        }
    }

    // 发现新版本 → 弹出更新弹窗（三选项 + 进度）
    val info = updateInfo
    if (showUpdateDialog && info != null) {
        UpdateDialog(
            updateInfo = info,
            downloadProgress = downloadProgress,
            onUpdate = {
                if (updateManager.checkInstallPermission()) {
                    updateManager.startDownload(info, UpdateMode.IMMEDIATE)
                } else {
                    (context as? android.app.Activity)?.let { act ->
                        updateManager.requestInstallPermission(act)
                    }
                }
            },
            onBackgroundUpdate = {
                updateManager.startDownload(info, UpdateMode.BACKGROUND)
                updateManager.dismissUpdate()
            },
            onNotNow = { updateManager.dismissUpdate() },
            onDismiss = { updateManager.dismissUpdate() }
        )
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.check_update_title),
                onBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = cardColor
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(
                        Brush.radialGradient(
                            colors = listOf(AppTheme.colors.success.copy(alpha = 0.8f), Color(0xFF05A350).copy(alpha = 0.6f))
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Sparkles,
                        contentDescription = null,
                        tint = AppTheme.colors.staticWhite.copy(alpha = 0.9f),
                        modifier = Modifier.size(36.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.current_version),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = "v$currentVersion",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    color = AppTheme.colors.onSurface
                )
            }

            Button(
                onClick = {
                    scope.launch { updateManager.checkForUpdates() }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = AppTheme.colors.staticWhite,
                    // 禁用态（检查中/下载中）显式透明，避免 Material3 默认 disabled 底色
                    // 在自定义渐变 Box 后多画一层形成"双层按钮"
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = AppTheme.colors.staticWhite
                ),
                enabled = checkState != UpdateCheckState.CHECKING
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(25.dp)).background(
                        Brush.horizontalGradient(
                            colors = listOf(AppTheme.colors.success.copy(alpha = 0.9f), Color(0xFF05A350).copy(alpha = 0.8f))
                        )
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    if (checkState == UpdateCheckState.CHECKING) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.colors.staticWhite, strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(imageVector = AppIcons.RefreshCw, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.check_update_btn), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp))
                        }
                    }
                }
            }

            when (checkState) {
                UpdateCheckState.LATEST -> {
                    AnimatedVisibility(visible = true, enter = fadeIn(tween(400)) + scaleIn(tween(400))) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawGlass(
                                    backdrop = LocalPageBackdrop.current,
                                    shape = RoundedCornerShape(16.dp),
                                    surfaceColor = cardColor
                                )
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(AppTheme.colors.successContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AppIcons.CircleCheckBig,
                                    contentDescription = null,
                                    tint = AppTheme.colors.success,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.latest_version),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                                color = AppTheme.colors.onSurface
                            )
                            Text(
                                text = "v$currentVersion",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                UpdateCheckState.ERROR -> {
                    AnimatedVisibility(visible = true, enter = fadeIn(tween(400))) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawGlass(
                                    backdrop = LocalPageBackdrop.current,
                                    shape = RoundedCornerShape(16.dp),
                                    surfaceColor = cardColor
                                )
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFFFEBEE).copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = AppIcons.CloudOff,
                                    contentDescription = null,
                                    tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.check_failed),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                                color = AppTheme.colors.onSurface
                            )
                            Text(
                                text = stringResource(R.string.check_failed_msg),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                UpdateCheckState.AVAILABLE -> {
                    AnimatedVisibility(visible = true, enter = fadeIn(tween(400)) + scaleIn(tween(400))) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawGlass(
                                    backdrop = LocalPageBackdrop.current,
                                    shape = RoundedCornerShape(16.dp),
                                    surfaceColor = cardColor
                                )
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Box(
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(AppTheme.colors.warningContainer.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = AppIcons.Sparkles,
                                        contentDescription = null,
                                        tint = AppTheme.colors.warning,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.new_version_found, updateInfo?.versionName ?: ""),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                                    color = AppTheme.colors.onSurface
                                )
                                updateInfo?.updateLog?.takeIf { it.isNotBlank() }?.let { log ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = log,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = AppTheme.colors.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))

                                val isDownloading = downloadProgress.status == DownloadStatus.DOWNLOADING
                                val isDownloaded = downloadProgress.status == DownloadStatus.COMPLETED
                                val isDownloadFailed = downloadProgress.status == DownloadStatus.FAILED

                                if (isDownloading) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.downloading, downloadProgress.progress),
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                                                color = AppTheme.colors.warning
                                            )
                                            Text(
                                                text = formatDownloadBytes(downloadProgress.downloadedBytes),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = { if (downloadProgress.totalBytes > 0) downloadProgress.downloadedBytes.toFloat() / downloadProgress.totalBytes else 0f },
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                            color = AppTheme.colors.warning,
                                            trackColor = AppTheme.colors.warningContainer
                                        )
                                    }
                                }

                                if (isDownloaded) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.CircleCheckBig,
                                            contentDescription = null,
                                            tint = AppTheme.colors.success,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.download_complete),
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
                                            color = AppTheme.colors.success
                                        )
                                    }
                                }

                                if (isDownloadFailed) {
                                    Text(
                                        text = stringResource(R.string.download_failed),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = AppTheme.colors.danger
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (!isDownloaded) {
                                    Button(
                                        onClick = {
                                            updateInfo?.let {
                                                if (it.updateUrl.isNotEmpty()) {
                                                    if (updateManager.checkInstallPermission()) {
                                                        updateManager.startDownload(it, UpdateMode.IMMEDIATE)
                                                    } else {
                                                        (context as? android.app.Activity)?.let { act ->
                                                            updateManager.requestInstallPermission(act)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                        shape = RoundedCornerShape(22.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color.Transparent,
                                            contentColor = AppTheme.colors.staticWhite,
                                            disabledContainerColor = Color.Transparent,
                                            disabledContentColor = AppTheme.colors.staticWhite
                                        ),
                                        enabled = !isDownloading
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(AppTheme.colors.warning.copy(alpha = 0.9f), Color(0xFFFFB74D).copy(alpha = 0.85f))
                                                )
                                            ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                                if (isDownloading) {
                                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AppTheme.colors.staticWhite, strokeWidth = 2.dp)
                                                } else {
                                                    Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(stringResource(R.string.update_now), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
                else -> {}
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatDownloadBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
