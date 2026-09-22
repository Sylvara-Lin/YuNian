package com.yunian.ai.feature.chat.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-4：跨轮滚动查重窗口（[ChatTurnState.recentDedupWindow]）。
 *
 * 缺陷：旧实现每轮只用「最近 3 条历史 AI 消息」做查重窗口，窗口随轮次滚动后，
 * 上几轮刚发过的内容掉出窗口 → 跨轮复读。修复后已落实气泡的归一化内容进入
 * 容量 ≤ [RECENT_DEDUP_WINDOW_CAP] 的滚动窗口，且 reset 不清空。
 */
class ChatTurnStateRecentDedupTest {

    @Test
    fun `reset 清本轮缓存但不清跨轮窗口`() {
        val state = ChatTurnState()
        state.pushRecentDedup("上一轮发过的内容")
        state.dedupTurnKey = "turn-1"
        state.dedupWindow = mutableListOf("本轮缓存")

        state.reset()

        // 本轮缓存被清
        assertNull(state.dedupTurnKey)
        assertNull(state.dedupWindow)
        // 跨轮窗口保留——这正是它的价值所在
        assertEquals(listOf("上一轮发过的内容"), state.recentDedupWindow.toList())
    }

    @Test
    fun `容量滚动 - 超过上限时从队首滚出`() {
        val state = ChatTurnState()
        val overflow = 3
        repeat(RECENT_DEDUP_WINDOW_CAP + overflow) { state.pushRecentDedup("msg$it") }

        assertEquals(RECENT_DEDUP_WINDOW_CAP, state.recentDedupWindow.size)
        // 最早的 overflow 条被滚出，窗口里是最新的 RECENT_DEDUP_WINDOW_CAP 条
        assertEquals("msg$overflow", state.recentDedupWindow.first())
        assertEquals("msg${RECENT_DEDUP_WINDOW_CAP + overflow - 1}", state.recentDedupWindow.last())
    }

    @Test
    fun `空归一化内容不入窗`() {
        val state = ChatTurnState()
        state.pushRecentDedup("")
        assertEquals(0, state.recentDedupWindow.size)
    }

    @Test
    fun `并发冒烟 - 多线程交替 push 与 snapshot 不抛异常且内容有序不丢`() {
        // 生产环境场景：deliverResponse 在逐条送达协程 push、loadDedupWindow 在生成协程读，
        // 线程不 join（stale-job 取消即放手）→ 窗口并发读改。加固后必须无 CME、无内容丢失。
        val state = ChatTurnState()
        val total = 5000
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        fun guarded(body: () -> Unit): Thread = Thread {
            try {
                body()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        // 单生产者：保证窗口内容天然有序（msg0..msg4999），便于快照一致性断言
        val producer = guarded {
            repeat(total) { state.pushRecentDedup("msg$it") }
        }
        val consumers = (1..4).map {
            guarded {
                repeat(2000) {
                    val snapshot = state.snapshotRecentDedup()
                    // 快照必须是「某一时刻的尾窗口」：不超容量、msgK 的 K 严格递增
                    if (snapshot.size > RECENT_DEDUP_WINDOW_CAP) {
                        throw AssertionError("快照超容量: size=${snapshot.size}")
                    }
                    val indices = snapshot.map { it.removePrefix("msg").toInt() }
                    if (indices.zipWithNext().any { (a, b) -> b <= a }) {
                        throw AssertionError("快照内容乱序/被改写: $indices")
                    }
                }
            }
        }

        (consumers + producer).forEach { it.start() }
        producer.join()
        consumers.forEach { it.join() }

        assertEquals("并发执行出现未捕获异常: ${errors.firstOrNull()}", 0, errors.size)

        // 内容不丢：全部 join 后窗口 = 恰好最后 RECENT_DEDUP_WINDOW_CAP 条
        val expected = (total - RECENT_DEDUP_WINDOW_CAP until total).map { "msg$it" }
        assertEquals(expected, state.snapshotRecentDedup())
    }
}
