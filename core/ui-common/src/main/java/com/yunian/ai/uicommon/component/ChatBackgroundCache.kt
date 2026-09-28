package com.yunian.ai.uicommon.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yunian.ai.common.ApplicationScopeProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义聊天背景位图缓存。
 *
 * 本轮重构要点（对应背景加载 P0 缺陷）：
 * 1. **彻底移除 `recycle()`**（修 B4）：位图可能正被 `BitmapPainter` / 绘制层持有，
 *    在缓存层无法安全判定「已无绘制者」—— `YuNianApplication.onTrimMemory/onTerminate`
 *    会在界面仍可能绘制时调用 [clear]，此前的 `recycle()` 是最高危崩溃源
 *    （`Canvas: trying to use a recycled bitmap`）。淘汰与清理**只丢引用，交由 GC**。
 * 2. **LruCache（条目数 + 总字节双限）**：≤ [MAX_ENTRY_COUNT] 张且 ≤ [MAX_TOTAL_BYTES]。
 * 3. **[load] 改为 `suspend` + single-flight**（修 B5）：同一 key 全应用只解码一次。
 * 4. **失败不再永久缓存 `null`**（修 B3）：失败时不入缓存，保证后续可重试。
 * 5. [getCachedBitmap] / [loadBitmap] / [preload] / [clear] 保留，向后兼容既有调用方。
 */
object ChatBackgroundCache {

    private const val MAX_ENTRY_COUNT = 4
    private const val MAX_TOTAL_BYTES = 24 * 1024 * 1024L

    private const val REQ_WIDTH = 1080
    private const val REQ_HEIGHT = 1920

    private val cacheLock = Any()

    /** 访问序 LinkedHashMap：迭代序即「最久未用 → 最近使用」，用于实现 LRU 淘汰。 */
    private val cache = LinkedHashMap<String, Bitmap>(MAX_ENTRY_COUNT, 0.75f, true)
    private var cacheBytes = 0L

    /** single-flight：同一 key 并发只解码一次，完成后移除表项。 */
    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /**
     * 同步读取缓存（兼容既有调用）。未加载 / 已失败 / 已淘汰均返回 `null`。
     * 语义与旧实现保持一致（区分不出「未加载」与「失败」——需要该区分请用 [load] 的返回值）。
     */
    fun getCachedBitmap(key: String): Bitmap? {
        if (key.isEmpty()) return null
        synchronized(cacheLock) {
            return cache[key]
        }
    }

    /**
     * 同步加载（**当前已无调用方，保留仅为向后兼容**）。
     *
     * ⚠️ 本方法会在调用线程同步解码（`BitmapFactory.decodeFile` 两遍），**不得在主线程调用**。
     * 窗口背景路径（[WindowMainBackground]）已改为「占位 + 后台挂起版 [load] 换图」，不再使用本方法；
     * 若未来确有同步需求，请确认处于后台线程并明确注释原因。
     */
    fun loadBitmap(context: Context, key: String): Bitmap? {
        if (key.isEmpty() || !isCustomBackground(key)) return null
        getCachedBitmap(key)?.let { return it }

        val bitmap = decode(context.applicationContext, key) ?: return null
        putInCache(key, bitmap)
        return bitmap
    }

    /**
     * 挂起加载：single-flight，同一 key 并发只解码一次。
     *
     * @return 解码成功返回 [Bitmap]，失败返回 `null`（**失败不写缓存**，可重试）。
     */
    suspend fun load(context: Context, key: String): Bitmap? {
        if (key.isEmpty() || !isCustomBackground(key)) return null
        getCachedBitmap(key)?.let { return it }

        val appContext = context.applicationContext
        val deferred = inFlight.computeIfAbsent(key) {
            ApplicationScopeProvider.scope.async {
                val bitmap = decode(appContext, key)
                if (bitmap != null) putInCache(key, bitmap)
                bitmap
            }
        }
        return try {
            deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /** 预加载：复用 [load] 的 single-flight 入口。 */
    fun preload(context: Context, key: String) {
        if (!isCustomBackground(key)) return
        val appContext = context.applicationContext
        ApplicationScopeProvider.scope.launch { load(appContext, key) }
    }

    /**
     * 清空缓存：**只丢引用，绝不 `recycle()`**（修 B4）。
     * 进行中的解码不取消——完成后照常写入缓存，下次仍可命中。
     */
    fun clear() {
        synchronized(cacheLock) {
            cache.clear()
            cacheBytes = 0L
        }
    }

    // ---- 内部实现 ----------------------------------------------------------------

    private fun decode(context: Context, key: String): Bitmap? {
        val file: File = getCustomBackgroundFile(context, key) ?: return null
        if (!file.exists()) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(options, REQ_WIDTH, REQ_HEIGHT)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.RGB_565

            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: OutOfMemoryError) {
            // OOM 是 Error 非 Exception：MIUI/MTK 等激进机型上即便降采样仍可能 OOM，
            // 不兜底会直接崩进程（修 FIX-1）。失败不入缓存，保证后续可重试。
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun putInCache(key: String, bitmap: Bitmap) {
        synchronized(cacheLock) {
            cache.remove(key)?.let { cacheBytes -= it.byteCount }
            cache[key] = bitmap
            cacheBytes += bitmap.byteCount
            trimCacheLocked()
        }
    }

    /** 双限淘汰：仅从缓存移除引用，交由 GC 回收（**永不 recycle**）。 */
    private fun trimCacheLocked() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext() && (cache.size > MAX_ENTRY_COUNT || cacheBytes > MAX_TOTAL_BYTES)) {
            val eldest = iterator.next()
            cacheBytes -= eldest.value.byteCount
            iterator.remove()
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
