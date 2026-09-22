package com.yunian.ai.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration34To35Test {
    private val databaseName = "migration-34-35"

    @Test
    fun migration_adds_timeline_columns_and_preserves_rows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v34Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(34) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE messages (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                conversationId INTEGER NOT NULL,
                                conversationType TEXT NOT NULL,
                                isFromUser INTEGER NOT NULL,
                                senderId INTEGER NOT NULL,
                                timestamp INTEGER NOT NULL,
                                type TEXT NOT NULL,
                                fileFormat TEXT NOT NULL
                            )""".trimIndent()
                        )
                        db.execSQL(
                            """CREATE TABLE archived_messages (
                                id INTEGER PRIMARY KEY NOT NULL,
                                conversationId INTEGER NOT NULL,
                                conversationType TEXT NOT NULL,
                                isFromUser INTEGER NOT NULL,
                                senderId INTEGER NOT NULL,
                                timestamp INTEGER NOT NULL,
                                type TEXT NOT NULL,
                                fileFormat TEXT NOT NULL
                            )""".trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v34Helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO messages VALUES (42, 7, 'chat', 0, 0, 1234, 'TEXT', 'TEXT')"
            )
            execSQL(
                "INSERT INTO archived_messages VALUES (43, 7, 'chat', 0, 0, 1000, 'TEXT', 'TEXT')"
            )
        }
        v34Helper.close()

        val v35Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(35) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_34_35.migrate(db)
                    }
                })
                .build()
        )

        val db = v35Helper.writableDatabase
        assertTrue(hasColumn(db, "messages", "turnId"))
        assertTrue(hasColumn(db, "messages", "eventIndex"))
        assertTrue(hasColumn(db, "messages", "durationMs"))
        assertTrue(hasColumn(db, "messages", "anchorMessageId"))
        assertTrue(hasColumn(db, "archived_messages", "turnId"))

        db.query("SELECT id, turnId, eventIndex FROM messages WHERE id = 42").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(42L, c.getLong(0))
            assertTrue(c.isNull(1))
            assertTrue(c.isNull(2))
        }

        v35Helper.close()
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
