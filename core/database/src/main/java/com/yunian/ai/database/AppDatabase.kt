package com.yunian.ai.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.dao.AgentDispatchLogDao
import com.yunian.ai.database.dao.AgentSkillDao
import com.yunian.ai.database.dao.ApiConfigDao
import com.yunian.ai.database.dao.ApiProviderPresetDao
import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.database.dao.ChatGroupDao
import com.yunian.ai.database.dao.CompanionDao
import com.yunian.ai.database.dao.ConversationSummaryDao
import com.yunian.ai.database.dao.DelegationDao
import com.yunian.ai.database.dao.DiaryDao
import com.yunian.ai.database.dao.EventLedgerDao
import com.yunian.ai.database.dao.KeywordDao
import com.yunian.ai.database.dao.LorebookDao
import com.yunian.ai.database.dao.MemoryDao
import com.yunian.ai.database.dao.MessageDao
import com.yunian.ai.database.dao.PromptAuditDao
import com.yunian.ai.database.dao.QuizQuestionDao
import com.yunian.ai.database.dao.StickerEntryDao
import com.yunian.ai.database.dao.StickerTagDao
import com.yunian.ai.database.dao.StickerUsageLogDao
import com.yunian.ai.database.dao.TokenUsageDao
import com.yunian.ai.database.dao.UnifiedMemoryDao
import com.yunian.ai.database.dao.WeChatInboxDedupeDao
import com.yunian.ai.database.dao.WeChatOutboxDao
import com.yunian.ai.database.dao.WorldbookDao
import com.yunian.ai.database.model.AgentDispatchLogEntity
import com.yunian.ai.database.model.AgentSkillEntity
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.AppMetaEntity
import com.yunian.ai.database.model.ApiProviderPreset
import com.yunian.ai.database.model.ArchivedMessage
import com.yunian.ai.database.model.ArchivedMessageBody
import com.yunian.ai.database.model.ChatGroup
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.ConversationSummary
import com.yunian.ai.database.model.DelegationRecordEntity
import com.yunian.ai.database.model.DiaryEntry
import com.yunian.ai.database.model.EventLedgerEntity
import com.yunian.ai.database.model.EventLedgerSnapshotEntity
import com.yunian.ai.database.model.FileFormat
import com.yunian.ai.database.model.KeywordEntity
import com.yunian.ai.database.model.LorebookEntity
import com.yunian.ai.database.model.LorebookEntryEntity
import com.yunian.ai.database.model.Message
import com.yunian.ai.database.model.MemoryCategory
import com.yunian.ai.database.model.MemoryEntry
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.model.MessageBody
import com.yunian.ai.database.model.MessageSearchIndex
import com.yunian.ai.database.model.PromptAuditEntity
import com.yunian.ai.database.model.QuizQuestionEntity
import com.yunian.ai.database.model.StickerEntryEntity
import com.yunian.ai.database.model.StickerTagEntity
import com.yunian.ai.database.model.StickerUsageLogEntity
import com.yunian.ai.database.model.TempMemory
import com.yunian.ai.database.model.TokenUsage
import com.yunian.ai.database.model.WeChatInboxDedupeEntity
import com.yunian.ai.database.model.WeChatOutboxEntity
import com.yunian.ai.database.model.WorldbookEntity
import com.yunian.ai.database.repository.MessageSearchTokenizer
import java.io.File

@Database(
    entities = [
        AppMetaEntity::class,
        CompanionEntity::class,
        ApiConfig::class,
        ApiProviderPreset::class,
        MemoryEntry::class,
        TempMemory::class,
        MemoryRecord::class,
        DiaryEntry::class,
        ChatGroup::class,
        KeywordEntity::class,
        QuizQuestionEntity::class,
        TokenUsage::class,
        ConversationSummary::class,
        Message::class,
        MessageBody::class,
        ArchivedMessage::class,
        ArchivedMessageBody::class,
        MessageSearchIndex::class,
        WeChatOutboxEntity::class,
        WeChatInboxDedupeEntity::class,
        LorebookEntity::class,
        LorebookEntryEntity::class,
        // ==== Agent 架构迁移新增（v45，10 张表，纯增量）====
        AgentSkillEntity::class,
        PromptAuditEntity::class,
        AgentDispatchLogEntity::class,
        StickerEntryEntity::class,
        StickerTagEntity::class,
        StickerUsageLogEntity::class,
        EventLedgerEntity::class,
        EventLedgerSnapshotEntity::class,
        DelegationRecordEntity::class,
        WorldbookEntity::class,
    ],
    version = 45,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun companionDao(): CompanionDao
    abstract fun appMetaDao(): AppMetaDao
    abstract fun apiConfigDao(): ApiConfigDao
    abstract fun apiProviderPresetDao(): ApiProviderPresetDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatGroupDao(): ChatGroupDao
    abstract fun keywordDao(): KeywordDao
    abstract fun quizQuestionDao(): QuizQuestionDao
    abstract fun tokenUsageDao(): TokenUsageDao
    abstract fun unifiedMemoryDao(): UnifiedMemoryDao
    abstract fun lorebookDao(): LorebookDao
    abstract fun diaryDao(): DiaryDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun messageDao(): MessageDao
    abstract fun weChatOutboxDao(): WeChatOutboxDao
    abstract fun weChatInboxDedupeDao(): WeChatInboxDedupeDao
    // ==== Agent 架构迁移新增（v45）====
    abstract fun agentSkillDao(): AgentSkillDao
    abstract fun promptAuditDao(): PromptAuditDao
    abstract fun agentDispatchLogDao(): AgentDispatchLogDao
    abstract fun stickerEntryDao(): StickerEntryDao
    abstract fun stickerTagDao(): StickerTagDao
    abstract fun stickerUsageLogDao(): StickerUsageLogDao
    abstract fun eventLedgerDao(): EventLedgerDao
    abstract fun delegationDao(): DelegationDao
    abstract fun worldbookDao(): WorldbookDao

    companion object {
        private const val DB_NAME = "yunian_database"

        // schema 冻结基线：自 v41 起，新增功能走 AppMetaStore(KV) 或 ExtJson，禁止加表/字段/索引。
        // 确需变更见 docs/database-schema-freeze.md，走向后兼容迁移。
        //
        // 【已批准的冻结例外 #1】v44 → v45：Agent 架构迁移（10 张新表，纯增量）。
        // 详见 MIGRATION_44_45 的注释与 docs/database-schema-freeze.md 第七节「冻结例外记录」。
        private const val SCHEMA_FROZEN_VERSION = 41
        private val LOCK = Any()

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun resetForTest() {
            synchronized(LOCK) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        fun shutdown() {
            synchronized(LOCK) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {

            return createDatabase(context)
        }

        fun verifyAndRecover(context: Context) {
            synchronized(LOCK) {
                val current = INSTANCE ?: createDatabase(context.applicationContext).also { INSTANCE = it }
                try {
                    verifyDatabaseCanOpen(current)
                } catch (error: Throwable) {
                    runCatching { current.close() }
                    INSTANCE = null
                    INSTANCE = openVerifiedDatabase(context.applicationContext, allowRecovery = true)
                }
            }
        }

        private fun openVerifiedDatabase(context: Context, allowRecovery: Boolean): AppDatabase {
            var candidate: AppDatabase? = null
            return try {
                candidate = createDatabase(context)
                verifyDatabaseCanOpen(candidate)
                candidate
            } catch (e: Throwable) {
                runCatching { candidate?.close() }
                if (!allowRecovery) throw e

                val messages = extractExceptionMessages(e)

                val isRealSchemaMismatch = messages.any { it.contains("identity hash") }
                val isCorruption = messages.any { it.contains("cannot verify the data integrity") }

                when {
                    isRealSchemaMismatch -> {

                        SecureLog.w("AppDatabase", "Schema identity hash mismatch detected")
                        backupBeforeRecovery(context)
                        if (tryRecoverFromBackup(context)) {
                            SecureLog.i("AppDatabase", "Database restored from recovery backup")
                            createDatabase(context).also { verifyDatabaseCanOpen(it) }
                        } else {
                            SecureLog.w("AppDatabase", "No valid backup, recreating database (data loss unavoidable)")
                            deleteDatabaseFiles(context)
                            createDatabase(context).also {
                                verifyDatabaseCanOpen(it)
                                SecureLog.i("AppDatabase", "Database recreated after schema mismatch")
                            }
                        }
                    }
                    isCorruption && !isRealSchemaMismatch -> {

                        SecureLog.w("AppDatabase", "Database corruption detected, attempting repair...")
                        backupBeforeRecovery(context)

                        if (tryRepairWalFiles(context)) {
                            SecureLog.i("AppDatabase", "WAL repair succeeded")
                            runCatching { createDatabase(context).also { verifyDatabaseCanOpen(it) } }.getOrElse {

                                recoverDatabase(context)
                                createDatabase(context).also {
                                    verifyDatabaseCanOpen(it)
                                    SecureLog.i("AppDatabase", "Database recovered from backup after failed WAL repair")
                                }
                            }
                        } else {

                            recoverDatabase(context)
                            createDatabase(context).also {
                                verifyDatabaseCanOpen(it)
                                SecureLog.i("AppDatabase", "Database recovered from backup")
                            }
                        }
                    }
                    else -> {

                        SecureLog.e("AppDatabase", "Unexpected database error: ${e.message}", e)
                        backupBeforeRecovery(context)
                        recoverDatabase(context)
                        createDatabase(context).also {
                            verifyDatabaseCanOpen(it)
                            SecureLog.i("AppDatabase", "Database recovered after unknown error")
                        }
                    }
                }
            }
        }

        private fun extractExceptionMessages(e: Throwable): List<String> {
            val messages = mutableListOf<String>()
            var current: Throwable? = e
            while (current != null) {
                current.message?.let { messages.add(it) }
                current = current.cause
                if (current == e) break
            }
            return messages
        }

        private fun tryRepairWalFiles(context: Context): Boolean {
            val dbFile = context.getDatabasePath(DB_NAME)
            val walFile = File(dbFile.path + "-wal")
            val shmFile = File(dbFile.path + "-shm")

            if (!dbFile.exists() || dbFile.length() < 512) return false

            var repaired = false
            if (walFile.exists()) {
                repaired = walFile.delete()
                SecureLog.i("AppDatabase", "Deleted corrupted WAL file: $repaired")
            }
            if (shmFile.exists()) {
                val deleted = shmFile.delete()
                repaired = repaired || deleted
                SecureLog.i("AppDatabase", "Deleted corrupted SHM file: $deleted")
            }
            return repaired || dbFile.exists()
        }

        private fun tryRecoverFromBackup(context: Context): Boolean {
            val dbFile = context.getDatabasePath(DB_NAME)
            val recoveryDir = File(dbFile.parentFile, "recovery")
            if (!recoveryDir.exists()) return false

            val backups = recoveryDir.listFiles()
                ?.filter { it.name.endsWith(".db") || it.name.contains("corrupted") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            for (backup in backups) {
                if (backup.length() > 1024) {

                    backup.copyTo(dbFile, overwrite = true)

                    listOf("-wal", "-shm", "-journal").forEach { suffix ->
                        val backupAux = File(backup.parentFile, "${backup.name}$suffix")
                        val targetAux = File(dbFile.path + suffix)
                        if (backupAux.exists() && backupAux.length() > 0) {
                            runCatching { backupAux.copyTo(targetAux, overwrite = true) }
                        }
                    }
                    SecureLog.i("AppDatabase", "Restored from backup: ${backup.name}")
                    return true
                }
            }
            return false
        }

        private fun deleteDatabaseFiles(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-journal").delete()
        }

        private fun backupBeforeRecovery(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return

            val recoveryDir = File(dbFile.parentFile, "recovery")
            recoveryDir.mkdirs()
            val timestamp = System.currentTimeMillis().toString()

            listOf(
                dbFile,
                File(dbFile.path + "-wal"),
                File(dbFile.path + "-shm"),
                File(dbFile.path + "-journal")
            ).filter { it.exists() }.forEach { file ->
                val target = File(recoveryDir, "${file.name}.corrupted_$timestamp")
                runCatching { file.copyTo(target, overwrite = true) }
            }
        }

        private fun recoverDatabase(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            val recoveryDir = File(dbFile.parentFile, "recovery")

            if (!recoveryDir.exists()) return

            val backups = recoveryDir.listFiles()
                ?.filter { it.name.endsWith(".db") || it.name.contains("corrupted") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (backups.isNotEmpty()) {
                val latestBackup = backups.first()
                if (latestBackup.length() > 1024) {
                    latestBackup.copyTo(dbFile, overwrite = true)
                    SecureLog.i("AppDatabase", "Restored database from ${latestBackup.name}")
                    return
                }
            }

            dbFile.delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            File(dbFile.path + "-journal").delete()
        }

        private fun createDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )

                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        seedApiProviderPresets(db)

                    }
                    override fun onOpen(db: SupportSQLiteDatabase) {

                    }
                })
                .build()
        }

        private fun verifyDatabaseCanOpen(database: AppDatabase) {

            try {
                database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            } catch (_: Exception) { }
            database.openHelper.writableDatabase.query("PRAGMA user_version").close()
        }

        private fun backupBrokenDatabase(context: Context) {
            val dbFile = context.applicationContext.getDatabasePath(DB_NAME)
            val candidates = listOf(
                dbFile,
                File(dbFile.path + "-wal"),
                File(dbFile.path + "-shm"),
                File(dbFile.path + "-journal")
            ).filter { it.exists() }

            if (candidates.isEmpty()) return

            val backupDir = File(dbFile.parentFile, "recovery")
            backupDir.mkdirs()
            val suffix = System.currentTimeMillis().toString()

            candidates.forEach { file ->
                val target = File(backupDir, "${file.name}.broken.$suffix")
                if (!file.renameTo(target)) {
                    runCatching { file.copyTo(target, overwrite = true) }
                    runCatching { file.delete() }
                }
            }
        }

        private fun addColumnIfMissing(
            db: SupportSQLiteDatabase,
            tableName: String,
            columnName: String,
            columnDefinition: String
        ) {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) return
                }
            }
            db.execSQL("ALTER TABLE `$tableName` ADD COLUMN $columnName $columnDefinition")
        }

        private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) return true
                }
            }
            return false
        }

        private fun rebuildApiConfigsToCurrentSchema(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `api_configs_new`")
            db.execSQL(
                """
                CREATE TABLE `api_configs_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `apiKey` TEXT NOT NULL,
                    `extraApiKeys` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `temperature` REAL NOT NULL,
                    `maxTokens` INTEGER,
                    `isEnabled` INTEGER NOT NULL,
                    `connectionTested` INTEGER NOT NULL,
                    `connectionTestedAt` INTEGER NOT NULL,
                    `latencyMs` INTEGER NOT NULL,
                    `formatHint` TEXT NOT NULL
                )
                """.trimIndent()
            )
            val hasFormatHint = hasColumn(db, "api_configs", "formatHint")
            val hasExtraKeys = hasColumn(db, "api_configs", "extraApiKeys")
            val formatExpr = if (hasFormatHint) {
                "COALESCE(NULLIF(`formatHint`, ''), 'openai')"
            } else {
                "'openai'"
            }
            val extraExpr = if (hasExtraKeys) {
                "COALESCE(`extraApiKeys`, '')"
            } else {
                "''"
            }
            db.execSQL(
                """
                INSERT INTO `api_configs_new` (
                    `id`, `provider`, `name`, `apiKey`, `extraApiKeys`, `baseUrl`, `model`,
                    `temperature`, `maxTokens`, `isEnabled`, `connectionTested`,
                    `connectionTestedAt`, `latencyMs`, `formatHint`
                )
                SELECT
                    `id`, `provider`, COALESCE(`name`, ''), `apiKey`, $extraExpr, `baseUrl`, `model`,
                    COALESCE(`temperature`, 0.7), `maxTokens`, COALESCE(`isEnabled`, 1),
                    COALESCE(`connectionTested`, 0), COALESCE(`connectionTestedAt`, 0),
                    COALESCE(`latencyMs`, 0), $formatExpr
                FROM `api_configs`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `api_configs`")
            db.execSQL("ALTER TABLE `api_configs_new` RENAME TO `api_configs`")
        }

        private fun rebuildApiProviderPresetsToCurrentSchema(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "api_provider_presets")) return
            if (!hasColumn(db, "api_provider_presets", "skipCertVerify")) return

            db.execSQL("DROP TABLE IF EXISTS `api_provider_presets_new`")
            db.execSQL(
                """
                CREATE TABLE `api_provider_presets_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `formatHint` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `isVisible` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val hasFormatHint = hasColumn(db, "api_provider_presets", "formatHint")
            val formatExpr = if (hasFormatHint) {
                "COALESCE(NULLIF(`formatHint`, ''), 'openai')"
            } else {
                "'openai'"
            }
            db.execSQL(
                """
                INSERT INTO `api_provider_presets_new` (
                    `id`, `provider`, `displayName`, `baseUrl`, `model`,
                    `formatHint`, `sortOrder`, `isVisible`, `updatedAt`
                )
                SELECT
                    `id`, `provider`, `displayName`, `baseUrl`, `model`,
                    $formatExpr,
                    COALESCE(`sortOrder`, 0),
                    COALESCE(`isVisible`, 1),
                    COALESCE(`updatedAt`, 0)
                FROM `api_provider_presets`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `api_provider_presets`")
            db.execSQL("ALTER TABLE `api_provider_presets_new` RENAME TO `api_provider_presets`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_api_provider_presets_provider` ON `api_provider_presets` (`provider`)"
            )
        }

        private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(tableName)
            ).use { cursor ->
                return cursor.moveToFirst()
            }
        }

        private fun migrateLegacyTo6(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `companions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `avatarUrl` TEXT,
                    `age` INTEGER,
                    `personality` TEXT NOT NULL DEFAULT '',
                    `backstory` TEXT,
                    `speakingStyle` TEXT,
                    `tags` TEXT,
                    `rawPrompt` TEXT,
                    `systemPrompt` TEXT,
                    `intimacy` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "companions", "avatarUrl", "TEXT")
            addColumnIfMissing(db, "companions", "age", "INTEGER")
            addColumnIfMissing(db, "companions", "personality", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "companions", "backstory", "TEXT")
            addColumnIfMissing(db, "companions", "speakingStyle", "TEXT")
            addColumnIfMissing(db, "companions", "tags", "TEXT")
            addColumnIfMissing(db, "companions", "rawPrompt", "TEXT")
            addColumnIfMissing(db, "companions", "systemPrompt", "TEXT")
            addColumnIfMissing(db, "companions", "intimacy", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "companions", "createdAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "companions", "updatedAt", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `chat_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `isFromUser` INTEGER NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `type` TEXT NOT NULL DEFAULT 'TEXT',
                    `searchContent` TEXT NOT NULL DEFAULT '',
                    `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                    `linkString` TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(`companionId`) REFERENCES `companions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            addColumnIfMissing(db, "chat_messages", "type", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "chat_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId` ON `chat_messages` (`companionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_timestamp` ON `chat_messages` (`companionId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_fileFormat` ON `chat_messages` (`companionId`, `fileFormat`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `api_configs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `apiKey` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `temperature` REAL NOT NULL DEFAULT 0.7,
                    `maxTokens` INTEGER,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `connectionTested` INTEGER NOT NULL DEFAULT 0,
                    `connectionTestedAt` INTEGER NOT NULL DEFAULT 0,
                    `latencyMs` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "api_configs", "id", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "name", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "api_configs", "temperature", "REAL NOT NULL DEFAULT 0.7")
            addColumnIfMissing(db, "api_configs", "maxTokens", "INTEGER")
            addColumnIfMissing(db, "api_configs", "isEnabled", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "latencyMs", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `memory_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `category` TEXT NOT NULL DEFAULT 'FACT',
                    `importance` REAL NOT NULL DEFAULT 0.5,
                    `context` TEXT NOT NULL DEFAULT '',
                    `accessCount` INTEGER NOT NULL DEFAULT 1,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `lastAccessed` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            addColumnIfMissing(db, "memory_entries", "category", "TEXT NOT NULL DEFAULT 'FACT'")
            addColumnIfMissing(db, "memory_entries", "importance", "REAL NOT NULL DEFAULT 0.5")
            addColumnIfMissing(db, "memory_entries", "context", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "memory_entries", "accessCount", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "memory_entries", "lastAccessed", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `temp_memory` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `userInput` TEXT NOT NULL,
                    `botResponse` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `chat_groups` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `avatarUrl` TEXT,
                    `companionIds` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `group_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `groupId` INTEGER NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `content` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL DEFAULT 0,
                    `searchContent` TEXT NOT NULL DEFAULT '',
                    `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                    `linkString` TEXT NOT NULL DEFAULT '',
                    FOREIGN KEY(`groupId`) REFERENCES `chat_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId` ON `group_messages` (`groupId`)")
            addColumnIfMissing(db, "group_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "group_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "group_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_timestamp` ON `group_messages` (`groupId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_fileFormat` ON `group_messages` (`groupId`, `fileFormat`)")
        }

        val MIGRATION_1_6 = object : Migration(1, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_2_6 = object : Migration(2, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_3_6 = object : Migration(3, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateLegacyTo6(db)
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        private fun migrateApiConfigsTo9(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "api_configs", "name", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "api_configs", "temperature", "REAL NOT NULL DEFAULT 0.7")
            addColumnIfMissing(db, "api_configs", "maxTokens", "INTEGER")
            addColumnIfMissing(db, "api_configs", "isEnabled", "INTEGER NOT NULL DEFAULT 1")
            addColumnIfMissing(db, "api_configs", "connectionTested", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "connectionTestedAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "api_configs", "latencyMs", "INTEGER NOT NULL DEFAULT 0")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `api_configs_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `name` TEXT NOT NULL DEFAULT '',
                    `apiKey` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `temperature` REAL NOT NULL DEFAULT 0.7,
                    `maxTokens` INTEGER,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `connectionTested` INTEGER NOT NULL DEFAULT 0,
                    `connectionTestedAt` INTEGER NOT NULL DEFAULT 0,
                    `latencyMs` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO `api_configs_new` (`id`, `provider`, `name`, `apiKey`, `baseUrl`, `model`, `temperature`, `maxTokens`, `isEnabled`, `connectionTested`, `connectionTestedAt`, `latencyMs`)
                SELECT `id`, `provider`, `name`, `apiKey`, `baseUrl`, `model`, `temperature`, `maxTokens`, `isEnabled`, `connectionTested`, `connectionTestedAt`, `latencyMs`
                FROM `api_configs`
            """.trimIndent())
            db.execSQL("DROP TABLE `api_configs`")
            db.execSQL("ALTER TABLE `api_configs_new` RENAME TO `api_configs`")
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateApiConfigsTo9(db)
        }

        private fun migrateChatStorageTo10(db: SupportSQLiteDatabase) {
            addColumnIfMissing(db, "chat_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "chat_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "chat_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `chat_messages` SET `searchContent` = `content` WHERE `searchContent` = ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_timestamp` ON `chat_messages` (`companionId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_companionId_fileFormat` ON `chat_messages` (`companionId`, `fileFormat`)")

            addColumnIfMissing(db, "group_messages", "searchContent", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(db, "group_messages", "fileFormat", "TEXT NOT NULL DEFAULT 'TEXT'")
            addColumnIfMissing(db, "group_messages", "linkString", "TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `group_messages` SET `searchContent` = `content` WHERE `searchContent` = ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_timestamp` ON `group_messages` (`groupId`, `timestamp`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_messages_groupId_fileFormat` ON `group_messages` (`groupId`, `fileFormat`)")
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateChatStorageTo10(db)
        }

        private fun migrateSecurityTablesTo11(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `keywords` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `keyword` TEXT NOT NULL,
                    `pattern` TEXT,
                    `level` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `banDays` INTEGER NOT NULL,
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `checksum` TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_keywords_level` ON `keywords` (`level`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_keywords_type` ON `keywords` (`type`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `quiz_questions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `question` TEXT NOT NULL,
                    `options` TEXT NOT NULL,
                    `correctIndex` INTEGER NOT NULL,
                    `category` TEXT NOT NULL,
                    `difficulty` TEXT NOT NULL DEFAULT 'MEDIUM',
                    `isEnabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `checksum` TEXT NOT NULL DEFAULT ''
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_category` ON `quiz_questions` (`category`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_quiz_difficulty` ON `quiz_questions` (`difficulty`)")
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateSecurityTablesTo11(db)
        }

        private fun migrateTokenUsageTo12(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `token_usage` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `companionId` INTEGER NOT NULL,
                    `date` TEXT NOT NULL,
                    `inputTokens` INTEGER NOT NULL DEFAULT 0,
                    `outputTokens` INTEGER NOT NULL DEFAULT 0,
                    `totalTokens` INTEGER NOT NULL DEFAULT 0,
                    `requestCount` INTEGER NOT NULL DEFAULT 0,
                    `timestamp` INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_companionId_date` ON `token_usage` (`companionId`, `date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_date` ON `token_usage` (`date`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_companionId` ON `token_usage` (`companionId`)")
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) = migrateTokenUsageTo12(db)
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "chat_messages", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "memory_entries", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "temp_memory", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateChatStorageTo10(db)
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP INDEX IF EXISTS `index_token_usage_companionId_date`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_token_usage_companionId_date_deviceId` ON `token_usage` (`companionId`, `date`, `deviceId`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "extraApiKeys", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {

                addColumnIfMissing(db, "chat_messages", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "memory_entries", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "temp_memory", "deviceId", "TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "token_usage", "deviceId", "TEXT NOT NULL DEFAULT ''")

                db.execSQL("DROP INDEX IF EXISTS index_chat_messages_companionId_deviceId")
                db.execSQL("DROP INDEX IF EXISTS index_memory_entries_companionId_deviceId")
                db.execSQL("DROP INDEX IF EXISTS index_temp_memory_companionId_deviceId")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "skipCertVerify", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "api_configs", "formatHint", "TEXT NOT NULL DEFAULT 'openai'")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `unified_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memoryType` TEXT NOT NULL DEFAULT 'SEMANTIC',
                        `scope` TEXT NOT NULL DEFAULT 'COMPANION',
                        `source` TEXT NOT NULL DEFAULT 'CHAT',
                        `content` TEXT NOT NULL,
                        `summary` TEXT NOT NULL DEFAULT '',
                        `confidence` REAL NOT NULL DEFAULT 1.0,
                        `importance` REAL NOT NULL DEFAULT 0.5,
                        `sourceId` INTEGER NOT NULL DEFAULT 0,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastAccessedAt` INTEGER NOT NULL,
                        `observedAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER,
                        `validFrom` INTEGER,
                        `validTo` INTEGER,
                        `temporalAnchor` TEXT NOT NULL DEFAULT '',
                        `accessCount` INTEGER NOT NULL DEFAULT 1,
                        `tags` TEXT NOT NULL DEFAULT '',
                        `fuzzyHints` TEXT NOT NULL DEFAULT '',
                        `mergedFrom` TEXT NOT NULL DEFAULT '',
                        `isDeleted` INTEGER NOT NULL DEFAULT 0,
                        `version` INTEGER NOT NULL DEFAULT 1,
                        `deviceId` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_scope_sourceId` ON `unified_memories` (`deviceId`, `scope`, `sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_memoryType` ON `unified_memories` (`deviceId`, `memoryType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_observedAt` ON `unified_memories` (`deviceId`, `observedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_deviceId_importance` ON `unified_memories` (`deviceId`, `importance`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_isDeleted` ON `unified_memories` (`isDeleted`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_unified_memories_expiresAt` ON `unified_memories` (`expiresAt`)")

                db.execSQL("""
                    INSERT OR IGNORE INTO `unified_memories` (
                        `memoryType`, `scope`, `source`, `content`, `summary`,
                        `confidence`, `importance`, `sourceId`,
                        `createdAt`, `updatedAt`, `lastAccessedAt`, `observedAt`,
                        `accessCount`, `tags`, `deviceId`
                    )
                    SELECT
                        CASE `category`
                            WHEN 'EMOTION' THEN 'EPISODIC'
                            WHEN 'EVENT' THEN 'EPISODIC'
                            WHEN 'PREFERENCE' THEN 'PREFERENCE'
                            WHEN 'HABIT' THEN 'PREFERENCE'
                            WHEN 'RELATIONSHIP' THEN 'RELATIONSHIP'
                            ELSE 'SEMANTIC'
                        END,
                        'COMPANION',
                        'CHAT',
                        `content`, '',
                        0.7, `importance`, `companionId`,
                        `timestamp`, `timestamp`, `lastAccessed`, `timestamp`,
                        `accessCount`, '', `deviceId`
                    FROM `memory_entries`
                    WHERE `deviceId` != ''
                """.trimIndent())

                db.execSQL("""
                    INSERT OR IGNORE INTO `unified_memories` (
                        `memoryType`, `scope`, `source`, `content`, `summary`,
                        `confidence`, `importance`, `sourceId`,
                        `createdAt`, `updatedAt`, `lastAccessedAt`, `observedAt`,
                        `expiresAt`, `accessCount`, `tags`, `deviceId`
                    )
                    SELECT
                        'WORKING', 'COMPANION', 'CHAT',
                        `userInput` || ' | ' || `botResponse`, '',
                        0.5, 0.3, `companionId`,
                        `timestamp`, `timestamp`, `timestamp`, `timestamp`,
                        `timestamp` + 86400000, 1, '', `deviceId`
                    FROM `temp_memory`
                    WHERE `deviceId` != ''
                """.trimIndent())
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {

                addColumnIfMissing(db, "unified_memories", "embedding", "BLOB")
                addColumnIfMissing(db, "unified_memories", "embeddingModel", "TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `diary_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `companionId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL DEFAULT '',
                        `content` TEXT NOT NULL,
                        `mood` INTEGER NOT NULL DEFAULT 2,
                        `date` INTEGER NOT NULL,
                        `weather` TEXT NOT NULL DEFAULT '',
                        `tags` TEXT NOT NULL DEFAULT '',
                        `deviceId` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_companionId_deviceId` ON `diary_entries` (`companionId`, `deviceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_date` ON `diary_entries` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_deviceId` ON `diary_entries` (`deviceId`)")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createApiProviderPresetTable(db)
                seedApiProviderPresets(db)
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_chat_msg_comp` ON `chat_messages` (`companionId`, `timestamp` DESC, `id` DESC)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_group_msg_comp` ON `group_messages` (`groupId`, `timestamp` DESC)"
                )

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `conversation_summary` (
                        `sessionId` INTEGER NOT NULL,
                        `sessionType` TEXT NOT NULL,
                        `lastMessagePreview` TEXT NOT NULL,
                        `lastMessageTimestamp` INTEGER NOT NULL,
                        `lastMessageIsFromUser` INTEGER NOT NULL,
                        `unreadCount` INTEGER NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `isMuted` INTEGER NOT NULL,
                        PRIMARY KEY (`sessionId`, `sessionType`)
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_summary_type_time` ON `conversation_summary` (`sessionType`, `lastMessageTimestamp`)"
                )

            }
        }

        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL("DROP INDEX IF EXISTS `index_chat_messages_companionId`")
                db.execSQL("DROP INDEX IF EXISTS `index_chat_messages_timestamp`")

                db.execSQL("DROP INDEX IF EXISTS `index_group_messages_groupId`")
                db.execSQL("DROP INDEX IF EXISTS `index_group_messages_groupId_timestamp`")
                db.execSQL("DROP INDEX IF EXISTS `index_group_messages_groupId_fileFormat`")

                db.execSQL("DROP INDEX IF EXISTS `idx_group_msg_comp`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_group_msg_comp` ON `group_messages` (`groupId`, `timestamp` DESC, `id` DESC)"
                )
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `conversationType` TEXT NOT NULL,
                        `isFromUser` INTEGER NOT NULL,
                        `senderId` INTEGER NOT NULL DEFAULT 0,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL DEFAULT 0,
                        `type` TEXT NOT NULL DEFAULT 'TEXT',
                        `searchContent` TEXT NOT NULL DEFAULT '',
                        `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                        `linkString` TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_messages_conv` ON `messages` (`conversationId`, `timestamp` DESC, `id` DESC)"
                )

                db.execSQL("""
                    INSERT OR IGNORE INTO `messages`
                        (`id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                         `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`)
                    SELECT
                        `id`, `companionId`, 'chat', `isFromUser`, 0,
                        `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`
                    FROM `chat_messages`
                """.trimIndent())

                db.execSQL("""
                    INSERT OR IGNORE INTO `messages`
                        (`id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                         `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`)
                    SELECT
                        `id`, `groupId`, 'group',
                        CASE WHEN `companionId` = -1 THEN 1 ELSE 0 END,
                        `companionId`,
                        `content`, `timestamp`, 'TEXT', `searchContent`, `fileFormat`, `linkString`
                    FROM `group_messages`
                """.trimIndent())
            }
        }

        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `messages_v28`")
                db.execSQL(
                    """
                    CREATE TABLE `messages_v28` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `conversationType` TEXT NOT NULL,
                        `isFromUser` INTEGER NOT NULL,
                        `senderId` INTEGER NOT NULL DEFAULT 0,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL DEFAULT 0,
                        `type` TEXT NOT NULL DEFAULT 'TEXT',
                        `searchContent` TEXT NOT NULL DEFAULT '',
                        `fileFormat` TEXT NOT NULL DEFAULT 'TEXT',
                        `linkString` TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO `messages_v28` (
                        `id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                        `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`
                    )
                    SELECT
                        `id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                        `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`
                    FROM `messages`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `messages_v28` (
                        `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                        `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`
                    )
                    SELECT
                        legacy.`companionId`, 'chat', legacy.`isFromUser`, 0,
                        legacy.`content`, legacy.`timestamp`, legacy.`type`, legacy.`searchContent`,
                        legacy.`fileFormat`, legacy.`linkString`
                    FROM `chat_messages` AS legacy
                    WHERE NOT EXISTS (
                        SELECT 1 FROM `messages_v28` AS current
                        WHERE current.`conversationType` = 'chat'
                          AND current.`conversationId` = legacy.`companionId`
                          AND current.`isFromUser` = legacy.`isFromUser`
                          AND current.`content` = legacy.`content`
                          AND current.`timestamp` = legacy.`timestamp`
                          AND current.`type` = legacy.`type`
                          AND current.`searchContent` = legacy.`searchContent`
                          AND current.`fileFormat` = legacy.`fileFormat`
                          AND current.`linkString` = legacy.`linkString`
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `messages_v28` (
                        `conversationId`, `conversationType`, `isFromUser`, `senderId`,
                        `content`, `timestamp`, `type`, `searchContent`, `fileFormat`, `linkString`
                    )
                    SELECT
                        legacy.`groupId`, 'group',
                        CASE WHEN legacy.`companionId` = -1 THEN 1 ELSE 0 END,
                        legacy.`companionId`, legacy.`content`, legacy.`timestamp`, 'TEXT',
                        legacy.`searchContent`, legacy.`fileFormat`, legacy.`linkString`
                    FROM `group_messages` AS legacy
                    WHERE NOT EXISTS (
                        SELECT 1 FROM `messages_v28` AS current
                        WHERE current.`conversationType` = 'group'
                          AND current.`conversationId` = legacy.`groupId`
                          AND current.`senderId` = legacy.`companionId`
                          AND current.`content` = legacy.`content`
                          AND current.`timestamp` = legacy.`timestamp`
                          AND current.`searchContent` = legacy.`searchContent`
                          AND current.`fileFormat` = legacy.`fileFormat`
                          AND current.`linkString` = legacy.`linkString`
                    )
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_v28` RENAME TO `messages`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_messages_conv` ON `messages` (`conversationId`, `timestamp` DESC, `id` DESC)"
                )
                db.execSQL("DROP TABLE `chat_messages`")
                db.execSQL("DROP TABLE `group_messages`")
            }
        }

        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `idx_messages_conv`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_messages_conv` ON `messages` (`conversationType`, `conversationId`, `timestamp` DESC, `id` DESC)"
                )
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `message_bodies_v30` (
                        `messageId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `searchContent` TEXT NOT NULL DEFAULT '',
                        `linkString` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`messageId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `message_bodies_v30` (`messageId`, `content`, `searchContent`, `linkString`)
                    SELECT `id`, `content`, `searchContent`, `linkString` FROM `messages`
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE `messages_v30` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `conversationType` TEXT NOT NULL,
                        `isFromUser` INTEGER NOT NULL,
                        `senderId` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `fileFormat` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `messages_v30` (`id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`, `timestamp`, `type`, `fileFormat`)
                    SELECT `id`, `conversationId`, `conversationType`, `isFromUser`, `senderId`, `timestamp`, `type`, `fileFormat` FROM `messages`
                    """.trimIndent()
                )
                db.execSQL("DROP INDEX IF EXISTS `idx_messages_conv`")
                db.execSQL("DROP TABLE `messages`")
                db.execSQL("ALTER TABLE `messages_v30` RENAME TO `messages`")
                db.execSQL("CREATE INDEX `idx_messages_conv` ON `messages` (`conversationType`, `conversationId`, `timestamp` DESC, `id` DESC)")
                db.execSQL(
                    """
                    CREATE TABLE `message_bodies` (
                        `messageId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `searchContent` TEXT NOT NULL DEFAULT '',
                        `linkString` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `message_bodies` (`messageId`, `content`, `searchContent`, `linkString`)
                    SELECT `messageId`, `content`, `searchContent`, `linkString` FROM `message_bodies_v30`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `message_bodies_v30`")
                db.execSQL("ALTER TABLE `conversation_summary` ADD COLUMN `lastMessageId` INTEGER")
                db.execSQL("ALTER TABLE `conversation_summary` ADD COLUMN `readThroughMessageId` INTEGER")
                db.execSQL(
                    """
                    UPDATE `conversation_summary`
                    SET `lastMessageId` = (
                        SELECT `id` FROM `messages`
                        WHERE `conversationType` = `conversation_summary`.`sessionType`
                          AND `conversationId` = `conversation_summary`.`sessionId`
                        ORDER BY `timestamp` DESC, `id` DESC LIMIT 1
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation_summary` ADD COLUMN `readThroughMessageTimestamp` INTEGER")
                db.execSQL(
                    """
                    UPDATE `conversation_summary`
                    SET `readThroughMessageTimestamp` = (
                        SELECT `timestamp` FROM `messages`
                        WHERE `id` = `conversation_summary`.`readThroughMessageId`
                    )
                    WHERE `readThroughMessageId` IS NOT NULL
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `archived_messages` (
                        `id` INTEGER NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `conversationType` TEXT NOT NULL,
                        `isFromUser` INTEGER NOT NULL,
                        `senderId` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `fileFormat` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_archived_messages_conv` ON `archived_messages` (`conversationType`, `conversationId`, `timestamp` DESC, `id` DESC)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `archived_message_bodies` (
                        `messageId` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `searchContent` TEXT NOT NULL,
                        `linkString` TEXT NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`messageId`) REFERENCES `archived_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
            }
        }

        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `message_search_index` USING FTS4(`tokens` TEXT NOT NULL)"
                )
                db.query(
                    """SELECT messageId, searchContent FROM message_bodies
                       UNION ALL
                       SELECT messageId, searchContent FROM archived_message_bodies""".trimIndent()
                ).use { cursor ->
                    val messageIdIndex = cursor.getColumnIndexOrThrow("messageId")
                    val contentIndex = cursor.getColumnIndexOrThrow("searchContent")
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO message_search_index(rowid, tokens) VALUES (?, ?)",
                            arrayOf<Any>(
                                cursor.getLong(messageIdIndex),
                                MessageSearchTokenizer.indexTokens(cursor.getString(contentIndex))
                            )
                        )
                    }
                }
            }
        }

        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {

                rebuildApiConfigsToCurrentSchema(db)
                rebuildApiProviderPresetsToCurrentSchema(db)
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "messages", "turnId", "TEXT")
                addColumnIfMissing(db, "messages", "eventIndex", "INTEGER")
                addColumnIfMissing(db, "messages", "durationMs", "INTEGER")
                addColumnIfMissing(db, "messages", "anchorMessageId", "INTEGER")
                addColumnIfMissing(db, "archived_messages", "turnId", "TEXT")
                addColumnIfMissing(db, "archived_messages", "eventIndex", "INTEGER")
                addColumnIfMissing(db, "archived_messages", "durationMs", "INTEGER")
                addColumnIfMissing(db, "archived_messages", "anchorMessageId", "INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_messages_turn` ON `messages` (`turnId` ASC, `eventIndex` ASC, `id` ASC)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_archived_messages_turn` ON `archived_messages` (`turnId` ASC, `eventIndex` ASC, `id` ASC)"
                )
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wechat_outbox` (
                        `id` TEXT NOT NULL,
                        `rootId` TEXT NOT NULL,
                        `companionId` INTEGER NOT NULL,
                        `wechatUserId` TEXT NOT NULL,
                        `kind` INTEGER NOT NULL,
                        `text` TEXT,
                        `mediaLocalPath` TEXT,
                        `mediaFileName` TEXT,
                        `mediaDescription` TEXT,
                        `segmentIndex` INTEGER NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        `contextToken` TEXT,
                        `sourceMessageId` INTEGER,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `nextAttemptAtMs` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_status_nextAttemptAtMs` ON `wechat_outbox` (`status`, `nextAttemptAtMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_wechatUserId_status` ON `wechat_outbox` (`wechatUserId`, `status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_rootId` ON `wechat_outbox` (`rootId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_outbox_companionId` ON `wechat_outbox` (`companionId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `wechat_inbox_dedupe` (
                        `dedupeKey` TEXT NOT NULL,
                        `messageId` INTEGER,
                        `fromUserId` TEXT NOT NULL,
                        `processedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`dedupeKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_inbox_dedupe_processedAtMs` ON `wechat_inbox_dedupe` (`processedAtMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wechat_inbox_dedupe_fromUserId` ON `wechat_inbox_dedupe` (`fromUserId`)",
                )
            }
        }

        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").use { cursor ->
                    cursor.moveToFirst()
                }
                db.execSQL(
                    """
                    CREATE TABLE `wechat_outbox_new` (
                        `id` TEXT NOT NULL,
                        `rootId` TEXT NOT NULL,
                        `companionId` INTEGER NOT NULL,
                        `wechatUserId` TEXT NOT NULL,
                        `kind` INTEGER NOT NULL,
                        `text` TEXT,
                        `mediaLocalPath` TEXT,
                        `mediaFileName` TEXT,
                        `mediaDescription` TEXT,
                        `segmentIndex` INTEGER NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        `sourceMessageId` INTEGER,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `nextAttemptAtMs` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `wechat_outbox_new` (
                        `id`, `rootId`, `companionId`, `wechatUserId`, `kind`, `text`,
                        `mediaLocalPath`, `mediaFileName`, `mediaDescription`, `segmentIndex`,
                        `segmentCount`, `sourceMessageId`, `status`, `retryCount`,
                        `nextAttemptAtMs`, `lastError`, `createdAtMs`, `updatedAtMs`
                    )
                    SELECT
                        `id`, `rootId`, `companionId`, `wechatUserId`, `kind`, `text`,
                        `mediaLocalPath`, `mediaFileName`, `mediaDescription`, `segmentIndex`,
                        `segmentCount`, `sourceMessageId`, `status`, `retryCount`,
                        `nextAttemptAtMs`, `lastError`, `createdAtMs`, `updatedAtMs`
                    FROM `wechat_outbox`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `wechat_outbox`")
                db.execSQL("ALTER TABLE `wechat_outbox_new` RENAME TO `wechat_outbox`")
                db.execSQL(
                    "CREATE INDEX `index_wechat_outbox_status_nextAttemptAtMs` ON `wechat_outbox` (`status`, `nextAttemptAtMs`)",
                )
                db.execSQL(
                    "CREATE INDEX `index_wechat_outbox_wechatUserId_status` ON `wechat_outbox` (`wechatUserId`, `status`)",
                )
                db.execSQL("CREATE INDEX `index_wechat_outbox_rootId` ON `wechat_outbox` (`rootId`)")
                db.execSQL("CREATE INDEX `index_wechat_outbox_companionId` ON `wechat_outbox` (`companionId`)")
            }
        }

        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `lorebooks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `companionId` INTEGER,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebooks_companionId` ON `lorebooks` (`companionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebooks_enabled` ON `lorebooks` (`enabled`)")

                db.execSQL(
                    """
                    CREATE TABLE `lorebook_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lorebookId` INTEGER NOT NULL,
                        `keywordsJson` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `injectionPosition` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL DEFAULT 0,
                        `injectDepth` INTEGER,
                        `role` TEXT NOT NULL,
                        `caseSensitive` INTEGER NOT NULL DEFAULT 0,
                        `scanDepth` INTEGER NOT NULL DEFAULT 10,
                        `constantActive` INTEGER NOT NULL DEFAULT 0,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_lorebookId` ON `lorebook_entries` (`lorebookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_enabled` ON `lorebook_entries` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_priority` ON `lorebook_entries` (`priority`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_injectionPosition` ON `lorebook_entries` (`injectionPosition`)")
            }
        }

        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL(
                    """
                    CREATE TABLE `lorebook_entries_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lorebookId` INTEGER NOT NULL,
                        `keywordsJson` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `injectionPosition` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL DEFAULT 0,
                        `injectDepth` INTEGER,
                        `role` TEXT NOT NULL,
                        `caseSensitive` INTEGER NOT NULL DEFAULT 0,
                        `scanDepth` INTEGER NOT NULL DEFAULT 10,
                        `constantActive` INTEGER NOT NULL DEFAULT 0,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `lorebook_entries_new` (
                        `id`, `lorebookId`, `keywordsJson`, `content`, `injectionPosition`,
                        `priority`, `injectDepth`, `role`, `caseSensitive`, `scanDepth`,
                        `constantActive`, `enabled`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `lorebookId`, `keywordsJson`, `content`,
                        CASE
                            WHEN `injectionPosition` = 0 THEN 'BEFORE_SYSTEM_PROMPT'
                            WHEN `injectionPosition` = 1 THEN 'AFTER_SYSTEM_PROMPT'
                            WHEN `injectionPosition` = 2 THEN 'TOP_OF_CHAT'
                            WHEN `injectionPosition` = 3 THEN 'BOTTOM_OF_CHAT'
                            WHEN `injectionPosition` = 4 THEN 'AT_DEPTH'
                            ELSE 'BEFORE_SYSTEM_PROMPT'
                        END,
                        `priority`, `injectDepth`,
                        CASE
                            WHEN `role` = 0 THEN 'SYSTEM'
                            WHEN `role` = 1 THEN 'USER'
                            WHEN `role` = 2 THEN 'ASSISTANT'
                            ELSE 'SYSTEM'
                        END,
                        `caseSensitive`, `scanDepth`, `constantActive`, `enabled`, `createdAt`, `updatedAt`
                    FROM `lorebook_entries`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `lorebook_entries`")
                db.execSQL("ALTER TABLE `lorebook_entries_new` RENAME TO `lorebook_entries`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_lorebookId` ON `lorebook_entries` (`lorebookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_enabled` ON `lorebook_entries` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_priority` ON `lorebook_entries` (`priority`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_injectionPosition` ON `lorebook_entries` (`injectionPosition`)")
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.execSQL(
                    """
                    CREATE TABLE `lorebook_entries_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lorebookId` INTEGER NOT NULL,
                        `keywordsJson` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `injectionPosition` TEXT NOT NULL,
                        `priority` INTEGER NOT NULL DEFAULT 0,
                        `injectDepth` INTEGER,
                        `role` TEXT NOT NULL,
                        `caseSensitive` INTEGER NOT NULL DEFAULT 0,
                        `scanDepth` INTEGER NOT NULL DEFAULT 10,
                        `constantActive` INTEGER NOT NULL DEFAULT 0,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `lorebook_entries_new` SELECT * FROM `lorebook_entries`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `lorebook_entries`")
                db.execSQL("ALTER TABLE `lorebook_entries_new` RENAME TO `lorebook_entries`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_lorebookId` ON `lorebook_entries` (`lorebookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_enabled` ON `lorebook_entries` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_priority` ON `lorebook_entries` (`priority`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lorebook_entries_injectionPosition` ON `lorebook_entries` (`injectionPosition`)")
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_meta` (
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "lorebook_entries", "useRegex", "INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "lorebook_entries", "sortOrder", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(db, "companions", "lorebookIdsJson", "TEXT NOT NULL DEFAULT '[]'")
            }
        }

        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 角色级 API 隔离：companions.apiConfigId（可空 INTEGER，null = 跟随全局）
                addColumnIfMissing(db, "companions", "apiConfigId", "INTEGER")
            }
        }

        /**
         * 44 → 45：Agent 架构迁移（唯一一次 schema 冻结例外，纯增量）。
         *
         * 背景：本地 v44 与 master(lianyu) v44 **不是同一套 schema** —— 两边版本号巧合一致，
         * 但 master 的 agent 相关表分散建立在它的 v38 / v39 / v43 / v44 / v45…v48 上，
         * 本地 v38~v44 用在 quiz / lorebook / app_meta 上，因此本地**从未**有过这 10 张表。
         * 故这里不照搬 master 的 44→48 迁移链，而是一次性把 10 张表补齐为最终形态：
         * 已核对 master v44→v48 期间这 10 张表的结构，agent_skills / prompt_audit /
         * agent_dispatch_log / sticker_* 均**无变更**，其余 4 张是 v45+ 新建 → 直接建终态即可。
         *
         * 红线：仅 `CREATE TABLE/INDEX IF NOT EXISTS`，不做任何 DROP / TRUNCATE / 改列；
         * 不动本地既有 22 张表（含本地独有 app_meta / keywords / quiz_questions /
         * lorebooks / lorebook_entries，及 companions.apiConfigId / lorebookIdsJson）。
         *
         * DDL 来源：master 仓库 `core/database/schemas/` 下其 AppDatabase 的 `48.json`
         * 的 entities[].createSql / indices[].createSql —— 即 Room 校验迁移时使用的目标语句，
         * 逐字照抄可保证迁移后校验必过（含 sticker_* 的自定义索引名 idx_*）。
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- agent_skills：技能索引表（正文存 filesDir/agent_skills/<skillId>/content.md）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_skills` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `skillId` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT NOT NULL, `tags` TEXT NOT NULL, `tools` TEXT NOT NULL, `contentPath` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `contentLength` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `companionId` INTEGER, `version` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_skills_skillId` ON `agent_skills` (`skillId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_category` ON `agent_skills` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_companionId` ON `agent_skills` (`companionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_enabled` ON `agent_skills` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_skills_updatedAt` ON `agent_skills` (`updatedAt`)")

                // ---- prompt_audit：提示词审计（片段构成 / 版本 / 命中工具）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `prompt_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `companionId` INTEGER, `groupId` INTEGER, `sessionId` TEXT, `promptVersion` INTEGER NOT NULL, `fragmentsJson` TEXT NOT NULL, `roundsUsed` INTEGER NOT NULL, `systemPromptHash` TEXT NOT NULL, `toolNames` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_audit_timestamp` ON `prompt_audit` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_audit_companionId` ON `prompt_audit` (`companionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_audit_groupId` ON `prompt_audit` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_prompt_audit_sessionId` ON `prompt_audit` (`sessionId`)")

                // ---- agent_dispatch_log：每回合派发日志（工具调用 / 事件 / 结束原因）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `agent_dispatch_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `companionId` INTEGER, `groupId` INTEGER, `sessionId` TEXT, `dispatchId` TEXT NOT NULL, `provider` TEXT NOT NULL, `model` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `completedAtMs` INTEGER NOT NULL, `roundsUsed` INTEGER NOT NULL, `finishedReason` TEXT NOT NULL, `error` TEXT NOT NULL, `toolNames` TEXT NOT NULL, `toolCallsJson` TEXT NOT NULL, `eventsJson` TEXT NOT NULL, `querySummary` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_timestamp` ON `agent_dispatch_log` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_companionId` ON `agent_dispatch_log` (`companionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_groupId` ON `agent_dispatch_log` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_dispatch_log_sessionId` ON `agent_dispatch_log` (`sessionId`)")

                // ---- sticker_entries：贴纸条目（自定义索引名 idx_*，与 master 一致）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `sticker_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `description` TEXT, `hash` TEXT NOT NULL, `tags` TEXT NOT NULL, `embeddedText` TEXT, `detail` TEXT, `fileName` TEXT NOT NULL, `source` TEXT NOT NULL, `fileSize` INTEGER, `userUsageCount` INTEGER NOT NULL, `modelUsageCount` INTEGER NOT NULL, `createdAt` INTEGER, `lastUsedAt` INTEGER)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `idx_sticker_entries_hash` ON `sticker_entries` (`hash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sticker_entries_tags` ON `sticker_entries` (`tags`)")

                // ---- sticker_tags：标签聚合 ----
                db.execSQL("CREATE TABLE IF NOT EXISTS `sticker_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tag` TEXT NOT NULL, `stickerCount` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sticker_tags_tag` ON `sticker_tags` (`tag`)")

                // ---- sticker_usage_log：使用日志（自定义索引名 idx_*，与 master 一致）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `sticker_usage_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `stickerId` INTEGER NOT NULL, `source` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `contextTags` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sticker_usage_sticker` ON `sticker_usage_log` (`stickerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sticker_usage_time` ON `sticker_usage_log` (`timestamp`)")

                // ---- event_ledger：事件账本（哈希链 + 幂等键）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `event_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `streamId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `payloadJson` TEXT NOT NULL, `metadataJson` TEXT NOT NULL, `idempotencyKey` TEXT, `prevHash` TEXT NOT NULL, `hash` TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_event_ledger_streamId_sequence` ON `event_ledger` (`streamId`, `sequence`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_event_ledger_idempotencyKey` ON `event_ledger` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_ledger_timestamp` ON `event_ledger` (`timestamp`)")

                // ---- event_ledger_snapshot：账本快照（主键即 streamId）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `event_ledger_snapshot` (`streamId` TEXT NOT NULL, `version` INTEGER NOT NULL, `stateJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`streamId`))")

                // ---- delegation_records：子代理委派记录 ----
                db.execSQL("CREATE TABLE IF NOT EXISTS `delegation_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `prompt` TEXT NOT NULL, `status` TEXT NOT NULL, `result` TEXT NOT NULL, `error` TEXT NOT NULL, `companionId` INTEGER, `dispatchId` TEXT, `createdAtMs` INTEGER NOT NULL, `completedAtMs` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delegation_records_status` ON `delegation_records` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_delegation_records_companionId` ON `delegation_records` (`companionId`)")

                // ---- worldbooks：世界书（ST World Info JSON 原样存储）----
                db.execSQL("CREATE TABLE IF NOT EXISTS `worldbooks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `json` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `companionId` INTEGER, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_worldbooks_enabled` ON `worldbooks` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_worldbooks_companionId` ON `worldbooks` (`companionId`)")
            }
        }

        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE `api_provider_presets`
                    SET `baseUrl` = ?
                    WHERE `provider` = ? AND `baseUrl` = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        ApiProvider.DEEPSEEK.defaultBaseUrl,
                        ApiProvider.DEEPSEEK.name,
                        "https://api.deepseek.com/v1/"
                    )
                )
            }
        }

        private fun createApiProviderPresetTable(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `api_provider_presets` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `displayName` TEXT NOT NULL,
                    `baseUrl` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `formatHint` TEXT NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `isVisible` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_api_provider_presets_provider` ON `api_provider_presets` (`provider`)")
        }

        private fun seedApiProviderPresets(db: SupportSQLiteDatabase) {
            createApiProviderPresetTable(db)
            val now = System.currentTimeMillis()
            val presets = listOf(
                ApiProvider.OPENAI to 10,
                ApiProvider.DEEPSEEK to 20,
                ApiProvider.DASHSCOPE to 30,
                ApiProvider.KIMI to 40,
                ApiProvider.ZHIPU to 50,
                ApiProvider.SILICONFLOW to 60,
                ApiProvider.OPENROUTER to 70,
                ApiProvider.GROQ to 80,
                ApiProvider.GEMINI to 90,
                ApiProvider.ANTHROPIC to 100,
                ApiProvider.XIAOMI to 110,
                ApiProvider.IFLYTEK to 120,
                ApiProvider.CUSTOM to 130
            )
            presets.forEach { (provider, sortOrder) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `api_provider_presets` (
                        `provider`, `displayName`, `baseUrl`, `model`, `formatHint`,
                        `sortOrder`, `isVisible`, `updatedAt`
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        provider.name,
                        provider.displayName,
                        provider.defaultBaseUrl,
                        provider.defaultModel,
                        if (provider == ApiProvider.ANTHROPIC) "anthropic" else "openai",
                        sortOrder,
                        now
                    )
                )
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_6,
            MIGRATION_2_6,
            MIGRATION_3_6,
            MIGRATION_4_6,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
        )

        private var lastBackupTime: Long = 0L
        private val BACKUP_INTERVAL_MS = 2 * 60 * 60 * 1000L

        fun backupDatabase(context: Context): Boolean {
            return try {
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) return false

                val backupDir = File(context.filesDir, "db_backup")
                backupDir.mkdirs()

                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())

                val filesToBackup = listOf(
                    dbFile,
                    File(dbFile.path + "-wal"),
                    File(dbFile.path + "-shm"),
                    File(dbFile.path + "-journal")
                ).filter { it.exists() && it.length() > 0 }

                if (filesToBackup.isEmpty()) return false

                val backupSubDir = File(backupDir, "backup_$timestamp")
                backupSubDir.mkdirs()

                filesToBackup.forEach { file ->
                    val target = File(backupSubDir, file.name)
                    file.copyTo(target, overwrite = true)
                }

                lastBackupTime = System.currentTimeMillis()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun restoreFromBackup(context: Context): Boolean {
            return try {
                val backupDir = File(context.filesDir, "db_backup")
                if (!backupDir.exists()) return false

                val backups = backupDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

                if (backups.isEmpty()) return false

                val latestBackup = backups.first()
                val dbFile = context.getDatabasePath(DB_NAME)

                INSTANCE?.close()
                INSTANCE = null

                latestBackup.listFiles()?.forEach { backupFile ->
                    val target = when {
                        backupFile.name == DB_NAME -> dbFile
                        else -> File(dbFile.parentFile, backupFile.name)
                    }
                    backupFile.copyTo(target, overwrite = true)
                }

                true
            } catch (e: Exception) {
                false
            }
        }

        fun getBackupInfo(context: Context): List<Map<String, Any>> {
            val backupDir = File(context.filesDir, "db_backup")
            if (!backupDir.exists()) return emptyList()

            return backupDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                ?.sortedByDescending { it.name }
                ?.map { backup ->
                    val totalSize = backup.listFiles()?.sumOf { it.length() } ?: 0L
                    mapOf(
                        "name" to backup.name,
                        "timestamp" to backup.name.removePrefix("backup_"),
                        "size" to totalSize,
                        "fileCount" to (backup.listFiles()?.size ?: 0)
                    )
                } ?: emptyList()
        }

        fun autoBackupIfNeeded(context: Context) {
            val now = System.currentTimeMillis()
            if (now - lastBackupTime >= BACKUP_INTERVAL_MS) {
                backupDatabase(context)
            }
        }

        fun clearOldBackups(context: Context, keepCount: Int = 5): Int {
            return try {

                val backupDir = File(context.applicationContext.filesDir, "db_backup")
                if (!backupDir.exists()) return 0

                val backups = backupDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("backup_") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

                var deletedCount = 0
                backups.drop(keepCount).forEach { backup ->
                    if (backup.deleteRecursively()) deletedCount++
                }
                deletedCount
            } catch (e: Exception) {
                0
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromApiProvider(value: ApiProvider): String = value.name

    @TypeConverter
    fun toApiProvider(value: String?): ApiProvider {
        if (value.isNullOrBlank()) return ApiProvider.OPENAI
        return runCatching { ApiProvider.valueOf(value.trim().uppercase()) }.getOrDefault(ApiProvider.OPENAI)
    }

    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String?): MessageType {
        if (value.isNullOrBlank()) return MessageType.TEXT
        return runCatching { MessageType.valueOf(value.trim().uppercase()) }.getOrDefault(MessageType.TEXT)
    }

    @TypeConverter
    fun fromFileFormat(value: FileFormat): String = value.name

    @TypeConverter
    fun toFileFormat(value: String?): FileFormat {
        if (value.isNullOrBlank()) return FileFormat.UNKNOWN
        return runCatching { FileFormat.valueOf(value.trim().uppercase()) }.getOrDefault(FileFormat.UNKNOWN)
    }

    @TypeConverter
    fun fromMemoryCategory(value: MemoryCategory): String = value.name

    @TypeConverter
    fun toMemoryCategory(value: String?): MemoryCategory {
        if (value.isNullOrBlank()) return MemoryCategory.FACT
        return runCatching { MemoryCategory.valueOf(value.trim().uppercase()) }.getOrDefault(MemoryCategory.FACT)
    }

    @TypeConverter
    fun fromMemoryType(value: MemoryType): String = value.name

    @TypeConverter
    fun toMemoryType(value: String?): MemoryType {
        if (value.isNullOrBlank()) return MemoryType.SEMANTIC
        return runCatching { MemoryType.valueOf(value.trim().uppercase()) }.getOrDefault(MemoryType.SEMANTIC)
    }

    @TypeConverter
    fun fromMemoryScope(value: MemoryScope): String = value.name

    @TypeConverter
    fun toMemoryScope(value: String?): MemoryScope {
        if (value.isNullOrBlank()) return MemoryScope.COMPANION
        return runCatching { MemoryScope.valueOf(value.trim().uppercase()) }.getOrDefault(MemoryScope.COMPANION)
    }

    @TypeConverter
    fun fromMemorySource(value: MemorySource): String = value.name

    @TypeConverter
    fun toMemorySource(value: String?): MemorySource {
        if (value.isNullOrBlank()) return MemorySource.CHAT
        return runCatching { MemorySource.valueOf(value.trim().uppercase()) }.getOrDefault(MemorySource.CHAT)
    }

}
