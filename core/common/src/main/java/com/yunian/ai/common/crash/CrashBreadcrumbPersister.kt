package com.yunian.ai.common.crash

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台时机把内存 breadcrumbs 落盘。
 *
 * 为什么需要：native 崩溃 / 被系统杀 / ANR 时 **Java 处理器根本不执行**，
 * 内存里的 breadcrumbs 随进程一起消失，导致「系统级崩溃」的报告没有上下文。
 * 因此在**事件驱动**的时机（应用退到后台）把 breadcrumbs 落一份到磁盘，
 * 下次启动由 [ApplicationExitMonitor] 组装报告时读取。
 *
 * 设计约束（team-lead 要求）：
 *  - **不轮询**：只在 Activity 生命周期事件（所有 Activity `onStopped` = 进入后台）触发；
 *  - **有界**：复用 [CrashLogStore.writeBreadcrumbs] 的上限（单文件 128K 字符），只留最新一份；
 *  - **IO 线程**：落盘在独立后台线程，绝不阻塞主线程/启动；
 *  - **变更检测 + 限频**：breadcrumbs 无变化则不写；最小间隔 [MIN_INTERVAL_MS] 防抖。
 *
 * 已知取舍：前台发生的 native 崩溃，最后一次落盘可能停留在上一次退后台时刻，
 * breadcrumbs 可能略旧 —— 这是「不轮询/不耗电」与「上下文新鲜度」之间的自觉取舍。
 */
object CrashBreadcrumbPersister {

    private const val MIN_INTERVAL_MS = 5_000L

    private val installed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "yn-breadcrumb-persist").apply { isDaemon = true }
    }

    private val lock = Any()
    private var startedActivities = 0
    private var lastPersistedVersion = 0L
    private var lastPersistedAt = 0L

    /**
     * 安装生命周期回调。幂等；从 Application 尽早调用。
     *
     * @param application 宿主 Application。
     */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    synchronized(lock) { startedActivities++ }
                }

                override fun onActivityStopped(activity: Activity) {
                    val remaining = synchronized(lock) { --startedActivities }
                    if (remaining <= 0) persistIfNeeded(application)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    /** 供诊断/测试手动触发。 */
    fun persistNow(application: Application) = persistIfNeeded(application, force = true)

    private fun persistIfNeeded(application: Application, force: Boolean = false) {
        val version = CrashBreadcrumbs.version()
        if (version <= 0L) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!force) {
                if (version == lastPersistedVersion) return
                if (now - lastPersistedAt < MIN_INTERVAL_MS) return
            }
            lastPersistedVersion = version
            lastPersistedAt = now
        }
        val context = application.applicationContext ?: return
        // snapshot + 写盘**整体**下沉到单线程 executor。
        // onActivityStopped 是主线程回调；snapshot() 会对 ≤150 行做时间格式化 + 正则脱敏，
        // 若留在主线程会造成切前后台时 ms 级卡顿。snapshot 是纯内存操作，移入子线程零风险。
        executor.execute {
            runCatching {
                val snapshot = CrashBreadcrumbs.snapshot()
                CrashLogStore.writeBreadcrumbs(context, snapshot)
            }
        }
    }
}
