package com.yunian.ai

sealed class AppStartupState {

    data object CriticalInit : AppStartupState()

    data class BackgroundInit(
        val criticalMs: Long,
        val tasksCompleted: Int = 0,
        val tasksTotal: Int = 0
    ) : AppStartupState()

    data class Ready(val totalMs: Long) : AppStartupState()

    data class Failed(val phase: String, val reason: String) : AppStartupState()
}
