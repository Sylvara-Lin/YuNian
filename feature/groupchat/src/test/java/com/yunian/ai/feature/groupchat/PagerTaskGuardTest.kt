package com.yunian.ai.feature.groupchat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PagerTaskGuard] 的守卫语义测试：后台分页/订阅任务抛出的非取消异常必须被吞并上报，
 * 而不是冒泡到无 [kotlinx.coroutines.CoroutineExceptionHandler] 的 viewModelScope/applicationScope
 * （否则会沿线程默认未捕获处理器 → 直接闪退）。
 */
class PagerTaskGuardTest {

    @Test
    fun `swallows runtime exception and reports it`() = runBlocking {
        var captured: Exception? = null
        PagerTaskGuard.run({ e -> captured = e }) { throw IllegalStateException("db connection closed") }
        assertEquals("db connection closed", captured?.message)
    }

    @Test
    fun `runs block normally when no exception`() = runBlocking {
        var ran = false
        PagerTaskGuard.run({ }) { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `rethrows cancellation so scopes can still cancel`() = runBlocking {
        var rethrown = false
        try {
            PagerTaskGuard.run({ }) { throw CancellationException("cancel") }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate", rethrown)
    }
}
