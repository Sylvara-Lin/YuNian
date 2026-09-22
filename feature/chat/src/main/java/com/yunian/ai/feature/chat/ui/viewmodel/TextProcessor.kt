package com.yunian.ai.feature.chat.ui.viewmodel

import android.util.Log
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.StickerReservedNames

object TextProcessor {

    private val ROLE_PREFIX_REGEX = Regex("(?m)^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*")
    private val ENC_REGEX = Regex("(?m)^enc:\\S+$")
    private val STICKER_REGEX = Regex("\\[([^\\[\\]]+?)\\]")
    private val STICKER_FILE_REGEX = Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE)
    private val MULTI_NEWLINE_REGEX = Regex("\\n{2,}")
    private val SYSTEM_TAGS = StickerReservedNames.SYSTEM_TAGS

    fun removeLocalRepetition(text: String): String {
        if (text.length < 4) return text
        var result = text

        for (len in result.length / 2 downTo 2) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)
            if (beforeSuffix.endsWith(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        for (len in result.length / 2 downTo 4) {
            val suffix = result.takeLast(len)
            val beforeSuffix = result.dropLast(len)

            if (beforeSuffix.contains(suffix)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }

            val suffixCleaned = suffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            val beforeSuffixCleaned = beforeSuffix.trimEnd('。', '！', '？', '，', '.', '!', '?', ',', ' ')
            if (suffixCleaned.length >= 4 && beforeSuffixCleaned.endsWith(suffixCleaned)) {
                result = beforeSuffix
                return removeLocalRepetition(result)
            }
        }

        val sentenceDelimiters = Regex("(?<=[。！？.!?])")
        val sentences = result.split(sentenceDelimiters)
        if (sentences.size >= 2) {
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
                    deduped.add(trimmed)
                }
            }
            val joined = deduped.joinToString("")
            if (joined.length < result.length) {
                result = joined
                return removeLocalRepetition(result)
            }
        }

        return result
    }

    private fun cleanAiResponseText(text: String): String {
        val originalLength = text.length

        var cleaned = com.yunian.ai.network.ResponsePostProcessor.stripThinkingContent(text)
        cleaned = ROLE_PREFIX_REGEX.replace(cleaned, "")
        cleaned = ENC_REGEX.replace(cleaned, "").trim()

        val sb = StringBuilder(cleaned.length)
        var i = 0
        while (i < cleaned.length) {
            when (cleaned[i]) {
                '[', '【', '{', '<' -> {
                    val closeChar = when (cleaned[i]) {
                        '[' -> ']'
                        '【' -> '】'
                        '{' -> '}'
                        '<' -> '>'
                        else -> null
                    }
                    if (closeChar != null) {
                        val closeIdx = cleaned.indexOf(closeChar, i)
                        if (closeIdx >= 0 && closeIdx - i <= 50) {
                            i = closeIdx + 1
                            continue
                        }

                    }
                    sb.append(cleaned[i])
                    i++
                    continue
                }
                ']', '】', '}', '>' -> {

                    sb.append(cleaned[i])
                    i++
                    continue
                }
                else -> {
                    sb.append(cleaned[i])
                    i++
                }
            }
        }

        var result = sb.toString().trim()
        result = STICKER_FILE_REGEX.replace(result, "")
        result = MULTI_NEWLINE_REGEX.replace(result, "\n")

        val cleanedLength = result.length
        if (cleanedLength < 2 && originalLength > 5) {
            SecureLog.w("TextProcessor", "Aggressive cleaning detected: $originalLength → $cleanedLength chars, applying conservative cleanup")

            result = com.yunian.ai.network.ResponsePostProcessor.stripThinkingContent(text)
            result = ROLE_PREFIX_REGEX.replace(result, "")
            result = ENC_REGEX.replace(result, "").trim()
            if (result.length < 2 || com.yunian.ai.network.ResponsePostProcessor.looksLikeThinkingLeak(result)) {
                result = ""
            }
        }

        return result
    }

    private suspend fun clearLeakedStickerDescription(
        cleanText: String,
        sentStickers: List<StickerInfo>,
        stickerManager: StickerManager
    ): String {
        if (sentStickers.isEmpty() || cleanText.isBlank()) return cleanText
        if (cleanText.length > 20) return cleanText

        val allStickers = stickerManager.getAllStickers()
        val leaked = allStickers.any { s ->
            val desc = s.description
            val name = s.name
            (desc != null && desc.equals(cleanText, ignoreCase = true)) ||
                    name.equals(cleanText, ignoreCase = true)
        }
        return if (leaked) {
            SecureLog.d("TextProcessor", "Cleared leaked sticker description: $cleanText")
            ""
        } else cleanText
    }

    suspend fun processStickerTagsForSplit(
        text: String,
        stickerManager: StickerManager,
        stickerProbability: Int,
        sendStickerMessage: suspend (StickerInfo) -> Long
    ): String {
        val sentStickers = mutableListOf<StickerInfo>()

        val matches = STICKER_REGEX.findAll(text).toList()
        for (match in matches) {
            val desc = match.groupValues[1].trim()
            if (desc in SYSTEM_TAGS) continue
            // P6 增强：精确 → 别名 → 模糊 三级回退；未命中仍静默去标签（不产生文字泄漏）
            val sticker = stickerManager.findStickerByDescriptionExact(desc)
                ?: stickerManager.findStickerByAliases(desc)
                ?: stickerManager.findStickerByDescription(desc)
            if (sticker != null) {
                sendStickerMessage(sticker)
                sentStickers.add(sticker)
            }
        }

        var cleanText = cleanAiResponseText(text)

        cleanText = removeSentStickerResiduals(cleanText, sentStickers)

        if (sentStickers.isEmpty() && stickerProbability > 0 && cleanText.isNotBlank()) {
            val allRules = stickerManager.getAllRules()
            if (allRules.isNotEmpty()) {
                val random = kotlin.random.Random.nextInt(1, 101)
                if (random <= stickerProbability) {
                    val randomRule = allRules.random()
                    val sticker = stickerManager.findStickerByDescription(randomRule.description)
                    if (sticker != null) {
                        sendStickerMessage(sticker)
                        sentStickers.add(sticker)
                        val descToRemove = if (cleanText.contains(randomRule.description)) {
                            randomRule.description
                        } else {
                            sticker.description ?: sticker.name
                        }

                        if (descToRemove.length >= 2 && cleanText.length > descToRemove.length + 1) {
                            cleanText = cleanText.replace(descToRemove, "")
                            SecureLog.d("TextProcessor", "Removed sticker desc from split text (prob mode): $descToRemove")
                        } else {
                            SecureLog.d("TextProcessor", "Skipping desc removal: would leave text too short (${cleanText.length} - ${descToRemove.length})")
                        }
                        SecureLog.d("TextProcessor", "Auto-sent sticker by probability in split mode: ${sticker.name} ($stickerProbability%)")
                    }
                }
            }
        }

        cleanText = cleanText.trim()
        cleanText = removeLocalRepetition(cleanText)
        cleanText = clearLeakedStickerDescription(cleanText, sentStickers, stickerManager)

        if (cleanText.isBlank() && text.trim().isNotBlank()) {
            SecureLog.w("TextProcessor", "WARNING: Text processing resulted in blank output. Original: '${text.take(50)}', stickers sent: ${sentStickers.size}")
        }

        return cleanText
    }

    suspend fun processStickerTags(
        text: String,
        stickerManager: StickerManager,
        stickerProbability: Int,
        stickerSentThisTurn: Boolean,
        sendStickerMessage: suspend (StickerInfo) -> Long
    ): String {
        val sentStickers = mutableListOf<StickerInfo>()

        val matches = STICKER_REGEX.findAll(text).toList()

        Log.i("TextProcessor", "=== STICKER PROCESSING ===")
        Log.i("TextProcessor", "Input text: $text")
        Log.i("TextProcessor", "Found ${matches.size} bracket matches: ${matches.map { it.value }}")

        val stickerMatches = matches.filter { match ->
            val desc = match.groupValues[1].trim()
            desc !in SYSTEM_TAGS
        }

        Log.i("TextProcessor", "After filtering system tags: ${stickerMatches.map { it.value }}")

        for (match in stickerMatches) {
            val description = match.groupValues[1].trim()
            Log.i("TextProcessor", "Looking for sticker: [$description]")
            // P6 增强：精确 → 别名 → 模糊 三级回退；未命中仍静默去标签（不产生文字泄漏）
            val sticker = stickerManager.findStickerByDescriptionExact(description)
                ?: stickerManager.findStickerByAliases(description)
                ?: stickerManager.findStickerByDescription(description)
            if (sticker != null) {
                Log.i("TextProcessor", "Found sticker: ${sticker.name} at ${sticker.path}")
                sendStickerMessage(sticker)
                sentStickers.add(sticker)
            } else {
                Log.w("TextProcessor", "Sticker not found: [$description], removing tag")
            }
        }
        Log.i("TextProcessor", "=== END STICKER PROCESSING ===")

        var cleanText = cleanAiResponseText(text)

        cleanText = removeSentStickerResiduals(cleanText, sentStickers)

        if (sentStickers.isEmpty() && !stickerSentThisTurn && stickerProbability > 0 && cleanText.isNotBlank()) {
            val allRules = stickerManager.getAllRules()
            if (allRules.isNotEmpty()) {
                val matchedStickers = mutableListOf<Pair<StickerInfo, String>>()
                for (rule in allRules.shuffled()) {
                    val desc = rule.description
                    if (desc.length >= 2 && text.contains(desc)) {
                        val sticker = stickerManager.findStickerByDescription(desc)
                        if (sticker != null) matchedStickers.add(sticker to desc)
                    }
                }
                if (matchedStickers.isNotEmpty()) {
                    val (picked, matchedDesc) = matchedStickers.random()
                    sendStickerMessage(picked)
                    sentStickers.add(picked)
                    val descToRemove = if (cleanText.contains(matchedDesc)) matchedDesc else (picked.description ?: picked.name)
                    if (descToRemove.length >= 2) {
                        cleanText = cleanText.replace(descToRemove, "")
                        SecureLog.d("TextProcessor", "Removed sticker desc from text: $descToRemove (matched: $matchedDesc)")
                    }
                    cleanText = removeLocalRepetition(cleanText)
                    SecureLog.d("TextProcessor", "Matched sticker from text: ${picked.name}")
                } else {
                    val random = kotlin.random.Random.nextInt(1, 101)
                    if (random <= stickerProbability) {
                        val allStickers = stickerManager.getAllStickers()
                        if (allStickers.isNotEmpty()) {
                            val randomSticker = allStickers.random()
                            sendStickerMessage(randomSticker)
                            sentStickers.add(randomSticker)
                            val descToRemove = randomSticker.description ?: randomSticker.name
                            if (descToRemove.length >= 2) {
                                cleanText = cleanText.replace(descToRemove, "")
                                SecureLog.d("TextProcessor", "Removed random sticker desc from text: $descToRemove")
                            }
                        }
                    }
                }
            }
        }

        cleanText = cleanText.trim().replace(Regex("\\n{2,}"), "\n")
        cleanText = removeLocalRepetition(cleanText)
        cleanText = clearLeakedStickerDescription(cleanText, sentStickers, stickerManager)
        return cleanText
    }

    private fun removeSentStickerResiduals(
        text: String,
        sentStickers: List<StickerInfo>
    ): String {
        if (sentStickers.isEmpty()) return text
        var result = text
        val descsToRemove = mutableSetOf<String>()
        for (sticker in sentStickers) {
            sticker.description?.takeIf { it.length >= 2 }?.let { descsToRemove.add(it) }
            sticker.name.takeIf { it.length >= 2 }?.let { descsToRemove.add(it) }
        }
        for (desc in descsToRemove) {
            if (result.contains(desc)) {
                result = result.replace(desc, "")
                SecureLog.d("TextProcessor", "Removed residual sticker text: $desc")
            }
        }
        return result
    }
}
