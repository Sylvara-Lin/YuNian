package com.yunian.ai.database.repository

import com.yunian.ai.database.model.CompanionEntity

interface DiaryProvider {

    suspend fun generateDiary(
        companion: CompanionEntity,
        conversationText: String,
        memoryContext: String = ""
    ): String?
}
