package com.yunian.ai.feature.backup
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    onExportSelect: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    val viewModel: BackupViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); isVisible = true }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableStateOf(PasswordMode.EXPORT) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

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

    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingImportUri = it
            passwordMode = PasswordMode.IMPORT
            showPasswordDialog = true
        }
    }

    if (showPasswordDialog) {
        PasswordDialog(
            mode = passwordMode,
            isLoading = uiState is BackupViewModel.UiState.Exporting || uiState is BackupViewModel.UiState.Importing,
            onConfirm = { password ->
                when (passwordMode) {
                    PasswordMode.EXPORT -> viewModel.export(password)
                    PasswordMode.IMPORT -> {
                        pendingImportUri?.let { viewModel.import(it, password) }
                        pendingImportUri = null
                    }
                }
                showPasswordDialog = false
            },
            onDismiss = {
                showPasswordDialog = false
                pendingImportUri = null
                if (uiState !is BackupViewModel.UiState.Exporting &&
                    uiState !is BackupViewModel.UiState.Importing) {
                    viewModel.resetState()
                }
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

    GlassPageScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.backup_title),
                onBack = onNavigateBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }
            ) {
                BackupCard(
                    icon = AppIcons.Download,
                    title = stringResource(R.string.backup_export_title),
                    description = stringResource(R.string.backup_export_desc),
                    buttonText = stringResource(R.string.backup_export_btn),
                    buttonColor = AppTheme.colors.success,
                    isLoading = uiState is BackupViewModel.UiState.Exporting,
                    onClick = { onExportSelect() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(tween(400, delayMillis = 80)) { it / 4 }
            ) {
                BackupCard(
                    icon = AppIcons.FolderOpen,
                    title = stringResource(R.string.backup_import_title),
                    description = stringResource(R.string.backup_import_desc),
                    buttonText = stringResource(R.string.backup_import_btn),
                    buttonColor = AppTheme.colors.danger,
                    isLoading = uiState is BackupViewModel.UiState.Importing,
                    onClick = { importFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(12.dp),
                            surfaceColor = colorScheme.surfaceVariant
                        )
                        .padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.backup_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

enum class PasswordMode { EXPORT, IMPORT }

internal fun generateStrongPassword(length: Int = 24): String {
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val lower = "abcdefghijkmnpqrstuvwxyz"
    val digits = "23456789"
    val symbols = "!@#$%^&*()-_=+"
    val all = upper + lower + digits + symbols
    val random = SecureRandom()
    val sb = StringBuilder(length)

    sb.append(upper[random.nextInt(upper.length)])
    sb.append(lower[random.nextInt(lower.length)])
    sb.append(digits[random.nextInt(digits.length)])
    sb.append(symbols[random.nextInt(symbols.length)])
    repeat(length - 4) { sb.append(all[random.nextInt(all.length)]) }

    val chars = sb.toString().toCharArray()
    for (i in chars.size - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    return String(chars)
}

@Composable
internal fun PasswordDialog(
    mode: PasswordMode,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    val generated = remember(mode) {
        if (mode == PasswordMode.EXPORT) generateStrongPassword() else ""
    }
    var password by remember(mode) { mutableStateOf(generated) }
    var showPassword by remember { mutableStateOf(mode == PasswordMode.EXPORT) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                if (mode == PasswordMode.EXPORT) "设置备份密码" else "输入备份密码",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (mode == PasswordMode.EXPORT) {
                    Text(
                        "已自动生成高强度密码，点击复制保存。导入时需要输入此密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        "请输入备份时设置的密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null; copied = false },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            if (mode == PasswordMode.EXPORT) {

                                IconButton(
                                    onClick = {
                                        password = generateStrongPassword()
                                        copied = false
                                    },
                                    enabled = !isLoading
                                ) {
                                    Icon(AppIcons.RefreshCw, "重新生成密码")
                                }

                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(password))
                                        copied = true
                                    },
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        if (copied) AppIcons.Check else AppIcons.Copy,
                                        if (copied) "已复制" else "复制密码"
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                enabled = !isLoading
                            ) {
                                Icon(
                                    if (showPassword) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                if (mode == PasswordMode.EXPORT && copied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已复制到剪贴板",
                        color = AppTheme.colors.success,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = AppTheme.colors.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        password.length < 6 -> error = "密码至少6位"
                        else -> onConfirm(password)
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "处理中..." else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun BackupCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    buttonColor: Color,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, title, Modifier.size(28.dp), tint = buttonColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp), color = colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = AppTheme.colors.staticWhite, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isLoading) "处理中..." else buttonText, color = AppTheme.colors.staticWhite)
        }
    }
}

internal fun dateString(): String {
    val cal = java.util.Calendar.getInstance()
    return "${cal.get(java.util.Calendar.YEAR)}-${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}-${cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
}
