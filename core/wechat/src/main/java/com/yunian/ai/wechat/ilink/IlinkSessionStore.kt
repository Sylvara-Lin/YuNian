package com.yunian.ai.wechat.ilink

interface IlinkSessionStore {
    suspend fun getSessionAccount(): IlinkAccount?

    suspend fun saveSessionAccount(account: IlinkAccount)

    suspend fun clearSessionAccount()

    suspend fun getCursor(): String

    suspend fun saveCursor(cursor: String)

    suspend fun getContextToken(accountId: String, userId: String): String?

    /**
     * 该 context_token 的落库时间（epoch ms）。null 表示实现未记录时间，
     * 调用方应跳过基于年龄的过期判断。iLink 协议 token 24h 过期。
     */
    suspend fun getContextTokenSavedAt(accountId: String, userId: String): Long? = null

    suspend fun saveContextToken(accountId: String, userId: String, token: String)

    suspend fun getContextTokens(accountId: String): Map<String, String>
}