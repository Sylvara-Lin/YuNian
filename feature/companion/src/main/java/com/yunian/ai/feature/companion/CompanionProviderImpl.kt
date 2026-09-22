package com.yunian.ai.feature.companion

import android.content.Context
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.CompanionProvider

class CompanionProviderImpl(context: Context) : CompanionProvider {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val repository = CompanionRepository(database.companionDao())

    override suspend fun getCompanionName(id: Long): String {
        return repository.getCompanionById(id)?.name ?: "未知"
    }

    override suspend fun getCompanionAvatar(id: Long): String {
        return repository.getCompanionById(id)?.avatarUrl ?: ""
    }

    override suspend fun isCompanionAvailable(id: Long): Boolean {
        return repository.getCompanionById(id) != null
    }
}
