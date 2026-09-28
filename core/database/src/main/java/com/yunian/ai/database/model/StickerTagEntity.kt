package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 表情包 tag 统计映射表。
 *
 * 记录每个 tag 当前关联的表情包图片数量，避免每次从 sticker_entries.tags 全表拆分聚合。
 * 由 StickerPreferenceFacade.rebuildTagStats 在表情包元数据同步后全量重建（幂等，deleteAll + 批量插入）。
 * 用途：导入对话框展示已有 tag 及其图片数，供用户选择复用 / 自增。
 */
@Entity(
    tableName = "sticker_tags",
    indices = [Index(value = ["tag"], unique = true)]
)
@Serializable
data class StickerTagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** tag 名称（唯一） */
    val tag: String,
    /** 当前关联的表情包图片数量 */
    val stickerCount: Int = 0,
)
