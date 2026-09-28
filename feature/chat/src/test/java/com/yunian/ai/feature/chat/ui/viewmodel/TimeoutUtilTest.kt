package com.yunian.ai.feature.chat.ui.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QA 独立回归：验证 TimeoutUtil 重写后的 5 项语义等价性 + 无线程泄漏。
 * 逐条对应验证要求 9 的 (a)~(e) 与「超时后工作线程被中断」。
 * 该文件仅依赖 kotlinx.coroutines，可在 JVM 直接实测。
 */
class TimeoutUtilTest {

    // (a) 正常返回：在超时前完成 → 返回业务值
    @Test
    fun `a 正常返回业务值`() = runBlocking {
        val result = runInterruptibleSafe(timeoutMs = 5_000L) { "OK" }
        assertEquals("OK", result)
    }

    // (b) 超时：超过 timeoutMs → 返回 onTimeout
    @Test
    fun `b 超时返回 onTimeout`() = runBlocking {
        val result = runInterruptibleSafe(timeoutMs = 100L, onTimeout = "TIMEOUT") {
            Thread.sleep(3_000L) // 阻塞式（可中断）工作，模拟旧 future.get 场景
            "never"
        }
        assertEquals("TIMEOUT", result)
    }

    // (c) 被调方抛异常 → 原样透传（不包 ExecutionException）
    @Test
    fun `c 异常原样透传`() = runBlocking {
        try {
            runInterruptibleSafe<String>(timeoutMs = 5_000L) {
                throw IllegalStateException("[TOAST] boom")
            }
            fail("应当抛出 IllegalStateException")
        } catch (e: IllegalStateException) {
            // 关键：异常类型与 message 均原样保留（旧实现 future.get 会包 ExecutionException）
            assertEquals("[TOAST] boom", e.message)
        }
    }

    // (d) 返回 null 与超时可区分：Box 包装使「业务返回 null」与「超时」可辨
    @Test
    fun `d 业务返回 null 与超时可区分`() = runBlocking {
        // 超时 → onTimeout（哨兵）
        val timedOut = runInterruptibleSafe(timeoutMs = 100L, onTimeout = "TIMEOUT") {
            Thread.sleep(2_000L); "x"
        }
        // 业务在超时内返回 null → 应为 null（而非哨兵）
        val returnedNull = runInterruptibleSafe<String?>(timeoutMs = 5_000L, onTimeout = "TIMEOUT") {
            null
        }
        assertEquals("TIMEOUT", timedOut)
        assertNull(returnedNull)
    }

    // (e) 调用方协程取消 → 传播 CancellationException（不误判为超时）
    @Test
    fun `e 调用方取消传播 CancellationException`() = runBlocking {
        val outer = async(Dispatchers.Default) {
            runInterruptibleSafe(timeoutMs = 10_000L, onTimeout = "TIMEOUT") {
                delay(100_000L) // 挂起点，等待被取消
                "never"
            }
        }
        // 等它真正进入运行后取消
        delay(300L)
        outer.cancel()
        try {
            outer.await()
            fail("取消后 await 应抛出 CancellationException")
        } catch (e: CancellationException) {
            assertNotNull(e) // 传播的是取消，而非返回 onTimeout
        }
    }

    // 超时后工作线程确实被中断/回收（无残留线程）
    @Test
    fun `超时后工作线程被中断`() = runBlocking {
        val interrupted = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val result = runInterruptibleSafe(timeoutMs = 200L, onTimeout = "TIMEOUT") {
            try {
                Thread.sleep(10_000L)
                "never"
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            } finally {
                finished.countDown()
            }
        }
        assertEquals("TIMEOUT", result)
        // 给中断信号最多 3s 落地
        assertTrue("超时后应中断阻塞中的工作线程", finished.await(3, TimeUnit.SECONDS))
        assertTrue("工作线程应收到中断信号", interrupted.get())
    }

    // 若在该 block 内再切入其它 dispatcher 不会改变可中断语义的冒烟测试
    @Test
    fun `withContext 内正常完成`() = runBlocking {
        val r = runInterruptibleSafe(timeoutMs = 5_000L) {
            withContext(Dispatchers.Default) { "done" }
        }
        assertEquals("done", r)
    }
}
