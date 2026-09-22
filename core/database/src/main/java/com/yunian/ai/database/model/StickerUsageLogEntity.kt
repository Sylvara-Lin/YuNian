package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 表情包使用记录日志实体。
 *
 * - 每条记录 = 一次发送（user 手动 / model 自动），供 Rust 引擎做偏好学习、衰减、漂移检测。
 * - source 枚举值 'user' / 'model'；偏好先验只读 'user' 行（D4）。
 * - 保留策略：90 天 / 5000 条，由 WorkManager 聚合进 sticker_entries 计数列后清理。
 */
@Entity(
    tableName = "sticker_usage_log",
    indices = [
        Index(value = ["stickerId"], name = "idx_sticker_usage_sticker"),
        Index(value = ["timestamp"], name = "idx_sticker_usage_time"),
    ]
)
@Serializable
data class StickerUsageLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** FK → sticker_entries.id */
    val stickerId: Long,
    /** 'user' / 'model' */
    val source: String,
    /** 发送时间 epoch ms */
    val timestamp: Long,
    /** 发送时上下文标签（逗号分隔，可选） */
    val contextTags: String = "",
)
