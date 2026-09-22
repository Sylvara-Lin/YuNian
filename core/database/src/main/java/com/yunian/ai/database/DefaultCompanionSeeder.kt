package com.yunian.ai.database

import android.content.Context
import com.yunian.ai.database.dao.CompanionDao
import com.yunian.ai.database.model.CompanionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DefaultCompanionSeeder {
    private const val PREFS_NAME = "default_companion"
    private const val KEY_DELETED_BY_USER = "deleted_by_user"
    private val seedMutex = Mutex()

    const val defaultExperienceCompanionTag: String = "default-experience-companion"

    const val DEFAULT_COMPANION_TAGS: String =
        "体验,默认,$defaultExperienceCompanionTag"

    const val DEFAULT_GIRLFRIEND_AVATAR_URL: String =
        "android.resource://com.yunian.ai/drawable/avatar_xiaoyu"

    const val DEFAULT_BOYFRIEND_AVATAR_URL: String =
        "android.resource://com.yunian.ai/drawable/avatar_aze"

    private const val DEFAULT_NAME = "小鱼"

    suspend fun seedIfNeeded(context: Context) = seedMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DELETED_BY_USER, false)) return@withLock
        runCatching {
            val db = AppDatabase.getDatabase(context.applicationContext)
            ensureDefaultTestCompanion(db.companionDao())
        }
    }

    fun createDefaultTestCompanion(now: Long = System.currentTimeMillis()): CompanionEntity {
        val profile = RolePresets.girlfriend
        return profile.createCompanion(now = now).copy(
            tags = DEFAULT_COMPANION_TAGS,
            avatarUrl = profile.avatarUrl ?: DEFAULT_GIRLFRIEND_AVATAR_URL
        )
    }

    suspend fun ensureDefaultTestCompanion(companionDao: CompanionDao): Long? {
        val companions = companionDao.getAllCompanionsSync()

        companions.firstOrNull { it.isLegacyDefaultTestCompanion() }?.let { legacy ->
            companionDao.updateCompanion(
                upgradeToFullDefaultCompanion(legacy)
            )
            return null
        }

        val existing = companions.firstOrNull { it.isDefaultExperienceCompanion() }
        if (existing != null) {
            if (existing.needsFullDefaultUpgrade()) {
                companionDao.updateCompanion(upgradeToFullDefaultCompanion(existing))
            }
            return null
        }

        return companionDao.insertCompanion(createDefaultTestCompanion())
    }

    fun upgradeToFullDefaultCompanion(
        existing: CompanionEntity,
        now: Long = System.currentTimeMillis()
    ): CompanionEntity {
        val seed = createDefaultTestCompanion(now = existing.createdAt)
        val keepUserAvatar = existing.avatarUrl
            ?.takeIf { it.isNotBlank() && !isStockDefaultAvatar(it) && it != seed.avatarUrl }
        return if (existing.looksLikeStockDefaultSeed()) {
            seed.copy(
                id = existing.id,
                intimacy = existing.intimacy,
                createdAt = existing.createdAt,
                updatedAt = now,
                avatarUrl = keepUserAvatar ?: seed.avatarUrl
            )
        } else {
            existing.copy(
                avatarUrl = existing.avatarUrl?.takeIf { it.isNotBlank() } ?: seed.avatarUrl,
                age = existing.age ?: seed.age,
                backstory = existing.backstory?.takeIf { it.isNotBlank() } ?: seed.backstory,
                speakingStyle = existing.speakingStyle?.takeIf { it.isNotBlank() }
                    ?: seed.speakingStyle,
                tags = existing.tags?.takeIf { it.contains(defaultExperienceCompanionTag) }
                    ?: seed.tags,
                rawPrompt = existing.rawPrompt?.takeIf { it.isNotBlank() } ?: seed.rawPrompt,
                systemPrompt = existing.systemPrompt?.takeIf { it.isNotBlank() }
                    ?: seed.systemPrompt,
                updatedAt = now
            )
        }
    }

    private fun CompanionEntity.isDefaultExperienceCompanion(): Boolean {
        return name == DEFAULT_NAME ||
            name == LEGACY_NAME ||
            tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == defaultExperienceCompanionTag || it == LEGACY_TAG }
    }

    private fun CompanionEntity.isLegacyDefaultTestCompanion(): Boolean {
        return name == LEGACY_NAME ||
            tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == LEGACY_TAG }
    }

    private fun CompanionEntity.needsFullDefaultUpgrade(): Boolean {
        if (!isDefaultExperienceCompanion()) return false

        return name == LEGACY_NAME ||
            personality.contains("体验角色") ||
            avatarUrl.isNullOrBlank() ||
            systemPrompt.isNullOrBlank() ||
            rawPrompt.isNullOrBlank() ||
            backstory.isNullOrBlank() ||
            speakingStyle.isNullOrBlank() ||
            !tags.orEmpty().contains(defaultExperienceCompanionTag)
    }

    private fun CompanionEntity.looksLikeStockDefaultSeed(): Boolean {
        return name == LEGACY_NAME ||
            personality.contains("体验角色") ||
            systemPrompt.isNullOrBlank() ||
            rawPrompt.isNullOrBlank()
    }

    private fun isStockDefaultAvatar(url: String): Boolean {
        return url == DEFAULT_GIRLFRIEND_AVATAR_URL ||
            url == DEFAULT_BOYFRIEND_AVATAR_URL ||
            url.contains("avatar_xiaoyu") ||
            url.contains("avatar_aze")
    }

    const val LEGACY_TAG = "default-test-companion"
    private const val LEGACY_NAME = "测试小鱼"
}
