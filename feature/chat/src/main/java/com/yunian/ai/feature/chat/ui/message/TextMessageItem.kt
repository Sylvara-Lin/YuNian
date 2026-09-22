package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.feature.chat.ui.viewmodel.parseQuotedTextContent
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
fun TextMessageItem(
    item: ChatListItem.TextMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean
) {
    val message = item.message
    val isMine = message.isFromUser
    val displayContent = remember(message.content, isMine) {
        if (isMine) {
            message.content
        } else {
            message.content
                .replace(Regex("^\\s*\\[(?:角色\\d+|[^\\[\\]]+?)\\]\\s*"), "")
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                .replace(Regex("(?m)^enc:\\S+$"), "")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
        }
    }
    val quotedContent = remember(displayContent) { parseQuotedTextContent(displayContent) }

    if (quotedContent.body.isBlank()) return

    var selectedText by remember(quotedContent.body) { mutableStateOf("") }

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,

        enableFrameLongClick = false,
        copyText = selectedText.takeIf { it.isNotEmpty() }
    ) { menuExpanded, openMenu ->
        TextMessageContent(
            quotedContent = quotedContent,
            isMine = isMine,
            adaptiveSizing = adaptiveSizing,
            onIntent = onIntent,
            selectionActive = menuExpanded,
            onTextLongClick = openMenu,
            onSelectedTextChange = { selectedText = it }
        )
    }
}
