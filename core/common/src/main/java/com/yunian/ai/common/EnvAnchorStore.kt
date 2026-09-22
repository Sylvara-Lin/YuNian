package com.yunian.ai.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.envAnchorDataStore: DataStore<Preferences> by preferencesDataStore(name = "env_anchor")

class EnvAnchorStore(context: Context) {

    private val dataStore = context.applicationContext.envAnchorDataStore

    suspend fun getLastEnvAnchorAt(companionId: Long): Long {
        val key = keyFor(companionId)
        return dataStore.data.map { prefs -> prefs[key] ?: 0L }.first()
    }

    suspend fun markEnvAnchor(
        companionId: Long,
        atMs: Long = System.currentTimeMillis(),
    ) {
        if (companionId <= 0L) return
        val key = keyFor(companionId)
        dataStore.edit { prefs ->
            prefs[key] = atMs.coerceAtLeast(0L)
        }
    }

    suspend fun isEnvAnchorCoolingDown(
        companionId: Long,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        val last = getLastEnvAnchorAt(companionId)
        return EnvAnchorCooldown.isCoolingDown(last, nowMs, cooldownMs)
    }

    suspend fun allowEnvAnchor(
        companionId: Long,
        recentAiTexts: Iterable<String>,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        val last = getLastEnvAnchorAt(companionId)
        return EnvAnchorCooldown.allowEnvAnchor(last, recentAiTexts, nowMs, cooldownMs)
    }

    private fun keyFor(companionId: Long) =
        longPreferencesKey("last_env_anchor_at_$companionId")
}
