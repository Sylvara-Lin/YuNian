package com.yunian.ai.uicommon.picker.ui
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yunian.ai.uicommon.picker.model.AlbumInfo
import com.yunian.ai.uicommon.theme.*

private val Accent = PinkPrimary
private val Bg = WeChatDarkBackground
private val SurfaceColor = WeChatDarkSurface
private val DividerColor = WeChatDarkDivider

@Composable
internal fun AlbumListSheet(
    viewModel: PickerViewModel,
    onAlbumSelected: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            PickerTopBar(
                modifier = Modifier.background(SurfaceColor),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        AppIcons.ChevronLeft,
                        "返回",
                        tint = WeChatDarkTextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Text(
                    "选择相册",
                    color = WeChatDarkTextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            when {
                pickerState.isAlbumsLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
                pickerState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            pickerState.error!!,
                            color = ErrorRed,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = pickerState.albums,
                            key = { it.bucketId }
                        ) { album ->
                            AlbumRow(
                                album = album,
                                isSelected = album.bucketId == pickerState.currentBucketId,
                                onClick = { onAlbumSelected(album.bucketId, album.displayName) }
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 78.dp, end = 16.dp)
                                    .height(0.5.dp)
                                    .background(DividerColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: AlbumInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {

    val checkScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "checkScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(
                if (isSelected) Accent.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WeChatDarkSurface),
            contentAlignment = Alignment.Center
        ) {
            if (album.coverUri != null) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = AppIcons.Image,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = WeChatDarkTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.displayName,
                color = if (isSelected) Accent else WeChatDarkTextPrimary,
                fontSize = 15.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "${album.count} 张照片",
                color = WeChatDarkTextSecondary,
                fontSize = 13.sp
            )
        }

        Box(
            modifier = Modifier
                .size(24.dp)
                .scale(checkScale),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
