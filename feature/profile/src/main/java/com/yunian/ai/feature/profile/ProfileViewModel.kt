package com.yunian.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.DefaultCompanionSeeder
import com.yunian.ai.database.RolePresetStore
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.common.ImageUtils
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class RoleSwitchState {
    data object Idle : RoleSwitchState()
    data class InProgress(val stage: SwitchStage) : RoleSwitchState()
    data class Error(val message: String) : RoleSwitchState()
}

enum class SwitchStage {
    SAVING_SNAPSHOT,
    LOADING_PRESET,
    APPLYING_PRESET,
    UPDATING_PREFERENCE
}

sealed class RoleSwitchEvent {
    data class Error(val message: String) : RoleSwitchEvent()
    data class StageUpdate(val stage: SwitchStage) : RoleSwitchEvent()
    object Success : RoleSwitchEvent()
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val repository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }
    private val companionRepository by lazy { ServiceRegistry.getOrThrow(CompanionRepository::class.java) }
    private val rolePresetStore = RolePresetStore(application)

    val userName: StateFlow<String> by lazy { repository.userName }
    val userAvatar: StateFlow<String?> by lazy { repository.userAvatar }
    val selectedRole: StateFlow<CompanionRole> by lazy { repository.selectedRole }
    val userStatus: StateFlow<String> by lazy { repository.userStatus }
    val userSignature: StateFlow<String> by lazy { repository.userSignature }

    val userGender: StateFlow<String> by lazy { repository.userGender }
    val userRegion: StateFlow<String> by lazy { repository.userRegion }

    val companionCount: StateFlow<Int> by lazy {
        companionRepository.getAllCompanions()
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    }

    private val _switchState = MutableStateFlow<RoleSwitchState>(RoleSwitchState.Idle)
    val switchState: StateFlow<RoleSwitchState> = _switchState.asStateFlow()

    private val _switchEvent = MutableSharedFlow<RoleSwitchEvent>(extraBufferCapacity = 4)
    val switchEvent: SharedFlow<RoleSwitchEvent> = _switchEvent

    val isSwitchingRole: StateFlow<Boolean> = _switchState
        .map { it is RoleSwitchState.InProgress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun updateUserName(name: String) {
        viewModelScope.launch {
            repository.updateUserName(name)
        }
    }

    fun updateUserAvatar(avatarUri: String?) {
        viewModelScope.launch {
            val savedUri = if (avatarUri != null) {
                ImageUtils.saveUriToInternalStorage(getApplication(), avatarUri)
            } else null
            repository.updateUserAvatar(savedUri)
        }
    }

    fun updateUserStatus(status: String) {
        viewModelScope.launch {
            repository.updateUserStatus(status)
        }
    }

    fun updateUserSignature(signature: String) {
        viewModelScope.launch {
            repository.updateUserSignature(signature)
        }
    }

    fun updateUserGender(gender: String) {
        viewModelScope.launch {
            repository.updateUserGender(gender)
        }
    }

    fun updateUserRegion(region: String) {
        viewModelScope.launch {
            repository.updateUserRegion(region)
        }
    }

    fun switchRole(targetRole: CompanionRole, onComplete: (() -> Unit)? = null) {

        if (targetRole == repository.selectedRole.value) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    repository.updateSelectedRole(targetRole)
                }
                onComplete?.invoke()
            }
            return
        }

        viewModelScope.launch {
            _switchState.value = RoleSwitchState.InProgress(SwitchStage.SAVING_SNAPSHOT)
            val success = try {
                val currentRole = repository.selectedRole.value

                val defaultCompanion = withContext(Dispatchers.IO) {
                    companionRepository.getDefaultExperienceCompanion()
                }

                if (defaultCompanion != null) {

                    withContext(Dispatchers.IO) {
                        rolePresetStore.snapshotFromCompanion(currentRole, defaultCompanion)
                    }

                    _switchState.value = RoleSwitchState.InProgress(SwitchStage.LOADING_PRESET)
                    val targetPreset = withContext(Dispatchers.IO) {
                        rolePresetStore.getPreset(targetRole)
                    }

                    _switchState.value = RoleSwitchState.InProgress(SwitchStage.APPLYING_PRESET)
                    val updatedCompanion = targetPreset.applyTo(defaultCompanion)
                    withContext(Dispatchers.IO) {
                        companionRepository.updateCompanion(updatedCompanion)
                    }
                } else {

                    withContext(Dispatchers.IO) {
                        DefaultCompanionSeeder.seedIfNeeded(getApplication())
                    }
                    val seededCompanion = withContext(Dispatchers.IO) {
                        companionRepository.getDefaultExperienceCompanion()
                    }

                    if (seededCompanion != null) {
                        _switchState.value = RoleSwitchState.InProgress(SwitchStage.LOADING_PRESET)
                        val targetPreset = withContext(Dispatchers.IO) {
                            rolePresetStore.getPreset(targetRole)
                        }
                        _switchState.value = RoleSwitchState.InProgress(SwitchStage.APPLYING_PRESET)
                        withContext(Dispatchers.IO) {
                            companionRepository.updateCompanion(targetPreset.applyTo(seededCompanion))
                        }
                    } else {
                        _switchState.value = RoleSwitchState.InProgress(SwitchStage.LOADING_PRESET)
                        val targetPreset = withContext(Dispatchers.IO) {
                            rolePresetStore.getPreset(targetRole)
                        }
                        _switchState.value = RoleSwitchState.InProgress(SwitchStage.APPLYING_PRESET)
                        withContext(Dispatchers.IO) {
                            companionRepository.insertCompanion(targetPreset.createCompanion())
                        }
                    }
                }

                _switchState.value = RoleSwitchState.InProgress(SwitchStage.UPDATING_PREFERENCE)
                withContext(Dispatchers.IO) {
                    repository.updateSelectedRole(targetRole)
                }

                SecureLog.d("ProfileViewModel", "switchRole success: $targetRole")
                true
            } catch (e: CancellationException) {
                _switchState.value = RoleSwitchState.Idle
                throw e
            } catch (e: Exception) {
                SecureLog.e("ProfileViewModel", "switchRole failed", e)
                _switchState.value = RoleSwitchState.Idle

                _switchEvent.tryEmit(RoleSwitchEvent.Error(e.message ?: "切换失败"))
                false
            }

            if (success) {
                _switchState.value = RoleSwitchState.Idle
                _switchEvent.tryEmit(RoleSwitchEvent.Success)

                onComplete?.invoke()
            }
        }
    }

    fun consumeSwitchError() {

    }
}
