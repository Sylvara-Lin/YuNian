package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 世界书（SillyTavern World Info 格式，聊天陪伴）。
 * json 存社区格式全文（entries map/数组）；启用者回合组装时注入。
 */
@Entity(
    tableName = "worldbooks",
    indices = [Index(value = ["enabled"]), Index(value = ["companionId"])],
)
data class WorldbookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String = "",
    val json: String = "{}",
    val enabled: Boolean = false,
    /** 伴侣级世界书：非空 = 仅该伴侣会话生效；空 = 全局 */
    val companionId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
