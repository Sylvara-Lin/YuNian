package com.yunian.ai.network.tts

import android.content.Context
import com.yunian.ai.common.SecureLog

enum class ChatTtsMode(val displayName: String, val description: String) {
    SILENT("静音", "只显示文字，不生成语音"),
    VOICE_BAR("语音条", "单条消息同时显示语音条和文字，点击播放"),
    @Deprecated("已移除自动朗读，保留 ordinal 兼容旧配置")
    READ_ALOUD("语音朗读(已停用)", "已改为语音条入库，不再自动朗读");

    companion object {

        val selectableModes: List<ChatTtsMode> = listOf(SILENT, VOICE_BAR)

        fun fromOrdinalSafe(value: Int): ChatTtsMode {
            val raw = entries.elementAtOrNull(value) ?: SILENT

            return if (raw == READ_ALOUD) SILENT else raw
        }
    }
}

data class ChatTtsConfig(
    val mode: ChatTtsMode = ChatTtsMode.SILENT,
    val skipParentheses: Boolean = false,
    val autoDedup: Boolean = true,
    val beautify: Boolean = true
) {
    companion object {
        private const val PREFS_NAME = "tts_settings"
        private const val KEY_MODE = "chat_tts_mode"
        private const val KEY_SKIP_PARENTHESES = "key_skip_parentheses"
        private const val KEY_AUTO_DEDUP = "chat_tts_auto_dedup"
        private const val KEY_BEAUTIFY = "chat_tts_beautify"

        fun fromSharedPreferences(context: Context): ChatTtsConfig {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ChatTtsConfig(
                    mode = ChatTtsMode.fromOrdinalSafe(prefs.getInt(KEY_MODE, 0)),
                    skipParentheses = prefs.getBoolean(KEY_SKIP_PARENTHESES, false),
                    autoDedup = prefs.getBoolean(KEY_AUTO_DEDUP, true),
                    beautify = prefs.getBoolean(KEY_BEAUTIFY, true)
                )
            } catch (e: Exception) {
                SecureLog.e("ChatTtsConfig", "Failed to read prefs, using defaults", e)
                ChatTtsConfig()
            }
        }

        fun saveToSharedPreferences(context: Context, config: ChatTtsConfig) {
            try {

                val modeToSave = when (config.mode) {
                    ChatTtsMode.VOICE_BAR -> ChatTtsMode.VOICE_BAR
                    else -> ChatTtsMode.SILENT
                }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putInt(KEY_MODE, modeToSave.ordinal)
                    putBoolean(KEY_SKIP_PARENTHESES, config.skipParentheses)
                    putBoolean(KEY_AUTO_DEDUP, config.autoDedup)
                    putBoolean(KEY_BEAUTIFY, config.beautify)
                    apply()
                }
            } catch (e: Exception) {
                SecureLog.e("ChatTtsConfig", "Failed to save prefs", e)
            }
        }
    }
}
