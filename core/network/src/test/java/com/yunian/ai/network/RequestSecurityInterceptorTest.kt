package com.yunian.ai.network

import com.yunian.ai.security.SecurityState
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RequestSecurityInterceptorTest {

    @Test
    fun intercept_failsClosedWhenSecurityStateIsTampered() {
        SecurityState.resetForTest()
        SecurityState.markTampered("unit test tamper")
        val interceptor = RequestSecurityInterceptor(
            signer = RequestSecurityInterceptor.Signer { testSignature("abc123") }
        )
        val request = Request.Builder()
            .url("https://api.example.com/chat/completions")
            .get()
            .build()
        val chain = RecordingChain(request)

        val result = runCatching { interceptor.intercept(chain) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(false, chain.proceeded)
        SecurityState.resetForTest()
    }

    @Test
    fun intercept_failsClosedWhenSignerCannotProduceSignature() {
        val interceptor = RequestSecurityInterceptor(
            signer = RequestSecurityInterceptor.Signer { null }
        )
        val request = Request.Builder()
            .url("https://api.example.com/chat/completions")
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        val chain = RecordingChain(request)

        val result = runCatching { interceptor.intercept(chain) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(false, chain.proceeded)
    }

    @Test
    fun intercept_addsSignatureWhenSignerSucceeds() {
        val interceptor = RequestSecurityInterceptor(
            signer = RequestSecurityInterceptor.Signer { testSignature("abc123") }
        )
        val request = Request.Builder()
            .url("https://api.example.com/chat/completions")
            .get()
            .build()
        val chain = RecordingChain(request)

        interceptor.intercept(chain)

        assertTrue(chain.proceeded)
        assertEquals("abc123", chain.proceededRequest?.header("X-LianYu-Sig"))
    }

    @Test
    fun intercept_includesQueryStringInSignaturePayload() {
        val capturedPayload = mutableListOf<String>()
        val interceptor = RequestSecurityInterceptor(
            signer = RequestSecurityInterceptor.Signer {
                capturedPayload.add(String(it))
                testSignature("abc123")
            }
        )
        val request = Request.Builder()
            .url("https://api.example.com/chat/completions?prompt=hello&limit=1")
            .get()
            .build()
        val chain = RecordingChain(request)

        interceptor.intercept(chain)

        assertTrue(chain.proceeded)
        assertEquals(1, capturedPayload.size)
        val payload = capturedPayload.first().lines()
        assertEquals("v1", payload[0])
        assertEquals("GET", payload[1])
        assertEquals("/chat/completions?prompt=hello&limit=1", payload[2])
        assertEquals(8, payload.size)
    }

    @Test
    fun intercept_skipsSignatureWhenRequestDoesNotRequireSigning() {
        val interceptor = RequestSecurityInterceptor(
            signer = RequestSecurityInterceptor.Signer { null },
            shouldSignRequest = { false }
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .get()
            .build()
        val chain = RecordingChain(request)

        interceptor.intercept(chain)

        assertTrue(chain.proceeded)
        assertEquals(null, chain.proceededRequest?.header("X-LianYu-Sig"))
    }

    private fun testSignature(value: String = "abc123") =
        RequestSecurityInterceptor.RequestSignature(
            signature = value,
            keyId = "test-key",
            deviceId = "test-device"
        )

    private class RecordingChain(
        private val request: Request
    ) : Interceptor.Chain {
        var proceeded: Boolean = false
            private set
        var proceededRequest: Request? = null
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): okhttp3.Connection? = null
        override fun call(): okhttp3.Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }
}
