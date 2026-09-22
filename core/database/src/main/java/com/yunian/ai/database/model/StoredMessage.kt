package com.yunian.ai.database.model

import androidx.room.Embedded
import androidx.room.Relation

data class StoredMessage(
    @Embedded
    val metadata: Message,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val body: MessageBody
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        id = metadata.id,
        companionId = metadata.conversationId,
        content = body.content,
        isFromUser = metadata.isFromUser,
        timestamp = metadata.timestamp,
        type = metadata.type,
        searchContent = body.searchContent,
        fileFormat = metadata.fileFormat,
        linkString = body.linkString,
        turnId = metadata.turnId,
        eventIndex = metadata.eventIndex,
        durationMs = metadata.durationMs,
        anchorMessageId = metadata.anchorMessageId,
    )

    fun toGroupMessage(): GroupMessage = GroupMessage(
        id = metadata.id,
        groupId = metadata.conversationId,
        companionId = metadata.senderId,
        content = body.content,
        timestamp = metadata.timestamp,
        searchContent = body.searchContent,
        fileFormat = metadata.fileFormat,
        linkString = body.linkString
    )

    companion object {
        fun fromChatMessage(message: ChatMessage): Pair<Message, MessageBody> {
            val metadata = Message(
                id = message.id,
                conversationId = message.companionId,
                conversationType = "chat",
                isFromUser = message.isFromUser,
                timestamp = message.timestamp,
                type = message.type,
                fileFormat = message.fileFormat,
                turnId = message.turnId,
                eventIndex = message.eventIndex,
                durationMs = message.durationMs,
                anchorMessageId = message.anchorMessageId,
            )
            return metadata to MessageBody(message.id, message.content, message.searchContent, message.linkString)
        }

        fun fromGroupMessage(message: GroupMessage): Pair<Message, MessageBody> {
            val metadata = Message(
                id = message.id,
                conversationId = message.groupId,
                conversationType = "group",
                isFromUser = message.companionId == -1L,
                senderId = message.companionId,
                timestamp = message.timestamp,
                fileFormat = message.fileFormat
            )
            return metadata to MessageBody(message.id, message.content, message.searchContent, message.linkString)
        }
    }
}