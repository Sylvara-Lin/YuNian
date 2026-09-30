package com.yunian.ai.common.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo

/**
 * 「哪些进程退出原因值得向用户提示」的**纯逻辑**策略。
 *
 * **常量来源（根因修复）**：本文件**不再手抄任何数值**，全部直接引用框架常量
 * `ApplicationExitInfo.REASON_*` 与 `ActivityManager.RunningAppProcessInfo.IMPORTANCE_*`。
 * 它们都是 `public static final int`，Kotlin 编译期即**内联为字面量**，运行时不加载
 * Android 类（故 JVM 单测/API 30 以下均安全）。
 *
 * 为什么必须这么做：上一版把整张常量表**手抄错位** —— 将
 * `REASON_INITIALIZATION_FAILURE` 抄成了 `8`，而权威值 `8` 实际是
 * `REASON_PERMISSION_CHANGE`。由此产生两个真实后果：
 *  - **误报**：运行中 `pm revoke <权限>` 导致进程被杀，重启后被提示为「上次运行异常退出」
 *    （报告里写着 `reason: REASON_8 description: permissions revoked`）—— 权限变更不是崩溃；
 *  - **漏报**：真正的初始化失败（权威值 7）不在白名单，用户看不到。
 * 改为引用框架常量后，「抄错数字」这类错误从根上不可能再发生。
 *
 * 权威常量表（`javap -constants -classpath platforms/android-35/android.jar` 核对，仅作文档）：
 * ```
 * REASON_UNKNOWN                  = 0   → 不提示
 * REASON_EXIT_SELF                = 1   → 不提示（正常退出）
 * REASON_SIGNALED                 = 2   → ✅ 提示（被信号终止，疑似底层崩溃）
 * REASON_LOW_MEMORY               = 3   → ✅ 提示（仅当前台/前台服务，见 [isNotable]）
 * REASON_CRASH                    = 4   → ✅ 提示（Java 未捕获异常）
 * REASON_CRASH_NATIVE             = 5   → ✅ 提示（native 崩溃）
 * REASON_ANR                      = 6   → ✅ 提示（应用无响应）
 * REASON_INITIALIZATION_FAILURE   = 7   → ✅ 提示（初始化失败）
 * REASON_PERMISSION_CHANGE        = 8   → 不提示（权限变更，非崩溃）
 * REASON_EXCESSIVE_RESOURCE_USAGE = 9   → 不提示
 * REASON_USER_REQUESTED           = 10  → 不提示（用户从最近任务划掉）
 * REASON_USER_STOPPED             = 11  → 不提示
 * REASON_DEPENDENCY_DIED          = 12  → 不提示
 * REASON_OTHER                    = 13  → 不提示（语义不明）
 * REASON_FREEZER                  = 14  → 不提示
 * REASON_PACKAGE_STATE_CHANGE     = 15  → 不提示
 * REASON_PACKAGE_UPDATED          = 16  → 不提示
 * ```
 */
internal object ApplicationExitPolicy {

    /** 值得向用户提示的退出原因（白名单）。 */
    private val NOTABLE: Set<Int> = setOf(
        ApplicationExitInfo.REASON_SIGNALED,               // 2  被信号终止（疑似底层崩溃）
        ApplicationExitInfo.REASON_LOW_MEMORY,             // 3  内存不足（仅前台/前台服务时提示）
        ApplicationExitInfo.REASON_CRASH,                  // 4  Java 未捕获异常
        ApplicationExitInfo.REASON_CRASH_NATIVE,           // 5  native 崩溃（华为那类）
        ApplicationExitInfo.REASON_ANR,                    // 6  应用无响应
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, // 7  初始化失败
    )

    /**
     * `REASON_LOW_MEMORY` 的收窄阈值：仅当进程**死亡时刻**的重要性 ≤ 该值时才提示。
     *
     * 取值 = `IMPORTANCE_FOREGROUND_SERVICE (125)`，即「用户确实在前台/前台服务」：
     * `FOREGROUND(100)` 与 `FOREGROUND_SERVICE(125)`。
     *
     * **为什么收窄**：系统回收纯后台进程同样会产生 `LOW_MEMORY`；若不加区分，用户每次回到
     * 应用都可能看到崩溃弹窗 = 骚扰。故只对「用户可感知」的前台/前台服务状态提示，
     * 纯后台被回收（`SERVICE(300)` / `BACKGROUND-CACHED(400)` / `EMPTY(500)`）**不提示**。
     *
     * 说明：`IMPORTANCE_VISIBLE(200)`（有可见界面但未获焦点）**刻意排除**，以严格贴合
     * 「前台/前台服务」口径；本 App 常驻保活前台服务，被 LMK 时通常为 125，已覆盖。
     * 如需放宽到可见即提示，把此处改为 `IMPORTANCE_VISIBLE` 即可。
     */
    private val LOW_MEMORY_MAX_IMPORTANCE: Int =
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

    /**
     * 该退出原因在给定**死亡时刻重要性**下是否值得提示。
     *
     * 仅对 `LOW_MEMORY` 施加重要性收窄；其余原因（崩溃/信号/ANR/初始化失败）与重要性无关。
     *
     * @param reason     退出原因，取自 [ApplicationExitInfo] 的 `REASON_*`。
     * @param importance 进程死亡时的 `ApplicationExitInfo.getImportance()`，
     *                   取自 `ActivityManager.RunningAppProcessInfo.IMPORTANCE_*`。
     */
    fun isNotable(reason: Int, importance: Int): Boolean {
        if (reason !in NOTABLE) return false
        if (reason == ApplicationExitInfo.REASON_LOW_MEMORY) {
            return importance <= LOW_MEMORY_MAX_IMPORTANCE
        }
        return true
    }
}
