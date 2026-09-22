package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "companions",
    indices = [
        Index(value = ["createdAt"], name = "index_companions_created_at")
    ]
)
@Serializable
@SerialName("E2")
data class CompanionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val avatarUrl: String? = null,
    val age: Int? = null,
    val personality: String,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val tags: String? = null,
    val rawPrompt: String? = null,
    val systemPrompt: String? = null,
    val intimacy: Int = 0,

    /** 绑定的全局世界书 ID 列表（JSON 数组字符串）。空数组表示沿用旧行为：所有全局世界书自动生效 */
    @ColumnInfo(defaultValue = "[]")
    val lorebookIdsJson: String = "[]",

    /**
     * 绑定的专属 API 配置 ID（api_configs.id）。
     * null = 跟随全局启用的 API 配置（旧行为）；
     * 非 null = 该角色所有 AI 请求强制走此配置（不同 Key 隔离，避免多角色共用 Key 导致上下文缓存串台）。
     * 绑定配置被删除/无 Key 时自动回退全局。
     */
    val apiConfigId: Long? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
