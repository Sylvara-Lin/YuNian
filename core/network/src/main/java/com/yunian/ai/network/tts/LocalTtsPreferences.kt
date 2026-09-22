package com.yunian.ai.network.tts

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.localTtsDataStore by preferencesDataStore(name = "local_tts_settings")

data class LocalTtsPreferencesState(
    val isEnabled: Boolean = false,
    val pendingAutoEnable: Boolean = false,
    val downloadId: Long? = null,
    val downloadFileIndex: Int = 0
)

class LocalTtsPreferences(private val context: Context) {

    private val dataStore = context.applicationContext.localTtsDataStore
    private val selectedModelIdKey = stringPreferencesKey("selected_model_id")

    val selectedModelId: Flow<String> = dataStore.data.map { prefs ->
        prefs[selectedModelIdKey] ?: LocalTtsCatalog.default.id
    }

    fun modelState(modelId: String): Flow<LocalTtsPreferencesState> = dataStore.data.map { prefs ->
        LocalTtsPreferencesState(
            isEnabled = prefs[booleanPreferencesKey("${modelId}_enabled")] ?: false,
            pendingAutoEnable = prefs[booleanPreferencesKey("${modelId}_pending_auto_enable")] ?: false,
            downloadId = prefs[longPreferencesKey("${modelId}_download_id")],
            downloadFileIndex = prefs[intPreferencesKey("${modelId}_download_file_index")] ?: 0
        )
    }

    suspend fun selectModel(modelId: String) {
        dataStore.edit { prefs -> prefs[selectedModelIdKey] = modelId }
    }

    suspend fun setEnabled(modelId: String, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey("${modelId}_enabled")] = enabled }
    }

    suspend fun setPendingAutoEnable(modelId: String, pending: Boolean) {
        dataStore.edit { prefs -> prefs[booleanPreferencesKey("${modelId}_pending_auto_enable")] = pending }
    }

    suspend fun setDownloadId(modelId: String, downloadId: Long?) {
        dataStore.edit { prefs ->
            val key = longPreferencesKey("${modelId}_download_id")
            if (downloadId == null) prefs.remove(key) else prefs[key] = downloadId
        }
    }

    suspend fun setDownloadFileIndex(modelId: String, index: Int) {
        dataStore.edit { prefs -> prefs[intPreferencesKey("${modelId}_download_file_index")] = index }
    }
}
