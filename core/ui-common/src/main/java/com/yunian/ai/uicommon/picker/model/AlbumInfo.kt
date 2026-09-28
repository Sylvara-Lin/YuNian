package com.yunian.ai.uicommon.picker.model

import android.net.Uri

data class AlbumInfo(

    val bucketId: Long,

    val displayName: String,

    val count: Int,

    val coverUri: Uri?
)
