package com.yunian.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.uicommon.model.ApiProviderInfo

@Stable
sealed interface ChatIntent {
    data class SendText(val content: String) : ChatIntent
    data class SendImage(val imagePath: String) : ChatIntent
    data class SendVideo(val videoPath: String) : ChatIntent
    data class SendVoice(val audioPath: String, val duration: Int) : ChatIntent
    data class SendSticker(val sticker: StickerInfo) : ChatIntent
    data object ShareLocation : ChatIntent
    data class SwitchApi(val provider: ApiProviderInfo) : ChatIntent
    data object LoadEarlier : ChatIntent
    data class QuoteReply(val message: ChatMessage) : ChatIntent
    data class CopyText(val text: String) : ChatIntent
    data class OpenMedia(val path: String, val mimeType: String) : ChatIntent

    /** 把本地图片保存进系统相册（AI 生图 / 表情包等） */
    data class SaveImage(val path: String) : ChatIntent
    data class NavigateToMessage(val messageId: Long) : ChatIntent
    data class Recall(val message: ChatMessage) : ChatIntent
    data class Regenerate(val message: ChatMessage) : ChatIntent
}
