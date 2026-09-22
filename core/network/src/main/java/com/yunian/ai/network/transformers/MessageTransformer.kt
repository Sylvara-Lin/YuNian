package com.yunian.ai.network.transformers

import com.yunian.ai.domain.PlaceholderProvider
import com.yunian.ai.network.Message

data class TransformerContext(

    val sessionId: Long,

    val isGroupChat: Boolean = false,

    val modelId: String? = null,

    val modelName: String? = null,

    val characterName: String? = null,

    val userNickname: String? = null,

    val placeholderProvider: PlaceholderProvider? = null,

    val currentTimeMillis: Long = System.currentTimeMillis(),

    val workspaceCwd: String? = null,

    val processingStatus: ((String) -> Unit)? = null,
)

interface MessageTransformer {

    val id: String

    val isInput: Boolean

    suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message>

    val priority: Int
}

suspend fun List<MessageTransformer>.runPipeline(
    context: TransformerContext,
    messages: List<Message>,
    isInput: Boolean
): List<Message> {
    var current = messages
    for (transformer in this.filter { it.isInput == isInput }.sortedByDescending { it.priority }) {
        current = transformer.transform(context, current)
    }
    return current
}
