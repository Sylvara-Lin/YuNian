package com.yunian.ai.uicommon.component

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Window
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.yunian.ai.common.ApplicationScopeProvider
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.uicommon.theme.WeChatLightBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WindowMainBackground {

    private const val THEME_PREFS = "theme_prefs"
    private const val THEME_MODE_KEY = "theme_mode"

    @Volatile
    private var appliedKey: String? = null

    @Volatile
    private var appliedDark: Boolean? = null

    /**
     * 自定义背景「成品 Drawable」单条记忆（进程级、跨 Activity 生命周期保留）。
     *
     * 作用：使**同一 key** 背景再次应用（返回主页、`activity.recreate()`、深浅色切换重设等）
     * 时可瞬时复用，做到零解码、零裁剪、零闪烁。
     *
     * 生命周期归属：[WindowMainBackground] 是进程级 `object`，只持 1 条；
     * 背景 key 变更时被子条目替换、旧对象交 GC；窗口仍在绘制的那张位图本就需常驻，
     * 因此不引入额外泄漏。`ChatBackgroundCache.clear()`（内存压力）只清缓存引用，
     * 不影响本记忆，保证正在显示的窗口背景不被回收掉引用。
     */
    @Volatile
    private var lastCustomKey: String? = null

    @Volatile
    private var lastCustomDrawable: Drawable? = null

    /**
     * 应用窗口主背景（带缓存去重）。**绝不在调用线程做位图解码/裁剪。**
     *
     * @param scope 受控协程作用域，用于后台解码后回主线程换图。
     *   - Activity 路径请传 `activity.lifecycleScope`（随 Activity 销毁自动取消）；
     *   - Compose 路径请传 `rememberCoroutineScope()`。
     */
    fun applyFromPrefs(activity: Activity) {
        val key = getMainBackgroundKey(activity)
        val isDark = resolveIsDarkTheme(activity)
        forceApply(activity.window, activity, key, isDark, activityScope(activity))
    }

    /**
     * 取得受控协程作用域：优先 Activity 的 lifecycleScope（随 Activity 销毁自动取消，
     * 不会泄漏、不会在已销毁窗口上换图）；仅在 Activity 非 [LifecycleOwner] 的极端情况下，
     * 回落到应用级作用域（[ApplicationScopeProvider]）。
     */
    internal fun activityScope(activity: Activity): CoroutineScope =
        (activity as? LifecycleOwner)?.lifecycleScope ?: ApplicationScopeProvider.scope

    fun apply(
        window: Window,
        context: Context,
        key: String,
        isDark: Boolean,
        scope: CoroutineScope
    ) {
        if (appliedKey == key && appliedDark == isDark) return
        applyInternal(window, context, key, isDark, scope)
    }

    fun forceApply(
        window: Window,
        context: Context,
        key: String,
        isDark: Boolean,
        scope: CoroutineScope
    ) {
        appliedKey = null
        appliedDark = null
        applyInternal(window, context, key, isDark, scope)
    }

    private fun applyInternal(
        window: Window,
        context: Context,
        key: String,
        isDark: Boolean,
        scope: CoroutineScope
    ) {
        appliedKey = key
        appliedDark = isDark

        if (!isCustomBackground(key)) {
            // 预设色块 / 纯色 / 渐变：纯内存构造，零解码，直接同步设置。
            window.setBackgroundDrawable(createPresetDrawable(context, key, isDark))
            return
        }

        // 自定义背景：优先复用「成品记忆」，避免任何解码与裁剪。
        lastCustomDrawable?.takeIf { lastCustomKey == key }?.let { memo ->
            window.setBackgroundDrawable(memo)
            return
        }

        // 冷路径：先设零成本的纯色占位（绝不解码），再于后台解码 + 裁剪，完成后回主线程换图。
        window.setBackgroundDrawable(ColorDrawable(fallbackSolid(isDark)))
        scope.launch {
            val built = buildCustomDrawable(context, key) ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                // 期间背景可能已被切换：丢弃过期结果，避免用旧图覆盖新背景。
                if (appliedKey != key) return@withContext
                lastCustomKey = key
                lastCustomDrawable = built
                window.setBackgroundDrawable(built)
            }
        }
    }

    /**
     * 兼容保留：仅做「零解码」的同步构造。
     *
     * 自定义背景在无成品记忆时返回纯色占位（**真实位图请走 [apply] / [forceApply] 的异步换图路径**），
     * 因此本方法可安全地挂在主线程调用，不再触发 `BitmapFactory.decodeFile` 与全屏 `createBitmap`。
     */
    fun createDrawable(context: Context, key: String, isDark: Boolean): Drawable {
        if (isCustomBackground(key)) {
            return lastCustomDrawable?.takeIf { lastCustomKey == key }
                ?: ColorDrawable(fallbackSolid(isDark))
        }
        return createPresetDrawable(context, key, isDark)
    }

    private fun createPresetDrawable(context: Context, key: String, isDark: Boolean): Drawable {
        parseColorBackground(key)?.let { color ->
            return ColorDrawable(color.toArgb())
        }

        val gradientColors = presetGradientArgb(key, isDark)
        if (gradientColors != null && gradientColors.size >= 2) {
            return GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                gradientColors
            )
        }

        presetSolidArgb(key, isDark)?.let { argb ->
            return ColorDrawable(argb)
        }

        return ColorDrawable(fallbackSolid(isDark))
    }

    /**
     * 后台解码 + 居中裁剪，返回成品 [Drawable]；失败返回 `null`。
     *
     * 位图解码复用 [ChatBackgroundCache.load]（挂起版、single-flight，同一 key 全应用只解一次）；
     * 裁剪（全屏 `createBitmap` + `drawBitmap`）同样在后台线程完成，绝不占用主线程。
     */
    private suspend fun buildCustomDrawable(context: Context, key: String): Drawable? =
        withContext(Dispatchers.IO) {
            val bitmap = ChatBackgroundCache.load(context, key) ?: return@withContext null
            if (bitmap.isRecycled) return@withContext null
            BitmapDrawable(context.resources, centerCropToDisplay(context, bitmap))
        }

    private fun fallbackSolid(isDark: Boolean): Int {
        return if (isDark) WeChatDarkBackground.toArgb() else WeChatLightBackground.toArgb()
    }

    private fun presetGradientArgb(key: String, isDark: Boolean): IntArray? {
        if (!isDark) {
            return when (key) {
                "warm_pink" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFE8F6FC.toInt(), 0xFFCADEFC.toInt())
                "lavender" -> intArrayOf(0xFFF0ECFC.toInt(), 0xFFE4E0F6.toInt(), 0xFFC3BEF0.toInt())
                "ocean" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFCADEFC.toInt(), 0xFFB8D4F8.toInt())
                "forest" -> intArrayOf(0xFFDEFCF9.toInt(), 0xFFD4F4F0.toInt(), 0xFFC8E8E4.toInt())
                "sunset" -> intArrayOf(0xFFF4ECFC.toInt(), 0xFFE8DCF8.toInt(), 0xFFCCA8E9.toInt())
                "night" -> intArrayOf(0xFFEEEAF8.toInt(), 0xFFE0DCF0.toInt(), 0xFFC3BEF0.toInt())
                else -> null
            }
        }

        return when (key) {
            "warm_pink" -> intArrayOf(0xFF2A2034.toInt(), 0xFF221A2C.toInt(), 0xFF1A1424.toInt())
            "lavender" -> intArrayOf(0xFF242030.toInt(), 0xFF1C1828.toInt(), 0xFF161220.toInt())
            "ocean" -> intArrayOf(0xFF1A2430.toInt(), 0xFF141C28.toInt(), 0xFF101820.toInt())
            "forest" -> intArrayOf(0xFF1A2420.toInt(), 0xFF141C1A.toInt(), 0xFF101614.toInt())
            "sunset" -> intArrayOf(0xFF2C2030.toInt(), 0xFF241820.toInt(), 0xFF1C1418.toInt())
            "night" -> intArrayOf(0xFF16161E.toInt(), 0xFF101018.toInt(), 0xFF0C0C12.toInt())
            else -> null
        }
    }

    private fun presetSolidArgb(key: String, isDark: Boolean): Int? {
        if (!isDark) {
            return when (key) {
                "default" -> 0xFFDEFCF9.toInt()
                "warm_pink" -> 0xFFE8F6FC.toInt()
                "lavender" -> 0xFFE8E4F8.toInt()
                "ocean" -> 0xFFE0F0FC.toInt()
                "forest" -> 0xFFE4F8F4.toInt()
                "sunset" -> 0xFFF0E8FC.toInt()
                "night" -> 0xFFE8E4F4.toInt()
                else -> null
            }
        }
        return when (key) {
            "default" -> WeChatDarkBackground.toArgb()
            "warm_pink" -> 0xFF24161C.toInt()
            "lavender" -> 0xFF1C1724.toInt()
            "ocean" -> 0xFF141C24.toInt()
            "forest" -> 0xFF141C16.toInt()
            "sunset" -> 0xFF241814.toInt()
            "night" -> 0xFF101018.toInt()
            else -> null
        }
    }

    private fun centerCropToDisplay(context: Context, source: Bitmap): Bitmap {
        val metrics = context.resources.displayMetrics
        val targetW = metrics.widthPixels.coerceAtLeast(1)
        val targetH = metrics.heightPixels.coerceAtLeast(1)
        val srcW = source.width.coerceAtLeast(1)
        val srcH = source.height.coerceAtLeast(1)

        val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(1)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (targetW - scaledW) / 2f
        val dy = (targetH - scaledH) / 2f

        val config = source.config ?: Bitmap.Config.RGB_565
        val output = Bitmap.createBitmap(targetW, targetH, config)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawColor(AndroidColor.BLACK)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    fun resolveIsDarkTheme(context: Context): Boolean {
        val prefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
        val modeName = prefs.getString(THEME_MODE_KEY, "SYSTEM") ?: "SYSTEM"
        return when (modeName) {
            "LIGHT" -> false
            "DARK" -> true
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}
