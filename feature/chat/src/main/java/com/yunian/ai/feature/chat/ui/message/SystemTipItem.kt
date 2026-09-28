package com.yunian.ai.feature.chat.ui.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.theme.AppTheme

@Composable
fun SystemTipItem(item: ChatListItem.SystemTip) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AppTheme.dimens.systemTipHorizontalPadding,
                vertical = AppTheme.dimens.systemTipVerticalPadding
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = item.content,
            style = AppTheme.typography.bodySmall.copy(fontSize = AppTheme.dimens.systemTipFontSize),
            color = AppTheme.colors.captionContent,
            textAlign = TextAlign.Center
        )
    }
}
