package com.yunian.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.DefaultCompanionSeeder
import com.yunian.ai.database.cache.HomeListCache
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.repository.CompanionRepository
import androidx.core.content.edit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompanionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CompanionRepository
    val companions: StateFlow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = CompanionRepository(database.companionDao())
        companions = repository.getAllCompanions()
            .onEach { HomeListCache.putCompanions(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = HomeListCache.snapshotCompanions()
            )
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {

            val isDefaultCompanion = companion.tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == DefaultCompanionSeeder.LEGACY_TAG || it == DefaultCompanionSeeder.defaultExperienceCompanionTag }
            if (isDefaultCompanion) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            repository.deleteCompanion(companion)
        }
    }
}
