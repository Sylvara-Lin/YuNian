package com.yunian.ai.feature.chat.ui.message
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.runtime.Composable
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.theme.AdaptiveSizing

@Composable
fun FileMessageItem(
    item: ChatListItem.FileMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean
) {
    AttachmentMessageItem(
        message = item.message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,
        icon = AppIcons.FileText,
        label = "文件"
    )
}
