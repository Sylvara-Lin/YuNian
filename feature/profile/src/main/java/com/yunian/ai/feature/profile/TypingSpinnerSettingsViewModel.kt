package com.yunian.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.AppSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TypingSpinnerSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppSettingsStore(application)

    val showTypingSpinner: StateFlow<Boolean> = store.showTypingSpinnerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setShowTypingSpinner(enabled: Boolean) {
        viewModelScope.launch { store.setShowTypingSpinner(enabled) }
    }
}
