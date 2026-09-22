package com.yunian.ai.network

import okhttp3.logging.HttpLoggingInterceptor

internal class RedactingLogger : HttpLoggingInterceptor.Logger {
    private val sensitiveHeaders = setOf(
        "Authorization", "authorization",
        "x-api-key", "X-Api-Key", "X-API-KEY"
    )
    private val bodyMaxLength = 80

    override fun log(message: String) {
        val sanitized = sanitize(message)
        android.util.Log.d("OkHttp", sanitized)
    }

    private fun sanitize(message: String): String {
        var result = message

        for (header in sensitiveHeaders) {
            result = result.replace(
                Regex("($header:\\s*).*", RegexOption.IGNORE_CASE),
                "$1[REDACTED]"
            )
        }

        if (result.length > bodyMaxLength + 20) {
            result = result.take(bodyMaxLength) + "...[truncated]"
        }

        return result
    }
}
