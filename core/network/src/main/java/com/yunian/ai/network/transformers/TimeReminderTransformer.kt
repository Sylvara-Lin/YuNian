package com.yunian.ai.network.transformers

import com.yunian.ai.network.Message
import com.yunian.ai.network.transformers.TransformerContext
import java.text.SimpleDateFormat
import java.util.Locale

class TimeReminderTransformer : MessageTransformer {
    override val id = "time_reminder"
    override val isInput = true
    override val priority = 80

    companion object {
        const val DEFAULT_THRESHOLD_MS = 60 * 60 * 1000L
    }

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        if (context.isGroupChat) return messages

        val userMessages = messages.filter { it.role == "user" }.takeLast(2)
        if (userMessages.size < 2) return messages

        val latest = userMessages[1]
        val previous = userMessages[0]

        val latestTime = latest.timestamp ?: 0L
        val previousTime = previous.timestamp ?: 0L
        val gap = latestTime - previousTime

        if (gap < DEFAULT_THRESHOLD_MS) return messages

        val gapText = formatGap(gap)
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(java.util.Date(context.currentTimeMillis))

        val reminder = "<time_reminder>Current time: $currentTime. The previous message was sent $gapText ago.</time_reminder>"

        val insertIndex = messages.lastIndexOf(latest)
        if (insertIndex >= 0) {
            val result = messages.toMutableList()
            result.add(insertIndex, Message("system", reminder))
            return result
        }

        return messages
    }

    private fun formatGap(ms: Long): String {
        val seconds = ms / 1000L
        val minutes = seconds / 60L
        val hours = minutes / 60L
        val days = hours / 24L

        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""}"
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "$seconds second${if (seconds != 1L) "s" else ""}"
        }
    }
}
