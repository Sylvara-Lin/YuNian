package com.yunian.ai.uicommon.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun CompanionAvatar(
    avatarUrl: String?,
    name: String?,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            val initial = name?.firstOrNull()?.toString() ?: "?"
            Text(
                text = initial,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}

@Composable
fun UserAvatar(
    avatarUrl: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = name.firstOrNull()?.toString() ?: "?",
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
