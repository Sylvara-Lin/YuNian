package com.yunian.ai.common.crash

import android.content.Context
import android.os.Process
import java.io.File

/**
 * 崩溃日志的**磁盘存储**。
 *
 * 关键约束（本项目血泪教训）：
 *  - **绝不依赖 `BuildConfig.DEBUG` / `SecureLog` / `ChatDebugLog`**：那些在 release 是 no-op。
 *    这里只用 `Context.filesDir` + 原生文件 IO，因此 **release 包同样生效**。
 *  - **不依赖 Room / Repository**：崩溃很可能正是 DB 引起的，展示路径必须与之解耦，
 *    否则「想看图 -> 界面又崩」。本类只碰文件系统。
 *  - **有界**：单文件上限 [MAX_CHARS]，写入走「临时文件 + rename」避免半截文件。
 *
 * 落盘布局（`filesDir/crash/`）：
 *  - `crash_business.txt` —— 业务层处理器写入的**完整**报告（含 breadcrumbs）。
 *  - `crash_shell.txt`    —— 壳层处理器写入的**最小**报告（业务 DEX 未加载时也能落盘）。
 *  - `.business_alive`    —— 业务层处理器安装标记（含 PID），供壳层判断「业务层是否已接管」。
 *
 * 读取规则：两个崩溃文件都在时，取 **lastModified 更新** 的那份（壳层崩溃只更新 shell 文件，
 * 业务层崩溃只更新 business 文件，二者互斥，故 mtime 比较无歧义）。
 */
object CrashLogStore {

    private const val DIR_NAME = "crash"
    private const val BUSINESS_FILE = "crash_business.txt"
    private const val SHELL_FILE = "crash_shell.txt"
    private const val ALIVE_FILE = ".business_alive"
    private const val BREADCRUMBS_FILE = "breadcrumbs.txt"
    private const val EXIT_ACK_FILE = ".last_exit_ts"

    /** 单文件字符上限（约 256KB 的 UTF-16 近似；崩溃报告远小于此）。 */
    internal const val MAX_CHARS = 128 * 1024

    /** 崩溃目录（必要时创建）。 */
    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun businessFile(context: Context): File = File(dir(context), BUSINESS_FILE)
    fun shellFile(context: Context): File = File(dir(context), SHELL_FILE)
    fun businessAliveMarker(context: Context): File = File(dir(context), ALIVE_FILE)

    // ── 业务层存活标记（跨 ClassLoader 通过文件系统通信）────────────────────────

    /** 业务层安装处理器时写入当前 PID，声明「本进程业务层已接管崩溃捕获」。 */
    fun markBusinessAlive(context: Context) {
        runCatching { businessAliveMarker(context).writeText(Process.myPid().toString()) }
    }

    /** 业务层写盘失败时清除标记，让壳层兜底写一份最小报告。 */
    fun clearBusinessAlive(context: Context) {
        runCatching { businessAliveMarker(context).delete() }
    }

    /** 判断业务层处理器是否已在**本进程**接管。 */
    fun isBusinessAliveInThisProcess(context: Context): Boolean {
        return runCatching {
            val f = businessAliveMarker(context)
            f.exists() && f.readText().trim() == Process.myPid().toString()
        }.getOrDefault(false)
    }

    // ── 写入 ─────────────────────────────────────────────────────────────────

    fun writeBusiness(context: Context, text: String) = writeAtomic(businessFile(context), text)

    fun writeShell(context: Context, text: String) = writeAtomic(shellFile(context), text)

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            val dir = file.parentFile ?: return
            if (!dir.exists()) dir.mkdirs()
            val payload = if (text.length > MAX_CHARS) {
                text.substring(0, MAX_CHARS) + "\n… (truncated)\n"
            } else {
                text
            }
            // 临时文件 + rename：避免下次启动读到半截 JSON 文本。
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(payload)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    // ── 读取 / 清理 ────────────────────────────────────────────────────────────

    /** 是否存在待展示的崩溃记录。 */
    fun hasCrash(context: Context): Boolean =
        businessFile(context).exists() || shellFile(context).exists()

    /**
     * 读取最近一次崩溃报告（两份文件中 lastModified 更新者）。
     *
     * @return 报告文本；无记录或读取失败返回 null。
     */
    fun readLastCrash(context: Context): String? {
        val biz = businessFile(context).let { if (it.exists()) it else null }
        val shell = shellFile(context).let { if (it.exists()) it else null }
        val chosen = when {
            biz != null && shell != null -> if (biz.lastModified() >= shell.lastModified()) biz else shell
            biz != null -> biz
            shell != null -> shell
            else -> return null
        }
        return runCatching { chosen.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 清除所有崩溃记录（用户点「清除记录」）。 */
    fun clear(context: Context) {
        runCatching { businessFile(context).delete() }
        runCatching { shellFile(context).delete() }
    }

    // ── 持久化 breadcrumbs（供 native/系统级崩溃的上下文）────────────────────────

    private fun breadcrumbsFile(context: Context): File = File(dir(context), BREADCRUMBS_FILE)

    /** 后台时把内存面包屑落盘（有界，只留最新一份）。 */
    fun writeBreadcrumbs(context: Context, text: String) = writeAtomic(breadcrumbsFile(context), text)

    /** 读取上次落盘的面包屑（native 崩溃后进程内存已失，只能靠它）。 */
    fun readBreadcrumbs(context: Context): String? =
        runCatching { breadcrumbsFile(context).readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    // ── ApplicationExitInfo 消费游标 ─────────────────────────────────────────────

    private fun exitAckFile(context: Context): File = File(dir(context), EXIT_ACK_FILE)

    /** 已处理过的最近一次进程退出时间戳（避免同一退出反复弹窗）。 */
    fun readExitAck(context: Context): Long =
        runCatching { exitAckFile(context).readText().trim().toLong() }.getOrDefault(0L)

    fun writeExitAck(context: Context, timestamp: Long) =
        runCatching { exitAckFile(context).writeText(timestamp.toString()) }
}
