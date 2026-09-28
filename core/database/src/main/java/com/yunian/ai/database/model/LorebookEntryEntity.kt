package com.yunian.ai.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "lorebook_entries",
    indices = [
        Index(value = ["lorebookId"]),
        Index(value = ["enabled"]),
        Index(value = ["priority"]),
        Index(value = ["injectionPosition"])
    ]
)
@Serializable
data class LorebookEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val lorebookId: Long,

    val keywordsJson: String,

    val content: String,

    val injectionPosition: InjectionPosition = InjectionPosition.BEFORE_SYSTEM_PROMPT,

    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0,

    val injectDepth: Int? = null,

    val role: EntryRole = EntryRole.SYSTEM,

    @ColumnInfo(defaultValue = "0")
    val caseSensitive: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val useRegex: Int = 0,

    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(defaultValue = "10")
    val scanDepth: Int = 10,

    @ColumnInfo(defaultValue = "0")
    val constantActive: Int = 0,

    @ColumnInfo(defaultValue = "1")
    val enabled: Int = 1,

    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,

    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    fun isEnabled(): Boolean = enabled == 1
    fun isConstantActive(): Boolean = constantActive == 1
    fun isCaseSensitive(): Boolean = caseSensitive == 1
    fun isUseRegex(): Boolean = useRegex == 1
}

@Serializable
enum class InjectionPosition {

    BEFORE_SYSTEM_PROMPT,

    AFTER_SYSTEM_PROMPT,

    TOP_OF_CHAT,

    BOTTOM_OF_CHAT,

    AT_DEPTH
}

@Serializable
enum class EntryRole {
    SYSTEM,
    USER,
    ASSISTANT
}
