package com.yunian.ai.database

import com.yunian.ai.database.dao.CompanionDao
import com.yunian.ai.database.model.CompanionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCompanionSeederTest {
    @Test
    fun defaultTestCompanionHasFullCustomRoleFields() {
        val companion = DefaultCompanionSeeder.createDefaultTestCompanion(now = 123L)
        val preset = RolePresets.girlfriend

        assertEquals("小鱼", companion.name)
        assertEquals(123L, companion.createdAt)
        assertEquals(123L, companion.updatedAt)
        assertEquals(22, companion.age)
        assertEquals(preset.personality, companion.personality)
        assertEquals(preset.backstory, companion.backstory)
        assertEquals(preset.speakingStyle, companion.speakingStyle)
        assertEquals(preset.rawPrompt, companion.rawPrompt)
        assertEquals(DefaultCompanionSeeder.DEFAULT_GIRLFRIEND_AVATAR_URL, companion.avatarUrl)
        assertTrue(
            companion.tags.orEmpty().contains(DefaultCompanionSeeder.defaultExperienceCompanionTag)
        )
        assertFalse(companion.personality.contains("体验角色"))

        val systemPrompt = companion.systemPrompt.orEmpty()
        assertTrue(systemPrompt.contains("名字：小鱼"))
        assertTrue(systemPrompt.contains("人设："))
        assertTrue(systemPrompt.contains("说话风格："))
        assertTrue(systemPrompt.contains("背景："))
        assertTrue(systemPrompt.contains("温柔体贴"))
    }

    @Test
    fun insertsDefaultTestCompanionWhenItIsMissing() = runBlocking {
        val dao = FakeCompanionDao(existing = emptyList())

        val insertedId = DefaultCompanionSeeder.ensureDefaultTestCompanion(dao)

        assertEquals(42L, insertedId)
        assertEquals(1, dao.inserted.size)
        val inserted = dao.inserted.single()
        assertEquals("小鱼", inserted.name)
        assertNotNull(inserted.avatarUrl)
        assertNotNull(inserted.systemPrompt)
        assertNotNull(inserted.rawPrompt)
        assertNotNull(inserted.backstory)
        assertNotNull(inserted.speakingStyle)
    }

    @Test
    fun doesNotInsertDuplicateWhenDefaultTestCompanionAlreadyExists() = runBlocking {
        val existing = DefaultCompanionSeeder.createDefaultTestCompanion(now = 123L).copy(id = 7L)
        val dao = FakeCompanionDao(existing = listOf(existing))

        val insertedId = DefaultCompanionSeeder.ensureDefaultTestCompanion(dao)

        assertNull(insertedId)
        assertTrue(dao.inserted.isEmpty())
        assertTrue(dao.updated.isEmpty())
    }

    @Test
    fun upgradesIncompleteExistingDefaultCompanion() = runBlocking {
        val incomplete = CompanionEntity(
            id = 9L,
            name = "小鱼",
            avatarUrl = null,
            age = 22,
            personality = "旧版体验角色文案",
            backstory = null,
            speakingStyle = null,
            tags = DefaultCompanionSeeder.defaultExperienceCompanionTag,
            rawPrompt = null,
            systemPrompt = null,
            intimacy = 12,
            createdAt = 100L,
            updatedAt = 100L
        )
        val dao = FakeCompanionDao(existing = listOf(incomplete))

        val insertedId = DefaultCompanionSeeder.ensureDefaultTestCompanion(dao)

        assertNull(insertedId)
        assertTrue(dao.inserted.isEmpty())
        assertEquals(1, dao.updated.size)
        val upgraded = dao.updated.single()
        assertEquals(9L, upgraded.id)
        assertEquals(12, upgraded.intimacy)
        assertEquals(100L, upgraded.createdAt)
        assertEquals("小鱼", upgraded.name)
        assertEquals(DefaultCompanionSeeder.DEFAULT_GIRLFRIEND_AVATAR_URL, upgraded.avatarUrl)
        assertEquals(RolePresets.girlfriend.personality, upgraded.personality)
        assertNotNull(upgraded.systemPrompt)
        assertNotNull(upgraded.rawPrompt)
        assertNotNull(upgraded.backstory)
        assertNotNull(upgraded.speakingStyle)
    }

    @Test
    fun upgradesLegacyTestCompanionInPlace() = runBlocking {
        val legacy = CompanionEntity(
            id = 3L,
            name = "测试小鱼",
            personality = "legacy",
            tags = DefaultCompanionSeeder.LEGACY_TAG,
            intimacy = 5,
            createdAt = 50L,
            updatedAt = 50L
        )
        val dao = FakeCompanionDao(existing = listOf(legacy))

        val insertedId = DefaultCompanionSeeder.ensureDefaultTestCompanion(dao)

        assertNull(insertedId)
        assertEquals(1, dao.updated.size)
        val upgraded = dao.updated.single()
        assertEquals(3L, upgraded.id)
        assertEquals(5, upgraded.intimacy)
        assertEquals("小鱼", upgraded.name)
        assertTrue(upgraded.tags.orEmpty().contains(DefaultCompanionSeeder.defaultExperienceCompanionTag))
        assertNotNull(upgraded.avatarUrl)
    }

    private class FakeCompanionDao(
        private val existing: List<CompanionEntity>
    ) : CompanionDao {
        val inserted = mutableListOf<CompanionEntity>()
        val updated = mutableListOf<CompanionEntity>()

        override fun getAllCompanions(): Flow<List<CompanionEntity>> = flowOf(existing + inserted)

        override suspend fun getCompanionById(id: Long): CompanionEntity? =
            (existing + inserted + updated).firstOrNull { it.id == id }

        override fun getCompanionByIdFlow(id: Long): Flow<CompanionEntity?> =
            flowOf((existing + inserted + updated).firstOrNull { it.id == id })

        override suspend fun getAllCompanionsSync(): List<CompanionEntity> {
            val byId = LinkedHashMap<Long, CompanionEntity>()
            existing.forEach { byId[it.id] = it }
            inserted.forEach { byId[it.id] = it }
            updated.forEach { byId[it.id] = it }
            return byId.values.toList()
        }

        override suspend fun insertCompanion(companion: CompanionEntity): Long {
            inserted += companion.copy(id = 42L)
            return 42L
        }

        override suspend fun updateCompanion(companion: CompanionEntity): Int {
            updated += companion
            return 1
        }

        override suspend fun deleteCompanion(companion: CompanionEntity): Int = 0

        override suspend fun updateTimestamp(id: Long, timestamp: Long): Int = 0

        override suspend fun increaseIntimacy(id: Long, amount: Int): Int = 0

        override suspend fun getIntimacy(id: Long): Int? =
            (existing + inserted + updated).firstOrNull { it.id == id }?.intimacy
    }
}