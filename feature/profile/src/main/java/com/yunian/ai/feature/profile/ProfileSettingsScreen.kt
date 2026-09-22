package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yunian.ai.feature.profile.R
import com.yunian.ai.uicommon.picker.ui.CustomImagePicker
import com.yunian.ai.uicommon.image.cropper.ImageCropperDialog
import com.yunian.ai.uicommon.image.decodeUriSampledForCrop
import com.yunian.ai.uicommon.component.bounceVerticalScroll
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val colorScheme = AppTheme.colors
    val context = LocalContext.current

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userSignature by viewModel.userSignature.collectAsState()
    val userStatus by viewModel.userStatus.collectAsState()
    val userGender by viewModel.userGender.collectAsState()
    val userRegion by viewModel.userRegion.collectAsState()

    val genderLabel = when (userGender) {
        "male" -> stringResource(R.string.profile_gender_male)
        "female" -> stringResource(R.string.profile_gender_female)
        else -> stringResource(R.string.profile_not_set)
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var editSignature by remember { mutableStateOf(userSignature) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(userStatus) }
    var showGenderDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var editRegion by remember { mutableStateOf(userRegion) }
    var showAvatarFullscreen by remember { mutableStateOf(false) }

    var showPicker by remember { mutableStateOf(false) }

    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.profile_settings_title),
                onBack = onNavigateBack
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.38f, maxOverscrollDp = 88f)
                .verticalScroll(rememberScrollState())
        ) {

            ProfileSectionRow(onClick = { showPicker = true }) {
                Text(
                    stringResource(R.string.profile_avatar),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                val avatarInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant)
                        .clickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = { showAvatarFullscreen = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (userAvatar != null) {
                        AsyncImage(
                            model = userAvatar,
                            contentDescription = stringResource(R.string.profile_avatar),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            AppIcons.User,
                            stringResource(R.string.profile_avatar),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            ProfileSectionDivider()

            ProfileLabelValueRow(
                label = stringResource(R.string.profile_name),
                value = userName.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userName.isBlank(),
                onClick = { editName = userName; showNameDialog = true }
            )
            ProfileSectionDivider()

            ProfileLabelValueRow(
                label = stringResource(R.string.profile_gender),
                value = genderLabel,
                dimmed = userGender.isBlank(),
                onClick = { showGenderDialog = true }
            )
            ProfileSectionDivider()

            ProfileLabelValueRow(
                label = stringResource(R.string.profile_region),
                value = userRegion.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userRegion.isBlank(),
                onClick = {
                    editRegion = userRegion
                    showRegionDialog = true
                }
            )
            ProfileSectionDivider()

            ProfileLabelValueRow(
                label = stringResource(R.string.profile_status),
                value = userStatus.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userStatus.isBlank(),
                onClick = { editStatus = userStatus; showStatusDialog = true }
            )
            ProfileSectionDivider()

            ProfileLabelValueRow(
                label = stringResource(R.string.profile_signature),
                value = userSignature.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userSignature.isBlank(),
                onClick = { editSignature = userSignature; showSignatureDialog = true }
            )
        }
    }

    if (showAvatarFullscreen && userAvatar != null) {
        AvatarFullscreenDialog(
            avatarUri = userAvatar!!,
            onDismiss = { showAvatarFullscreen = false }
        )
    }

    if (showNameDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_name),
            value = editName,
            onValueChange = { editName = it },
            placeholder = stringResource(R.string.profile_name),
            onConfirm = {
                if (editName.isNotBlank()) viewModel.updateUserName(editName.trim())
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false }
        )
    }

    if (showSignatureDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_signature),
            value = editSignature,
            onValueChange = { if (it.length <= 30) editSignature = it },
            placeholder = stringResource(R.string.profile_signature),
            maxLength = 30,
            onConfirm = {
                viewModel.updateUserSignature(editSignature.trim())
                showSignatureDialog = false
            },
            onDismiss = { showSignatureDialog = false }
        )
    }

    if (showStatusDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_status),
            value = editStatus,
            onValueChange = { if (it.length <= 16) editStatus = it },
            placeholder = stringResource(R.string.profile_status),
            maxLength = 16,
            onConfirm = {
                viewModel.updateUserStatus(editStatus.trim())
                showStatusDialog = false
            },
            onDismiss = { showStatusDialog = false }
        )
    }

    if (showGenderDialog) {
        GenderPickerDialog(
            initialGender = userGender,
            onConfirm = { selectedGender ->
                viewModel.updateUserGender(selectedGender)
                showGenderDialog = false
            },
            onDismiss = { showGenderDialog = false }
        )
    }

    if (showRegionDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_region),
            value = editRegion,
            onValueChange = { if (it.length <= 32) editRegion = it },
            placeholder = "如：广东·深圳",
            maxLength = 32,
            onConfirm = {
                viewModel.updateUserRegion(editRegion.trim())
                showRegionDialog = false
            },
            onDismiss = { showRegionDialog = false }
        )
    }

    if (showPicker) {
        CustomImagePicker(
            maxSelection = 1,
            onConfirmed = { uris ->
                showPicker = false
                if (uris.isNotEmpty()) {
                    pendingCropUri = uris.first()
                }
            },
            onDismiss = { showPicker = false }
        )
    }

    LaunchedEffect(pendingCropUri) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        val ctx = context
        // 采样解码 + OOM 兜底（修 FIX-1）：全尺寸截图解码 + 裁剪峰值易在 MIUI/MTK 机型 OOM 闪退。
        cropBitmap = withContext(Dispatchers.IO) {
            decodeUriSampledForCrop(ctx, uri)?.asImageBitmap()
        }
    }

    if (cropBitmap != null) {
        ImageCropperDialog(
            bitmap = cropBitmap!!,
            cropRatio = 1f,
            onConfirm = { cropped ->

                val cacheFile = java.io.File(context.cacheDir, "avatar_cropped_${System.currentTimeMillis()}.jpg")
                try {
                    java.io.FileOutputStream(cacheFile).use { out ->
                        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    viewModel.updateUserAvatar(cacheFile.absolutePath)
                } catch (_: Exception) { }
                cropBitmap = null
                pendingCropUri = null
            },
            onDismiss = {
                cropBitmap = null
                pendingCropUri = null
            }
        )
    }
}

@Composable
private fun AvatarFullscreenDialog(
    avatarUri: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {

        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                )
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = avatarUri,
                contentDescription = "头像大图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    AppIcons.X,
                    "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun BottomLineEditDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxLength: Int = Int.MAX_VALUE,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val lineColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
    val accentColor = AppTheme.colors.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 16.sp
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            color = AppTheme.colors.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        cursorBrush = SolidColor(accentColor)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(lineColor)
                )
                if (maxLength < Int.MAX_VALUE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${value.length}/$maxLength",
                        fontSize = 12.sp,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认", color = accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun GenderPickerDialog(
    initialGender: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "male" to stringResource(R.string.profile_gender_male),
        "female" to stringResource(R.string.profile_gender_female)
    )
    val initialIndex = options.indexOfFirst { it.first == initialGender }.let { if (it >= 0) it else 0 }
    val itemHeight = 48.dp
    val visibleCount = 3
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    val selectedIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                listState.firstVisibleItemIndex.coerceIn(0, options.lastIndex)
            } else {
                val viewportCenter =
                    layoutInfo.viewportStartOffset +
                        (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
                visible.minByOrNull { info ->
                    abs((info.offset + info.size / 2) - viewportCenter)
                }?.index?.coerceIn(0, options.lastIndex)
                    ?: listState.firstVisibleItemIndex.coerceIn(0, options.lastIndex)
            }
        }
    }

    LaunchedEffect(initialIndex) {
        listState.scrollToItem(initialIndex)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.profile_gender),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight * visibleCount),
                contentAlignment = Alignment.Center
            ) {

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppTheme.colors.primary.copy(alpha = 0.12f))
                )

                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    contentPadding = PaddingValues(vertical = itemHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(options.size, key = { options[it].first }) { index ->
                        val isSelected = index == selectedIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    scope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                options[index].second,
                                fontSize = if (isSelected) 20.sp else 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    AppTheme.colors.primary
                                } else {
                                    AppTheme.colors.onSurface.copy(alpha = 0.45f)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {

                    val index = if (listState.isScrollInProgress) {
                        val offsetBias = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
                        (listState.firstVisibleItemIndex + offsetBias).coerceIn(0, options.lastIndex)
                    } else {
                        selectedIndex
                    }
                    onConfirm(options[index].first)
                }
            ) {
                Text(stringResource(R.string.profile_confirm), color = AppTheme.colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}
