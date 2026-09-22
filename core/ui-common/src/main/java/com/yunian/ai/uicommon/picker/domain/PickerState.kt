package com.yunian.ai.uicommon.picker.domain

import com.yunian.ai.uicommon.picker.model.AlbumInfo

data class PickerState(

    val albums: List<AlbumInfo> = emptyList(),

    val currentBucketId: Long = 0L,

    val currentAlbumName: String = "全部照片",

    val isAlbumsLoading: Boolean = false,

    val isMediaLoading: Boolean = false,

    val error: String? = null,

    val maxSelection: Int = 1
)
