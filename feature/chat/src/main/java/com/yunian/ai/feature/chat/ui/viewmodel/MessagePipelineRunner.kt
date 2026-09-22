package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.feature.chat.ui.viewmodel.ChatDebugLog

import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MessagePipelineRunner(
    private val onViolation: ((ContentFilter.ViolationLevel) -> Unit)? = null
) : MessagePipeline {

    private val _pipelineState = MutableStateFlow(MessagePipeline.PipelineState())
    override val pipelineState: StateFlow<MessagePipeline.PipelineState> = _pipelineState

    private val _queueDepth = MutableStateFlow(0)
    override val queueDepth: StateFlow<Int> = _queueDepth

    override suspend fun execute(input: MessagePipeline.PipelineInput): Boolean {
        val startTime = System.currentTimeMillis()
        _pipelineState.value = MessagePipeline.PipelineState(stage = MessagePipeline.Stage.VALIDATE)
        _queueDepth.value = maxOf(0, _queueDepth.value + 1)

        return try {

            ChatDebugLog.log("[Pipeline] ContentFilter skipped (disabled)")

            _pipelineState.value = MessagePipeline.PipelineState(
                stage = MessagePipeline.Stage.SEND,
                totalDurationMs = System.currentTimeMillis() - startTime
            )
            _queueDepth.value = maxOf(0, _queueDepth.value - 1)
            true

        } catch (e: kotlinx.coroutines.CancellationException) {

            _queueDepth.value = maxOf(0, _queueDepth.value - 1)
            throw e
        } catch (e: Throwable) {
            SecureLog.e("MessagePipeline", "[${_pipelineState.value.stage}] 失败", e)
            ChatDebugLog.log("[MessagePipeline] error at stage=${_pipelineState.value.stage}: ${e.javaClass.simpleName}: ${e.message}")
            _pipelineState.value = MessagePipeline.PipelineState(
                stage = _pipelineState.value.stage,
                error = e.message
            )
            _queueDepth.value = maxOf(0, _queueDepth.value - 1)
            false
        }
    }
}
