package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.domain.imagegen.ImageGenProtocol

/** Native 气泡与视觉回复共用的展示清洗，避免仅含生图指令的有效回复被丢弃。 */
internal object AgentReplyText {
    private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"

    fun forDisplay(raw: String, imageGenEnabled: Boolean): String {
        if (!imageGenEnabled) return raw
        val stripped = ImageGenProtocol.sanitizeForDisplay(raw)
        return when {
            stripped.isNotBlank() -> stripped
            ImageGenProtocol.isPromptOnly(raw) -> IMAGE_GEN_ONLY_REPLY_TEXT
            else -> raw
        }
    }
}
