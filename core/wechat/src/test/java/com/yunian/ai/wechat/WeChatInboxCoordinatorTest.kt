package com.yunian.ai.wechat

import com.yunian.ai.database.dao.WeChatInboxDedupeDao
import com.yunian.ai.database.model.WeChatInboxDedupeEntity
import com.yunian.ai.wechat.inbox.WeChatInboxCoordinator
import com.yunian.ai.wechat.map.WeChatInboundMapper
import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireTextItem
import com.yunian.ai.wechat.wire.WireWeChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatInboxCoordinatorTest {

    @Test
    fun failedHandler_isClaimedSoPollCannotDoubleProcess() = runBlocking {

        val dao = FakeInboxDedupeDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = WeChatInboxCoordinator(dao, scope, nowMs = { 123L })
        val inbound = textInbound()
        var attempts = 0

        val accepted = runCatching {
            coordinator.acceptIfNew(inbound, awaitHandler = true) {
                attempts += 1
                error("transient failure")
            }
        }

        assertTrue(accepted.isFailure)
        assertEquals(1, attempts)
        assertEquals(inbound.dedupeKey, dao.findKey(inbound.dedupeKey))
        assertFalse(coordinator.acceptIfNew(inbound, awaitHandler = true) { attempts += 1 })
        assertEquals(1, attempts)
        scope.cancel()
    }

    @Test
    fun successfulHandler_isDedupedOnNextDelivery() = runBlocking {
        val dao = FakeInboxDedupeDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = WeChatInboxCoordinator(dao, scope)
        val inbound = textInbound()
        var handled = 0

        assertTrue(coordinator.acceptIfNew(inbound, awaitHandler = true) { handled += 1 })
        assertFalse(coordinator.acceptIfNew(inbound, awaitHandler = true) { handled += 1 })
        assertEquals(1, handled)
        scope.cancel()
    }

    @Test
    fun acceptIfNew_defaultDoesNotWaitForSlowHandler() = runBlocking {
        val dao = FakeInboxDedupeDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = WeChatInboxCoordinator(dao, scope)
        val inbound = textInbound()
        val handlerStarted = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val accepted = withTimeout(1_000) {
            coordinator.acceptIfNew(inbound) {
                handlerStarted.complete(Unit)
                allowFinish.await()
            }
        }

        assertTrue(accepted)
        assertNotNull(dao.findKey(inbound.dedupeKey))
        withTimeout(1_000) { handlerStarted.await() }

        assertFalse(coordinator.acceptIfNew(inbound) { error("should not run") })
        allowFinish.complete(Unit)
        delay(50)
        scope.cancel()
    }

    private fun textInbound() = WeChatInboundMapper.toDomain(
        WireWeChatMessage(
            messageId = 42L,
            fromUserId = "wx-user",
            itemList = listOf(
                WireMessageItem(type = 1, textItem = WireTextItem(text = "hello")),
            ),
        ),
    )!!

    private class FakeInboxDedupeDao : WeChatInboxDedupeDao {
        private val entities = linkedMapOf<String, WeChatInboxDedupeEntity>()

        override suspend fun findKey(key: String): String? = entities[key]?.dedupeKey

        override suspend fun insertIgnore(entity: WeChatInboxDedupeEntity): Long {
            if (entities.containsKey(entity.dedupeKey)) return -1L
            entities[entity.dedupeKey] = entity
            return entities.size.toLong()
        }

        override suspend fun deleteOlderThan(cutoffMs: Long): Int {
            val expired = entities.values.filter { it.processedAtMs < cutoffMs }
            expired.forEach { entities.remove(it.dedupeKey) }
            return expired.size
        }

        override suspend fun count(): Int = entities.size
    }
}
