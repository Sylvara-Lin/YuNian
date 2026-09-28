package com.yunian.ai.database.repository

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.GroupMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MessageWriteCoordinator(
    private val chatRepository: ChatRepository,
    private val groupMessageRepository: GroupMessageRepository,
    scope: CoroutineScope,
    private val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS
) {
    private val chatQueue = Channel<PendingChatWrite>(QUEUE_CAPACITY)
    private val groupQueue = Channel<PendingGroupWrite>(QUEUE_CAPACITY)

    init {
        scope.launch { consumeChatWrites() }
        scope.launch { consumeGroupWrites() }
    }

    /**
     * 入队一条聊天消息并等待其写入完成。
     *
     * [onPersisted] 会在**该条消息确实写入数据库之后**、由不可取消的消费协程（构造时传入的
     * `scope`）在持久化成功分支回调一次；它与调用方协程是否被取消**无关**——即便调用方在
     * `await()` 期间被取消，只要消息进了队列、行落库了，回调仍会触发。写库失败时不会回调。
     */
    suspend fun enqueueChat(message: ChatMessage, onPersisted: (() -> Unit)? = null): Long {
        val result = CompletableDeferred<Long>()
        chatQueue.send(PendingChatWrite(message, result, flushImmediately = true, onPersisted = onPersisted))
        return result.await()
    }

    suspend fun submitChat(message: ChatMessage, onPersisted: (() -> Unit)? = null): Deferred<Long> {
        val result = CompletableDeferred<Long>()
        chatQueue.send(PendingChatWrite(message, result, flushImmediately = false, onPersisted = onPersisted))
        return result
    }

    suspend fun enqueueChats(messages: List<ChatMessage>): List<Long> =
        messages.map { submitChat(it) }.awaitAll()

    suspend fun enqueueGroup(message: GroupMessage): Long {
        val result = CompletableDeferred<Long>()
        groupQueue.send(PendingGroupWrite(message, result, flushImmediately = true))
        return result.await()
    }

    suspend fun submitGroup(message: GroupMessage): Deferred<Long> {
        val result = CompletableDeferred<Long>()
        groupQueue.send(PendingGroupWrite(message, result, flushImmediately = false))
        return result
    }

    suspend fun enqueueGroups(messages: List<GroupMessage>): List<Long> =
        messages.map { submitGroup(it) }.awaitAll()

    fun close() {
        chatQueue.close()
        groupQueue.close()
    }

    private suspend fun consumeChatWrites() {
        consumeBatches(chatQueue) { batch ->
            chatRepository.batchInsertMessages(batch.map { it.message })
        }
    }

    private suspend fun consumeGroupWrites() {
        consumeBatches(groupQueue) { batch ->
            groupMessageRepository.batchInsertMessages(batch.map { it.message })
        }
    }

    private suspend fun <T : PendingWrite> consumeBatches(
        queue: Channel<T>,
        persist: suspend (List<T>) -> List<Long>
    ) {
        for (first in queue) {
            val batch = mutableListOf(first)
            if (first.flushImmediately) {
                persistBatch(batch, persist)
                continue
            }
            val deadline = System.currentTimeMillis() + batchWindowMs
            while (batch.size < BATCH_SIZE) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining) { queue.receiveCatching().getOrNull() } ?: break
                batch += next
                if (next.flushImmediately) break
            }
            persistBatch(batch, persist)
        }
    }

    private suspend fun <T : PendingWrite> persistBatch(
        batch: List<T>,
        persist: suspend (List<T>) -> List<Long>
    ) {
        runCatching { persist(batch) }
            .onSuccess { ids ->
                check(ids.size == batch.size) { "Persisted ID count does not match message count" }
                batch.zip(ids).forEach { (pending, id) ->
                    // 先置"已落库"信号再 complete：保证任何因 complete 被唤醒的等待方
                    // 观察到的信号一定不晚于其自身完成，杜绝"行已入库但信号未置位"的竞态。
                    // 此回调运行在不可取消的消费协程上，与调用方是否被取消无关。
                    pending.onPersisted?.invoke()
                    pending.result.complete(id)
                }
            }
            .onFailure { error -> batch.forEach { it.result.completeExceptionally(error) } }
    }

    private interface PendingWrite {
        val result: CompletableDeferred<Long>
        val flushImmediately: Boolean
        val onPersisted: (() -> Unit)?
    }

    private data class PendingChatWrite(
        val message: ChatMessage,
        override val result: CompletableDeferred<Long>,
        override val flushImmediately: Boolean,
        override val onPersisted: (() -> Unit)? = null
    ) : PendingWrite

    private data class PendingGroupWrite(
        val message: GroupMessage,
        override val result: CompletableDeferred<Long>,
        override val flushImmediately: Boolean,
        override val onPersisted: (() -> Unit)? = null
    ) : PendingWrite

    private companion object {
        const val BATCH_SIZE = 5
        const val DEFAULT_BATCH_WINDOW_MS = 200L
        const val QUEUE_CAPACITY = 100
    }
}
