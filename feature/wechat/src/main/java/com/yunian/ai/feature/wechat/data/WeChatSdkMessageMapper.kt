package com.yunian.ai.feature.wechat.data

import com.yunian.ai.wechat.wire.WireCdnMedia
import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireWeChatMessage
import com.yunian.ai.feature.wechat.data.model.M7
import com.yunian.ai.feature.wechat.data.model.M5
import com.yunian.ai.feature.wechat.data.model.M3
import com.yunian.ai.feature.wechat.data.model.M1
import com.yunian.ai.feature.wechat.data.model.M2
import com.yunian.ai.feature.wechat.data.model.M6
import com.yunian.ai.feature.wechat.data.model.M4
import com.yunian.ai.feature.wechat.data.model.M0

object WeChatSdkMessageMapper {
    fun toAppMessage(message: WireWeChatMessage): M0 {
        return message.toAppMessageInternal()
    }

    private fun WireWeChatMessage.toAppMessageInternal(): M0 {
        return M0(
            seq = seq,
            messageId = messageId,
            fromUserId = fromUserId,
            toUserId = toUserId,
            createTimeMs = createTimeMs,
            messageType = messageType,
            itemList = itemList?.map { it.toAppItem() },
            contextToken = contextToken,
        )
    }

    private fun WireMessageItem.toAppItem(): M1 {
        return M1(
            type = type,
            textItem = textItem?.let { M2(text = it.text.orEmpty()) },
            imageItem = imageItem?.let { M3(cdnImg = it.cdnImg?.toAppMedia()) },
            voiceItem = voiceItem?.let { M4(cdnVoice = it.cdnVoice?.toAppMedia()) },
            fileItem = fileItem?.let {
                M5(
                    cdnFile = it.cdnFile?.toAppMedia(),
                    fileName = it.fileName,
                )
            },
            videoItem = videoItem?.let {
                M6(
                    cdnVideo = it.cdnVideo?.toAppMedia(),
                    cdnThumb = it.cdnThumb?.toAppMedia(),
                )
            },
        )
    }

    private fun WireCdnMedia.toAppMedia(): M7 {
        return M7(
            encryptQueryParam = encryptQueryParam,
            aesKey = aesKey,
        )
    }
}
