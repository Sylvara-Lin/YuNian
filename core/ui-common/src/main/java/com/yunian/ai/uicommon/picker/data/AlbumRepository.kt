package com.yunian.ai.uicommon.picker.data

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.yunian.ai.uicommon.picker.model.AlbumInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AlbumRepository(
    private val contentResolver: ContentResolver
) {

    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED
    )

    suspend fun loadAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val bucketMap = LinkedHashMap<Long, BucketAcc>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {

                val bucketId = cursor.getLong(bucketCol)
                val mediaId  = cursor.getLong(idCol)
                val date     = cursor.getLong(dateCol)
                val rawName  = cursor.getString(nameCol)?.trim().orEmpty()
                val name     = rawName.ifEmpty { "未知相册" }

                val acc = bucketMap[bucketId]
                if (acc == null) {
                    bucketMap[bucketId] = BucketAcc(
                        name    = name,
                        count   = 1,
                        latestDate = date,
                        coverId = mediaId
                    )
                } else {
                    acc.count++

                    if (acc.name == "未知相册" && name != "未知相册") {
                        acc.name = name
                    }
                    if (date > acc.latestDate) {
                        acc.latestDate = date
                        acc.coverId = mediaId
                    }
                }
            }
        }

        var totalCount = 0
        var allCoverId: Long? = null
        var allLatestDate = Long.MIN_VALUE

        val albums = mutableListOf<AlbumInfo>()

        for ((bucketId, acc) in bucketMap) {
            totalCount += acc.count
            if (acc.latestDate > allLatestDate) {
                allLatestDate = acc.latestDate
                allCoverId = acc.coverId
            }
            albums.add(
                AlbumInfo(
                    bucketId    = bucketId,
                    displayName = acc.name,
                    count       = acc.count,
                    coverUri    = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, acc.coverId
                    )
                )
            )
        }

        albums.sortByDescending { it.count }

        albums.add(0, AlbumInfo(
            bucketId    = 0L,
            displayName = "全部照片",
            count       = totalCount,
            coverUri    = allCoverId?.let {
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
            }
        ))

        albums
    }
}

private class BucketAcc(
    var name: String,
    var count: Int,
    var latestDate: Long,
    var coverId: Long
)
