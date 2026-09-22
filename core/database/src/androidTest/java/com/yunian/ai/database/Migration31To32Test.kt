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
class Migration31To32Test {
    private val databaseName = "migration-31-32"

    @Test
    fun migration_adds_empty_archive_tables_without_changing_hot_messages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v31Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(31) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, conversationType TEXT NOT NULL, isFromUser INTEGER NOT NULL, senderId INTEGER NOT NULL, timestamp INTEGER NOT NULL, type TEXT NOT NULL, fileFormat TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE message_bodies (messageId INTEGER PRIMARY KEY NOT NULL, content TEXT NOT NULL, searchContent TEXT NOT NULL, linkString TEXT NOT NULL, FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v31Helper.writableDatabase.apply {
            execSQL("INSERT INTO messages VALUES (42, 7, 'chat', 0, 0, 1234, 'TEXT', 'TEXT')")
            execSQL("INSERT INTO message_bodies VALUES (42, 'encrypted', 'search', '')")
        }
        v31Helper.close()

        val v32Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(32) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_31_32.migrate(db)
                    }
                })
                .build()
        )

        val db = v32Helper.writableDatabase
        assertEquals(1, db.query("SELECT COUNT(*) FROM messages").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT COUNT(*) FROM message_bodies").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM archived_messages").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM archived_message_bodies").use { it.moveToFirst(); it.getInt(0) })
        val archiveIndexes = db.query("PRAGMA index_list('archived_messages')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(archiveIndexes.contains("idx_archived_messages_conv"))

        v32Helper.close()
        context.deleteDatabase(databaseName)
    }
}