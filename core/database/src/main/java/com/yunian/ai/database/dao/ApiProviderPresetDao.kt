package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ApiProviderPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiProviderPresetDao {
    @Query("SELECT * FROM api_provider_presets WHERE isVisible = 1 ORDER BY sortOrder ASC, id ASC")
    fun getVisiblePresets(): Flow<List<ApiProviderPreset>>

    @Query("SELECT * FROM api_provider_presets WHERE provider = :provider LIMIT 1")
    suspend fun getPreset(provider: ApiProvider): ApiProviderPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreset(preset: ApiProviderPreset)
}