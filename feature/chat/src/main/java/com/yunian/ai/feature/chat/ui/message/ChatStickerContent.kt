package com.yunian.ai.feature.chat.ui.message

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunian.ai.common.StickerManager
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun StickerContentBubble(
    stickerName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(stickerName) {
        bitmap = null
        bitmap = loadStickerBitmap(appContext, stickerName)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = stickerName,
            modifier = modifier
                .sizeIn(maxWidth = 140.dp, maxHeight = 140.dp)
                .width(120.dp)
                .height(120.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Brush,
                contentDescription = stickerName,
                modifier = Modifier.size(32.dp),
                tint = AppTheme.colors.metadataContent
            )
        }
    }
}

private suspend fun loadStickerBitmap(context: Context, stickerName: String): Bitmap? = withContext(Dispatchers.IO) {
    val manager = StickerManager.getInstance(context)
    var sticker = manager.findStickerByDescriptionExact(stickerName)
    if (sticker == null && !stickerName.endsWith(".png")) {
        sticker = manager.findStickerByDescriptionExact("$stickerName.png")
    }
    if (sticker == null) {
        sticker = manager.findStickerByDescription(stickerName)
    }
    if (sticker != null) {
        // P11：气泡显示尺寸 ≤140dp，用 ≤512px 降采样加载，避免全尺寸位图内存抖动
        return@withContext manager.loadStickerBitmapSampled(sticker.path, 512)
    }

    val importedDir = File(context.filesDir, "stickers/imported")
    val possibleFiles = listOf(
        "$stickerName.png", "$stickerName.jpg", "$stickerName.jpeg",
        "$stickerName.gif", "$stickerName.webp",
        "sticker_$stickerName.png"
    )
    for (fileName in possibleFiles) {
        val file = File(importedDir, fileName)
        if (file.exists()) {
            return@withContext manager.decodeSampledFile(file.absolutePath, 512)
        }
    }

    runCatching {
        context.assets.list("stickers")
            ?.firstOrNull { it.equals("$stickerName.png", ignoreCase = true) || it.equals(stickerName, ignoreCase = true) }
            ?.let { assetName ->
                manager.decodeSampledAsset("stickers/$assetName", 512)
            }
    }.getOrNull()
}
