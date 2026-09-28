package com.yunian.ai.network.tts

import android.content.Context

interface TtsProviderInterface {
    suspend fun synthesize(context: Context, text: String, voiceId: String?): String?
    fun getVoices(): List<TtsVoice>

    suspend fun testConnection(context: Context): Boolean

    fun lastError(): String? = null
}
