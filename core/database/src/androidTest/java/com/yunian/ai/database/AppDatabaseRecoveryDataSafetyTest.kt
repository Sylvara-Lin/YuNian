package com.yunian.ai.database

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机/模拟器上的故障注入 A/B，验证硬约束「数据不可丢」。
 *
 * 故障注入方式：把 `room_master_table` 里的 identity_hash 改坏 → 下次打开时 Room 抛
 * "…identity hash…"（被分类为 SCHEMA_MISMATCH，**可恢复**），从而真实走一遍恢复分支。
 *
 *  A. 有正式备份时：恢复后**数据仍在**（聊天记录/人设不会丢）。
 *  B. 无正式备份时：主库**绝不被删除**，且原始字节被 recovery/ 留证。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseRecoveryDataSafetyTest {

    private val dbName = "yunian_database"
    private lateinit var context: android.content.Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.resetForTest()
        dbFile = context.getDatabasePath(dbName)
        File(context.filesDir, "db_backup").deleteRecursively()
        File(dbFile.parentFile, "recovery").deleteRecursively()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTest()
        context.deleteDatabase(dbName)
        File(dbFile.parentFile, "recovery").deleteRecursively()
        File(context.filesDir, "db_backup").deleteRecursively()
    }

    @Test
    fun withValidBackup_schemaMismatchIsRestored_andDataSurvives() {
        // 1) 建真库 + 写入探针数据。
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('safety_probe','hello',1)"
        )
        assertEquals("hello", readProbeViaSingleton())

        // 2) 做一次正式备份（此时数据完整）。
        assertTrue("备份应成功", AppDatabase.backupDatabase(context))

        // 3) 注入故障：改坏 identity_hash，然后关闭单例。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE room_master_table SET identity_hash = 'deadbeefdeadbeef' WHERE id = 42"
        )
        AppDatabase.shutdown()

        // 4) 触发真实恢复。应分类为 SCHEMA_MISMATCH 并从备份整库恢复。
        val probe = runCatching { AppDatabase.verifyAndRecover(context) }
        Log.i("DataSafetyProbe", "A: verifyAndRecover threw=${probe.exceptionOrNull()}")

        // 5) 断言：主库未删、数据仍在。
        assertTrue("主库必须存在", dbFile.exists())
        assertEquals("恢复后探针数据必须仍在（数据不可丢）", "hello", readProbeViaSingleton())
        AppDatabase.resetForTest()
    }

    @Test
    fun withoutBackup_corruptionNeverDeletesMainDb_andQuarantinesOriginal() {
        // 1) 建真库 + 探针。
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('safety_probe','hello',1)"
        )
        // 隔离：无正式备份。
        File(context.filesDir, "db_backup").deleteRecursively()

        // 2) 注入故障：改坏 identity_hash。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE room_master_table SET identity_hash = 'deadbeefdeadbeef' WHERE id = 42"
        )
        AppDatabase.shutdown()
        val sizeBefore = dbFile.length()

        // 3) 触发真实恢复。
        val probe = runCatching { AppDatabase.verifyAndRecover(context) }
        val recoveryDir = File(dbFile.parentFile, "recovery")
        Log.i(
            "DataSafetyProbe",
            "B: threw=${probe.exceptionOrNull()}; dbExists=${dbFile.exists()} " +
                "sizeBefore=$sizeBefore sizeAfter=${if (dbFile.exists()) dbFile.length() else -1}; " +
                "recovery=${recoveryDir.listFiles()?.map { it.name }.orEmpty()}"
        )

        // 4) 硬约束：主库不得被删除/清空；且原始字节必须被 recovery/ 留证。
        assertTrue("主库文件必须仍然存在（不得删库）", dbFile.exists())
        assertTrue("主库文件不得被清空", dbFile.length() > 512)
        assertTrue(
            "必须留证原始主库字节（recovery/ 下应有 $dbName.corrupted_* 副本）",
            recoveryDir.listFiles()?.any { it.name.startsWith("$dbName.corrupted_") } == true
        )
        AppDatabase.resetForTest()
    }

    /** 通过真实单例读取探针值（打开失败则返回 null）。 */
    private fun readProbeViaSingleton(): String? = kotlinx.coroutines.runBlocking {
        runCatching { AppDatabase.getDatabase(context).appMetaDao().get("safety_probe") }.getOrNull()
    }
}
