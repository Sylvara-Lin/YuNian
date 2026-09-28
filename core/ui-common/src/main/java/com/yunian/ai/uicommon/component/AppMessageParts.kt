package com.yunian.ai.uicommon.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AppMessageAvatar(
    isMine: Boolean,
    companionAvatarUrl: String?,
    companionName: String?,
    userAvatarUrl: String?,
    userName: String,
    size: Dp
) {
    if (isMine) {
        UserAvatar(
            avatarUrl = userAvatarUrl,
            name = userName,
            size = size
        )
    } else {
        CompanionAvatar(
            avatarUrl = companionAvatarUrl,
            name = companionName,
            size = size
        )
    }
}

@Composable
fun AppMessageTimestamp(
    time: String,
    isMine: Boolean,
    style: TextStyle,
    color: Color
) {
    Text(
        text = time,
        style = style,
        color = color,
        textAlign = if (isMine) TextAlign.End else TextAlign.Start
    )
}

fun formatAppMessageTime(timestamp: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))