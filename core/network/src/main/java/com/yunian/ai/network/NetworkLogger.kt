package com.yunian.ai.network

import com.yunian.ai.common.SecureLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class NetworkLogger : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()
        val requestId = generateRequestId()

        SecureLog.network("REQ", "[$requestId] ${request.method} ${request.url.encodedPath} host=${request.url.host}")

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - startTime
            SecureLog.network("ERR", "[$requestId] FAILED after ${elapsed}ms: ${e.message}")
            throw e
        }

        val elapsed = System.currentTimeMillis() - startTime
        val contentLength = response.body?.contentLength() ?: -1

        SecureLog.network("RESP", "[$requestId] HTTP ${response.code} in ${elapsed}ms, body=${contentLength}bytes")

        if (!response.isSuccessful) {
            val errorBody = response.peekBody(1024).string()
            SecureLog.network("ERR", "[$requestId] Error body: ${errorBody.take(200)}")
        }

        return response
    }

    private fun generateRequestId(): String {
        return (System.currentTimeMillis() % 10000).toString(36).uppercase()
    }
}
