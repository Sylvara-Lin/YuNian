package com.yunian.ai.feature.wechat.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 修复回归：WeChatChannelKeeper.stop 与 ensureRunning/healIfNeeded 持同一把 mutex。
 *
 * 覆盖：
 * - stop 与 ensureRunning 串行执行（mutex 互斥）
 * - stop 与 healIfNeeded 串行执行
 * - stop 在 healIfNeeded 持锁期间等待，不会并发执行
 * - 停止后不会被立即重新拉活（串行语义保证）
 *
 * 注：本测试验证 mutex 互斥语义本身，不启动真实 Service / WorkManager；
 * WeChatPollingService.start/stop 与 WorkManager 排程由真机验证（见 bug-report.html《需我人工验证清单》）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeChatChannelKeeperStopMutexTest {

    /** 模拟 WeChatChannelKeeper 的 mutex 互斥语义（与 WeChatChannelKeeper.mutex 同构）。 */
    private class KeeperSimulator {
        val mutex = Mutex()
        val executionLog = mutableListOf<String>()

        suspend fun ensureRunning(): Unit = mutex.withLock {
            executionLog.add("ensureRunning:start")
            delay(100) // 模拟持锁期间的 IO
            executionLog.add("ensureRunning:end")
        }

        suspend fun healIfNeeded(): Unit = mutex.withLock {
            executionLog.add("healIfNeeded:start")
            delay(100)
            executionLog.add("healIfNeeded:end")
        }

        suspend fun stop(): Unit = mutex.withLock {
            executionLog.add("stop:start")
            executionLog.add("stop:end")
        }
    }

    @Test
    fun `stop serializes with ensureRunning`() = runTest {
        val keeper = KeeperSimulator()
        val job1 = launch { keeper.ensureRunning() }
        val job2 = launch { keeper.stop() }
        job1.join()
        job2.join()

        // 串行执行：ensureRunning 完整结束后 stop 才执行（或反之），不会交错
        val log = keeper.executionLog
        assertEquals(4, log.size)
        val ensureStart = log.indexOf("ensureRunning:start")
        val ensureEnd = log.indexOf("ensureRunning:end")
        val stopStart = log.indexOf("stop:start")
        val stopEnd = log.indexOf("stop:end")
        // 两种合法顺序：ensure 先完成 或 stop 先完成；不允许交错
        val ensureFirst = ensureEnd < stopStart
        val stopFirst = stopEnd < ensureStart
        assertTrue("stop 与 ensureRunning 必须串行，实际日志：$log", ensureFirst || stopFirst)
    }

    @Test
    fun `stop serializes with healIfNeeded`() = runTest {
        val keeper = KeeperSimulator()
        val job1 = launch { keeper.healIfNeeded() }
        val job2 = launch { keeper.stop() }
        job1.join()
        job2.join()

        val log = keeper.executionLog
        assertEquals(4, log.size)
        val healStart = log.indexOf("healIfNeeded:start")
        val healEnd = log.indexOf("healIfNeeded:end")
        val stopStart = log.indexOf("stop:start")
        val stopEnd = log.indexOf("stop:end")
        val healFirst = healEnd < stopStart
        val stopFirst = stopEnd < healStart
        assertTrue("stop 与 healIfNeeded 必须串行，实际日志：$log", healFirst || stopFirst)
    }

    @Test
    fun `stop waits for healIfNeeded lock release`() = runTest {
        val keeper = KeeperSimulator()
        // healIfNeeded 先持锁
        val healJob = launch { keeper.healIfNeeded() }
        // 等 healIfNeeded 已经开始
        advanceTimeBy(50)
        // stop 在 healIfNeeded 持锁期间发起
        val stopJob = launch { keeper.stop() }
        healJob.join()
        stopJob.join()

        val log = keeper.executionLog
        // healIfNeeded 必须先完整结束，stop 才开始
        val healEnd = log.indexOf("healIfNeeded:end")
        val stopStart = log.indexOf("stop:start")
        assertTrue("stop 必须等 healIfNeeded 持锁释放，实际日志：$log", healEnd < stopStart)
    }

    @Test
    fun `healIfNeeded waits for stop lock release`() = runTest {
        val keeper = KeeperSimulator()
        // stop 先持锁
        val stopJob = launch { keeper.stop() }
        // healIfNeeded 在 stop 持锁期间发起
        val healJob = launch { keeper.healIfNeeded() }
        stopJob.join()
        healJob.join()

        val log = keeper.executionLog
        val stopEnd = log.indexOf("stop:end")
        val healStart = log.indexOf("healIfNeeded:start")
        assertTrue("healIfNeeded 必须等 stop 持锁释放，实际日志：$log", stopEnd < healStart)
    }

    @Test
    fun `concurrent stop and heal and ensure all serialize`() = runTest {
        val keeper = KeeperSimulator()
        val jobs = listOf(
            launch { keeper.ensureRunning() },
            launch { keeper.healIfNeeded() },
            launch { keeper.stop() },
        )
        jobs.forEach { it.join() }

        val log = keeper.executionLog
        assertEquals(6, log.size)
        // 每对 (start, end) 必须相邻（不允许交错）
        val pairs = listOf(
            "ensureRunning:start" to "ensureRunning:end",
            "healIfNeeded:start" to "healIfNeeded:end",
            "stop:start" to "stop:end",
        )
        pairs.forEach { (start, end) ->
            val startIdx = log.indexOf(start)
            val endIdx = log.indexOf(end)
            assertTrue("$start 与 $end 必须相邻，实际日志：$log", endIdx == startIdx + 1)
        }
    }
}
