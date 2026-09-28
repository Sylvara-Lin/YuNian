package com.yunian.ai.database.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.GroupMessage
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationSummaryRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var groupRepository: GroupMessageRepository

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
        database.close()
    }

    @Test
    fun chat_batch_counts_each_incoming_message_and_repairs_latest_after_delete() = runBlocking {
        val companionId = 7L
        chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "sent", isFromUser = true, timestamp = 100)
        )
        chatRepository.batchInsertMessages(
            listOf(
                ChatMessage(companionId = companionId, content = "incoming-1", isFromUser = false, timestamp = 200),
                ChatMessage(companionId = companionId, content = "sent-2", isFromUser = true, timestamp = 300),
                ChatMessage(companionId = companionId, content = "incoming-2", isFromUser = false, timestamp = 400)
            )
        )

        val summary = database.conversationSummaryDao().getSummarySync(companionId, "chat")
        assertNotNull(summary)
        assertEquals(2, summary!!.unreadCount)
        assertEquals("incoming-2", summary.lastMessagePreview)
        assertEquals(400, summary.lastMessageTimestamp)

        val latest = chatRepository.getMessagesForCompanionSync(companionId).maxBy { it.timestamp }
        chatRepository.deleteMessage(latest)

        val repaired = database.conversationSummaryDao().getSummarySync(companionId, "chat")
        assertNotNull(repaired)
        assertEquals("sent-2", repaired!!.lastMessagePreview)
        assertEquals(300, repaired.lastMessageTimestamp)
        assertEquals(1, repaired.unreadCount)
    }

    @Test
    fun group_batch_counts_members_and_mark_read_advances_cursor() = runBlocking {
        val groupId = 9L
        groupRepository.batchInsertMessages(
            listOf(
                GroupMessage(groupId = groupId, companionId = 11, content = "member-1", timestamp = 100),
                GroupMessage(groupId = groupId, companionId = -1, content = "mine", timestamp = 200),
                GroupMessage(groupId = groupId, companionId = 12, content = "member-2", timestamp = 300)
            )
        )

        val unread = database.conversationSummaryDao().getSummarySync(groupId, "group")
        assertNotNull(unread)
        assertEquals(2, unread!!.unreadCount)
        assertEquals("member-2", unread.lastMessagePreview)

        groupRepository.markReadThroughLatest(groupId)

        val read = database.conversationSummaryDao().getSummarySync(groupId, "group")
        assertNotNull(read)
        assertEquals(0, read!!.unreadCount)
        assertEquals(read.lastMessageId, read.readThroughMessageId)
    }

    @Test
    fun coordinator_flushes_interactive_chat_message_without_waiting_for_batch_window() = runBlocking {
        val companionId = 13L
        coroutineScope {
            val coordinator = MessageWriteCoordinator(
                chatRepository,
                groupRepository,
                this,
                batchWindowMs = 5_000L
            )
            val id = withTimeout(1_000L) {
                coordinator.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = "incoming",
                        isFromUser = false,
                        timestamp = 1L
                    )
                )
            }
            coordinator.close()

            assertTrue(id > 0)
        }

        val summary = database.conversationSummaryDao().getSummarySync(companionId, "chat")
        assertNotNull(summary)
        assertEquals(1, summary!!.unreadCount)
        assertEquals("incoming", summary.lastMessagePreview)
        assertEquals(1, chatRepository.getMessagesForCompanionSync(companionId).size)
    }

    @Test
    fun coordinator_batches_sequential_submissions_before_awaiting() = runBlocking {
        val companionId = 14L
        val coordinator = MessageWriteCoordinator(chatRepository, groupRepository, this)
        val pendingIds = (1..5).map { index ->
            coordinator.submitChat(
                ChatMessage(
                    companionId = companionId,
                    content = "sequential-$index",
                    isFromUser = false,
                    timestamp = index.toLong()
                )
            )
        }

        val ids = pendingIds.awaitAll()
        coordinator.close()

        assertEquals(5, ids.distinct().size)
        val summary = database.conversationSummaryDao().getSummarySync(companionId, "chat")
        assertNotNull(summary)
        assertEquals(5, summary!!.unreadCount)
        assertEquals("sequential-5", summary.lastMessagePreview)
    }

    @Test
    fun coordinator_batch_api_persists_messages_and_summary_atomically() = runBlocking {
        val companionId = 15L
        val coordinator = MessageWriteCoordinator(chatRepository, groupRepository, this)

        val ids = coordinator.enqueueChats(
            (1..5).map { index ->
                ChatMessage(
                    companionId = companionId,
                    content = "batch-$index",
                    isFromUser = false,
                    timestamp = index.toLong()
                )
            }
        )
        coordinator.close()

        assertEquals(5, ids.distinct().size)
        val summary = database.conversationSummaryDao().getSummarySync(companionId, "chat")
        assertNotNull(summary)
        assertEquals(5, summary!!.unreadCount)
        assertEquals("batch-5", summary.lastMessagePreview)
    }

    @Test
    fun media_pagination_uses_id_to_continue_with_same_timestamp() = runBlocking {
        val companionId = 17L
        val ids = (1..3).map { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "image-$index",
                    isFromUser = true,
                    timestamp = 500,
                    fileFormat = FileFormat.IMAGE
                )
            )
        }

        val firstPage = chatRepository.getMessagesByFileFormat(companionId, FileFormat.IMAGE, limit = 2)
        val secondPage = chatRepository.getMessagesByFileFormatBefore(
            companionId,
            FileFormat.IMAGE,
            beforeTimestamp = firstPage.last().timestamp,
            beforeId = firstPage.last().id,
            limit = 2
        )

        assertEquals(ids.takeLast(2).reversed(), firstPage.map { it.id })
        assertEquals(listOf(ids.first()), secondPage.map { it.id })
    }

    @Test
    fun timeline_loads_metadata_then_bodies_and_pages_in_both_directions() = runBlocking {
        val companionId = 20L
        val ids = (1..4).map { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "body-$index",
                    isFromUser = true,
                    timestamp = 700
                )
            )
        }

        val metadata = database.messageDao().getRecentMessageMetadataSync(companionId, "chat", 4)
        val bodies = database.messageDao().getMessageBodies(metadata.map { it.id })
        assertEquals(ids.reversed(), metadata.map { it.id })
        assertEquals(ids.toSet(), bodies.map { it.messageId }.toSet())

        val before = chatRepository.getMessagesBeforeSync(companionId, 700, ids[2], limit = 2)
        val after = chatRepository.getMessagesAfterSync(companionId, 700, ids[1], limit = 2)
        assertEquals(listOf(ids[1], ids[0]), before.map { it.id })
        assertEquals(listOf(ids[2], ids[3]), after.map { it.id })
        assertEquals(listOf("body-3", "body-4"), after.map { it.content })
    }

    @Test
    fun on_demand_body_load_only_returns_requested_metadata() = runBlocking {
        val companionId = 21L
        (1..4).forEach { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "visible-$index",
                    isFromUser = true,
                    timestamp = index.toLong()
                )
            )
        }

        val metadata = chatRepository.getRecentMetadata(companionId, 4)
        val requested = listOf(metadata.first(), metadata.last())
        val loaded = chatRepository.loadMessages(requested)

        assertEquals(requested.map { it.id }.toSet(), loaded.keys)
        assertEquals(setOf("visible-1", "visible-4"), loaded.values.map { it.content }.toSet())
    }

    @Test
    fun archive_preserves_encrypted_bodies_and_transparently_pages_then_restores() = runBlocking {
        val companionId = 22L
        val ids = (1..6).map { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "archive-$index",
                    isFromUser = index % 2 == 0,
                    timestamp = 900
                )
            )
        }
        val encryptedBefore = database.messageDao().getMessageBodies(ids).associateBy { it.messageId }

        assertEquals(4, chatRepository.archiveOldMessages(companionId, retainCount = 2))
        assertEquals(2, database.messageDao().getMessageCount(companionId, "chat"))
        assertEquals(4, database.messageDao().getArchivedMessageCount(companionId, "chat"))

        val page = chatRepository.getMetadataBefore(companionId, 900, ids.last(), limit = 4)
        assertEquals(listOf(ids[4], ids[3], ids[2], ids[1]), page.map { it.id })
        val loaded = chatRepository.loadMessages(listOf(page.first(), page.last()))
        assertEquals(setOf("archive-2", "archive-5"), loaded.values.map { it.content }.toSet())
        val encryptedArchived = database.messageDao()
            .getArchivedMessageBodies(ids.take(4))
            .associateBy { it.messageId }
        ids.take(4).forEach { id ->
            assertEquals(encryptedBefore.getValue(id).content, encryptedArchived.getValue(id).content)
        }
        assertEquals(ids, chatRepository.getMessagesForCompanionSync(companionId).map { it.id })

        assertEquals(4, chatRepository.restoreArchivedMessages(companionId))
        assertEquals(0, database.messageDao().getArchivedMessageCount(companionId, "chat"))
        assertEquals(ids, chatRepository.getMessagesForCompanionSync(companionId).map { it.id })
    }

    @Test
    fun archive_cursor_respects_same_timestamp_boundary_and_retain_zero() = runBlocking {
        val companionId = 23L
        val ids = (1..5).map { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "boundary-$index",
                    isFromUser = true,
                    timestamp = 1_000
                )
            )
        }

        assertEquals(2, chatRepository.archiveOldMessages(companionId, retainCount = 3))
        assertEquals(ids.takeLast(3), database.messageDao().getRecentMessageMetadataSync(companionId, "chat", 5).map { it.id }.reversed())
        assertEquals(ids.take(2), database.messageDao().getAllArchivedMessageMetadata(companionId, "chat").map { it.id })

        assertEquals(3, chatRepository.archiveOldMessages(companionId, retainCount = 0))
        assertEquals(0, database.messageDao().getMessageCount(companionId, "chat"))
        assertEquals(ids, chatRepository.getMessagesForCompanionSync(companionId).map { it.id })
        assertEquals(0, chatRepository.archiveOldMessages(companionId, retainCount = 3))
    }

    @Test
    fun archived_messages_remain_searchable_and_visible_in_media_pages() = runBlocking {
        val companionId = 24L
        val ids = (1..4).map { index ->
            chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = "cold-match-$index",
                    isFromUser = true,
                    timestamp = 2_000,
                    fileFormat = FileFormat.IMAGE
                )
            )
        }

        assertEquals(3, chatRepository.archiveOldMessages(companionId, retainCount = 1))

        val searchResults = chatRepository.searchMessages(companionId, "cold-match", limit = 10)
        val firstMediaPage = chatRepository.getMessagesByFileFormat(companionId, FileFormat.IMAGE, limit = 2)
        val secondMediaPage = chatRepository.getMessagesByFileFormatBefore(
            companionId,
            FileFormat.IMAGE,
            beforeTimestamp = firstMediaPage.last().timestamp,
            beforeId = firstMediaPage.last().id,
            limit = 2
        )

        assertEquals(ids.reversed(), searchResults.map { it.id })
        assertEquals(ids.takeLast(2).reversed(), firstMediaPage.map { it.id })
        assertEquals(ids.take(2).reversed(), secondMediaPage.map { it.id })
    }

    @Test
    fun timeline_cursor_query_uses_conversation_index() {
        val plan = database.openHelper.readableDatabase.query(
            """EXPLAIN QUERY PLAN
               SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat
               FROM messages
               WHERE conversationType = 'chat' AND conversationId = 20
                 AND (timestamp < 700 OR (timestamp = 700 AND id < 4))
               ORDER BY timestamp DESC, id DESC LIMIT 50"""
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
            }
        }

        assertTrue(plan.joinToString().contains("idx_messages_conv"))
    }

    @Test
    fun read_cursor_uses_timestamp_before_id_for_out_of_order_messages() = runBlocking {
        val companionId = 18L
        val latestId = chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "latest", isFromUser = false, timestamp = 200)
        )
        chatRepository.markReadThroughLatest(companionId)

        val olderId = chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "older", isFromUser = false, timestamp = 100)
        )

        val afterOutOfOrderInsert = database.conversationSummaryDao().getSummarySync(companionId, "chat")!!
        assertEquals(0, afterOutOfOrderInsert.unreadCount)
        assertEquals(latestId, afterOutOfOrderInsert.lastMessageId)
        assertEquals(200L, afterOutOfOrderInsert.lastMessageTimestamp)
        assertEquals(latestId, afterOutOfOrderInsert.readThroughMessageId)
        assertEquals(200L, afterOutOfOrderInsert.readThroughMessageTimestamp)

        chatRepository.deleteMessage(chatRepository.getMessageById(latestId)!!)

        val afterRebuild = database.conversationSummaryDao().getSummarySync(companionId, "chat")!!
        assertEquals(0, afterRebuild.unreadCount)
        assertEquals(olderId, afterRebuild.lastMessageId)
        assertEquals(100L, afterRebuild.lastMessageTimestamp)
    }

    @Test
    fun clear_chat_history_removes_messages_summary_and_cache() = runBlocking {
        val companionId = 19L
        chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "to-clear", isFromUser = false)
        )

        chatRepository.clearChatHistory(companionId)

        assertEquals(emptyList<ChatMessage>(), chatRepository.getMessagesForCompanionSync(companionId))
        assertEquals(null, database.conversationSummaryDao().getSummarySync(companionId, "chat"))
        assertEquals(null, chatRepository.getCachedRecent(companionId))
    }

    @Test
    fun message_search_supports_unicode_substrings_and_tracks_lifecycle() = runBlocking {
        val companionId = 21L
        val chineseId = chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "今天去图书馆看书", isFromUser = true)
        )
        val englishId = chatRepository.sendMessage(
            ChatMessage(companionId = companionId, content = "Alpha-Beta release", isFromUser = false)
        )

        assertEquals(listOf(chineseId), chatRepository.searchMessages(companionId, "书馆").map { it.id })
        assertEquals(listOf(englishId), chatRepository.searchMessages(companionId, "ha-Be").map { it.id })
        assertEquals(listOf(englishId), chatRepository.searchMessages(companionId, "-").map { it.id })

        chatRepository.updateMessageContent(chineseId, "已经修改为新内容")
        assertTrue(chatRepository.searchMessages(companionId, "图书馆").isEmpty())
        assertEquals(listOf(chineseId), chatRepository.searchMessages(companionId, "新内容").map { it.id })

        assertEquals(1, chatRepository.archiveOldMessages(companionId, retainCount = 1))
        assertEquals(listOf(chineseId), chatRepository.searchMessages(companionId, "新内容").map { it.id })
        assertEquals(1, chatRepository.restoreArchivedMessages(companionId))
        assertEquals(listOf(chineseId), chatRepository.searchMessages(companionId, "新内容").map { it.id })

        chatRepository.deleteMessage(chatRepository.getMessageById(chineseId)!!)
        assertTrue(chatRepository.searchMessages(companionId, "新内容").isEmpty())
    }
}
