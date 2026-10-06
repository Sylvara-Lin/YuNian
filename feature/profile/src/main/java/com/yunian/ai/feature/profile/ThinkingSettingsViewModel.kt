package com.yunian.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.ReasoningEffort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThinkingSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppSettingsStore(application)

    val showReasoning: StateFlow<Boolean> = store.showReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoCollapseReasoning: StateFlow<Boolean> = store.autoCollapseReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val sendReasoning: StateFlow<Boolean> = store.sendReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val responseField: StateFlow<String> = store.reasoningResponseFieldFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "reasoning_content")

    val requestField: StateFlow<String> = store.reasoningRequestFieldFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "reasoning_content")

    /** 模型思考程度 wire 值（off/low/medium/high，见 ReasoningEffort） */
    val reasoningEffort: StateFlow<String> = store.reasoningEffortFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReasoningEffort.OFF.wire)

    fun saveThinkingSettings(
        showReasoning: Boolean,
        autoCollapseReasoning: Boolean,
        responseField: String,
        requestField: String,
        sendReasoning: Boolean,
        reasoningEffort: String,
    ): Job = viewModelScope.launch {
        store.saveThinkingSettings(
            showReasoning = showReasoning,
            autoCollapseReasoning = autoCollapseReasoning,
            responseField = responseField,
            requestField = requestField,
            sendReasoning = sendReasoning
        )
        store.setReasoningEffort(reasoningEffort)
    }
}
