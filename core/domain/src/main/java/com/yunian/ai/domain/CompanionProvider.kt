package com.yunian.ai.domain

interface CompanionProvider {
    suspend fun getCompanionName(id: Long): String
    suspend fun getCompanionAvatar(id: Long): String
    suspend fun isCompanionAvailable(id: Long): Boolean
}
