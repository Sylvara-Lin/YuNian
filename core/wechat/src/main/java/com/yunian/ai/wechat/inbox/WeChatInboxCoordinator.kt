package com.yunian.ai.wechat.inbox

import com.yunian.ai.database.dao.WeChatInboxDedupeDao
import com.yunian.ai.database.model.WeChatInboxDedupeEntity
import com.yunian.ai.domain.wechat.WeChatInboundMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class WeChatInboxCoordinator(
    private val dedupeDao: WeChatInboxDedupeDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val queues = ConcurrentHashMap<String, UserQueue>()
    private val mapMutex = Mutex()

    suspend fun acceptIfNew(
        inbound: WeChatInboundMessage,
        awaitHandler: Boolean = false,
        handler: suspend (WeChatInboundMessage) -> Unit,
    ): Boolean {
        return enqueue(inbound.fromUserId, inbound, awaitHandler, handler)
    }

    suspend fun tryMarkProcessed(inbound: WeChatInboundMessage): Boolean {
        val inserted = dedupeDao.insertIgnore(
            WeChatInboxDedupeEntity(
                dedupeKey = inbound.dedupeKey,
                messageId = inbound.messageId,
                fromUserId = inbound.fromUserId,
                processedAtMs = nowMs(),
            ),
        )
        return inserted != -1L
    }

    suspend fun purgeExpired(ttlMs: Long = DEFAULT_TTL_MS): Int {
        return dedupeDao.deleteOlderThan(nowMs() - ttlMs)
    }

    fun cancelAll() {
        queues.values.forEach { it.job.cancel() }
        queues.clear()
    }

    private suspend fun enqueue(
        userId: String,
        inbound: WeChatInboundMessage,
        awaitHandler: Boolean,
        handler: suspend (WeChatInboundMessage) -> Unit,
    ): Boolean {
        if (dedupeDao.findKey(inbound.dedupeKey) != null) {
            return false
        }
        val q = mapMutex.withLock {
            queues.getOrPut(userId) {
                UserQueue(scope, dedupeDao, nowMs).also { it.start() }
            }
        }
        return q.offer(WorkItem(inbound, handler), awaitHandler)
    }

    private class WorkItem(
        val inbound: WeChatInboundMessage,
        val handler: suspend (WeChatInboundMessage) -> Unit,
        val accepted: CompletableDeferred<Boolean> = CompletableDeferred(),
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class UserQueue(
        private val scope: CoroutineScope,
        private val dedupeDao: WeChatInboxDedupeDao,
        private val nowMs: () -> Long,
    ) {
        private val channel = Channel<WorkItem>(capacity = Channel.UNLIMITED)
        lateinit var job: Job
            private set

        fun start() {
            job = scope.launch {
                for (item in channel) {
                    val claimed = runCatching {
                        dedupeDao.insertIgnore(
                            WeChatInboxDedupeEntity(
                                dedupeKey = item.inbound.dedupeKey,
                                messageId = item.inbound.messageId,
                                fromUserId = item.inbound.fromUserId,
                                processedAtMs = nowMs(),
                            ),
                        ) != -1L
                    }.getOrDefault(false)

                    if (!claimed) {
                        item.accepted.complete(false)
                        item.finished.complete(Unit)
                        continue
                    }

                    item.accepted.complete(true)
                    try {
                        item.handler(item.inbound)
                        item.finished.complete(Unit)
                    } catch (error: Throwable) {

                        item.finished.completeExceptionally(error)
                    }
                }
            }
        }

        suspend fun offer(item: WorkItem, awaitHandler: Boolean): Boolean {
            channel.send(item)
            val accepted = item.accepted.await()
            if (awaitHandler && accepted) {
                item.finished.await()
            }
            return accepted
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
