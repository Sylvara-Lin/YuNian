package com.yunian.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable

import kotlinx.coroutines.flow.StateFlow

interface MessagePipeline {

    data class PipelineInput(
        val rawText: String,
        val companionId: Long,
        val isVision: Boolean = false,
        val imageUri: String? = null
    )

    @Stable
data class PipelineState(
        val stage: Stage = Stage.IDLE,
        val stageDurationMs: Long = 0,
        val totalDurationMs: Long = 0,
        val error: String? = null
    )

    enum class Stage {
        IDLE,

        VALIDATE,

        CLASSIFY,

        ENCRYPT,

        SEND,

        CONFIRM,
        DONE
    }

    val pipelineState: StateFlow<PipelineState>

    val queueDepth: StateFlow<Int>

    suspend fun execute(input: PipelineInput): Boolean
}
