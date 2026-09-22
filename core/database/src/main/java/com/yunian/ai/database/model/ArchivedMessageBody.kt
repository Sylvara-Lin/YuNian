package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "archived_message_bodies",
    foreignKeys = [
        ForeignKey(
            entity = ArchivedMessage::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ArchivedMessageBody(
    @PrimaryKey val messageId: Long,
    val content: String,
    val searchContent: String = "",
    val linkString: String = ""
)