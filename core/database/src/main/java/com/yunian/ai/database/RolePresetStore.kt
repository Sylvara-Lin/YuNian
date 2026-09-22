package com.yunian.ai.database

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.RoleProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RolePresetStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun savePreset(role: CompanionRole, profile: RoleProfile) {
        prefs.edit { putString(keyFor(role), json.encodeToString(profile)) }
    }

    fun getPreset(role: CompanionRole): RoleProfile {
        val raw = prefs.getString(keyFor(role), null) ?: return RolePresets.defaultFor(role)
        return runCatching { json.decodeFromString<RoleProfile>(raw) }.getOrNull()
            ?: RolePresets.defaultFor(role)
    }

    fun snapshotFromCompanion(role: CompanionRole, companion: CompanionEntity) {
        val existingPreset = getPreset(role)
        val profile = RoleProfile(
            role = role,
            name = companion.name,
            age = companion.age,
            avatarUrl = companion.avatarUrl ?: existingPreset.avatarUrl,
            personality = companion.personality,
            backstory = companion.backstory,
            speakingStyle = companion.speakingStyle,
            rawPrompt = companion.rawPrompt,
            systemPrompt = companion.systemPrompt,
            tags = companion.tags,
            bodyType = existingPreset.bodyType,
            profession = existingPreset.profession,
            personalityTags = existingPreset.personalityTags
        )
        savePreset(role, profile)
    }

    companion object {
        private const val PREFS_NAME = "role_presets"
        private fun keyFor(role: CompanionRole) = "preset_${role.name.lowercase()}"
    }
}
