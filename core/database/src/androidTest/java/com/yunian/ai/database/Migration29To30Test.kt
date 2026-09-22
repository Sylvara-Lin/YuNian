package com.yunian.ai.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration29To30Test {
    private val databaseName = "migration-29-30"

    @Test
    fun migration_preserves_bodies_backfills_summary_and_enforces_cascade() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v29Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE messages (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                conversationId INTEGER NOT NULL,
                                conversationType TEXT NOT NULL,
                                isFromUser INTEGER NOT NULL,
                                senderId INTEGER NOT NULL,
                                content TEXT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                type TEXT NOT NULL,
                                searchContent TEXT NOT NULL,
                                fileFormat TEXT NOT NULL,
                                linkString TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE conversation_summary (
                                sessionId INTEGER NOT NULL,
                                sessionType TEXT NOT NULL,
                                lastMessagePreview TEXT NOT NULL,
                                lastMessageTimestamp INTEGER NOT NULL,
                                lastMessageIsFromUser INTEGER NOT NULL,
                                unreadCount INTEGER NOT NULL,
                                isPinned INTEGER NOT NULL,
                                isMuted INTEGER NOT NULL,
                                PRIMARY KEY(sessionId, sessionType)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE INDEX idx_messages_conv ON messages (conversationType, conversationId, timestamp DESC, id DESC)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v29Helper.writableDatabase.apply {
            execSQL(
                """
                INSERT INTO messages
                    (id, conversationId, conversationType, isFromUser, senderId, content, timestamp, type, searchContent, fileFormat, linkString)
                VALUES
                    (41, 7, 'CHAT', 1, 0, 'cipher-old', 1000, 'TEXT', 'old', 'TEXT', ''),
                    (42, 7, 'CHAT', 0, 7, 'cipher-latest', 1000, 'TEXT', 'latest', 'IMAGE', 'cipher-link')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO conversation_summary
                    (sessionId, sessionType, lastMessagePreview, lastMessageTimestamp, lastMessageIsFromUser, unreadCount, isPinned, isMuted)
                VALUES (7, 'CHAT', 'latest', 1000, 0, 2, 0, 0)
                """.trimIndent()
            )
        }
        v29Helper.close()

        val v30Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_29_30.migrate(db)
                    }
                })
                .build()
        )
        val database = v30Helper.writableDatabase

        database.query(
            """
            SELECT m.id, b.content, b.searchContent, b.linkString
            FROM messages m JOIN message_bodies b ON b.messageId = m.id
            ORDER BY m.id
            """.trimIndent()
        ).use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToLast()
            assertEquals(42L, cursor.getLong(0))
            assertEquals("cipher-latest", cursor.getString(1))
            assertEquals("latest", cursor.getString(2))
            assertEquals("cipher-link", cursor.getString(3))
        }

        database.query(
            "SELECT lastMessageId, readThroughMessageId FROM conversation_summary WHERE sessionId = 7 AND sessionType = 'CHAT'"
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(42L, cursor.getLong(0))
            assertEquals(true, cursor.isNull(1))
        }

        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM messages WHERE id = 42")
        database.query("SELECT 1 FROM message_bodies WHERE messageId = 42").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        v30Helper.close()
        context.deleteDatabase(databaseName)
    }
}