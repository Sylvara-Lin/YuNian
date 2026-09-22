package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yunian.ai.database.model.StickerTagEntity
import kotlinx.coroutines.flow.Flow

/**
 * 表情包 tag 统计映射表 DAO。
 *
 * getAll / observeAll 按数量降序、名称升序返回，直接作为导入对话框的 tag 候选列表。
 */
@Dao
interface StickerTagDao {

    /** 全部 tag（数量降序、名称升序），一次性读取用于导入对话框候选 */
    @Query("SELECT * FROM sticker_tags ORDER BY stickerCount DESC, tag ASC")
    suspend fun getAll(): List<StickerTagEntity>

    /** 全部 tag 的可观察流 */
    @Query("SELECT * FROM sticker_tags ORDER BY stickerCount DESC, tag ASC")
    fun observeAll(): Flow<List<StickerTagEntity>>

    @Query("SELECT * FROM sticker_tags WHERE tag = :tag LIMIT 1")
    suspend fun getByTag(tag: String): StickerTagEntity?

    /** 插入或覆盖（tag 唯一索引） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StickerTagEntity): Long

    @Update
    suspend fun update(entity: StickerTagEntity): Int

    /** 全量重建用：清空后批量插入 */
    @Query("DELETE FROM sticker_tags")
    suspend fun deleteAll()
}
