package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import kotlinx.coroutines.flow.Flow

@Dao
interface UnifiedMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: MemoryRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<MemoryRecord>): List<Long>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getAllActive(deviceId: String, now: Long = System.currentTimeMillis()): Flow<List<MemoryRecord>>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getByScope(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = :type
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    fun getByType(
        deviceId: String,
        type: MemoryType,
        now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getByScopeSync(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getAllActiveSync(
        deviceId: String,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun search(
        deviceId: String,
        query: String,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND (content LIKE '%' || :query || '%' OR summary LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun searchInScope(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        query: String,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND observedAt >= :since
        ORDER BY observedAt DESC
        LIMIT :limit
    """)
    suspend fun getRecentSince(
        deviceId: String,
        since: Long,
        limit: Int = 20,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        DELETE FROM unified_memories
        WHERE memoryType = 'WORKING'
          AND expiresAt IS NOT NULL
          AND expiresAt < :now
    """)
    suspend fun cleanupExpiredWorkingMemories(now: Long = System.currentTimeMillis()): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        limit: Int = 50,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
      SELECT * FROM unified_memories
      WHERE deviceId = :deviceId
        AND memoryType = 'WORKING'
        AND scope = :scope
        AND sourceId = :sourceId
        AND isDeleted = 0
        AND (expiresAt IS NULL OR expiresAt > :now)
      ORDER BY createdAt DESC
      LIMIT :limit
    """)
    fun getWorkingMemoriesFlow(
      deviceId: String,
      scope: MemoryScope,
      sourceId: Long,
      limit: Int = 50,
      now: Long = System.currentTimeMillis()
    ): Flow<List<MemoryRecord>>

    @Query("UPDATE unified_memories SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("""
      UPDATE unified_memories
      SET isDeleted = 1, updatedAt = :now
      WHERE deviceId = :deviceId
        AND scope = :scope
        AND sourceId = :sourceId
        AND source = :source
        AND isDeleted = 0
    """)
    suspend fun softDeleteByScopeAndSource(
      deviceId: String,
      scope: MemoryScope,
      sourceId: Long,
      source: MemorySource,
      now: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM unified_memories WHERE deviceId = :deviceId AND scope = :scope AND sourceId = :sourceId")
    suspend fun hardDeleteByScope(deviceId: String, scope: MemoryScope, sourceId: Long): Int

    @Query("UPDATE unified_memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE unified_memories SET importance = :importance, version = version + 1, updatedAt = :now WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Float, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE unified_memories SET confidence = :confidence, version = version + 1, updatedAt = :now WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Float, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM unified_memories WHERE deviceId = :deviceId AND isDeleted = 0")
    suspend fun count(deviceId: String): Int

    @Query("SELECT COUNT(*) FROM unified_memories WHERE deviceId = :deviceId AND scope = :scope AND sourceId = :sourceId AND isDeleted = 0")
    suspend fun countByScope(deviceId: String, scope: MemoryScope, sourceId: Long): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND content LIKE '%' || :content || '%'
        LIMIT 1
    """)
    suspend fun findByContent(deviceId: String, content: String): MemoryRecord?

    @Query("SELECT * FROM unified_memories WHERE deviceId = :deviceId ORDER BY observedAt DESC")
    suspend fun getAllSync(deviceId: String): List<MemoryRecord>

    @Query("UPDATE unified_memories SET embedding = :embedding, embeddingModel = :model, updatedAt = :now WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray, model: String, now: Long = System.currentTimeMillis()): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NOT NULL
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getWithEmbeddings(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND scope = 'GLOBAL'
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NOT NULL
        ORDER BY importance DESC, observedAt DESC
    """)
    suspend fun getGlobalWithEmbeddings(
        deviceId: String,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
          AND embedding IS NULL
          AND memoryType != 'WORKING'
        ORDER BY importance DESC, observedAt DESC
        LIMIT :limit
    """)
    suspend fun getWithoutEmbeddings(
        deviceId: String,
        limit: Int = 50,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("""
        SELECT COUNT(*) FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
    """)
    suspend fun countWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("""
        SELECT * FROM unified_memories
        WHERE deviceId = :deviceId
          AND memoryType = 'WORKING'
          AND scope = :scope
          AND sourceId = :sourceId
          AND isDeleted = 0
          AND (expiresAt IS NULL OR expiresAt > :now)
        ORDER BY createdAt ASC
        LIMIT :limit
    """)
    suspend fun getOldestWorkingMemories(
        deviceId: String,
        scope: MemoryScope,
        sourceId: Long,
        limit: Int = 10,
        now: Long = System.currentTimeMillis()
    ): List<MemoryRecord>

    @Query("UPDATE unified_memories SET isDeleted = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun softDeleteByIds(ids: List<Long>, now: Long = System.currentTimeMillis()): Int

    /** 按主键查询（排除软删除），Agent Memory 层复核用 */
    @Query("SELECT * FROM unified_memories WHERE id = :id AND isDeleted = 0")
    suspend fun getByIdSync(id: Long): MemoryRecord?
}
