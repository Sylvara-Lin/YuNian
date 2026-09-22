package com.yunian.ai.uicommon.picker.model

import android.net.Uri

data class MediaItem(

    val id: Long,

    val uri: Uri,

    val dateAdded: Long,

    val bucketId: Long,

    val displayName: String
)
