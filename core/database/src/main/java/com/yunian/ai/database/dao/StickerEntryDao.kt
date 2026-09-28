package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yunian.ai.database.model.StickerEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 表情包条目元数据 DAO（元数据侧；图片本体在文件系统）。
 *
 * Rust 引擎（StickerPreferenceEngine）通过 StickerPreferenceStoreImpl 回调此 DAO，
 * Kotlin 侧只做读写，不做决策。
 */
@Dao
interface StickerEntryDao {

    /** 全部条目（供引擎 rebuild 全量载入） */
    @Query("SELECT * FROM sticker_entries ORDER BY id ASC")
    suspend fun getAll(): List<StickerEntryEntity>

    /** 观察全部条目 */
    @Query("SELECT * FROM sticker_entries ORDER BY id ASC")
    fun observeAll(): Flow<List<StickerEntryEntity>>

    /** 按 id 查 */
    @Query("SELECT * FROM sticker_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): StickerEntryEntity?

    /** 按 hash 查（导入幂等去重） */
    @Query("SELECT * FROM sticker_entries WHERE hash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): StickerEntryEntity?

    /** 按文件名查 */
    @Query("SELECT * FROM sticker_entries WHERE fileName = :fileName LIMIT 1")
    suspend fun getByFileName(fileName: String): StickerEntryEntity?

    /** 插入，冲突（hash 唯一）时忽略并返回既有行 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: StickerEntryEntity): Long

    @Update
    suspend fun update(entry: StickerEntryEntity): Int

    /** 更新使用计数与最后使用时间（Rust recordUsage 落库用） */
    @Query(
        """
        UPDATE sticker_entries
        SET userUsageCount = userUsageCount + :delta,
            lastUsedAt = :now
        WHERE id = :id
        """
    )
    suspend fun bumpUserUsage(id: Long, delta: Int = 1, now: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE sticker_entries
        SET modelUsageCount = modelUsageCount + :delta,
            lastUsedAt = :now
        WHERE id = :id
        """
    )
    suspend fun bumpModelUsage(id: Long, delta: Int = 1, now: Long = System.currentTimeMillis()): Int

    /** 删除条目（含配套文件由调用方处理） */
    @Query("DELETE FROM sticker_entries WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** 按 fileName 删除 */
    @Query("DELETE FROM sticker_entries WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String): Int

    /** 统计条数 */
    @Query("SELECT COUNT(*) FROM sticker_entries")
    suspend fun count(): Int
}
