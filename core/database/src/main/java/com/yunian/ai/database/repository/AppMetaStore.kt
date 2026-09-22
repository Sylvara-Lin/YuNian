package com.yunian.ai.database.repository

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.model.AppMetaEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class AppMetaStore(private val dao: AppMetaDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun getString(key: String): String? = dao.get(key)

    suspend fun putString(key: String, value: String) {
        dao.put(AppMetaEntity(key = key, value = value))
    }

    suspend fun remove(key: String) = dao.remove(key)

    suspend fun contains(key: String): Boolean = dao.get(key) != null

    suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        val raw = dao.get(key) ?: return null
        // P2-B3：解码失败（KV 内容损坏）此前静默按「无值」处理，现记 error 日志（含 key）便于观测。
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { SecureLog.e(TAG, "decode failed, treating as absent. key=$key", it) }
            .getOrNull()
    }

    suspend fun <T> put(key: String, value: T, serializer: KSerializer<T>) {
        // P2-B3：写失败记 error 日志（含 key）后原样抛出，交由调用方决定容错策略（不静默吞）。
        runCatching { dao.put(AppMetaEntity(key = key, value = json.encodeToString(serializer, value))) }
            .onFailure { SecureLog.e(TAG, "put failed. key=$key", it) }
            .getOrThrow()
    }

    suspend fun <T> getOrPut(
        key: String,
        serializer: KSerializer<T>,
        default: suspend () -> T
    ): T {
        return get(key, serializer) ?: default().also { put(key, it, serializer) }
    }

    private companion object {
        const val TAG = "AppMetaStore"
    }
}
