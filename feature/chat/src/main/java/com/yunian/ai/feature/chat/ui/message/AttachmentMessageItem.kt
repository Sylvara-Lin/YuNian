package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
internal fun AttachmentMessageItem(
    message: ChatMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    icon: ImageVector,
    label: String
) {
    val isMine = message.isFromUser
    val mediaPath = remember(message.linkString, message.content) {
        message.linkString.ifBlank { message.content }
    }
    val mimeType = remember(label) {
        when (label) {
            "视频" -> "video/*"
            else -> "*/*"
        }
    }

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,
        onClick = { onIntent(ChatIntent.OpenMedia(mediaPath, mimeType)) }
    ) { _, _ ->
        AttachmentMessageContent(
            icon = icon,
            label = label,
            isMine = isMine,
            adaptiveSizing = adaptiveSizing
        )
    }
}
