package com.yunian.ai.feature.chat.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.yunian.ai.feature.chat.ui.viewmodel.ChatListItem
import com.yunian.ai.uicommon.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TimeDividerItem(item: ChatListItem.TimeDivider) {
    val label = remember(item.timestamp) { formatTimeDividerLabel(item.timestamp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.dimens.timeDividerVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(AppTheme.dimens.timeDividerCornerRadius))
                .background(AppTheme.colors.dividerBackground)
                .padding(
                    horizontal = AppTheme.dimens.timeDividerHorizontalPadding,
                    vertical = AppTheme.dimens.timeDividerInnerVerticalPadding
                ),
            style = AppTheme.typography.labelSmall.copy(fontSize = AppTheme.dimens.timeDividerFontSize),
            color = AppTheme.colors.metadataContent,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatTimeDividerLabel(timestamp: Long): String {
    val zoneId = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDateTime()
    val today = LocalDate.now(zoneId)
    val timeText = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))

    return when (dateTime.toLocalDate()) {
        today -> timeText
        today.minusDays(1) -> "昨天 $timeText"
        else -> dateTime.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))
    }
}
