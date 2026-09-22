package com.yunian.ai.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.ImageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class UserRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val _userName = MutableStateFlow(prefs.getString("user_name", "我") ?: "我")
    val userName: StateFlow<String> = _userName

    private val _userAvatar = MutableStateFlow(prefs.getString("user_avatar", null))
    val userAvatar: StateFlow<String?> = _userAvatar

    private val _selectedRole = MutableStateFlow(
        CompanionRole.fromName(prefs.getString("selected_role", null))
    )
    val selectedRole: StateFlow<CompanionRole> = _selectedRole

    private val _userStatus = MutableStateFlow(prefs.getString("user_status", "") ?: "")
    val userStatus: StateFlow<String> = _userStatus

    private val _userSignature = MutableStateFlow(prefs.getString("user_signature", "") ?: "")
    val userSignature: StateFlow<String> = _userSignature

    private val _userGender = MutableStateFlow(
        normalizeGender(prefs.getString("user_gender", "") ?: "")
    )
    val userGender: StateFlow<String> = _userGender

    private val _userRegion = MutableStateFlow(prefs.getString("user_region", "") ?: "")
    val userRegion: StateFlow<String> = _userRegion

    fun updateUserName(name: String) {
        prefs.edit { putString("user_name", name) }
        _userName.value = name
    }

    fun updateUserAvatar(avatarUri: String?) {
        if (avatarUri != null) {
            prefs.edit { putString("user_avatar", avatarUri) }
        } else {
            prefs.edit { remove("user_avatar") }
        }
        _userAvatar.value = avatarUri
    }

    suspend fun repairUserAvatar(context: Context) {
        val current = prefs.getString("user_avatar", null) ?: return
        val file = runCatching { File(current) }.getOrNull() ?: return

        if (!file.isAbsolute) return
        if (file.absolutePath.startsWith(context.cacheDir.absolutePath)) {
            val migrated = ImageUtils.saveUriToInternalStorage(context, current)
            if (migrated != null) {
                prefs.edit { putString("user_avatar", migrated) }
                _userAvatar.value = migrated
            } else {
                prefs.edit { remove("user_avatar") }
                _userAvatar.value = null
            }
        } else if (!file.exists()) {

            prefs.edit { remove("user_avatar") }
            _userAvatar.value = null
        }
    }

    fun updateSelectedRole(role: CompanionRole) {
        prefs.edit { putString("selected_role", role.name) }
        _selectedRole.value = role
    }

    fun updateUserStatus(status: String) {
        prefs.edit { putString("user_status", status) }
        _userStatus.value = status
    }

    fun updateUserSignature(signature: String) {
        prefs.edit { putString("user_signature", signature) }
        _userSignature.value = signature
    }

    fun updateUserGender(gender: String) {
        val normalized = normalizeGender(gender)
        prefs.edit {
            if (normalized.isEmpty()) {
                remove("user_gender")
            } else {
                putString("user_gender", normalized)
            }
        }
        _userGender.value = normalized
    }

    fun updateUserRegion(region: String) {
        val value = region.trim()
        prefs.edit {
            if (value.isEmpty()) {
                remove("user_region")
            } else {
                putString("user_region", value)
            }
        }
        _userRegion.value = value
    }

    private fun normalizeGender(raw: String): String {
        return when (raw.trim().lowercase()) {
            "male", "m", "男", "man", "boy" -> "male"
            "female", "f", "女", "woman", "girl" -> "female"
            else -> ""
        }
    }
}
