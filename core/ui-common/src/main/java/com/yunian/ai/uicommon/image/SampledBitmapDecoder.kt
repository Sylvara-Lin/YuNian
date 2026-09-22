package com.yunian.ai.uicommon.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.calcSampleSizeForMaxDimension

/**
 * 裁剪输入（头像 / 聊天背景）的解码长边上限（像素）。
 *
 * 取值依据：裁剪结果落在屏幕上（`CropCoordinator` 输出 ≈ 裁剪框像素尺寸，约 ≤1400px），
 * 故 1200px 的输入足够渲染，同时把 1080×2400 这类长截图从 ≈10MB 降到 ≈2.6MB（长边采样 /2），
 * 明显压低「解码 + 裁剪」的内存峰值（Redmi Note 12 Pro / MIUI / 天玑1080 上报的闪退路径）。
 */
const val CROP_SOURCE_MAX_DIMENSION = 1200

/**
 * 从 [uri] 两遍采样解码为 [Bitmap]（裁剪输入用）：
 *  1. `inJustDecodeBounds` 读原始尺寸 → 算 `inSampleSize`（长边采样到 ≥ [maxDimension] 的最小 2 的幂）；
 *  2. 按采样率（与 [config] 色彩配置）二次解码。
 *
 * @param maxDimension 解码长边上限（像素）。注意 [calcSampleSizeForMaxDimension] 的语义是
 *   「采样后长边落在 `[maxDimension, 2*maxDimension)`」，故**实际长边可能达到约 `2*maxDimension`**，
 *   而非严格 `<= maxDimension`。
 * @param config 二次解码的 [`Bitmap.Config`]（`inPreferredConfig`）。**默认 `ARGB_8888`，与旧行为逐位一致**
 *   （`ARGB_8888` 正是 `BitmapFactory` 的默认值，故既有调用点语义零变化）。
 *   按用途分档：头像用默认（小显示区，需 alpha 安全）；大尺寸全屏背景可传 `RGB_565`
 *   （内存减半、无 alpha，见 [com.yunian.ai.uicommon.component.BackgroundSettingsScreen]）。
 *
 * 健壮性：`OutOfMemoryError` 是 `Error` 而非 `Exception`，**不捕获会直接崩进程**；
 * 这里显式兜底为 `null`（MIUI/MTK 等内存激进的机型上，畸形/超大图即便降采样仍可能 OOM），
 * 由调用方退化为「不进入裁剪」。
 */
fun decodeUriSampledForCrop(
    context: Context,
    uri: Uri,
    maxDimension: Int = CROP_SOURCE_MAX_DIMENSION,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calcSampleSizeForMaxDimension(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = config
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (e: OutOfMemoryError) {
        SecureLog.e("SampledBitmapDecoder", "decode uri OOM: $uri")
        null
    } catch (e: Exception) {
        SecureLog.e("SampledBitmapDecoder", "decode uri failed: ${e.message}")
        null
    }
}
