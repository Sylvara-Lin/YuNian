package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.uicommon.component.AppMessageScaffold
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
internal fun ChatMessageScaffold(
    message: ChatMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean = true,
    drawBubble: Boolean = true,
    onClick: (() -> Unit)? = null,

    enableFrameLongClick: Boolean = true,
    copyText: String? = null,
    content: @Composable (menuExpanded: Boolean, openMenu: () -> Unit) -> Unit
) {
    val isMine = message.isFromUser
    val time = remember(message.timestamp) { formatChatMessageTime(message.timestamp) }

    AppMessageScaffold(
        frame = { menuExpanded, onLongClickFromScaffold ->
            ChatMessageFrame(
                isMine = isMine,
                adaptiveSizing = adaptiveSizing,
                avatar = {
                    ChatMessageAvatar(
                        isMine = isMine,
                        companionData = companionData,
                        userAvatar = userAvatar,
                        userName = userName,
                        adaptiveSizing = adaptiveSizing
                    )
                },
                timestamp = {
                    ChatMessageTimestamp(
                        time = time,
                        isMine = isMine,
                        adaptiveSizing = adaptiveSizing
                    )
                },
                drawBubble = drawBubble,
                isDarkTheme = isDarkTheme,
                onClick = onClick,

                onLongClick = if (enableFrameLongClick) onLongClickFromScaffold else null,
                content = { content(menuExpanded, onLongClickFromScaffold) }
            )
        },
        menu = { expanded, onDismiss ->
            ChatMessageMenu(
                expanded = expanded,
                message = message,
                onDismiss = onDismiss,
                onIntent = onIntent,
                copyText = copyText
            )
        }
    )
}
