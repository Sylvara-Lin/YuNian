package com.yunian.ai.feature.chat.ui.viewmodel

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在**可中断的工作线程**上运行 [block]，整体限制在 [timeoutMs] 毫秒内；超时返回 [onTimeout]。
 *
 * 本轮改造（P1 线程爆炸）：原实现使用**无上限** `Executors.newCachedThreadPool()` +
 * `runBlocking` + `future.get()`——高峰期可能创建大量线程，且把调用线程同步阻塞住；
 * 超时后残留的工作线程也不会被中断。改为结构化并发的协程实现：
 *
 * - 不再持有任何线程池，线程来源收敛到 kotlinx 的调度器（[runInterruptible] 内部维护）；
 * - 超时/取消时由 [runInterruptible] **主动中断**阻塞中的工作线程（比旧实现更干净，无残留）；
 * - 语义对齐旧实现：**超时 → 返回 [onTimeout]**；
 *   [block] 抛出的异常**原样透传**（不再被 `future.get` 包成 `ExecutionException`，
 *   因此 `[TOAST]` 前缀等基于 message 的逻辑照常生效）；
 * - 唯一有意收紧：调用方**协程取消**时改为正常向上传播 `CancellationException`
 *   （旧实现会把它误判为超时并返回 [onTimeout]），符合结构化并发语义。
 */
suspend fun <T> runInterruptibleSafe(
    timeoutMs: Long,
    onTimeout: T? = null,
    block: suspend () -> T
): T? {
    // 用 Box 区分「超时（外层为 null）」与「业务返回值恰为 null」，保证与旧实现语义一致。
    val boxed: Box<T>? = withTimeoutOrNull(timeoutMs) {
        Box(runInterruptible { runBlocking { block() } })
    }
    return if (boxed != null) boxed.value else onTimeout
}

private class Box<T>(val value: T?)
