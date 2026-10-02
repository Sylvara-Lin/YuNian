package com.yunian.ai.agent

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Rust 侧日志文件 → logcat 的增量转发桥（真机可见性**方案 A**）。
 *
 * **背景（真机实测复现）**：Android 应用进程的 native `stdout`/`stderr` 默认被系统
 * 重定向到 `/dev/null`，因此 Rust 侧的 `eprintln!`（含 LLM 自动重试链路日志）
 * 在真机 logcat 里**完全看不到**。方案 A：Rust `agentlog` 把关键日志写文件，
 * 本桥在回合边界增量读取并把新增行转发到 logcat（tag = [TAG]），真机即可见。
 *
 * 路径约定须与 Rust `agent-native/src/agentlog.rs` + `agent.rs` 的 `init` 保持一致：
 * `<db 目录>/rust_agent_log.txt`（db = `context.getDatabasePath("yunian_database")`，
 * 库名须与 `AgentFacade.DB_NAME` / `AppDatabase.DB_NAME` 一致）。
 *
 * 增量语义：把「已转发到的字符偏移」持久化到 SharedPreferences；每次只转发新增部分，
 * 避免同一行重复刷屏。日志文件被 Rust 侧轮转清空（长度变小）时自动从头重读。
 *
 * 安全：转发内容可能含模型/网络错误文本，但**不含**密钥（密钥经 `ApiConfigRepository`
 * 解密后只在请求头使用，错误串不含密钥）；仍对原文做长度截断，避免单条过大。
 */
object RustAgentLogBridge {

    /** logcat tag（真机 `adb logcat -s LianYuNative` 过滤）。 */
    const val TAG = "LianYuNative"

    /** 与 [AgentFacade] 保持一致的数据库文件名。 */
    private const val DB_NAME = "yunian_database"

    /** 与 Rust `agentlog::LOG_FILE` 保持一致。 */
    private const val LOG_FILE_NAME = "rust_agent_log.txt"

    private const val PREFS = "rust_agent_log_bridge"
    private const val KEY_OFFSET = "offset"

    /** 单行最大转发长度（logcat 单条上限约 4K，留余量）。 */
    private const val MAX_LINE_CHARS = 2000

    /**
     * 增量转发 Rust 日志新内容到 logcat。幂等、线程安全（SharedPreferences 原子）、
     * 任何 IO 异常都静默吞掉——日志桥绝不允许影响主流程。
     */
    fun dump(context: Context) {
        runCatching {
            val file = File(context.getDatabasePath(DB_NAME).parentFile, LOG_FILE_NAME)
            if (!file.exists()) return
            val text = file.readText()
            if (text.isEmpty()) return

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var offset = prefs.getInt(KEY_OFFSET, 0)
            // 越界（文件被轮转清空/意外缩短）则从头重读。
            if (offset < 0 || offset > text.length) offset = 0
            if (offset == text.length) return

            val fresh = text.substring(offset)
            fresh.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line -> Log.i(TAG, line.take(MAX_LINE_CHARS)) }

            prefs.edit().putInt(KEY_OFFSET, text.length).apply()
        }.onFailure {
            // 刻意不用 SecureLog，避免与本桥自身的落盘面包屑形成回环。
            Log.w(TAG, "dump rust agent log failed: ${it.javaClass.simpleName}")
        }
    }

    /** 清空已转发游标（调试用：下次 [dump] 会从头重发全部日志）。 */
    fun reset(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_OFFSET, 0)
                .apply()
        }
    }
}
