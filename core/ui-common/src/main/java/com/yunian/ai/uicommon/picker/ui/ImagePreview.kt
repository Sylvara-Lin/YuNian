package com.yunian.ai.uicommon.picker.ui
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.yunian.ai.uicommon.theme.*

private val Accent = PinkPrimary

@Composable
internal fun ImagePreview(
    viewModel: PickerViewModel,
    initialIndex: Int,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    val mediaList = viewModel.mediaList.collectAsState().value

    val itemCount = mediaList.size
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { itemCount })

    val currentItem = mediaList.getOrNull(pagerState.currentPage)

    var isZoomed by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(250),
        label = "controlsAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { controlsVisible = !controlsVisible }
        ) { page ->
            val item = mediaList.getOrNull(page)
            if (item != null) {
                val model = if (isZoomed) {
                    ImageRequest.Builder(LocalContext.current)
                        .data(item.uri)
                        .size(Size.ORIGINAL)
                        .crossfade(true)
                        .build()
                } else {
                    item.uri
                }

                AsyncImage(
                    model = model,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isZoomed) ContentScale.Fit else ContentScale.Fit
                )
            }
        }

        AnimatedVisibility(
            visible = controlsAlpha > 0.01f,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            PickerTopBar(
                modifier = Modifier
                    .alpha(controlsAlpha)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC000000),
                                Color(0x88000000),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        AppIcons.ArrowLeft,
                        "返回",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    "${pagerState.currentPage + 1} / $itemCount",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                if (pickerState.maxSelection > 1) {
                    TextButton(
                        onClick = onConfirm,
                        enabled = selectionMap.isNotEmpty()
                    ) {
                        val text = if (selectionMap.isNotEmpty())
                            "完成(${selectionMap.size})" else "完成"
                        Text(
                            text,
                            color = if (selectionMap.isNotEmpty()) Accent
                                else Color.White.copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (currentItem != null) {
                TextButton(
                    onClick = { isZoomed = !isZoomed },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isZoomed) Accent else Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    if (isZoomed) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        "原图",
                        fontSize = 14.sp,
                        fontWeight = if (isZoomed) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            if (pickerState.maxSelection > 1 && currentItem != null) {
                val currentId = currentItem.id
                val isCurrentSelected by remember(currentId) {
                    derivedStateOf { selectionMap.containsKey(currentId) }
                }
                val order by remember(currentId) {
                    derivedStateOf { selectionMap[currentId] }
                }

                val btnScale by animateFloatAsState(
                    targetValue = if (isCurrentSelected) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "selectBtnScale"
                )

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(btnScale)
                        .clip(CircleShape)
                        .background(
                            if (isCurrentSelected) Accent
                            else Color.White.copy(alpha = 0.25f)
                        )
                        .clickable { viewModel.toggleSelection(currentId) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentSelected && order != null) {
                        Text(
                            "$order",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
