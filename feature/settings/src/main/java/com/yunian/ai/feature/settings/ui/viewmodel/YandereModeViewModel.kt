package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.YandereModeManager
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class YandereModeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = AppSettingsStore(application)
    private val manager: YandereModeManager =
        ServiceRegistry.getOrThrow(YandereModeManager::class.java)

    val isEnabled: StateFlow<Boolean> = settingsStore.yandereModeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val collectUsage: StateFlow<Boolean> = settingsStore.yandereModeUsageStatsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val collectInstalled: StateFlow<Boolean> = settingsStore.yandereModeInstalledAppsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val snapshot = manager.cacheSnapshot
    val isRefreshing = manager.isRefreshing

    fun canAccessUsageStats(): Boolean = manager.canAccessUsageStats()

    fun setEnabled(enabled: Boolean): Job = viewModelScope.launch {
        try {
            settingsStore.setYandereModeEnabled(enabled)
            if (enabled) {
                manager.start()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {

        }
    }

    fun setCollectUsage(enabled: Boolean): Job = viewModelScope.launch {
        settingsStore.setYandereModeUsageStats(enabled)
        if (isEnabled.value) {
            manager.requestRefresh(force = true)
        }
    }

    fun setCollectInstalled(enabled: Boolean): Job = viewModelScope.launch {
        settingsStore.setYandereModeInstalledApps(enabled)
        if (isEnabled.value) {
            manager.requestRefresh(force = true)
        }
    }

    fun requestRefresh(force: Boolean = true): Job = viewModelScope.launch {
        manager.requestRefresh(force = force)
    }
}
