package com.yunian.ai.common

object AiResponseCleaner {

    private val RESPONSE_PREFIX_REGEX = Regex("(?i)^\\s*response(?![a-z0-9_])[\\s:：]*")

    fun stripResponsePrefix(text: String): String {
        if (text.isBlank()) return text
        val stripped = text.replace(RESPONSE_PREFIX_REGEX, "").trim()
        return stripped.ifEmpty { text.trim() }
    }
}
