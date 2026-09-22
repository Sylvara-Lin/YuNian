package com.yunian.ai.common.concurrent

import java.util.concurrent.atomic.AtomicReference

/**
 * 发送防重守卫：同一内容在时间窗口内的重复提交判定为误触（双击/回车连按），静默拒绝。
 *
 * - 仅比对内容本身：不同内容不受窗口限制（用户有意连发不同消息不受影响）
 * - 窗口锚定在**第一次接受**的时刻：窗口内的重复点击不刷新锚点，
 *   因此连续点击最多放行第一条，窗口过后自然恢复
 * - 线程安全：@Synchronized（发送入口均在主线程，开销可忽略；防御性覆盖后台调用）
 *
 * @param windowMs 防重窗口（毫秒）。2000ms 为常见 IM 防连击口径。
 */
class DuplicateSendGuard(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val last = AtomicReference<SendRecord?>(null)

    private data class SendRecord(val content: String, val at: Long)

    /**
     * 判定并记录一次发送。
     * @return true = 判定为窗口内重复提交，调用方应**静默忽略**本次发送
     */
    @Synchronized
    fun shouldReject(content: String): Boolean {
        val now = clock()
        val prev = last.get()
        if (prev != null && prev.content == content && now - prev.at < windowMs) {
            return true // 窗口内重复：拒绝且不刷新锚点（窗口锚定首次接受）
        }
        last.set(SendRecord(content, now))
        return false
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2000L
    }
}
