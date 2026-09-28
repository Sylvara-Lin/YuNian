package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.yunian.ai.common.FrameRateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FrameRateViewModel(application: Application) : AndroidViewModel(application) {
    private val _frameRate = MutableStateFlow(
        FrameRateManager.getSavedFrameRate(application)
    )
    val frameRate: StateFlow<FrameRateManager.FrameRate> = _frameRate.asStateFlow()

    fun setFrameRate(rate: FrameRateManager.FrameRate) {
        if (rate == _frameRate.value) return
        _frameRate.value = rate
        FrameRateManager.saveFrameRate(getApplication(), rate)
    }
}
