package com.yunian.ai.uicommon.picker.ui
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.yunian.ai.uicommon.picker.model.MediaItem
import com.yunian.ai.uicommon.theme.PinkPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkBackground
import com.yunian.ai.uicommon.theme.WeChatDarkSurface
import com.yunian.ai.uicommon.theme.WeChatDarkTextPrimary
import com.yunian.ai.uicommon.theme.WeChatDarkTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Accent = PinkPrimary
private val Bg = WeChatDarkBackground
private val SurfaceColor = WeChatDarkSurface

private val dateFormat = SimpleDateFormat("yyyy年M月", Locale.getDefault())
private fun formatDate(ts: Long): String = dateFormat.format(Date(ts * 1000))

@Composable
internal fun ImageGrid(
    viewModel: PickerViewModel,
    onShowAlbums: () -> Unit,
    onItemClick: (Long) -> Unit,
    onItemPreview: (Int) -> Unit,
    onToggleSelection: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    hasSelection: Boolean,
    maxSelection: Int
) {
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    val mediaList by viewModel.mediaList.collectAsState()
    val selectedItems = remember(selectionMap, mediaList) {
        selectionMap.entries
            .sortedBy { it.value }
            .mapNotNull { entry -> mediaList.firstOrNull { it.id == entry.key } }
    }

    val gridState = rememberLazyGridState()
    val totalCount = mediaList.size
    val dragScope = rememberCoroutineScope()

    var isDraggingScrollbar by remember { mutableStateOf(false) }
    var showDateLabel by remember { mutableStateOf(false) }

    LaunchedEffect(pickerState.currentBucketId) {
        gridState.scrollToItem(0)
    }

    val firstVisibleDate by remember(totalCount) {
        derivedStateOf {
            val idx = gridState.firstVisibleItemIndex
            mediaList.getOrNull(idx)?.let { formatDate(it.dateAdded) }.orEmpty()
        }
    }

    var dragProgress by remember { mutableFloatStateOf(0f) }
    val scrollProgress by remember {
        derivedStateOf {
            if (totalCount == 0) 0f
            else {
                val idx = gridState.firstVisibleItemIndex
                val off = gridState.firstVisibleItemScrollOffset
                val itemH = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 1
                val smoothIdx = idx.toFloat() - off.toFloat() / itemH.toFloat().coerceAtLeast(1f)
                (smoothIdx / totalCount).coerceIn(0f, 1f)
            }
        }
    }

    val displayProgress = if (isDraggingScrollbar) dragProgress else scrollProgress

    LaunchedEffect(gridState.isScrollInProgress, isDraggingScrollbar) {
        if (gridState.isScrollInProgress || isDraggingScrollbar) {
            showDateLabel = true
        } else {
            delay(500)
            showDateLabel = false
        }
    }

    val dateLabelAlpha by animateFloatAsState(
        targetValue = if (showDateLabel) 1f else 0f,
        animationSpec = tween(180),
        label = "dateAlpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {

            PickerTopBar(
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceColor,
                            SurfaceColor.copy(alpha = 0.95f),
                            SurfaceColor.copy(alpha = 0f)
                        )
                    )
                )
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = WeChatDarkTextPrimary, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onShowAlbums) {
                    Text(
                        pickerState.currentAlbumName,
                        color = WeChatDarkTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        AppIcons.ChevronDown,
                        "切换相册",
                        tint = WeChatDarkTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (maxSelection > 1) {
                    TextButton(onClick = onConfirm, enabled = hasSelection) {
                        val t = if (hasSelection) "完成(${selectionMap.size})" else "完成"
                        Text(
                            t,
                            color = if (hasSelection) Accent else WeChatDarkTextSecondary,
                            fontSize = 16.sp,
                            fontWeight = if (hasSelection) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 6.dp)

                        .padding(bottom = if (maxSelection > 1 && selectedItems.isNotEmpty()) 84.dp else 0.dp),
                    contentPadding = PaddingValues(horizontal = 1.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(
                        items = mediaList,
                        key = { it.id }
                    ) { item ->
                        val isSelected by remember(item.id) {
                            derivedStateOf { selectionMap.containsKey(item.id) }
                        }
                        val order by remember(item.id) {
                            derivedStateOf { selectionMap[item.id] }
                        }
                        GridPhotoItem(
                            item = item,
                            isSelected = isSelected,
                            selectedOrder = order,
                            maxSelection = maxSelection,

                            onClick = {
                                if (maxSelection == 1) {
                                    onItemClick(item.id)
                                } else {
                                    onItemPreview(mediaList.indexOf(item))
                                }
                            },

                            onToggleSelection = { onToggleSelection(item.id) }
                        )
                    }
                }

                if (maxSelection > 1 && selectedItems.isNotEmpty()) {
                    SelectionStrip(
                        items = selectedItems,
                        selectionMap = selectionMap,
                        onRemove = onToggleSelection,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    )
                }

                if (totalCount > 0) {
                    val thumbFraction = remember(totalCount) {
                        (40f / totalCount.coerceAtLeast(1)).coerceIn(0.03f, 0.12f)
                    }
                    var trackHeightPx by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(0.96f)
                            .width(12.dp)
                            .pointerInput(totalCount) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        isDraggingScrollbar = true
                                        dragProgress = (it.y / size.height).coerceIn(0f, 1f)
                                        val targetIdx = (dragProgress * totalCount).toInt().coerceIn(0, totalCount - 1)
                                        dragScope.launch { gridState.scrollToItem(targetIdx) }
                                    },
                                    onVerticalDrag = { change, _ ->
                                        dragProgress = (change.position.y / size.height).coerceIn(0f, 1f)
                                        val targetIdx = (dragProgress * totalCount).toInt().coerceIn(0, totalCount - 1)
                                        dragScope.launch { gridState.scrollToItem(targetIdx) }
                                    },
                                    onDragEnd = { isDraggingScrollbar = false },
                                    onDragCancel = { isDraggingScrollbar = false }
                                )
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) {

                        Box(
                            modifier = Modifier
                                .fillMaxHeight(1f)
                                .width(3.dp)
                                .padding(end = 1.dp)
                                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                        ) {

                            val maxY = trackHeightPx * (1f - thumbFraction)
                            val thumbY = displayProgress * maxY
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(thumbFraction)
                                    .offset { IntOffset(0, thumbY.toInt()) }
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White)
                            )
                        }
                    }
                }

                if (firstVisibleDate.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .offset(y = (-16).dp)
                            .alpha(dateLabelAlpha)
                            .background(Color(0xCC1A1A1A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            firstVisibleDate,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridPhotoItem(
    item: MediaItem,
    isSelected: Boolean,
    selectedOrder: Int?,
    maxSelection: Int,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit
) {

    val targetScale by animateFloatAsState(
        targetValue = if (isSelected) 0.93f else 1f,
        animationSpec = tween(100),
        label = "selScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(SurfaceColor)
            .graphicsLayer {
                scaleX = targetScale
                scaleY = targetScale
                clip = true
                shape = RoundedCornerShape(2.dp)
            }
    ) {

        val ctx = LocalContext.current
        val request = remember(item.uri) {
            ImageRequest.Builder(ctx)
                .data(item.uri)
                .size(200)
                .build()
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() },
            contentScale = ContentScale.Crop,
            loading = {
                Box(Modifier.fillMaxSize().background(SurfaceColor))
            },
            error = {
                Box(Modifier.fillMaxSize().background(SurfaceColor))
            }
        )

        if (isSelected) {
            Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.15f)))
        }

        if (maxSelection > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggleSelection() },
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Accent else Color(0x55000000))
                        .then(
                            if (!isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected && selectedOrder != null) {
                        Text("$selectedOrder", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionStrip(
    items: List<MediaItem>,
    selectionMap: Map<Long, Int>,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.88f))
                )
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            val order = selectionMap[item.id]
            val request = remember(item.uri) {
                ImageRequest.Builder(ctx)
                    .data(item.uri)
                    .size(120)
                    .build()
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Accent.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRemove(item.id) }
            ) {
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (order != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(3.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$order", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            "已选 ${items.size}",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
