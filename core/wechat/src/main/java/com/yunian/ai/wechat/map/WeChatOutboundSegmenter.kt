package com.yunian.ai.wechat.map

import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatOutboundRequest
import com.yunian.ai.domain.wechat.WeChatOutboundSegment
import com.yunian.ai.domain.wechat.WeChatDeliveryStatus
import java.util.UUID

object WeChatOutboundSegmenter {

    private val SENTENCE_ENDERS = setOf('。', '！', '？', '!', '?', '…')

    fun splitTextSimple(text: String): List<String> {
        if (text.isBlank()) return listOf("")
        val paragraphs = text
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(text)

        val sentences = mutableListOf<String>()
        for (paragraph in paragraphs) {
            sentences.addAll(splitIntoSentences(paragraph))
        }
        return softCapToThree(sentences)
    }

    private fun splitIntoSentences(paragraph: String): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        for (ch in paragraph) {
            buffer.append(ch)
            if (ch in SENTENCE_ENDERS) {
                val part = buffer.toString().trim()
                if (part.isNotEmpty()) result.add(part)
                buffer.clear()
            }
        }
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    private fun softCapToThree(sentences: List<String>): List<String> {
        if (sentences.size <= 3) return sentences
        val mergeCount = sentences.size - 3
        val head = sentences.take(mergeCount + 1).joinToString("")
        return listOf(head) + sentences.drop(mergeCount + 1)
    }

    fun expand(
        request: WeChatOutboundRequest,
        wechatUserId: String,
        rootId: String = UUID.randomUUID().toString(),
    ): List<WeChatOutboundSegment> {
        require(wechatUserId.isNotBlank()) { "wechatUserId blank" }
        val media = request.media
        if (media != null && media.kind != WeChatContentKind.TEXT) {
            return listOf(
                WeChatOutboundSegment(
                    outboxId = "$rootId#0",
                    wechatUserId = wechatUserId,
                    kind = media.kind,
                    text = request.text,
                    media = media,
                    segmentIndex = 0,
                    segmentCount = 1,
                    contextToken = request.contextToken,
                    status = WeChatDeliveryStatus.PENDING,
                ),
            )
        }
        val raw = request.text.orEmpty()
        if (raw.trim().isEmpty()) return emptyList()
        val segments = splitTextSimple(raw)
        return segments.mapIndexed { index, part ->
            WeChatOutboundSegment(
                outboxId = "$rootId#$index",
                wechatUserId = wechatUserId,
                kind = WeChatContentKind.TEXT,
                text = part,
                media = null,
                segmentIndex = index,
                segmentCount = segments.size,
                contextToken = request.contextToken,
                status = WeChatDeliveryStatus.PENDING,
            )
        }
    }
}
