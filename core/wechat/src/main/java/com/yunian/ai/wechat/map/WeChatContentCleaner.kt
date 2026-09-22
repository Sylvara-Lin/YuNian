package com.yunian.ai.wechat.map

object WeChatContentCleaner {

    private val STICKER_PATTERN = Regex("^\\[([^\\[\\]]+)\\]$")
    private val ROLE_PREFIX_REGEX = Regex("(?m)^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*")
    private val ENC_LEAK_REGEX = Regex("(?m)^enc:\\S+$")

    private val THINK_CLOSED_REGEX = Regex("(?is)<(?:think|thinking|thought|reflection|reasoning|LM_THINK|redacted_reasoning)[^>]*>[\\s\\S]*?</(?:think|thinking|thought|reflection|reasoning|LM_THINK|redacted_reasoning)\\s*>")
    private val THINK_UNCLOSED_REGEX = Regex("(?is)<(?:think|thinking|thought|reflection|reasoning|LM_THINK|redacted_reasoning)[^>]*>[\\s\\S]*$")

    fun isStickerContent(content: String): Boolean {
        return STICKER_PATTERN.matches(content.trim())
    }

    fun extractStickerName(content: String): String? {
        return STICKER_PATTERN.find(content.trim())?.groupValues?.get(1)
    }

    fun clean(raw: String): String {
        var text = raw
        text = ROLE_PREFIX_REGEX.replace(text, "")
        text = THINK_CLOSED_REGEX.replace(text, "")
        text = THINK_UNCLOSED_REGEX.replace(text, "")
        text = ENC_LEAK_REGEX.replace(text, "")
        val bracketRegex = Regex("\\[([^\\[\\]]+?)\\]")
        val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
        val cleanedStickerDescs = mutableSetOf<String>()
        text = bracketRegex.replace(text) { match: MatchResult ->
            val inner = match.groupValues[1].trim()
            if (inner in systemTags) {
                match.value
            } else {
                cleanedStickerDescs.add(inner)
                ""
            }
        }
        text = text.replace("]", "").replace("[", "")
        text = Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE).replace(text, "")
        for (desc in cleanedStickerDescs) {
            if (desc.length >= 2 && text.contains(desc)) {
                text = text.replace(desc, "")
            }
        }
        text = text.replace(Regex("(?<=[。！？])\\s*\\n+\\s*"), "")
        text = text.replace(Regex("(?<![。！？])\\s*\\n+\\s*"), "，")
        text = text.replace(Regex("，{2,}"), "，")
            .trim()
            .trimStart('，', ',', '.', '。', ' ')

        val sentences = text.split(Regex("(?<=[。！？])"))
        val deduped = mutableListOf<String>()
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            val currentClean = trimmed.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            var isDuplicate = false
            for (prev in deduped) {
                val prevClean = prev.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
                if (currentClean == prevClean ||
                    (currentClean.length >= 4 && prevClean.endsWith(currentClean)) ||
                    (prevClean.length >= 4 && currentClean.endsWith(prevClean))
                ) {
                    isDuplicate = true
                    break
                }
            }
            if (!isDuplicate) {
                deduped.add(sentence)
            }
        }
        var result = deduped.joinToString("").trim()

        for (len in result.length / 2 downTo 4) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.contains(suffix)) {
                result = beforeSuffix
                break
            }
            val suffixCleaned = suffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            val beforeSuffixCleaned = beforeSuffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            if (suffixCleaned.length >= 4 && beforeSuffixCleaned.endsWith(suffixCleaned)) {
                result = beforeSuffix
                break
            }
        }

        return result
    }
}
