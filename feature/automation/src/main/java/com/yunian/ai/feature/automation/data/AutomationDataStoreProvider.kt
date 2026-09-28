package com.yunian.ai.feature.automation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

object AutomationDataStoreProvider {
    private const val NAME = "automation_store"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = NAME)

    fun get(context: Context): DataStore<Preferences> = context.applicationContext.dataStore
}
