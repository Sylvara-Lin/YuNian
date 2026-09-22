package com.yunian.ai.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration30To31Test {
    private val databaseName = "migration-30-31"

    @Test
    fun migration_backfills_read_cursor_timestamp_from_message_id() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v30Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE messages (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                conversationId INTEGER NOT NULL,
                                conversationType TEXT NOT NULL,
                                isFromUser INTEGER NOT NULL,
                                senderId INTEGER NOT NULL,
                                timestamp INTEGER NOT NULL,
                                type TEXT NOT NULL,
                                fileFormat TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE conversation_summary (
                                sessionId INTEGER NOT NULL,
                                sessionType TEXT NOT NULL,
                                lastMessageId INTEGER,
                                lastMessagePreview TEXT NOT NULL,
                                lastMessageTimestamp INTEGER NOT NULL,
                                lastMessageIsFromUser INTEGER NOT NULL,
                                readThroughMessageId INTEGER,
                                unreadCount INTEGER NOT NULL,
                                isPinned INTEGER NOT NULL,
                                isMuted INTEGER NOT NULL,
                                PRIMARY KEY(sessionId, sessionType)
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v30Helper.writableDatabase.apply {
            execSQL(
                "INSERT INTO messages (id, conversationId, conversationType, isFromUser, senderId, timestamp, type, fileFormat) VALUES (42, 7, 'chat', 0, 7, 1234, 'TEXT', 'TEXT')"
            )
            execSQL(
                "INSERT INTO conversation_summary (sessionId, sessionType, lastMessageId, lastMessagePreview, lastMessageTimestamp, lastMessageIsFromUser, readThroughMessageId, unreadCount, isPinned, isMuted) VALUES (7, 'chat', 42, 'latest', 1234, 0, 42, 0, 0, 0)"
            )
        }
        v30Helper.close()

        val v31Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(31) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_30_31.migrate(db)
                    }
                })
                .build()
        )

        v31Helper.writableDatabase.query(
            "SELECT readThroughMessageTimestamp FROM conversation_summary WHERE sessionId = 7 AND sessionType = 'chat'"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(1234L, cursor.getLong(0))
        }

        v31Helper.close()
        context.deleteDatabase(databaseName)
    }
}