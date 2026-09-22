package com.yunian.ai.database.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R1 加固（信号下沉到持久化侧）的行为级验证。
 *
 * 关注 [MessageWriteCoordinator.enqueueChat] / [MessageWriteCoordinator.submitChat] 的
 * `onPersisted` 回调语义：
 * - 置位必须发生在 `result.complete(id)` 之前（调用方 await 恢复时已是 true）；
 * - 置位由不可取消的写库侧消费协程执行，**与调用方协程是否被取消无关**（R1 本体）；
 * - 写库失败时不得置位（否则会误判"已落库"而丢消息）。
 */
@RunWith(AndroidJUnit4::class)
class MessageWriteCoordinatorPersistSignalTest {
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var groupRepository: GroupMessageRepository
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(
            database.messageDao(),
            database.conversationSummaryDao(),
            database
        )
        groupRepository = GroupMessageRepository(
            database.messageDao(),
            database.conversationSummaryDao(),
            database
        )
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
    }

    private fun reply(companionId: Long, content: String, ts: Long = System.currentTimeMillis()) =
        ChatMessage(companionId = companionId, content = content, isFromUser = false, timestamp = ts)

    /** P0-1：置位不晚于调用方 await 恢复（"先 onPersisted 再 complete" 的直接后果）。 */
    @Test
    fun onPersisted_is_invoked_before_awaiting_caller_resumes() = runBlocking {
        val persisted = AtomicBoolean(false)
        val coordinator = MessageWriteCoordinator(
            chatRepository, groupRepository, bgScope, batchWindowMs = 100L
        )

        val id = withTimeout(3_000L) {
            coordinator.enqueueChat(reply(31L, "reply"), onPersisted = { persisted.set(true) })
        }
        assertTrue(id > 0)
        // enqueueChat 已返回 ⇒ result.await() 已恢复 ⇒ 此刻置位必须已经发生。
        assertTrue(
            "onPersisted 必须在 complete 之前置位（调用方恢复时已为 true）",
            persisted.get()
        )
        coordinator.close()
    }

    /** P0-1（R1 本体）：置位由写库侧 W 执行，与调用方协程被取消无关。 */
    @Test
    fun onPersisted_fires_from_write_side_even_when_caller_cancelled() = runBlocking {
        val companionId = 32L
        val persisted = AtomicBoolean(false)
        // 较大批窗口：W 在窗口结束后才落库，给出一个确定的"取消窗口"。
        val coordinator = MessageWriteCoordinator(
            chatRepository, groupRepository, bgScope, batchWindowMs = 1_200L
        )

        val pending = coordinator.submitChat(reply(companionId, "reply"), onPersisted = { persisted.set(true) })
        // 调用方 J 在 await 期间被取消（远早于 1200ms 写库窗口结束）。
        val caller = launch { pending.await() }
        caller.cancelAndJoin()
        assertFalse("取消应发生在落库之前", persisted.get())

        // 尽管调用方已被取消，不可取消的写库侧仍应落库并置位。
        withTimeout(6_000L) {
            while (!persisted.get()) delay(20)
        }
        assertTrue("onPersisted 必须由不可取消的写库侧执行", persisted.get())
        assertTrue(chatRepository.getMessagesForCompanionSync(companionId).any { it.content == "reply" })
        coordinator.close()
    }

    /** P0-2：写库失败不得置位（否则会误判"已落库" → 丢消息）。 */
    @Test
    fun onPersisted_is_not_invoked_when_persist_fails() = runBlocking {
        val persisted = AtomicBoolean(false)
        val coordinator = MessageWriteCoordinator(
            chatRepository, groupRepository, bgScope, batchWindowMs = 100L
        )

        database.close() // 迫使写库抛异常
        var threw = false
        try {
            withTimeout(3_000L) {
                coordinator.enqueueChat(reply(33L, "reply"), onPersisted = { persisted.set(true) })
            }
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("写库失败必须向调用方抛出，而非静默", threw)
        assertFalse("写库失败时 onPersisted 不得置位", persisted.get())
        coordinator.close()
    }
}
