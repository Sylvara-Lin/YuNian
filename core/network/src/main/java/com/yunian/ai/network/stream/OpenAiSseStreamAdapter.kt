package com.yunian.ai.network.stream

import com.yunian.ai.domain.stream.AssistantStreamEvent
import com.yunian.ai.domain.timeline.TurnId
import com.yunian.ai.network.ResponsePostProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object OpenAiSseStreamAdapter {

    fun fromSseLineFlow(
        turnId: TurnId,
        lines: Flow<String>,
        startedAtMs: Long,
        reasoningFields: Collection<String> = OpenAiSseChunkParser.DEFAULT_REASONING_FIELDS,
        completedAtMs: (() -> Long)? = null,
        anchorMessageId: Long? = null,
        postProcessText: (String) -> String = { it },
    ): Flow<AssistantStreamEvent> = flow {
        emit(AssistantStreamEvent.TurnStarted(turnId = turnId, anchorMessageId = anchorMessageId))

        val reasoningBuf = StringBuilder()
        val contentBuf = StringBuilder()
        var sawDone = false
        var failed: String? = null

        lines.collect { line ->
            if (failed != null || sawDone) return@collect
            val data = OpenAiSseChunkParser.extractDataPayload(line) ?: return@collect
            val delta = OpenAiSseChunkParser.parseDataPayload(data, reasoningFields)

            if (delta.errorMessage != null) {
                failed = delta.errorMessage
                return@collect
            }
            if (delta.done) {
                sawDone = true
                return@collect
            }

            val r = delta.reasoning
            if (!r.isNullOrEmpty()) {
                reasoningBuf.append(r)
                emit(AssistantStreamEvent.ReasoningDelta(turnId = turnId, delta = r))
            }
            val c = delta.content
            if (!c.isNullOrEmpty()) {
                if (contentBuf.length > 50000) {
                    failed = "回复过长，已截断"
                    return@collect
                }
                contentBuf.append(c)
                emit(AssistantStreamEvent.TextDelta(turnId = turnId, delta = c))
            }
        }

        if (failed != null) {
            emit(AssistantStreamEvent.TurnFailed(turnId = turnId, message = failed!!))
            return@flow
        }

        val endMs = completedAtMs?.invoke() ?: System.currentTimeMillis()
        val durationMs = (endMs - startedAtMs).coerceAtLeast(1L)

        val rawContent = contentBuf.toString()
        val fieldReasoning = reasoningBuf.toString().trim().ifBlank { null }
        val (cleanedFromTags, tagReasoning) = if (rawContent.isNotBlank()) {
            ResponsePostProcessor.extractThinkingContent(rawContent)
        } else {
            "" to null
        }
        val reasoning = listOfNotNull(fieldReasoning, tagReasoning)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .ifBlank { null }

        if (!reasoning.isNullOrBlank()) {

            if (fieldReasoning.isNullOrBlank() && tagReasoning != null) {
                emit(AssistantStreamEvent.ReasoningDelta(turnId = turnId, delta = tagReasoning))
            }
            emit(
                AssistantStreamEvent.ReasoningCompleted(
                    turnId = turnId,
                    fullText = reasoning,
                    durationMs = durationMs,
                )
            )
        }

        val processed = postProcessText(cleanedFromTags)
        if (processed.isBlank() && reasoning.isNullOrBlank() && !sawDone && contentBuf.isEmpty() && reasoningBuf.isEmpty()) {
            emit(AssistantStreamEvent.TurnFailed(turnId = turnId, message = "API返回空内容，请检查模型名是否正确"))
            return@flow
        }
        if (processed.isBlank() && !reasoning.isNullOrBlank()) {
            emit(
                AssistantStreamEvent.TurnFailed(
                    turnId = turnId,
                    message = "模型仅返回了思考过程，未生成实际回复，请重试",
                )
            )
            return@flow
        }

        emit(
            AssistantStreamEvent.TextCompleted(
                turnId = turnId,
                fullText = processed,
                segmentIndex = 0,
            )
        )
        emit(AssistantStreamEvent.TurnCompleted(turnId = turnId))
    }

    fun fromSseLines(
        turnId: TurnId,
        lines: Sequence<String>,
        startedAtMs: Long,
        reasoningFields: Collection<String> = OpenAiSseChunkParser.DEFAULT_REASONING_FIELDS,
        completedAtMs: (() -> Long)? = null,
        anchorMessageId: Long? = null,
        postProcessText: (String) -> String = { it },
    ): Flow<AssistantStreamEvent> = fromSseLineFlow(
        turnId = turnId,
        lines = flow { lines.forEach { emit(it) } },
        startedAtMs = startedAtMs,
        reasoningFields = reasoningFields,
        completedAtMs = completedAtMs,
        anchorMessageId = anchorMessageId,
        postProcessText = postProcessText,
    )
}
