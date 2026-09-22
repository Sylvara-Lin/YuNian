package com.yunian.ai.uicommon.picker

import android.content.Context
import android.graphics.Bitmap
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

object PickerImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }
    }

    private fun build(context: Context): ImageLoader {
        return ImageLoader.Builder(context.applicationContext)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(context.applicationContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("picker_thumbnails"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
