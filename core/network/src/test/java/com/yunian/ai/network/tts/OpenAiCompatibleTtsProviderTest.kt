package com.yunian.ai.network.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleTtsProviderTest {

    @Test
    fun publicHost_requiresHttps() {
        assertNull(OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://api.example.com/v1"))
    }

    @Test
    fun publicHost_https_isAccepted() {
        assertEquals(
            "https://api.example.com/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("https://api.example.com/v1")
        )
    }

    @Test
    fun publicHost_httpsWithPort_isPreserved() {
        assertEquals(
            "https://api.example.com:8443/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("https://api.example.com:8443/v1")
        )
    }

    @Test
    fun lanIp_http_isAccepted() {
        assertEquals(
            "http://192.168.5.180:9899/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl(
                "http://192.168.5.180:9899/v1/audio/speech"
            )
        )
    }

    @Test
    fun lanIp_http_baseUrl_isExpanded() {
        assertEquals(
            "http://192.168.5.180:9899/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://192.168.5.180:9899/v1")
        )
    }

    @Test
    fun lanIp_http_v1Audio_isExpanded() {
        assertEquals(
            "http://10.0.0.8:8080/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://10.0.0.8:8080/v1/audio")
        )
    }

    @Test
    fun localhost_http_isAccepted() {
        assertEquals(
            "http://localhost:5000/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://localhost:5000/v1")
        )
    }

    @Test
    fun loopbackIp_http_isAccepted() {
        assertEquals(
            "http://127.0.0.1:9899/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://127.0.0.1:9899/v1/audio/speech")
        )
    }

    @Test
    fun linkLocalIp_http_isAccepted() {
        assertEquals(
            "http://169.254.1.1:9899/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("http://169.254.1.1:9899/v1/audio/speech")
        )
    }

    @Test
    fun lanIp_https_isAccepted() {
        assertEquals(
            "https://192.168.5.180:9899/v1/audio/speech",
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("https://192.168.5.180:9899/v1/audio/speech")
        )
    }

    @Test
    fun blank_isRejected() {
        assertNull(OpenAiCompatibleTtsProvider.normalizeSpeechUrl(""))
        assertNull(OpenAiCompatibleTtsProvider.normalizeSpeechUrl("   "))
    }

    @Test
    fun unsupportedPath_isRejected() {
        assertNull(OpenAiCompatibleTtsProvider.normalizeSpeechUrl("https://api.example.com/other"))
    }

    @Test
    fun queryString_isRejected() {
        assertNull(
            OpenAiCompatibleTtsProvider.normalizeSpeechUrl("https://api.example.com/v1?key=1")
        )
    }

    @Test
    fun isPrivateHost_recognizesPrivateRanges() {
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("localhost"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("127.0.0.1"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("10.1.2.3"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("172.16.0.1"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("172.31.255.255"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("192.168.5.180"))
        assertTrue(OpenAiCompatibleTtsProvider.isPrivateHost("169.254.10.10"))
    }

    @Test
    fun isPrivateHost_rejectsPublic() {
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost("api.example.com"))
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost("8.8.8.8"))
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost("172.32.0.1"))
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost("11.0.0.1"))
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost("192.169.0.1"))
        assertFalse(OpenAiCompatibleTtsProvider.isPrivateHost(""))
    }

    @Test
    fun normalizeFormat_allowsKnownFormats() {
        assertEquals("wav", OpenAiCompatibleTtsProvider.normalizeFormat("wav"))
        assertEquals("mp3", OpenAiCompatibleTtsProvider.normalizeFormat("MP3"))
        assertEquals("pcm", OpenAiCompatibleTtsProvider.normalizeFormat("pcm"))
    }

    @Test
    fun normalizeFormat_fallsBackToMp3() {
        assertEquals("mp3", OpenAiCompatibleTtsProvider.normalizeFormat("ogg"))
        assertEquals("mp3", OpenAiCompatibleTtsProvider.normalizeFormat(""))
    }
}
