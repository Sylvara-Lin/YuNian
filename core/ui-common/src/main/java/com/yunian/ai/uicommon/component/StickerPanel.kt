package com.yunian.ai.uicommon.component
import com.yunian.ai.uicommon.icon.AppIcons


import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.Backdrop
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.StickerManager
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.sticker.StickerImportOverlay
import com.yunian.ai.uicommon.component.sticker.suggestStickerName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 表情面板（聊天页 / 群聊页共用）：
 * - 订阅 StickerManager.version：导入 / 重命名 / 删除后自动刷新（修 P8）
 * - "+" 三通道 ActionSheet：相册（内置 launcher）/ 图片文件（内置 launcher）/ ZIP 包（页面既有通道）
 *   相册与文件通道由面板自带 launcher + 命名过场收口，群聊页共用 "+" 入口自动获得同等能力
 * - 长按已导入项：重命名 / 删除 / 取消（JSON 同步，修 P3/P8）
 * - 新导入项 jellyEntrance 果冻入场（P4 收尾阶段）；位图 inSampleSize 降采样（修 P11）
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StickerPanel(
    isVisible: Boolean,
    onStickerClick: (StickerInfo) -> Unit,
    onImportClick: () -> Unit = {},
    onDeleteAllClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 聊天页背景 backdrop（液态玻璃取样用）；null 时面板回退纯色样式，群聊页暂不传也不会坏 */
    backdrop: Backdrop? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stickerManager = remember { StickerManager.getInstance(context) }
    val stickers = remember { mutableStateListOf<StickerInfo>() }
    var isLoading by remember { mutableStateOf(true) }

    // 变更通知：version 变化 → 重载列表（面板打开期间导入也能即时刷新）
    val version by stickerManager.version.collectAsState()

    var showImportSheet by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<StickerInfo?>(null) }
    var renameTarget by remember { mutableStateOf<StickerInfo?>(null) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var jellyFileName by remember { mutableStateOf<String?>(null) }
    // 预填名异步解析结果（contentResolver 查询不能放组合里同步跑，见下方 LaunchedEffect）
    var importSuggestedName by remember { mutableStateOf("") }

    // 相册 / 图片文件两个通道由面板自带 launcher（聊天页与群聊页共用，无需页面各自接线）
    // 统一先拷贝到 cache 再进命名：PickVisualMedia 授予的读权限只在本次选择会话内可靠，
    // 命名确认时才读取存在 uri 失效隐患（与聊天页拖拽路径 copyUriToCache 同思路）
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> uri?.let { scope.launch { importUri = stageImportUri(context, it) } } }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { scope.launch { importUri = stageImportUri(context, it) } } }

    LaunchedEffect(isVisible, version) {
        if (isVisible) {
            isLoading = true
            val loaded = stickerManager.getAllStickers()
            stickers.clear()
            stickers.addAll(loaded)
            isLoading = false
        }
    }

    // 新导入项定位：version 变化后取最新条目播果冻入场，1.5s 后清除避免滚动时重播
    LaunchedEffect(version) {
        if (version > 0) {
            jellyFileName = stickerManager.newestImportedFileName()
            if (jellyFileName != null) {
                delay(1500)
                jellyFileName = null
            }
        }
    }

    // 预填名异步解析：乱码 / 纯数字缓存名返回空串，让占位文案引导用户自己起名
    LaunchedEffect(importUri) {
        val uri = importUri ?: return@LaunchedEffect
        importSuggestedName = withContext(Dispatchers.IO) { suggestStickerName(context, uri) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150)),
        ) {
            val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
            // 浅色主题副标题 / 说明文字一律纯黑（全站玻璃范式对比度要求）
            val hintColor = if (isLightTheme) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            val panelShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (backdrop != null) {
                            // 液态玻璃：取样聊天页背景 backdrop，贴底只做顶部圆角（lens 仅支持 CornerBasedShape）
                            Modifier.drawGlass(
                                backdrop = backdrop,
                                shape = panelShape,
                                surfaceColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            // 回退：backdrop 为 null 时保持原纯色面板，群聊页暂不传 backdrop 也不会坏
                            Modifier.background(MaterialTheme.colorScheme.background)
                        }
                    )
                    .padding(vertical = 12.dp)
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "表情包",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row {

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                // 玻璃片按钮：白色低 alpha + 细白描边（亮色主题略提高 alpha 保证可见）
                                .background(Color.White.copy(alpha = if (isLightTheme) 0.50f else 0.12f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = if (isLightTheme) 0.65f else 0.28f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(onClick = onDeleteAllClick),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Trash2,
                                contentDescription = "删除全部表情包",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = if (isLightTheme) 0.50f else 0.12f))
                                .border(
                                    1.dp,
                                    Color.White.copy(alpha = if (isLightTheme) 0.65f else 0.28f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { showImportSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Plus,
                                contentDescription = "导入表情包",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "加载中...",
                            fontSize = 13.sp,
                            color = hintColor
                        )
                    }
                } else if (stickers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "暂无表情包",
                                fontSize = 13.sp,
                                color = hintColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击 + 从相册 / 文件 / ZIP 导入",
                                fontSize = 12.sp,
                                color = hintColor
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(stickers, key = { it.path }) { sticker ->
                            StickerGridItem(
                                sticker = sticker,
                                playJelly = sticker.fileName != null && sticker.fileName == jellyFileName,
                                onClick = { onStickerClick(sticker) },
                                onLongClick = {
                                    // 仅已导入的自定义项支持管理（内置 assets 不可改）
                                    if (sticker.category == "imported" && sticker.fileName != null) {
                                        manageTarget = sticker
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 导入 ActionSheet：相册 / 文件 / ZIP 三通道
        if (showImportSheet) {
            ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
                ImportActionRow(
                    icon = AppIcons.Image,
                    text = "从相册选一张",
                    onClick = {
                        showImportSheet = false
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                ImportActionRow(
                    icon = AppIcons.FileText,
                    text = "选图片文件",
                    onClick = {
                        showImportSheet = false
                        fileLauncher.launch("image/*")
                    }
                )
                ImportActionRow(
                    icon = AppIcons.FolderOpen,
                    text = "导入 ZIP 包",
                    onClick = {
                        showImportSheet = false
                        onImportClick()
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 长按管理 ActionSheet：重命名 / 删除 / 取消
        manageTarget?.let { target ->
            ModalBottomSheet(onDismissRequest = { manageTarget = null }) {
                ImportActionRow(
                    icon = AppIcons.Pencil,
                    text = "重命名",
                    onClick = {
                        manageTarget = null
                        renameTarget = target
                    }
                )
                ImportActionRow(
                    icon = AppIcons.Trash2,
                    text = "删除",
                    tint = MaterialTheme.colorScheme.error
                ) {
                    manageTarget = null
                    scope.launch {
                        val ok = stickerManager.deleteImportedSticker(target.fileName!!)
                        showToast(context, if (ok) "已删除「${target.name}」" else "删除失败")
                    }
                }
                ImportActionRow(icon = AppIcons.X, text = "取消") { manageTarget = null }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 首次导入命名过场：所有导入路径收口到「导入 → 命名 → 确认」流程（Dialog 全屏，不随面板收起）。
    // 用 Dialog 而非 Popup：Popup 独立窗口键盘弹出时被系统 ADJUST_PAN 整体平移、imePadding 失效，
    // 导致卡片被推出屏幕只剩聚焦输入框；Dialog(decorFitsSystemWindows=false) 下 imePadding 正常避让。
    importUri?.let { uri ->
        Dialog(
            onDismissRequest = {
                // 返回键关闭：暂存文件一并清理，cache 不留 sticker_stage_* 垃圾
                deleteStagedImportFile(uri)
                importUri = null
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnClickOutside = false
            )
        ) {
            StickerImportOverlay(
                visible = true,
                previewUri = uri,
                suggestedName = importSuggestedName,
                isNameTaken = { stickerManager.isNameTaken(it) },
                onConfirm = { name, semantic, aliases ->
                    scope.launch {
                        stickerManager.importStickerFile(uri, name, semantic, aliases)
                            .onSuccess {
                                // cache 暂存文件已落盘为表情，清理临时文件
                                deleteStagedImportFile(uri)
                                importUri = null
                                showToast(context, "已添加表情「$name」")
                            }
                            .onFailure { showToast(context, "导入失败: ${it.message}") }
                    }
                },
                onDismiss = {
                    // 取消 / 点遮罩关闭：暂存文件一并清理，cache 不留 sticker_stage_* 垃圾
                    deleteStagedImportFile(uri)
                    importUri = null
                }
            )
        }
    }

    // 重命名过场：复用命名表单（回填语义 / 别名，原名不算重复），Dialog 理由同上
    renameTarget?.let { target ->
        val entry = remember(target) { stickerManager.getEntry(target.fileName!!) }
        Dialog(
            onDismissRequest = { renameTarget = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnClickOutside = false
            )
        ) {
            StickerImportOverlay(
                visible = true,
                previewUri = null,
                previewPath = target.path,
                suggestedName = target.description ?: target.name,
                isNameTaken = { stickerManager.isNameTaken(it) },
                onConfirm = { name, semantic, aliases ->
                    scope.launch {
                        stickerManager.renameImportedSticker(target.fileName!!, name, semantic, aliases)
                            .onSuccess {
                                renameTarget = null
                                showToast(context, "已重命名为「$name」")
                            }
                            .onFailure { showToast(context, "重命名失败: ${it.message}") }
                    }
                },
                onDismiss = { renameTarget = null },
                title = "重命名表情",
                confirmText = "保存",
                initialSemantic = entry?.semantic.orEmpty(),
                initialAliases = entry?.aliases.orEmpty(),
                currentName = target.description ?: target.name
            )
        }
    }
}

/** ActionSheet 通用行 */
@Composable
private fun ImportActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(22.dp),
            tint = tint
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(text = text, fontSize = 15.sp, color = tint)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

private fun showToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** 导入暂存接受的图片扩展名（与 StickerManager.IMAGE_EXTENSIONS 对齐） */
private val STICKER_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

/**
 * 相册 / 文件通道：先把 uri 内容拷贝到 cache 再进命名流程。
 *
 * PickVisualMedia 授予的读权限只在本次选择会话内可靠，而命名是异步的——
 * 用户打字 / 切后台期间源 uri 可能已失效（读取失败「导入失败」）。
 * 拷贝时保留扩展名落盘：importStickerFile 依赖 DISPLAY_NAME / MIME 识别图片格式，
 * file:// 的 cache 文件名若丢扩展名会被误判「仅支持图片文件」。
 * 拷贝失败回退原始 uri，保留旧行为（导入失败时有 toast 兜底）。
 */
private suspend fun stageImportUri(context: Context, source: Uri): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val displayName = try {
        resolver.query(
            source,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    val ext = displayName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it in STICKER_IMAGE_EXTENSIONS }
        ?: run {
            val mime = try {
                resolver.getType(source)
            } catch (_: Exception) {
                null
            }
            when (mime) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> ""
            }
        }
    val suffix = if (ext.isBlank()) "" else ".$ext"
    val cacheFile = File(context.cacheDir, "sticker_stage_${System.currentTimeMillis()}$suffix")
    var copyFinished = false
    try {
        resolver.openInputStream(source)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return@withContext source
        copyFinished = true
        SecureLog.i("StickerPanel", "Staged import uri to cache: ${cacheFile.name}")
        Uri.fromFile(cacheFile)
    } catch (e: Exception) {
        // 拷贝中断：清掉半写文件，cache 不留 sticker_stage_* 垃圾
        if (!copyFinished) {
            runCatching { cacheFile.delete() }
        }
        SecureLog.w("StickerPanel", "stageImportUri failed, fallback to source: ${e.message}")
        source
    }
}

/** 导入成功后清理命名流程的 cache 暂存文件（仅 file://；失败静默，cache 可被系统回收） */
private fun deleteStagedImportFile(uri: Uri) {
    if (uri.scheme == "file") {
        val path = uri.path ?: return
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerGridItem(
    sticker: StickerInfo,
    playJelly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sticker.path) {
        val manager = StickerManager.getInstance(context)
        // P11：网格位图降采样（显示尺寸 64dp，≤512px 绰绰有余），避免全尺寸解码内存抖动
        bitmap = withContext(Dispatchers.IO) {
            manager.loadStickerBitmapSampled(sticker.path, 512)
        }
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .jellyEntrance(play = playJelly)
            .clip(RoundedCornerShape(12.dp))
            // 玻璃片格子：白色低 alpha + 细白描边（亮色主题略提高 alpha 保证格子可辨）
            .background(
                Color.White.copy(
                    alpha = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) 0.55f else 0.12f
                )
            )
            .border(
                1.dp,
                Color.White.copy(
                    alpha = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) 0.70f else 0.25f
                ),
                RoundedCornerShape(12.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = sticker.name,
                modifier = Modifier.size(56.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = sticker.name.take(2),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // 自定义表情小徽标
        if (sticker.category == "imported") {
            Icon(
                imageVector = AppIcons.Sparkles,
                contentDescription = "自定义表情",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(12.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
