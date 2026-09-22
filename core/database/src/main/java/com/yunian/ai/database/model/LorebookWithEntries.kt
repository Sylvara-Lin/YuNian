package com.yunian.ai.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yunian.ai.database.model.LorebookEntryEntity

data class LorebookWithEntries(
    @Embedded val lorebook: LorebookEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "lorebookId"
    )
    val entries: List<LorebookEntryEntity>
)
