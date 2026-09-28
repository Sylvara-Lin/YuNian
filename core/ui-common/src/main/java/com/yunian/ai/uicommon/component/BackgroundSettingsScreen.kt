package com.yunian.ai.uicommon.component
import com.yunian.ai.uicommon.icon.AppIcons


import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.uicommon.image.cropper.ImageCropperDialog
import com.yunian.ai.uicommon.image.decodeUriSampledForCrop
import com.yunian.ai.uicommon.image.viewer.FullscreenImageViewer
import com.yunian.ai.uicommon.picker.ui.CustomImagePicker
import com.yunian.ai.uicommon.component.glass.LiquidBottomTab
import com.yunian.ai.uicommon.component.glass.LiquidBottomTabs
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BG_PREFS_NAME = "chat_prefs"
private const val MAIN_BG_KEY = "main_background"

/**
 * 背景裁剪输入的**解码长边上限**（像素），与 [com.yunian.ai.uicommon.image.CROP_SOURCE_MAX_DIMENSION]
 * （头像 1200）分开取值，按用途分档：背景全屏显示 → 更大、且用 `RGB_565`（FIX-9，用户拍板）。
 *
 * 取值 2000 的理由（**注意采样器语义**）：[com.yunian.ai.common.calcSampleSizeForMaxDimension]
 * 保证的是「采样后长边落在 `[max, 2*max)`」，即**实际长边可能达到约 `2*max`**，并非严格 `<= max`。
 * 据此：
 *  - `1080×2400`（本机型长截图）→ `sample=1` → **原生 1080×2400，不缩水**；
 *  - `4000×3000`（相机横向）→ `sample=2` → `2000×1500`（565 ≈ 5.7MB）；
 *  - `6000×4000`（相机超大）→ `sample=2` → `3000×2000`（565 ≈ 11.4MB）。
 *
 * **内存上界的真实情况（勿高估）**：因降采样只能按 2 的幂，**长边 < `2*max` = 4000 的图一律 `sample=1` 不降采样**，
 * 所以上界不是 12MB，而是 `(2*max)² × 2B ≈ 32MB`（近方形极端）。典型值：
 *  - `3840×2160`（4K 16:9，常见壁纸）→ `sample=1` → 原生 → 565 ≈ **16.6MB**；
 *  - `3000×3000`（方形）→ `sample=1` → 原生 → 565 ≈ **18MB**；
 *  - `3999×3999`（极端近方形）→ `sample=1` → 原生 → 565 ≈ **32MB**。
 * 仍显著优于改前（ARGB_8888 全尺寸下上述分别为 33/36/64MB），且解码已带 `catch(OutOfMemoryError)` 优雅降级。
 *
 * **为何取 2000 而非更小的 1400**：1400 可把 4K 压到 ~4.2MB、最坏 ~13MB，但 `2*1400=2800` →
 * 长边 >2800 的图会被降采样，**包括 1440p 机型壁纸（`3200×1440`）** → 全屏背景发虚。
 * 本档的首要诉求是「清晰不缩水」，故取 2000（保住长边 <4000 的图原生），以更高的内存上界换取画质。
 * 若日后以内存为先，可下调至 1400（代价即上述降采样）。
 *
 * **透明源注意**：`inPreferredConfig = RGB_565` 对**含 per-pixel alpha 的源**（透明 PNG/WebP）
 * 可能被解码器升级回 `ARGB_8888`，「内存减半」对这类图不保证（截图/照片多为不透明，影响很小）。
 *
 * **为何不是字面的 2400**：若取 2400，按同一采样语义 `4000×3000` 会命中 `sample=1` → 仍为
 * `4000×3000`（565 ≈ 22.9MB），**反而比 6000×4000 那档（11.4MB）更高**，与「内存兜底」意图相悖。
 */
private const val BACKGROUND_CROP_SOURCE_MAX_DIMENSION = 2000

fun getMainBackgroundKey(context: android.content.Context): String {
    return context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getString(MAIN_BG_KEY, "default") ?: "default"
}

fun setMainBackgroundKey(context: android.content.Context, key: String) {
    context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(MAIN_BG_KEY, key)
        .apply()

    val activity = context.findActivity()
    if (activity != null) {
        WindowMainBackground.forceApply(
            activity.window,
            activity,
            key,
            WindowMainBackground.resolveIsDarkTheme(activity),
            WindowMainBackground.activityScope(activity)
        )
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private enum class BgTarget { Main, Chat }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BackgroundSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors
    val viewModel: BackgroundSettingsViewModel = viewModel()

    var target by remember { mutableStateOf(BgTarget.Main) }
    val mainBgKey by viewModel.mainBgKey.collectAsState()
    val chatBgKey by viewModel.chatBgKey.collectAsState()
    val customImageKeys by viewModel.customImageKeys.collectAsState()
    val customSolidColors by viewModel.customSolidColors.collectAsState()

    var colorPickerSession by remember { mutableStateOf<ColorPickerSession?>(null) }
    var showImagePicker by remember { mutableStateOf(false) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var previewImageModel by remember { mutableStateOf<Any?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingSolidAction by remember { mutableStateOf<BackgroundEntry.Solid?>(null) }

    val isDark = colorScheme.surface.luminance() < 0.5f
    val presets = remember(isDark) {
        chatBackgroundOptions(context).map { option ->
            val (color, gradient) = resolveBackgroundPalette(option.key, isDark)
            option.copy(color = color, gradient = gradient)
        }
    }

    val currentKey = if (target == BgTarget.Main) mainBgKey else chatBgKey
    val targetMain = target == BgTarget.Main

    fun applyBackgroundKey(key: String) {
        viewModel.applyBackground(targetMain, key)
    }

    fun deleteEntry(entry: BackgroundEntry) {
        when (entry) {
            is BackgroundEntry.Solid -> {

                if (isColorBackground(entry.key)) {
                    viewModel.deleteSolidColor(entry.key, currentKey, targetMain)
                }
            }
            is BackgroundEntry.Image -> {
                viewModel.deleteImageBackground(entry.key, currentKey, targetMain)
            }
        }
    }

    val solidEntries = remember(presets, customSolidColors, currentKey) {
        val presetEntries = presets.map { option ->
            BackgroundEntry.Solid(
                key = option.key,
                color = option.color,
                label = option.name,
                deletable = false
            )
        }
        val customEntries = customSolidColors.map { solid ->
            BackgroundEntry.Solid(
                key = solid.key,
                color = solid.color,
                label = solid.name.ifBlank { "自定义纯色" },
                deletable = true
            )
        }

        val orphan = currentKey
            .takeIf { isColorBackground(it) && customSolidColors.none { c -> c.key == it } }
            ?.let { key ->
                parseColorBackground(key)?.let { color ->
                    BackgroundEntry.Solid(
                        key = key,
                        color = color,
                        label = "自定义纯色",
                        deletable = true
                    )
                }
            }
        if (orphan != null) presetEntries + customEntries + orphan else presetEntries + customEntries
    }

    val imageEntries = remember(customImageKeys) {
        customImageKeys.mapNotNull { key ->
            val file = getCustomBackgroundFile(context, key) ?: return@mapNotNull null
            BackgroundEntry.Image(key = key, file = file)
        }
    }

    GlassPageScaffold(
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "背景设置",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigateBack() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        AppIcons.ArrowLeft,
                        "返回",
                        tint = colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item {
                TargetToggleRow(
                    selected = target,
                    onSelect = { target = it }
                )
            }

            item {
                SectionLabel(title = "纯色背景")
            }

            items(solidEntries, key = { "solid_${it.key}" }) { entry ->
                SolidBackgroundRow(
                    entry = entry,
                    enabled = currentKey == entry.key,
                    onEnable = { applyBackgroundKey(entry.key) },
                    onLongPress = {
                        if (entry.deletable) {

                            pendingSolidAction = entry
                        }
                    }
                )
            }

            item {
                AddMoreSolidRow(
                    onClick = {

                        colorPickerSession = ColorPickerSession.Add(
                            sessionId = System.currentTimeMillis()
                        )
                    }
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = colorScheme.outline.copy(alpha = 0.35f)
                )
            }

            item {
                SectionLabel(title = "图片背景")
            }

            items(imageEntries, key = { "image_${it.key}" }) { entry ->
                ImageBackgroundRow(
                    entry = entry,
                    enabled = currentKey == entry.key,
                    onEnable = { applyBackgroundKey(entry.key) },
                    onPreview = { previewImageModel = entry.file },
                    onLongPress = {
                        pendingDelete = PendingDelete(entry, "删除该图片背景？")
                    }
                )
            }

            item {
                AddMoreImageRow(onClick = { showImagePicker = true })
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    colorPickerSession?.let { session ->
        val seedColor = when (session) {
            is ColorPickerSession.Add -> Color(0xFFF5F5F5)
            is ColorPickerSession.Edit -> session.color
        }
        val seedName = when (session) {
            is ColorPickerSession.Add -> ""
            is ColorPickerSession.Edit -> session.name
        }
        ProfessionalColorPickerDialog(
            currentColor = seedColor,
            initialName = seedName,
            sessionKey = session.sessionKey,
            onColorPicked = { color, name ->
                val key = when (session) {
                    is ColorPickerSession.Add -> {
                        viewModel.saveSolidColor(color, name)
                    }
                    is ColorPickerSession.Edit -> {
                        viewModel.updateSolidColor(
                            oldKey = session.key,
                            color = color,
                            name = name
                        )
                    }
                }
                applyBackgroundKey(key)
                colorPickerSession = null
            },
            onDismiss = { colorPickerSession = null }
        )
    }

    pendingSolidAction?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingSolidAction = null },
            title = { Text("自定义纯色") },
            text = {
                Text(
                    "${entry.label}\n${formatHex(entry.color)}  ·  ${formatRgb(entry.color)}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSolidAction = null
                        colorPickerSession = ColorPickerSession.Edit(
                            key = entry.key,
                            color = entry.color,
                            name = entry.label,
                            sessionId = System.currentTimeMillis()
                        )
                    }
                ) {
                    Text("编辑", color = colorScheme.success, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingSolidAction = null
                            pendingDelete = PendingDelete(entry, "删除该自定义纯色？")
                        }
                    ) {
                        Text("删除", color = colorScheme.danger)
                    }
                    TextButton(onClick = { pendingSolidAction = null }) {
                        Text("取消", color = colorScheme.onSurfaceVariant)
                    }
                }
            }
        )
    }

    if (showImagePicker) {
        CustomImagePicker(
            maxSelection = 1,
            onConfirmed = { uris ->
                showImagePicker = false
                if (uris.isNotEmpty()) {
                    pendingCropUri = uris.first()
                }
            },
            onDismiss = { showImagePicker = false }
        )
    }

    LaunchedEffect(pendingCropUri) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        // 采样解码 + OOM 兜底（修 FIX-1）。FIX-9：背景按用途采用「原生分辨率 + RGB_565」——
        // 上限 BACKGROUND_CROP_SOURCE_MAX_DIMENSION（1080×2400 走 sample=1 不缩水），
        // 色彩配置 RGB_565（内存减半；背景保存为 JPEG、显示链路亦为 RGB_565，全程无 alpha 依赖）。
        cropBitmap = withContext(Dispatchers.IO) {
            decodeUriSampledForCrop(
                context = context,
                uri = uri,
                maxDimension = BACKGROUND_CROP_SOURCE_MAX_DIMENSION,
                config = Bitmap.Config.RGB_565,
            )?.asImageBitmap()
        }
        if (cropBitmap == null) {
            pendingCropUri = null
        }
    }

    if (cropBitmap != null) {
        ImageCropperDialog(
            bitmap = cropBitmap!!,
            cropRatio = 9f / 16f,
            onConfirm = { cropped ->
                val key = viewModel.saveImageBackground(cropped)
                if (key != null) {
                    applyBackgroundKey(key)
                }
                cropBitmap = null
                pendingCropUri = null
            },
            onDismiss = {
                cropBitmap = null
                pendingCropUri = null
            }
        )
    }

    FullscreenImageViewer(
        models = listOfNotNull(previewImageModel),
        initialIndex = 0,
        visible = previewImageModel != null,
        onDismiss = { previewImageModel = null }
    )

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除背景") },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry(pending.entry)
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = colorScheme.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private sealed class ColorPickerSession {
    abstract val sessionKey: String

    data class Add(val sessionId: Long) : ColorPickerSession() {
        override val sessionKey: String = "add_$sessionId"
    }

    data class Edit(
        val key: String,
        val color: Color,
        val name: String,
        val sessionId: Long
    ) : ColorPickerSession() {
        override val sessionKey: String = "edit_${key}_$sessionId"
    }
}

private data class PendingDelete(
    val entry: BackgroundEntry,
    val message: String
)

private sealed class BackgroundEntry {
    abstract val key: String

    data class Solid(
        override val key: String,
        val color: Color,
        val label: String,
        val deletable: Boolean
    ) : BackgroundEntry()

    data class Image(
        override val key: String,
        val file: java.io.File
    ) : BackgroundEntry()
}

@Composable
private fun TargetToggleRow(
    selected: BgTarget,
    onSelect: (BgTarget) -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val contentColor = if (isDark) Color.White else Color.Black
    val selectedIndex = if (selected == BgTarget.Chat) 1 else 0

    // 与首页底部导航栏同机制（FloatingGlassBottomNav）：视觉状态与业务状态分离
    // 视觉先动（指示器立刻滑动），业务回调推迟到下一帧触发，避免两者同帧打架导致
    // 「点击无效 + 被拖拽吸附拽回原位」
    var visualIndex by remember { mutableIntStateOf(selectedIndex) }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)

    LaunchedEffect(selectedIndex) { visualIndex = selectedIndex }
    LaunchedEffect(pendingIndex) {
        val index = pendingIndex ?: return@LaunchedEffect
        withFrameNanos {}
        currentOnSelect(if (index == 1) BgTarget.Chat else BgTarget.Main)
        if (pendingIndex == index) pendingIndex = null
    }
    val selectIndex: (Int) -> Unit = { index ->
        if (index != visualIndex) {
            visualIndex = index
            pendingIndex = index
        }
    }

    LiquidBottomTabs(
        selectedTabIndex = { visualIndex },
        onTabSelected = selectIndex,
        backdrop = LocalPageBackdrop.current,
        tabsCount = 2,
        isDark = isDark,
        containerHeight = 56.dp,
        contentPadding = 4.dp,
        showSelectionShadow = false,
        // 二选一开关：调大拖拽阈值，单击绝不误触滑动；明确拖拽仍可切换
        dragTouchSlopDp = 32.dp
    ) {
        LiquidBottomTab(onClick = { selectIndex(0) }) {
            Text(
                text = "页面背景",
                fontSize = 14.sp,
                fontWeight = if (visualIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1
            )
        }
        LiquidBottomTab(onClick = { selectIndex(1) }) {
            Text(
                text = "聊天背景",
                fontSize = 14.sp,
                fontWeight = if (visualIndex == 1) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        ),
        color = AppTheme.colors.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SolidBackgroundRow(
    entry: BackgroundEntry.Solid,
    enabled: Boolean,
    onEnable: () -> Unit,
    onLongPress: () -> Unit
) {
    val colors = AppTheme.colors
    val hex = formatHex(entry.color)
    val rgb = formatRgb(entry.color)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(14.dp),
                surfaceColor = colors.surfaceVariant
            )
            .combinedClickable(
                onClick = onEnable,
                onLongClick = onLongPress
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(entry.color)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$hex  ·  $rgb",
                style = MaterialTheme.typography.bodySmall,
                color = colors.metadataContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RadioButton(
            selected = enabled,
            onClick = onEnable,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.outline
            )
        )
    }
}

@Composable
private fun AddMoreSolidRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(14.dp),
                surfaceColor = colors.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Palette,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = "添加更多",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = AppIcons.Plus,
            contentDescription = "添加纯色",
            tint = colors.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageBackgroundRow(
    entry: BackgroundEntry.Image,
    enabled: Boolean,
    onEnable: () -> Unit,
    onPreview: () -> Unit,
    onLongPress: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(14.dp),
                surfaceColor = colors.surfaceVariant
            )
            .combinedClickable(
                onClick = onEnable,
                onLongClick = onLongPress
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .clickable(onClick = onPreview)
        ) {
            AsyncImage(
                model = entry.file,
                contentDescription = "背景预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "自定义图片",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface
            )
            Text(
                text = "点击缩略图全屏查看",
                style = MaterialTheme.typography.bodySmall,
                color = colors.metadataContent
            )
        }
        RadioButton(
            selected = enabled,
            onClick = onEnable,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.outline
            )
        )
    }
}

@Composable
private fun AddMoreImageRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(14.dp),
                surfaceColor = colors.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = "添加图片",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = AppIcons.Plus,
            contentDescription = "添加图片",
            tint = colors.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun formatHex(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}

private fun formatRgb(color: Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgb($r, $g, $b)"
}
