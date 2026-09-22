package com.yunian.ai.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration36To37Test {
    private val databaseName = "migration-36-37"

    @Test
    fun migration_removesContextTokenAndPreservesOutboxRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v36Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(36) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE `wechat_outbox` (
                                `id` TEXT NOT NULL, `rootId` TEXT NOT NULL, `companionId` INTEGER NOT NULL,
                                `wechatUserId` TEXT NOT NULL, `kind` INTEGER NOT NULL, `text` TEXT,
                                `mediaLocalPath` TEXT, `mediaFileName` TEXT, `mediaDescription` TEXT,
                                `segmentIndex` INTEGER NOT NULL, `segmentCount` INTEGER NOT NULL,
                                `contextToken` TEXT, `sourceMessageId` INTEGER, `status` TEXT NOT NULL,
                                `retryCount` INTEGER NOT NULL, `nextAttemptAtMs` INTEGER NOT NULL,
                                `lastError` TEXT, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL,
                                PRIMARY KEY(`id`)
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v36Helper.writableDatabase.execSQL(
            "INSERT INTO wechat_outbox VALUES ('id-1', 'root-1', 7, 'user-1', 1, 'hello', NULL, NULL, NULL, 0, 1, 'secret-token', 9, 'PENDING', 0, 0, NULL, 10, 10)",
        )
        v36Helper.close()

        val v37Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(37) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_36_37.migrate(db)
                    }
                })
                .build()
        )

        val db = v37Helper.writableDatabase
        assertFalse(hasColumn(db, "wechat_outbox", "contextToken"))
        db.query("SELECT id, text, sourceMessageId FROM wechat_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("id-1", cursor.getString(0))
            assertEquals("hello", cursor.getString(1))
            assertEquals(9L, cursor.getLong(2))
        }

        v37Helper.close()
        context.deleteDatabase(databaseName)
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }
}
