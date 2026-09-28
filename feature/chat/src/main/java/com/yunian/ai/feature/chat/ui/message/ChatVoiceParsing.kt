package com.yunian.ai.feature.chat.ui.message

internal fun extractVoiceDuration(content: String): Int {
    val regex = Regex("\\[语音]\\s*(\\d+)[\"秒]")
    return regex.find(content)?.groupValues?.get(1)?.toIntOrNull() ?: 1
}
