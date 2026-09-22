package com.yunian.ai.feature.wechat.data

import com.yunian.ai.domain.wechat.WeChatInboundMessage
import com.yunian.ai.feature.wechat.data.model.M0
import com.yunian.ai.wechat.map.WeChatInboundMapper
import com.yunian.ai.wechat.map.WeChatLegacyM0Bridge

object M0WireAdapter {

    fun toInbound(message: M0): WeChatInboundMessage? {
        val wire = WeChatLegacyM0Bridge.fromFields(
            seq = message.seq,
            messageId = message.messageId,
            fromUserId = message.fromUserId,
            toUserId = message.toUserId,
            createTimeMs = message.createTimeMs,
            sessionId = message.sessionId,
            messageType = message.messageType,
            messageState = message.messageState,
            contextToken = message.contextToken,
            items = message.itemList.orEmpty().map { item ->
                WeChatLegacyM0Bridge.LegacyItem(
                    type = item.type,
                    text = item.textItem?.text,
                    imageEncryptQueryParam = item.imageItem?.cdnImg?.encryptQueryParam,
                    imageAesKey = item.imageItem?.cdnImg?.aesKey,
                    voiceEncryptQueryParam = item.voiceItem?.cdnVoice?.encryptQueryParam,
                    voiceAesKey = item.voiceItem?.cdnVoice?.aesKey,
                    fileName = item.fileItem?.fileName,
                    fileEncryptQueryParam = item.fileItem?.cdnFile?.encryptQueryParam,
                    fileAesKey = item.fileItem?.cdnFile?.aesKey,
                    videoEncryptQueryParam = item.videoItem?.cdnVideo?.encryptQueryParam,
                    videoAesKey = item.videoItem?.cdnVideo?.aesKey,
                    thumbEncryptQueryParam = item.videoItem?.cdnThumb?.encryptQueryParam,
                    thumbAesKey = item.videoItem?.cdnThumb?.aesKey,
                )
            },
        )
        return WeChatInboundMapper.toDomain(wire)
    }
}
