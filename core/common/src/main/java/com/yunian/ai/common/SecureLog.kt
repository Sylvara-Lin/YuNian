package com.yunian.ai.common

import android.util.Log

object SecureLog {
    private const val TAG = "YuNian"

    @Volatile
    private var isDebug: Boolean = false

    fun init(debug: Boolean) {
        isDebug = debug
    }

    @JvmStatic
    fun d(subtag: String, msg: String) {
        if (isDebug) Log.d(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun i(subtag: String, msg: String) {
        if (isDebug) Log.i(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun w(subtag: String, msg: String) {
        if (isDebug) Log.w(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun e(subtag: String, msg: String) {
        if (isDebug) Log.e(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun e(subtag: String, msg: String, tr: Throwable) {
        if (isDebug) Log.e(TAG, "[$subtag] $msg", tr)
    }

    @JvmStatic
    fun critical(msg: String) {

        Log.wtf(TAG, "[CRITICAL] $msg")
    }

    @JvmStatic
    fun security(msg: String) {
        if (isDebug) Log.d(TAG, "[SEC] $msg")
    }

    @JvmStatic
    fun api(subtag: String, msg: String) {
        if (isDebug) Log.d(TAG, "[API][$subtag] $msg")
    }

    @JvmStatic
    fun network(subtag: String, msg: String) {
        if (isDebug) Log.d(TAG, "[NET][$subtag] $msg")
    }

    @JvmStatic
    fun chunk(subtag: String, msg: String) {
        if (isDebug) Log.v(TAG, "[CHUNK][$subtag] $msg")
    }

    @JvmStatic
    fun typing(subtag: String, msg: String) {
        if (isDebug) Log.d(TAG, "[TYPING][$subtag] $msg")
    }

    @JvmStatic
    fun perf(subtag: String, msg: String) {
        if (isDebug) Log.d(TAG, "[PERF][$subtag] $msg")
    }

    inline fun <T> timed(subtag: String, operation: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - start
            perf(subtag, "$operation took ${elapsed}ms")
        }
    }
}
