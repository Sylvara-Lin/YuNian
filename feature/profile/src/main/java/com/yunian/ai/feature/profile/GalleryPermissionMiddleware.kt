package com.yunian.ai.feature.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun rememberGalleryPermissionLauncher(
    onImagePicked: (Uri) -> Unit
): GalleryPermissionHandle {
    val context = LocalContext.current

    val storagePermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var showPermissionGuideDialog by remember { mutableStateOf(false) }
    val hasPermission = remember { mutableStateOf(
        ContextCompat.checkSelfPermission(context, storagePermission) == PackageManager.PERMISSION_GRANTED
    ) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(onImagePicked)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasPermission.value = true
            imagePickerLauncher.launch("image/*")
        } else {

            showPermissionGuideDialog = true
        }
    }

    val handle = remember {
        GalleryPermissionHandle(
            pickImage = {
                when {
                    hasPermission.value -> imagePickerLauncher.launch("image/*")
                    else -> permissionLauncher.launch(storagePermission)
                }
            }
        )
    }

    if (showPermissionGuideDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionGuideDialog = false },
            title = {
                Text(
                    stringResource(R.string.permission_storage_title),
                    fontWeight = FontWeight.Medium
                )
            },
            text = {
                Text(
                    stringResource(R.string.permission_storage_rationale),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionGuideDialog = false

                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.permission_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionGuideDialog = false }) {
                    Text(stringResource(R.string.profile_cancel))
                }
            }
        )
    }

    return handle
}

class GalleryPermissionHandle(

    val pickImage: () -> Unit
)
