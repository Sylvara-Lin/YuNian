package com.yunian.ai.network.tts

object TtsTextCleaner {

    private val TAG_REMOVE = Regex("<[a-z_]+:?[^>]*>")

    private val PARENTHESES_REMOVE = Regex("(<[\\s\\S]*?>|\\([^\\)]*?\\)|（[^）]*?）)")

    private val TTS_BRACKET_REMOVE = Regex("\\[[^\\]]+\\]")

    private val TTS_MARKDOWN_REMOVE = Regex("[*_~]")

    fun clean(text: String, skipParentheses: Boolean): String {
        var s = text

        s = TAG_REMOVE.replace(s, "")

        if (skipParentheses) {
            s = PARENTHESES_REMOVE.replace(s, "")
        }

        s = TTS_BRACKET_REMOVE.replace(s, "")

        s = TTS_MARKDOWN_REMOVE.replace(s, "")
        return s.trim()
    }
}
