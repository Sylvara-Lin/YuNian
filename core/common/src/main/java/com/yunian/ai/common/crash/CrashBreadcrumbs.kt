package com.yunian.ai.common.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃「面包屑」—— 固定容量的**内存**环形缓冲，只记录最近若干条日志上下文，
 * 崩溃发生时才 dump，平时不落盘、不泄漏。
 *
 * 设计要点：
 *  - **release 也记录**：这是让「线上 release 包零可观测性」变得可诊断的关键一环。
 *    [SecureLog] 的每个方法无论 `isDebug` 与否都先写入本缓冲。
 *  - **零热路径开销**：`add()` 只做「截断 + 存字符串 + 存时间戳」，不做正则脱敏、
 *    不做时间格式化（这两件事都推迟到 [snapshot]，即崩溃那一刻才做）。
 *  - **线程安全**：崩溃可能来自任意线程，读写全部走同一把锁。
 *  - **有界**：固定 [MAX_ENTRIES] 条，单条 [MAX_MSG_LEN] 字符，dump 上限 [MAX_DUMP_CHARS]。
 *    环形覆盖保证内存占用恒定为常数。
 */
object CrashBreadcrumbs {

    private const val MAX_ENTRIES = 150
    private const val MAX_MSG_LEN = 300
    private const val MAX_DUMP_CHARS = 24 * 1024

    private val lock = Any()

    private val times = LongArray(MAX_ENTRIES)
    private val tags = arrayOfNulls<String>(MAX_ENTRIES)
    private val msgs = arrayOfNulls<String>(MAX_ENTRIES)

    private var next = 0
    private var count = 0

    /** 单调递增的「内容版本」，供后台落盘做变更检测（避免无变化时重复写盘）。 */
    @Volatile
    private var mutations: Long = 0L

    /** 内容版本：每次 [add] 自增。 */
    fun version(): Long = mutations

    /**
     * 记录一条面包屑。[msg] 超长会被截断；本方法**绝不抛出**。
     *
     * @param tag 子标签（通常是调用点所在类/模块名）。
     * @param msg 日志正文（原始，脱敏推迟到 [snapshot]）。
     */
    fun add(tag: String, msg: String) {
        try {
            val safeMsg = if (msg.length > MAX_MSG_LEN) msg.substring(0, MAX_MSG_LEN) else msg
            val now = System.currentTimeMillis()
            synchronized(lock) {
                times[next] = now
                tags[next] = tag
                msgs[next] = safeMsg
                next = (next + 1) % MAX_ENTRIES
                if (count < MAX_ENTRIES) count++
                mutations++
            }
        } catch (_: Throwable) {
            // 面包屑永远不能影响业务或崩溃路径。
        }
    }

    /**
     * dump 最近的面包屑（按时间正序、已脱敏）。
     *
     * 超过 [MAX_DUMP_CHARS] 时**从头部（最旧）丢弃**，始终保留最靠近崩溃时刻的最新若干条。
     *
     * @return 可直接拼进崩溃报告的文本；无记录时返回提示串。
     */
    fun snapshot(): String {
        return try {
            val n: Int
            val start: Int
            val lines: MutableList<String>
            synchronized(lock) {
                n = count
                start = if (n == MAX_ENTRIES) next else 0
                if (n == 0) return "(no breadcrumbs)"
                val fmt = newFormatter()
                lines = ArrayList(n)
                for (i in 0 until n) {
                    val idx = (start + i) % MAX_ENTRIES
                    val raw = buildString {
                        append(fmt.format(Date(times[idx])))
                        append(' ')
                        append('[')
                        append(tags[idx] ?: "?")
                        append("] ")
                        append(msgs[idx] ?: "")
                    }
                    lines.add(CrashRedactor.redact(raw))
                }
            }
            val total = lines.sumOf { it.length + 1 }
            val sb = StringBuilder(minOf(total, MAX_DUMP_CHARS + 64))
            if (total > MAX_DUMP_CHARS) {
                // 从尾部（最新）向前累积到上限 —— 保留最新、丢弃最旧。
                var acc = 0
                var from = lines.size
                for (i in lines.indices.reversed()) {
                    val len = lines[i].length + 1
                    if (acc + len > MAX_DUMP_CHARS) break
                    acc += len
                    from = i
                }
                sb.append("… (older breadcrumbs truncated)\n")
                for (i in from until lines.size) {
                    sb.append(lines[i]).append('\n')
                }
            } else {
                for (line in lines) {
                    sb.append(line).append('\n')
                }
            }
            sb.toString()
        } catch (_: Throwable) {
            "(breadcrumbs unavailable)"
        }
    }

    /** 清空面包屑（测试/诊断用）。 */
    fun clear() {
        synchronized(lock) {
            tags.fill(null)
            msgs.fill(null)
            next = 0
            count = 0
        }
    }

    private fun newFormatter(): SimpleDateFormat =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
