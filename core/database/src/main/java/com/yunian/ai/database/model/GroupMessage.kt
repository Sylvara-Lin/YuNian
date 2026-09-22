package com.yunian.ai.database.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("E1")
data class GroupMessage(
    val id: Long = 0,
    val groupId: Long,
    val companionId: Long,

    val content: String,

    val timestamp: Long = System.currentTimeMillis(),

    val searchContent: String = content,

    val fileFormat: FileFormat = FileFormat.TEXT,

    val linkString: String = ""
)
