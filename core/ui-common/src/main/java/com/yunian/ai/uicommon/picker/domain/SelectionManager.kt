package com.yunian.ai.uicommon.picker.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SelectionManager {

    private val _selectionMap = MutableStateFlow(LinkedHashMap<Long, Int>())
    val selectionMap: StateFlow<LinkedHashMap<Long, Int>> = _selectionMap.asStateFlow()

    private val _selectedIds = MutableStateFlow<List<Long>>(emptyList())
    val selectedIds: StateFlow<List<Long>> = _selectedIds.asStateFlow()

    private var maxSelection: Int = 1

    fun setMaxSelection(max: Int) {
        maxSelection = max
    }

    fun toggle(mediaId: Long): Boolean {
        val current = _selectionMap.value
        if (current.containsKey(mediaId)) {

            val newMap = LinkedHashMap<Long, Int>()
            var idx = 1
            for ((id, _) in current) {
                if (id != mediaId) {
                    newMap[id] = idx++
                }
            }
            _selectionMap.value = newMap
            _selectedIds.value = newMap.keys.toList()
            return true
        } else {

            if (current.size >= maxSelection) return false
            val newMap = LinkedHashMap(current)
            newMap[mediaId] = current.size + 1
            _selectionMap.value = newMap
            _selectedIds.value = newMap.keys.toList()
            return true
        }
    }

    fun getOrder(mediaId: Long): Int? = _selectionMap.value[mediaId]

    fun canSelect(): Boolean = _selectionMap.value.size < maxSelection

    fun isFull(): Boolean = _selectionMap.value.size >= maxSelection

    fun selectedCount(): Int = _selectionMap.value.size

    fun clear() {
        _selectionMap.value = LinkedHashMap()
        _selectedIds.value = emptyList()
    }
}
