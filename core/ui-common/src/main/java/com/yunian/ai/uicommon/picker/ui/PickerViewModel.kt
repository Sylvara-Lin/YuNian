package com.yunian.ai.uicommon.picker.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.uicommon.picker.data.AlbumRepository
import com.yunian.ai.uicommon.picker.domain.PickerState
import com.yunian.ai.uicommon.picker.domain.SelectionManager
import com.yunian.ai.uicommon.picker.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PickerViewModel(
    private val contentResolver: ContentResolver
) : ViewModel() {

    companion object {
        private val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        private const val ID_COL = 0
        private const val DATE_ADDED_COL = 1
        private const val BUCKET_ID_COL = 2
        private const val DISPLAY_NAME_COL = 3
    }

    private val _selectionManager = SelectionManager()
    val selectionMap: StateFlow<LinkedHashMap<Long, Int>> = _selectionManager.selectionMap

    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    private val albumRepository = AlbumRepository(contentResolver)

    private val _mediaList = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaList: StateFlow<List<MediaItem>> = _mediaList.asStateFlow()

    fun reload() {
        loadAlbums()
        val bucketId = _state.value.currentBucketId
        val name = _state.value.currentAlbumName.ifBlank { "全部照片" }
        switchAlbum(bucketId, name)
    }

    private fun loadAlbums() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isAlbumsLoading = true, error = null)
            try {
                val albums = albumRepository.loadAlbums()
                _state.value = _state.value.copy(
                    albums = albums,
                    isAlbumsLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isAlbumsLoading = false,
                    error = "加载相册失败: ${e.message}"
                )
            }
        }
    }

    fun switchAlbum(bucketId: Long, displayName: String) {

        _mediaList.value = emptyList()
        _state.value = _state.value.copy(
            currentBucketId = bucketId,
            currentAlbumName = displayName,
            isMediaLoading = true,
            error = null
        )
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    queryAllMedia(bucketId)
                }

                if (_state.value.currentBucketId != bucketId) return@launch
                _mediaList.value = items
                _state.value = _state.value.copy(isMediaLoading = false)
            } catch (e: Exception) {
                if (_state.value.currentBucketId != bucketId) return@launch
                _state.value = _state.value.copy(
                    isMediaLoading = false,
                    error = "加载图片失败: ${e.message}"
                )
            }
        }
    }

    private fun queryAllMedia(bucketId: Long): List<MediaItem> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val (selection, selectionArgs) = if (bucketId != 0L) {
            "${MediaStore.Images.Media.BUCKET_ID} = ?" to arrayOf(bucketId.toString())
        } else {
            null to null
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val list = mutableListOf<MediaItem>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(ID_COL)
                    list.add(
                        MediaItem(
                            id = id,
                            uri = Uri.parse("${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id"),
                            dateAdded = cursor.getLong(DATE_ADDED_COL),
                            bucketId = cursor.getLong(BUCKET_ID_COL),
                            displayName = cursor.getString(DISPLAY_NAME_COL) ?: ""
                        )
                    )
                }
                list
            } ?: emptyList()
    }

    fun setMaxSelection(max: Int) {
        _selectionManager.setMaxSelection(max)
        _state.value = _state.value.copy(maxSelection = max)
    }

    fun toggleSelection(mediaId: Long): Boolean {
        return _selectionManager.toggle(mediaId)
    }

    fun getSelectionOrder(mediaId: Long): Int? {
        return _selectionManager.getOrder(mediaId)
    }

    fun selectedIds(): List<Long> {
        return _selectionManager.selectedIds.value
    }

    fun clearSelection() {
        _selectionManager.clear()
    }
}
