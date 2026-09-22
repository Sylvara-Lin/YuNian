package com.yunian.ai.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yunian.ai.database.model.AgentSkillEntity
import kotlinx.coroutines.flow.Flow

/**
 * Agent Skills 索引 DAO。
 * 仅操作索引元数据；正文读写由 core:agent 的 SkillFileStore 负责（混合存储）。
 */
@Dao
interface AgentSkillDao {

    /** 观察全局技能（companionId 为 null） */
    @Query("SELECT * FROM agent_skills WHERE companionId IS NULL ORDER BY updatedAt DESC")
    fun observeGlobalSkills(): Flow<List<AgentSkillEntity>>

    /** 观察某陪伴者可见技能（全局 + 该陪伴者），按更新时间倒序 */
    @Query("SELECT * FROM agent_skills WHERE (companionId IS NULL OR companionId = :companionId) AND enabled = 1 ORDER BY updatedAt DESC")
    fun observeSkillsForCompanion(companionId: Long): Flow<List<AgentSkillEntity>>

    /** 同步获取某陪伴者可见技能（全局 + 该陪伴者），按更新时间倒序 */
    @Query("SELECT * FROM agent_skills WHERE (companionId IS NULL OR companionId = :companionId) AND enabled = 1 ORDER BY updatedAt DESC")
    suspend fun getSkillsForCompanionSync(companionId: Long?): List<AgentSkillEntity>

    /** 全量索引（含禁用项，由 Rust SkillSelector 过滤 enabled），供 list_skills 回调使用 */
    @Query("SELECT * FROM agent_skills WHERE (companionId IS NULL OR companionId = :companionId) ORDER BY updatedAt DESC")
    suspend fun listSkillsAll(companionId: Long?): List<AgentSkillEntity>

    /** 按业务唯一键查（全局或任意陪伴者） */
    @Query("SELECT * FROM agent_skills WHERE skillId = :skillId LIMIT 1")
    suspend fun getBySkillId(skillId: String): AgentSkillEntity?

    @Query("SELECT * FROM agent_skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): AgentSkillEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(skill: AgentSkillEntity): Long

    @Update
    suspend fun update(skill: AgentSkillEntity): Int

    @Delete
    suspend fun delete(skill: AgentSkillEntity): Int

    @Query("DELETE FROM agent_skills WHERE skillId = :skillId")
    suspend fun deleteBySkillId(skillId: String): Int

    /** 关键字检索：name / description / tags LIKE 匹配 */
    @Query(
        """
        SELECT * FROM agent_skills
        WHERE (companionId IS NULL OR companionId = :companionId)
          AND enabled = 1
          AND (name LIKE '%' || :query || '%'
               OR description LIKE '%' || :query || '%'
               OR tags LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchSkills(companionId: Long?, query: String, limit: Int = 10): List<AgentSkillEntity>

    /** 软开关 */
    @Query("UPDATE agent_skills SET enabled = :enabled, updatedAt = :now WHERE skillId = :skillId")
    suspend fun setEnabled(skillId: String, enabled: Boolean, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM agent_skills")
    suspend fun count(): Int

    @Query("SELECT * FROM agent_skills")
    suspend fun getAllSync(): List<AgentSkillEntity>
}
