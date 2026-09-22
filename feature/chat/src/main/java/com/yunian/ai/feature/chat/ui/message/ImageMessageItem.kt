package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
fun ImageMessageItem(
    item: ChatListItem.ImageMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val message = item.message
    val isMine = message.isFromUser
    val imageFile = remember(message.linkString, message.content) {
        java.io.File(message.linkString.ifBlank { message.content })
    }

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        drawBubble = false,
        onClick = { onIntent(ChatIntent.OpenMedia(imageFile.absolutePath, "image/*")) }
    ) { _, _ ->
        ImageMessageContent(
            imageFile = imageFile,
            isMine = isMine,
            adaptiveSizing = adaptiveSizing
        )
    }
}
