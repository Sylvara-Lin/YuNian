package com.yunian.ai.network

import com.yunian.ai.common.SecureLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 5000
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null

        for (attempt in 0..maxRetries) {
            try {
                val response = chain.proceed(request)

                if (response.isSuccessful || !shouldRetry(response)) {
                    if (attempt > 0) {
                        SecureLog.network("RETRY", "Request succeeded after ${attempt + 1} attempts")
                    }
                    return response
                }

                if (attempt < maxRetries) {
                    val delay = calculateDelay(attempt)
                    SecureLog.network("RETRY", "HTTP ${response.code}, will retry in ${delay}ms (attempt ${attempt + 1}/${maxRetries + 1})")
                    response.close()

                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Retry interrupted", lastException)
                    }
                } else {
                    SecureLog.network("RETRY", "Max retries reached, returning HTTP ${response.code}")
                    return response
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries && isRetryableError(e)) {
                    val delay = calculateDelay(attempt)
                    SecureLog.network("RETRY", "${e::class.simpleName}: ${e.message}, retrying in ${delay}ms (attempt ${attempt + 1}/${maxRetries + 1})")

                    try {
                        Thread.sleep(delay)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Retry interrupted", e)
                    }
                } else {
                    SecureLog.network("RETRY", "Non-retryable error or max retries reached: ${e.message}")
                    throw e
                }
            }
        }

        throw lastException ?: IOException("Unknown error after retries")
    }

    private fun shouldRetry(response: Response): Boolean {
        return when (response.code) {
            429,
            500,
            502,
            503,
            504
            -> true
            else -> false
        }
    }

    private fun isRetryableError(e: IOException): Boolean {
        return e is SocketTimeoutException ||
               e is UnknownHostException ||
               e.message?.contains("connection", ignoreCase = true) == true ||
               e.message?.contains("timeout", ignoreCase = true) == true ||
               e.message?.contains("reset", ignoreCase = true) == true
    }

    private fun calculateDelay(attempt: Int): Long {
        val exponential = initialDelayMs * (1 shl attempt)
        return minOf(exponential, maxDelayMs)
    }
}
