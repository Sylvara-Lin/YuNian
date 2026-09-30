package com.yunian.ai.common

import android.util.Log
import com.yunian.ai.common.crash.CrashBreadcrumbs

/**
 * 项目统一日志门面。
 *
 * **release 可诊断性改造（2026-10）**：
 *  每个方法无论 `isDebug` 与否，都**先写入 [CrashBreadcrumbs]**（内存环形缓冲，不落盘），
 *  再按 `isDebug` 决定是否 `Log.x`。这样全项目已有的 SecureLog 调用点**零改动**即可在
 *  release 崩溃时被 dump 出来，用于复盘「崩溃前发生了什么」。
 *
 *  - breadcrumbs 有界（固定条数 + 单条截断），内存恒定为常数，热路径开销极小；
 *  - 脱敏推迟到崩溃 dump 那一刻（[CrashBreadcrumbs.snapshot]）—— 热路径不做正则；
 *  - `Log.x` 的可读输出仍严格受 `isDebug` 守卫，保持 release 不刷 logcat 的既有语义。
 */
object SecureLog {
    private const val TAG = "YuNian"

    @Volatile
    private var isDebug: Boolean = false

    fun init(debug: Boolean) {
        isDebug = debug
    }

    @JvmStatic
    fun d(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.d(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun i(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.i(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun w(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.w(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun e(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.e(TAG, "[$subtag] $msg")
    }

    @JvmStatic
    fun e(subtag: String, msg: String, tr: Throwable) {
        CrashBreadcrumbs.add(subtag, "$msg | ${tr.javaClass.simpleName}: ${tr.message}")
        if (isDebug) Log.e(TAG, "[$subtag] $msg", tr)
    }

    @JvmStatic
    fun critical(msg: String) {
        // critical 原本就无 guard（永远打 logcat），此处同样永远记 breadcrumb。
        CrashBreadcrumbs.add("CRITICAL", msg)
        Log.wtf(TAG, "[CRITICAL] $msg")
    }

    @JvmStatic
    fun security(msg: String) {
        CrashBreadcrumbs.add("SEC", msg)
        if (isDebug) Log.d(TAG, "[SEC] $msg")
    }

    @JvmStatic
    fun api(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.d(TAG, "[API][$subtag] $msg")
    }

    @JvmStatic
    fun network(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.d(TAG, "[NET][$subtag] $msg")
    }

    @JvmStatic
    fun chunk(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.v(TAG, "[CHUNK][$subtag] $msg")
    }

    @JvmStatic
    fun typing(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
        if (isDebug) Log.d(TAG, "[TYPING][$subtag] $msg")
    }

    @JvmStatic
    fun perf(subtag: String, msg: String) {
        CrashBreadcrumbs.add(subtag, msg)
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
