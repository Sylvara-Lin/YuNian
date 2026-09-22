package com.yunian.ai.network.transformers

import com.yunian.ai.domain.PlaceholderProvider
import com.yunian.ai.network.Message
import com.yunian.ai.network.transformers.TransformerContext

class PlaceholderTransformer : MessageTransformer {
    override val id = "placeholder_expansion"
    override val isInput = true
    override val priority = 90

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        val provider = context.placeholderProvider ?: return messages

        return messages.map { msg ->
            val originalContent = msg.content ?: ""
            val resolvedContent = provider.resolve(originalContent, context)
            if (resolvedContent == originalContent) msg else msg.copy(content = resolvedContent)
        }
    }
}

fun PlaceholderProvider.resolve(text: String, context: TransformerContext): String {
    var result = text

    val charName = context.characterName ?: resolve("{{char}}") ?: "角色"
    val userName = context.userNickname ?: resolve("{{user}}") ?: "用户"
    val curDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(context.currentTimeMillis))
    val curTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(context.currentTimeMillis))
    val modelId = context.modelId ?: resolve("{{model_id}}") ?: ""
    val modelName = context.modelName ?: resolve("{{model_name}}") ?: ""

    result = result
        .replace("{{char}}", charName).replace("{char}", charName)
        .replace("{{CHAR}}", charName).replace("{CHAR}", charName)
        .replace("{{user}}", userName).replace("{user}", userName)
        .replace("{{USER}}", userName).replace("{USER}", userName)
        .replace("{{cur_date}}", curDate).replace("{cur_date}", curDate)
        .replace("{{cur_time}}", curTime).replace("{cur_time}", curTime)
        .replace("{{model_id}}", modelId).replace("{model_id}", modelId)
        .replace("{{model_name}}", modelName).replace("{model_name}", modelName)

    val placeholderRegex = "\\{\\{([^}]+)\\}\\}|\\{([^}]+)\\}".toRegex()
    return placeholderRegex.replace(result) { matchResult ->
        val key = matchResult.groupValues[1]?.ifBlank { matchResult.groupValues[2] }?.lowercase() ?: ""
        resolve(key)?.let { it } ?: matchResult.value
    }
}
