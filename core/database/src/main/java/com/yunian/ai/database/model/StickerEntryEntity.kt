package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 表情包条目元数据实体（文件系统 + 数据库元数据存储方案的"元数据"侧）。
 *
 * - 图片本体存文件系统（filesDir/stickers/），此表只存元数据索引。
 * - hash = 图片文件 SHA-256，导入时由应用计算（不信任 JSON 声明），UNIQUE 幂等去重。
 * - tags = 逗号分隔的精确标签，是 Rust 匹配层的唯一语义锚点（D1 纯 tag 决策）。
 * - userUsageCount / modelUsageCount 分开存：偏好先验只聚合 USER 列（D4）。
 */
@Entity(
    tableName = "sticker_entries",
    indices = [
        Index(value = ["hash"], unique = true, name = "idx_sticker_entries_hash"),
        Index(value = ["tags"], name = "idx_sticker_entries_tags"),
    ]
)
@Serializable
data class StickerEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 展示标签（可为空，匹配以 tags 为准） */
    val description: String? = null,
    /** 图片文件 SHA-256，导入时由应用计算，UNIQUE */
    val hash: String,
    /** 逗号分隔的精确标签（唯一语义锚点，Rust 精确匹配用） */
    val tags: String = "",
    /** 内置表情包附加文本 */
    val embeddedText: String? = null,
    /** 详情 */
    val detail: String? = null,
    /** 文件相对名（相对于 stickers/ 目录） */
    val fileName: String,
    /** 来源：imported / builtin */
    val source: String = "imported",
    /** 字节数 */
    val fileSize: Long? = null,
    /** 用户使用次数（偏好先验只聚合此列） */
    val userUsageCount: Int = 0,
    /** 模型使用次数（单独存，不污染偏好） */
    val modelUsageCount: Int = 0,
    /** 创建时间 epoch ms */
    val createdAt: Long? = null,
    /** 最后使用时间 epoch ms */
    val lastUsedAt: Long? = null,
)
