package com.yunian.ai.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yunian.ai.database.repository.MessageSearchTokenizer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration32To33Test {
    private val databaseName = "migration-32-33"

    @Test
    fun migration_backfills_hot_and_archived_search_content() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val v32Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(32) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE message_bodies (messageId INTEGER PRIMARY KEY NOT NULL, content TEXT NOT NULL, searchContent TEXT NOT NULL, linkString TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE archived_message_bodies (messageId INTEGER PRIMARY KEY NOT NULL, content TEXT NOT NULL, searchContent TEXT NOT NULL, linkString TEXT NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        v32Helper.writableDatabase.apply {
            execSQL("INSERT INTO message_bodies VALUES (41, 'encrypted', '热消息图书馆', '')")
            execSQL("INSERT INTO archived_message_bodies VALUES (42, 'encrypted', 'Archived Alpha-Beta', '')")
        }
        v32Helper.close()

        val v33Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(33) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        AppDatabase.MIGRATION_32_33.migrate(db)
                    }
                })
                .build()
        )

        val db = v33Helper.writableDatabase
        assertEquals(listOf(41L), matchingIds(db, "书馆"))
        assertEquals(listOf(42L), matchingIds(db, "ha-Be"))

        v33Helper.close()
        context.deleteDatabase(databaseName)
    }

    private fun matchingIds(db: SupportSQLiteDatabase, query: String): List<Long> {
        val matchQuery = requireNotNull(MessageSearchTokenizer.matchQuery(query))
        return db.query(
            "SELECT rowid FROM message_search_index WHERE message_search_index MATCH ? ORDER BY rowid",
            arrayOf(matchQuery)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }
    }
}