package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.AppMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppMetaEntity)

    @Query("DELETE FROM app_meta WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM app_meta")
    suspend fun getAll(): List<AppMetaEntity>

    /** 单键值的响应式流（首页会话「隐藏该聊天」状态实时刷新用；DB v41 既有表）。 */
    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    fun getFlow(key: String): Flow<String?>
}
