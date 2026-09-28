package com.yunian.ai.uicommon.picker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.LocalImageLoader
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.uicommon.picker.PickerImageLoader
import com.yunian.ai.uicommon.theme.WeChatDarkBackground

@Composable
fun CustomImagePicker(
    maxSelection: Int = 1,
    onConfirmed: (List<Uri>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val storagePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var isPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDeniedOnce by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isPermissionGranted = true
            permissionDeniedOnce = false
        } else {
            permissionDeniedOnce = true
            showPermissionRationale = true
        }
    }

    LaunchedEffect(Unit) {
        if (!isPermissionGranted && !permissionDeniedOnce) {
            permissionLauncher.launch(storagePermission)
        }
    }

    val viewModel: PickerViewModel = viewModel(
        factory = PickerViewModelFactory(context.contentResolver)
    )
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            viewModel.reload()
        }
    }

    LaunchedEffect(maxSelection) {
        viewModel.setMaxSelection(maxSelection)
    }

    var currentPage by remember { mutableStateOf(PickerPage.GRID) }
    var previewInitialIndex by remember { mutableStateOf(0) }
    val hasSelection = selectionMap.isNotEmpty()

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = {
                Text(
                    "需要相册权限",
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    "需要访问您的相册才能选择图片。请在系统设置中授予存储权限。",
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    val intent = android.content.Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text("前往设置")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    onDismiss()
                }) {
                    Text("取消")
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
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
                w.setBackgroundDrawable(ColorDrawable(0xFF1A1216.toInt()))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT

                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }

                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT <= 29) {
                    w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
            }
        }

        val pickerImageLoader = remember { PickerImageLoader.get(context) }
        CompositionLocalProvider(LocalImageLoader provides pickerImageLoader) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WeChatDarkBackground)
        ) {

        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = currentPage,
            transitionSpec = {
                when {

                    targetState == PickerPage.ALBUMS -> {
                        (slideInHorizontally(tween(280)) { -it / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(150)))
                    }

                    initialState == PickerPage.ALBUMS -> {
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(280)) { -it / 3 } + fadeOut(tween(150)))
                    }

                    else -> {
                        (scaleIn(tween(240), initialScale = 0.92f) + fadeIn(tween(200)))
                            .togetherWith(fadeOut(tween(180)))
                    }
                }
            },
            label = "pageTransition"
        ) { page ->
            when (page) {
                PickerPage.GRID -> {
                    ImageGrid(
                        viewModel = viewModel,
                        onShowAlbums = { currentPage = PickerPage.ALBUMS },
                        onItemClick = { id ->
                            if (maxSelection == 1) {

                                onConfirmed(listOf(contentResolverToUri(id)))
                            } else {

                            }
                        },
                        onItemPreview = { index ->
                            previewInitialIndex = index
                            currentPage = PickerPage.PREVIEW
                        },
                        onToggleSelection = { id ->
                            viewModel.toggleSelection(id)
                        },
                        onConfirm = {
                            val uris = viewModel.selectedIds().map { id ->
                                contentResolverToUri(id)
                            }
                            onConfirmed(uris)
                        },
                        onDismiss = onDismiss,
                        hasSelection = hasSelection,
                        maxSelection = maxSelection
                    )
                }
                PickerPage.ALBUMS -> {
                    AlbumListSheet(
                        viewModel = viewModel,
                        onAlbumSelected = { bucketId, name ->
                            viewModel.switchAlbum(bucketId, name)
                            currentPage = PickerPage.GRID
                        },
                        onDismiss = { currentPage = PickerPage.GRID }
                    )
                }
                PickerPage.PREVIEW -> {
                    ImagePreview(
                        viewModel = viewModel,
                        initialIndex = previewInitialIndex,
                        onBack = { currentPage = PickerPage.GRID },
                        onConfirm = {
                            val uris = viewModel.selectedIds().map { id ->
                                contentResolverToUri(id)
                            }
                            onConfirmed(uris)
                        }
                    )
                }
            }
        }
        }
        }
    }
}

private enum class PickerPage { GRID, ALBUMS, PREVIEW }

private fun contentResolverToUri(mediaId: Long): Uri =
    Uri.parse("${android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$mediaId")
