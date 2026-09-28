package com.yunian.ai.common

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    const val PREFS_NAME = "language_prefs"
    const val KEY_LANGUAGE = "language"
    const val DEFAULT_LANGUAGE = "zh-CN"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun saveLanguage(context: Context, code: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // apply()：内存态同步更新（同进程后续 applyToResources / recreate 读得到），
        // 磁盘写入异步，避免在主线程/设置回调里 fsync 阻塞。返回值保留为兼容签名。
        return runCatching {
            prefs.edit().putString(KEY_LANGUAGE, code).apply()
            true
        }.getOrDefault(false)
    }

    fun toLocale(code: String): Locale = when (code) {
        "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.ENGLISH
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        else -> Locale.SIMPLIFIED_CHINESE
    }

    fun applyToContext(base: Context, languageCode: String = getSavedLanguage(base)): Context {
        val locale = toLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }

    fun applyToResources(context: Context, languageCode: String) {
        val locale = toLocale(languageCode)
        Locale.setDefault(locale)
        val resources = context.resources
        val config = Configuration(resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
