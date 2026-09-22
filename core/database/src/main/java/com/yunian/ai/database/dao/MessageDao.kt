package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MessageBody
import com.yunian.ai.database.model.MessageSearchIndex
import com.yunian.ai.database.model.StoredMessage
import com.yunian.ai.database.repository.MessageSearchTokenizer
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

        @Query(
                """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages
                     WHERE conversationId = :conversationId AND conversationType = :type
                         AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
                     ORDER BY timestamp DESC, id DESC LIMIT :limit"""
        )
        fun getMessageMetadataBefore(
                conversationId: Long,
                type: String,
                beforeTimestamp: Long,
                beforeId: Long,
                limit: Int
        ): Flow<List<Message>>

        @Query(
                """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages
                     WHERE conversationId = :conversationId AND conversationType = :type
                         AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
                     ORDER BY timestamp DESC, id DESC LIMIT :limit"""
        )
        suspend fun getMessageMetadataBeforeSync(
                conversationId: Long,
                type: String,
                beforeTimestamp: Long,
                beforeId: Long,
                limit: Int
        ): List<Message>

        @Query(
                """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages
                     WHERE conversationId = :conversationId AND conversationType = :type
                         AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId))
                     ORDER BY timestamp ASC, id ASC LIMIT :limit"""
        )
        fun getMessageMetadataAfter(
                conversationId: Long,
                type: String,
                afterTimestamp: Long,
                afterId: Long,
                limit: Int
        ): Flow<List<Message>>

        @Query(
                """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages
                     WHERE conversationId = :conversationId AND conversationType = :type
                         AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId))
                     ORDER BY timestamp ASC, id ASC LIMIT :limit"""
        )
        suspend fun getMessageMetadataAfterSync(
                conversationId: Long,
                type: String,
                afterTimestamp: Long,
                afterId: Long,
                limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
        fun getRecentMessageMetadata(conversationId: Long, type: String, limit: Int): Flow<List<Message>>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
        suspend fun getRecentMessageMetadataSync(conversationId: Long, type: String, limit: Int): List<Message>

        @Query("SELECT * FROM message_bodies WHERE messageId IN (:messageIds)")
        suspend fun getMessageBodies(messageIds: List<Long>): List<MessageBody>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
        suspend fun getArchivedMessageMetadataBefore(
            conversationId: Long,
            type: String,
            beforeTimestamp: Long,
            beforeId: Long,
            limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type AND (timestamp > :afterTimestamp OR (timestamp = :afterTimestamp AND id > :afterId)) ORDER BY timestamp ASC, id ASC LIMIT :limit")
        suspend fun getArchivedMessageMetadataAfter(
            conversationId: Long,
            type: String,
            afterTimestamp: Long,
            afterId: Long,
            limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE id = :messageId")
        suspend fun getArchivedMessageMetadataById(messageId: Long): Message?

                @Query(
                        """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId
                             FROM archived_messages
                             WHERE conversationId = :conversationId AND conversationType = :type
                                 AND EXISTS (
                                         SELECT 1 FROM archived_message_bodies
                                         WHERE messageId = archived_messages.id
                                             AND messageId IN (SELECT rowid FROM message_search_index WHERE message_search_index MATCH :matchQuery)
                                 )
                             ORDER BY timestamp DESC, id DESC LIMIT :limit"""
                )
        suspend fun searchArchivedMessageMetadataByMatch(
            conversationId: Long,
            type: String,
            matchQuery: String,
            limit: Int
        ): List<Message>

        suspend fun searchArchivedMessageMetadata(
            conversationId: Long,
            type: String,
            query: String,
            limit: Int
        ): List<Message> = MessageSearchTokenizer.matchQuery(query)?.let { matchQuery ->
            searchArchivedMessageMetadataByMatch(conversationId, type, matchQuery, limit)
        } ?: emptyList()

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type AND fileFormat = :fileFormat ORDER BY timestamp DESC, id DESC LIMIT :limit")
        suspend fun getArchivedMessageMetadataByFileFormat(
            conversationId: Long,
            type: String,
            fileFormat: FileFormat,
            limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type AND fileFormat = :fileFormat AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId)) ORDER BY timestamp DESC, id DESC LIMIT :limit")
        suspend fun getArchivedMessageMetadataByFileFormatBefore(
            conversationId: Long,
            type: String,
            fileFormat: FileFormat,
            beforeTimestamp: Long,
            beforeId: Long,
            limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT 1")
        suspend fun getLastArchivedMessageMetadata(conversationId: Long, type: String): Message?

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
        suspend fun getRecentArchivedMessageMetadata(
            conversationId: Long,
            type: String,
            limit: Int
        ): List<Message>

        @Query("SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp ASC, id ASC")
        suspend fun getAllArchivedMessageMetadata(
            conversationId: Long,
            type: String
        ): List<Message>

        @Query("SELECT messageId, content, searchContent, linkString FROM archived_message_bodies WHERE messageId IN (:messageIds)")
        suspend fun getArchivedMessageBodies(messageIds: List<Long>): List<MessageBody>

    @Query(
        """SELECT * FROM messages
           WHERE conversationId = :conversationId AND conversationType = :type
             AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
           ORDER BY timestamp DESC, id DESC LIMIT :limit"""
    )
    @Transaction
    fun getMessagesBefore(
        conversationId: Long,
        type: String,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): Flow<List<StoredMessage>>

    @Query(
        """SELECT * FROM messages
           WHERE conversationId = :conversationId AND conversationType = :type
             AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
           ORDER BY timestamp DESC, id DESC LIMIT :limit"""
    )
    @Transaction
    suspend fun getMessagesBeforeSync(
        conversationId: Long,
        type: String,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<StoredMessage>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
    @Transaction
    fun getRecentMessages(conversationId: Long, type: String, limit: Int): Flow<List<StoredMessage>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT :limit")
    @Transaction
    suspend fun getRecentMessagesSync(conversationId: Long, type: String, limit: Int): List<StoredMessage>

    /**
     * 只读范围查询：取某一会话中 id 大于 [afterId] 的消息（按 id 正序，最多 [limit] 条）。
     * 供滚动摘要的增量补拉（gap fetcher）使用，保证增量片段连续、不重复。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :conversationType AND id > :afterId ORDER BY id ASC LIMIT :limit")
    @Transaction
    suspend fun getMessagesInRangeSync(
        conversationId: Long,
        conversationType: String,
        afterId: Long,
        limit: Int
    ): List<StoredMessage>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT 1")
    @Transaction
    fun getLastMessage(conversationId: Long, type: String): Flow<StoredMessage?>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp DESC, id DESC LIMIT 1")
    @Transaction
    suspend fun getLastMessageSync(conversationId: Long, type: String): StoredMessage?

    /**
     * 取某一轮（turnId）内指定类型的消息。用于工具调用卡片的幂等写入
     * （同 turnId 只保留一条 TOOL_ACTIVITY）与再生成时的旧卡片清理。
     * 由于 type 列由 Converters 以枚举 name 存字符串，这里用字面量匹配。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :conversationType AND turnId = :turnId AND type = :messageTypeName")
    @Transaction
    suspend fun getMessagesByTurnAndType(
        conversationId: Long,
        conversationType: String,
        turnId: String,
        messageTypeName: String
    ): List<StoredMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBodyRecord(body: MessageBody)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchIndex(index: MessageSearchIndex)

    @Transaction
    suspend fun insertBody(body: MessageBody) {
        insertBodyRecord(body)
        upsertSearchIndex(
            MessageSearchIndex(body.messageId, MessageSearchTokenizer.indexTokens(body.searchContent))
        )
    }

    @Transaction
    suspend fun insertStoredMessage(message: Message, body: MessageBody): Long {
        val messageId = insertMessage(message)
        insertBody(body.copy(messageId = messageId))
        return messageId
    }

    @Query("SELECT * FROM messages WHERE id = :messageId")
    @Transaction
    suspend fun getMessageById(messageId: Long): StoredMessage?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type ORDER BY timestamp ASC, id ASC")
    @Transaction
    suspend fun getAllMessagesSync(conversationId: Long, type: String): List<StoredMessage>

        @Query(
                """SELECT * FROM messages
                     WHERE conversationId = :conversationId AND conversationType = :type
                         AND EXISTS (
                                 SELECT 1 FROM message_bodies
                                 WHERE messageId = messages.id
                                     AND messageId IN (SELECT rowid FROM message_search_index WHERE message_search_index MATCH :matchQuery)
                         )
                     ORDER BY timestamp DESC, id DESC LIMIT :limit"""
        )
    @Transaction
    suspend fun searchMessagesByMatch(
        conversationId: Long,
        type: String,
        matchQuery: String,
        limit: Int
    ): List<StoredMessage>

    suspend fun searchMessages(
        conversationId: Long,
        type: String,
        query: String,
        limit: Int
    ): List<StoredMessage> = MessageSearchTokenizer.matchQuery(query)?.let { matchQuery ->
        searchMessagesByMatch(conversationId, type, matchQuery, limit)
    } ?: emptyList()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND conversationType = :type AND fileFormat = :fileFormat ORDER BY timestamp DESC, id DESC LIMIT :limit")
    @Transaction
    suspend fun getMessagesByFileFormat(
        conversationId: Long,
        type: String,
        fileFormat: FileFormat,
        limit: Int
    ): List<StoredMessage>

    @Query(
        """SELECT * FROM messages
           WHERE conversationId = :conversationId AND conversationType = :type
             AND fileFormat = :fileFormat
             AND (timestamp < :beforeTimestamp OR (timestamp = :beforeTimestamp AND id < :beforeId))
           ORDER BY timestamp DESC, id DESC LIMIT :limit"""
    )
    @Transaction
    suspend fun getMessagesByFileFormatBefore(
        conversationId: Long,
        type: String,
        fileFormat: FileFormat,
        beforeTimestamp: Long,
        beforeId: Long,
        limit: Int
    ): List<StoredMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>): List<Long>

    @Transaction
    suspend fun insertStoredMessages(messages: List<Pair<Message, MessageBody>>): List<Long> =
        messages.map { (message, body) -> insertStoredMessage(message, body) }

    @Delete
    suspend fun deleteMessageMetadata(message: Message): Int

    @Query("DELETE FROM message_search_index WHERE rowid = :messageId")
    suspend fun deleteSearchIndex(messageId: Long)

    @Transaction
    suspend fun deleteMessage(message: Message): Int {
        deleteSearchIndex(message.id)
        return deleteMessageMetadata(message)
    }

    @Query("DELETE FROM archived_messages WHERE id = :messageId")
    suspend fun deleteArchivedMessageMetadata(messageId: Long): Int

    @Transaction
    suspend fun deleteArchivedMessage(messageId: Long): Int {
        deleteSearchIndex(messageId)
        return deleteArchivedMessageMetadata(messageId)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND conversationType = :type")
    suspend fun deleteMessageMetadataForConversation(conversationId: Long, type: String): Int

    @Query("DELETE FROM message_search_index WHERE rowid IN (SELECT id FROM messages WHERE conversationId = :conversationId AND conversationType = :type)")
    suspend fun deleteHotSearchIndexForConversation(conversationId: Long, type: String)

    @Transaction
    suspend fun deleteMessagesForConversation(conversationId: Long, type: String): Int {
        deleteHotSearchIndexForConversation(conversationId, type)
        return deleteMessageMetadataForConversation(conversationId, type)
    }

    @Query("DELETE FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type")
    suspend fun deleteArchivedMessageMetadataForConversation(conversationId: Long, type: String): Int

    @Query("DELETE FROM message_search_index WHERE rowid IN (SELECT id FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type)")
    suspend fun deleteArchivedSearchIndexForConversation(conversationId: Long, type: String)

    @Transaction
    suspend fun deleteArchivedMessagesForConversation(conversationId: Long, type: String): Int {
        deleteArchivedSearchIndexForConversation(conversationId, type)
        return deleteArchivedMessageMetadataForConversation(conversationId, type)
    }

    @Transaction
    suspend fun deleteAllMessagesForConversation(conversationId: Long, type: String): Int =
        deleteMessagesForConversation(conversationId, type) +
            deleteArchivedMessagesForConversation(conversationId, type)

    @Query("UPDATE message_bodies SET content = :content, searchContent = :searchContent WHERE messageId = :messageId")
    suspend fun updateHotMessageContent(messageId: Long, content: String, searchContent: String): Int

    @Query("UPDATE messages SET anchorMessageId = :anchorMessageId WHERE id = :id")
    suspend fun updateAnchorMessageId(id: Long, anchorMessageId: Long?)

    @Transaction
    suspend fun updateMessageContent(messageId: Long, content: String, searchContent: String): Int {
        val updated = updateHotMessageContent(messageId, content, searchContent)
        if (updated > 0) {
            upsertSearchIndex(
                MessageSearchIndex(messageId, MessageSearchTokenizer.indexTokens(searchContent))
            )
        }
        return updated
    }

    @Query("UPDATE archived_message_bodies SET content = :content, searchContent = :searchContent WHERE messageId = :messageId")
    suspend fun updateColdMessageContent(messageId: Long, content: String, searchContent: String): Int

    @Transaction
    suspend fun updateArchivedMessageContent(messageId: Long, content: String, searchContent: String): Int {
        val updated = updateColdMessageContent(messageId, content, searchContent)
        if (updated > 0) {
            upsertSearchIndex(
                MessageSearchIndex(messageId, MessageSearchTokenizer.indexTokens(searchContent))
            )
        }
        return updated
    }

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND conversationType = :type AND isFromUser = 0")
    suspend fun getAiMessageCount(conversationId: Long, type: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND conversationType = :type AND isFromUser = 0 AND type NOT IN ('TOOL_ACTIVITY', 'REASONING') AND (:readThroughMessageTimestamp IS NULL OR timestamp > :readThroughMessageTimestamp OR (timestamp = :readThroughMessageTimestamp AND id > :readThroughMessageId))")
    suspend fun getUnreadMessageCount(
        conversationId: Long,
        type: String,
        readThroughMessageTimestamp: Long?,
        readThroughMessageId: Long?
    ): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND conversationType = :type")
    suspend fun getMessageCount(conversationId: Long, type: String): Int

    @Query("SELECT DISTINCT conversationId FROM messages WHERE conversationType = :type")
    suspend fun getDistinctConversationIds(type: String): List<Long>

    @Query(
        """DELETE FROM messages WHERE id IN (
              SELECT id FROM messages
              WHERE conversationId = :conversationId AND conversationType = :type
              ORDER BY timestamp ASC, id ASC LIMIT :count
           )"""
    )
    suspend fun deleteOldMessageMetadataForConversation(
        conversationId: Long,
        type: String,
        count: Int
    ): Int

    @Query(
        """DELETE FROM message_search_index WHERE rowid IN (
              SELECT id FROM messages
              WHERE conversationId = :conversationId AND conversationType = :type
              ORDER BY timestamp ASC, id ASC LIMIT :count
           )"""
    )
    suspend fun deleteOldSearchIndexForConversation(
        conversationId: Long,
        type: String,
        count: Int
    )

    @Transaction
    suspend fun deleteOldMessagesForConversation(
        conversationId: Long,
        type: String,
        count: Int
    ): Int {
        deleteOldSearchIndexForConversation(conversationId, type, count)
        return deleteOldMessageMetadataForConversation(conversationId, type, count)
    }

    @Query(
        """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId
           FROM messages
           WHERE id IN (
               SELECT id FROM messages
               WHERE conversationId = :conversationId AND conversationType = :type
               ORDER BY timestamp DESC, id DESC LIMIT :retainCount
           )
           ORDER BY timestamp ASC, id ASC LIMIT 1"""
    )
    suspend fun getArchiveBoundary(
        conversationId: Long,
        type: String,
        retainCount: Int
    ): Message?

    @Query(
        """INSERT OR REPLACE INTO archived_messages
           SELECT * FROM messages
           WHERE conversationId = :conversationId AND conversationType = :type
             AND (:boundaryTimestamp IS NULL OR timestamp < :boundaryTimestamp
                  OR (timestamp = :boundaryTimestamp AND id < :boundaryId))"""
    )
    suspend fun copyOldMetadataToArchive(
        conversationId: Long,
        type: String,
        boundaryTimestamp: Long?,
        boundaryId: Long?
    )

    @Query(
        """INSERT OR REPLACE INTO archived_message_bodies
           SELECT messageId, content, searchContent, linkString FROM message_bodies
           WHERE messageId IN (
               SELECT id FROM messages
               WHERE conversationId = :conversationId AND conversationType = :type
                 AND (:boundaryTimestamp IS NULL OR timestamp < :boundaryTimestamp
                      OR (timestamp = :boundaryTimestamp AND id < :boundaryId))
           )"""
    )
    suspend fun copyOldBodiesToArchive(
        conversationId: Long,
        type: String,
        boundaryTimestamp: Long?,
        boundaryId: Long?
    )

    @Query(
        """DELETE FROM messages WHERE id IN (
               SELECT id FROM messages
               WHERE conversationId = :conversationId AND conversationType = :type
                 AND (:boundaryTimestamp IS NULL OR timestamp < :boundaryTimestamp
                      OR (timestamp = :boundaryTimestamp AND id < :boundaryId))
           )"""
    )
    suspend fun deleteArchivedRangeFromHot(
        conversationId: Long,
        type: String,
        boundaryTimestamp: Long?,
        boundaryId: Long?
    ): Int

    @Transaction
    suspend fun archiveOldMessages(
        conversationId: Long,
        type: String,
        retainCount: Int
    ): Int {
        require(retainCount >= 0)
        val boundary = if (retainCount == 0) null else getArchiveBoundary(conversationId, type, retainCount)
            ?: return 0
        copyOldMetadataToArchive(conversationId, type, boundary?.timestamp, boundary?.id)
        copyOldBodiesToArchive(conversationId, type, boundary?.timestamp, boundary?.id)
        return deleteArchivedRangeFromHot(conversationId, type, boundary?.timestamp, boundary?.id)
    }

    @Query("INSERT OR REPLACE INTO messages SELECT * FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type")
    suspend fun restoreArchivedMetadata(conversationId: Long, type: String)

    @Query("INSERT OR REPLACE INTO message_bodies SELECT * FROM archived_message_bodies WHERE messageId IN (SELECT id FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type)")
    suspend fun restoreArchivedBodies(conversationId: Long, type: String)

    @Transaction
    suspend fun restoreArchivedMessages(conversationId: Long, type: String): Int {
        val archivedCount = getArchivedMessageCount(conversationId, type)
        restoreArchivedMetadata(conversationId, type)
        restoreArchivedBodies(conversationId, type)
        deleteArchivedMessageMetadataForConversation(conversationId, type)
        return archivedCount
    }

    @Query("SELECT COUNT(*) FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type")
    suspend fun getArchivedMessageCount(conversationId: Long, type: String): Int

    @Query("SELECT COUNT(*) FROM archived_messages WHERE conversationId = :conversationId AND conversationType = :type AND isFromUser = 0")
    suspend fun getArchivedAiMessageCount(conversationId: Long, type: String): Int

    @Query(
        """SELECT * FROM messages
           WHERE turnId = :turnId
           ORDER BY eventIndex ASC, id ASC"""
    )
    @Transaction
    suspend fun getMessagesByTurnId(turnId: String): List<StoredMessage>

    @Query(
        """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId
           FROM archived_messages
           WHERE turnId = :turnId
           ORDER BY eventIndex ASC, id ASC"""
    )
    suspend fun getArchivedMessageMetadataByTurnId(turnId: String): List<Message>

    @Query(
        """SELECT * FROM messages
           WHERE conversationId = :conversationId AND conversationType = :conversationType
             AND type IN (:types)
           ORDER BY timestamp DESC, id DESC
           LIMIT :limit"""
    )
    @Transaction
    suspend fun getRecentMessagesByTypes(
        conversationId: Long,
        conversationType: String,
        types: List<com.yunian.ai.database.model.MessageType>,
        limit: Int
    ): List<StoredMessage>

    @Query(
        """SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId
           FROM archived_messages
           WHERE conversationId = :conversationId AND conversationType = :conversationType
             AND type IN (:types)
           ORDER BY timestamp DESC, id DESC
           LIMIT :limit"""
    )
    suspend fun getRecentArchivedMessageMetadataByTypes(
        conversationId: Long,
        conversationType: String,
        types: List<com.yunian.ai.database.model.MessageType>,
        limit: Int
    ): List<Message>
}
