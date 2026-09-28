package com.yunian.ai.feature.automation.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AutomationStore(context: Context) {

    private val dataStore = AutomationDataStoreProvider.get(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val AUTOMATIONS_KEY = stringPreferencesKey("automations_json")
    }

    fun flow(): Flow<List<Automation>> = dataStore.data.map { prefs ->
        prefs[AUTOMATIONS_KEY]?.let(::decodeList) ?: emptyList()
    }

    suspend fun list(): List<Automation> = flow().first()

    suspend fun upsert(automation: Automation) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            val index = current.indexOfFirst { it.id == automation.id }
            if (index >= 0) current[index] = automation else current.add(automation)
            prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            current.removeAll { it.id == id }
            prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) {
                current[index] = current[index].copy(enabled = enabled)
                prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
            }
        }
    }

    private fun decodeList(raw: String): List<Automation> =
        runCatching { json.decodeFromString<List<Automation>>(raw) }.getOrElse { emptyList() }
}
