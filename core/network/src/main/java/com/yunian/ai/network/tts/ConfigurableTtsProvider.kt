package com.yunian.ai.network.tts

interface ConfigurableTtsProvider {
    fun updateConfig(config: TtsConfig)
}