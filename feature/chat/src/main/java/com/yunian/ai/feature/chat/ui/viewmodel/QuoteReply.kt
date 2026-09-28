package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType

data class QuoteReply(
    val messageId: Long,
    val authorName: String,
    val content: String,
    val mediaType: QuoteMediaType = QuoteMediaType.NONE,
    val mediaPath: String = ""
) {
    val previewText: String = when (mediaType) {
        QuoteMediaType.IMAGE -> "[图片]"
        QuoteMediaType.VIDEO -> "[视频]"
        QuoteMediaType.VOICE -> content.ifBlank { "[语音]" }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_QUOTE_PREVIEW_LENGTH)
        QuoteMediaType.FILE -> content.ifBlank { "[文件]" }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_QUOTE_PREVIEW_LENGTH)
        QuoteMediaType.NONE -> content
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_QUOTE_PREVIEW_LENGTH)
            .let { text ->

                if (isLikelyLocalMediaPath(text)) "[图片]" else text
            }
    }

    val hasMediaThumbnail: Boolean =
        mediaPath.isNotBlank() &&
            (mediaType == QuoteMediaType.IMAGE || mediaType == QuoteMediaType.VIDEO)
}

enum class QuoteMediaType(val wireName: String) {
    NONE(""),
    IMAGE("image"),
    VIDEO("video"),
    VOICE("voice"),
    FILE("file");

    companion object {
        fun fromWire(raw: String?): QuoteMediaType {
            return when (raw?.trim()?.lowercase()) {
                "image" -> IMAGE
                "video" -> VIDEO
                "voice", "audio" -> VOICE
                "file" -> FILE
                else -> NONE
            }
        }
    }
}

data class QuotedTextContent(
    val quote: QuoteReply?,
    val body: String
)

private const val QUOTE_PREFIX = "[引用回复]"
private const val QUOTE_SEPARATOR = "\n---\n"
private const val MEDIA_LINE_PREFIX = "[media]"
private const val MAX_QUOTE_PREVIEW_LENGTH = 80

fun ChatMessage.toQuoteReply(companionName: String?, userName: String): QuoteReply {
    val authorName = if (isFromUser) userName else companionName.orEmpty().ifBlank { "对方" }
    val mediaType = resolveQuoteMediaType()
    val mediaPath = resolveQuoteMediaPath(mediaType)
    return QuoteReply(
        messageId = id,
        authorName = authorName,
        content = quoteDisplayContent(mediaType),
        mediaType = mediaType,
        mediaPath = mediaPath
    )
}

fun encodeQuotedMessage(quote: QuoteReply, body: String): String {
    return buildString {
        append(QUOTE_PREFIX)
        append(quote.messageId)
        append("|")
        append(quote.authorName.sanitizeQuoteLine())
        append(": ")
        append(quote.previewText.sanitizeQuoteLine())
        if (quote.mediaType != QuoteMediaType.NONE && quote.mediaPath.isNotBlank()) {
            append('\n')
            append(MEDIA_LINE_PREFIX)
            append(quote.mediaType.wireName)
            append('|')
            append(quote.mediaPath.sanitizeQuoteLine())
        }
        append(QUOTE_SEPARATOR)
        append(body.trim())
    }
}

fun parseQuotedTextContent(content: String): QuotedTextContent {
    if (!content.startsWith(QUOTE_PREFIX)) return QuotedTextContent(quote = null, body = content)
    val separatorIndex = content.indexOf(QUOTE_SEPARATOR)
    if (separatorIndex <= QUOTE_PREFIX.length) return QuotedTextContent(quote = null, body = content)

    val quoteBlock = content.substring(QUOTE_PREFIX.length, separatorIndex).trim()
    val body = content.substring(separatorIndex + QUOTE_SEPARATOR.length).trim()
    val lines = quoteBlock.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return QuotedTextContent(quote = null, body = body)

    val quoteLine = lines.first().trim()
    val idParts = quoteLine.split("|", limit = 2)
    val messageId = if (idParts.size == 2) idParts[0].toLongOrNull() ?: 0 else 0
    val quoteText = if (idParts.size == 2) idParts[1] else quoteLine
    val parts = quoteText.split(": ", limit = 2)
    val authorName = parts.getOrNull(0).orEmpty().ifBlank { "引用" }
    var quoteContent = parts.getOrNull(1).orEmpty()

    var mediaType = QuoteMediaType.NONE
    var mediaPath = ""
    lines.drop(1).forEach { line ->
        val trimmed = line.trim()
        if (!trimmed.startsWith(MEDIA_LINE_PREFIX)) return@forEach
        val payload = trimmed.removePrefix(MEDIA_LINE_PREFIX)
        val mediaParts = payload.split("|", limit = 2)
        mediaType = QuoteMediaType.fromWire(mediaParts.getOrNull(0))
        mediaPath = mediaParts.getOrNull(1).orEmpty().trim()
    }

    if (mediaType == QuoteMediaType.NONE && isLikelyLocalMediaPath(quoteContent)) {
        mediaType = inferMediaTypeFromPath(quoteContent)
        mediaPath = quoteContent.trim()
        quoteContent = if (mediaType == QuoteMediaType.VIDEO) "[视频]" else "[图片]"
    }

    if (mediaType == QuoteMediaType.NONE) {
        mediaType = when {
            quoteContent == "[图片]" || quoteContent.startsWith("[图片]") -> QuoteMediaType.IMAGE
            quoteContent == "[视频]" || quoteContent.startsWith("[视频]") -> QuoteMediaType.VIDEO
            quoteContent.startsWith("[语音]") -> QuoteMediaType.VOICE
            quoteContent.startsWith("[文件]") -> QuoteMediaType.FILE
            else -> QuoteMediaType.NONE
        }
    }

    return QuotedTextContent(
        quote = QuoteReply(
            messageId = messageId,
            authorName = authorName,
            content = quoteContent,
            mediaType = mediaType,
            mediaPath = mediaPath
        ),
        body = body
    )
}

private fun ChatMessage.resolveQuoteMediaType(): QuoteMediaType {
    return when {
        type == MessageType.IMAGE -> QuoteMediaType.IMAGE
        type == MessageType.VIDEO || content.startsWith("[视频]") -> QuoteMediaType.VIDEO
        type == MessageType.VOICE ||
            type == MessageType.AUDIO ||
            content.startsWith("[语音]") -> QuoteMediaType.VOICE
        type == MessageType.FILE || content.startsWith("[文件]") -> QuoteMediaType.FILE
        isLikelyLocalMediaPath(content) -> inferMediaTypeFromPath(content)
        isLikelyLocalMediaPath(linkString) -> inferMediaTypeFromPath(linkString)
        else -> QuoteMediaType.NONE
    }
}

private fun ChatMessage.resolveQuoteMediaPath(mediaType: QuoteMediaType): String {
    if (mediaType != QuoteMediaType.IMAGE && mediaType != QuoteMediaType.VIDEO) return ""
    val fromLink = linkString.trim()
    if (fromLink.isNotBlank() && !fromLink.startsWith("[")) return fromLink
    val fromContent = content.trim()
    return if (isLikelyLocalMediaPath(fromContent)) fromContent else ""
}

private fun ChatMessage.quoteDisplayContent(mediaType: QuoteMediaType): String {
    return when (mediaType) {
        QuoteMediaType.IMAGE -> "[图片]"
        QuoteMediaType.VIDEO -> "[视频]"
        QuoteMediaType.VOICE -> {
            when {
                content.startsWith("[语音]") -> content

                !isFromUser && content.isNotBlank() ->
                    content.replace(Regex("\\s+"), " ").trim().take(MAX_QUOTE_PREVIEW_LENGTH)
                else -> "[语音]"
            }
        }
        QuoteMediaType.FILE -> {
            if (content.startsWith("[文件]")) content else "[文件]"
        }
        QuoteMediaType.NONE -> {
            when {
                content.startsWith("[") && content.endsWith("]") ->
                    "[表情] ${content.removeSurrounding("[", "]")}"
                else -> parseQuotedTextContent(content).body.ifBlank { content }
            }
        }
    }
}

internal fun isLikelyLocalMediaPath(text: String): Boolean {
    val value = text.trim()
    if (value.length < 4) return false
    if (value.startsWith("[") && value.endsWith("]")) return false
    if (value.startsWith("file://", ignoreCase = true)) return true
    if (value.startsWith("content://", ignoreCase = true)) return true
    if (value.startsWith("/")) return true

    if (value.length >= 3 && value[1] == ':' && (value[2] == '\\' || value[2] == '/')) return true
    return false
}

private fun inferMediaTypeFromPath(path: String): QuoteMediaType {
    val lower = path.trim().lowercase()
    val videoExt = listOf(".mp4", ".mov", ".mkv", ".webm", ".3gp", ".avi", ".m4v")
    return if (videoExt.any { lower.endsWith(it) }) QuoteMediaType.VIDEO else QuoteMediaType.IMAGE
}

private fun String.sanitizeQuoteLine(): String = replace("\n", " ").replace("\r", " ").trim()
