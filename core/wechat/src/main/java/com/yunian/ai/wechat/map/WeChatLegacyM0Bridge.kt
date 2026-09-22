package com.yunian.ai.wechat.map

import com.yunian.ai.wechat.wire.WireCdnMedia
import com.yunian.ai.wechat.wire.WireFileItem
import com.yunian.ai.wechat.wire.WireImageItem
import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireTextItem
import com.yunian.ai.wechat.wire.WireVideoItem
import com.yunian.ai.wechat.wire.WireVoiceItem
import com.yunian.ai.wechat.wire.WireWeChatMessage

object WeChatLegacyM0Bridge {

    fun fromFields(
        seq: Long? = null,
        messageId: Long? = null,
        fromUserId: String? = null,
        toUserId: String? = null,
        createTimeMs: Long? = null,
        sessionId: String? = null,
        messageType: Int? = null,
        messageState: Int? = null,
        contextToken: String? = null,
        items: List<LegacyItem> = emptyList(),
    ): WireWeChatMessage = WireWeChatMessage(
        seq = seq,
        messageId = messageId,
        fromUserId = fromUserId,
        toUserId = toUserId,
        createTimeMs = createTimeMs,
        sessionId = sessionId,
        messageType = messageType,
        messageState = messageState,
        itemList = items.map { it.toWireItem() },
        contextToken = contextToken,
    )

    data class LegacyItem(
        val type: Int,
        val text: String? = null,
        val imageEncryptQueryParam: String? = null,
        val imageAesKey: String? = null,
        val voiceEncryptQueryParam: String? = null,
        val voiceAesKey: String? = null,
        val fileName: String? = null,
        val fileEncryptQueryParam: String? = null,
        val fileAesKey: String? = null,
        val videoEncryptQueryParam: String? = null,
        val videoAesKey: String? = null,
        val thumbEncryptQueryParam: String? = null,
        val thumbAesKey: String? = null,
    )

    private fun LegacyItem.toWireItem(): WireMessageItem = WireMessageItem(
        type = type,
        textItem = text?.let { WireTextItem(text = it) },
        imageItem = if (imageEncryptQueryParam != null || imageAesKey != null) {
            WireImageItem(
                cdnImg = WireCdnMedia(
                    encryptQueryParam = imageEncryptQueryParam,
                    aesKey = imageAesKey,
                ),
            )
        } else null,
        voiceItem = if (voiceEncryptQueryParam != null || voiceAesKey != null) {
            WireVoiceItem(
                cdnVoice = WireCdnMedia(
                    encryptQueryParam = voiceEncryptQueryParam,
                    aesKey = voiceAesKey,
                ),
            )
        } else null,
        fileItem = if (fileName != null || fileEncryptQueryParam != null || fileAesKey != null) {
            WireFileItem(
                cdnFile = WireCdnMedia(
                    encryptQueryParam = fileEncryptQueryParam,
                    aesKey = fileAesKey,
                ),
                fileName = fileName,
            )
        } else null,
        videoItem = if (
            videoEncryptQueryParam != null || videoAesKey != null ||
            thumbEncryptQueryParam != null || thumbAesKey != null
        ) {
            WireVideoItem(
                cdnVideo = WireCdnMedia(
                    encryptQueryParam = videoEncryptQueryParam,
                    aesKey = videoAesKey,
                ),
                cdnThumb = WireCdnMedia(
                    encryptQueryParam = thumbEncryptQueryParam,
                    aesKey = thumbAesKey,
                ),
            )
        } else null,
    )
}
