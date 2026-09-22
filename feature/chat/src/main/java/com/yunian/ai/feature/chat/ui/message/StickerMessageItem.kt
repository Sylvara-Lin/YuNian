package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
fun StickerMessageItem(
    item: ChatListItem.StickerMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val message = item.message

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        drawBubble = false
    ) { _, _ ->
        StickerMessageContent(stickerName = item.stickerName)
    }
}
