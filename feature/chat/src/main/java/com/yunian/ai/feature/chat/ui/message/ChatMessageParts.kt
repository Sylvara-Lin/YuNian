package com.yunian.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.yunian.ai.database.model.CompanionEntity as CompanionModel
import com.yunian.ai.uicommon.component.AppMessageAvatar
import com.yunian.ai.uicommon.component.AppMessageTimestamp
import com.yunian.ai.uicommon.component.formatAppMessageTime
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.AppTheme

val LocalCompanionAvatarClick = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalUserAvatarClick = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun ChatMessageAvatar(
    isMine: Boolean,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    adaptiveSizing: AdaptiveSizing
) {
    val companionAvatarClick = LocalCompanionAvatarClick.current
    val userAvatarClick = LocalUserAvatarClick.current
    val onAvatarClick = when {
        isMine -> userAvatarClick
        else -> companionAvatarClick
    }
    androidx.compose.foundation.layout.Box(
        modifier = if (onAvatarClick != null) {
            Modifier.clickable(onClick = onAvatarClick)
        } else Modifier
    ) {
        AppMessageAvatar(
            isMine = isMine,
            companionAvatarUrl = companionData?.avatarUrl,
            companionName = companionData?.name,
            userAvatarUrl = userAvatar,
            userName = userName,
            size = adaptiveSizing.avatarSize
        )
    }
}

@Composable
fun ChatMessageTimestamp(
    time: String,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    AppMessageTimestamp(
        time = time,
        isMine = isMine,
        style = AppTheme.typography.labelSmall.copy(fontSize = adaptiveSizing.fontSizeCaption.sp),
        color = AppTheme.colors.metadataContent
    )
}

fun formatChatMessageTime(timestamp: Long): String = formatAppMessageTime(timestamp)
