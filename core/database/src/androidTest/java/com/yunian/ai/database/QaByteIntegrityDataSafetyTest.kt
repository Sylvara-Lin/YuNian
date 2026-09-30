package com.yunian.ai.database

import android.content.Context
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
import java.security.MessageDigest

/**
 * QA 独立验证：**字节级**证明「任何 DB 异常路径都不允许删除/覆盖主库」。
 *
 * 与 `AppDatabaseRecoveryDataSafetyTest` 的区别：本测试不只比较「尺寸」，
 * 而是对主库文件取 **sha256**，断言破坏性恢复触发前后**逐字节一致**；
 * 并断言 recovery/ 留证副本的 sha256 == 破坏前主库 sha256（证明是「无损复制留证」而非「搬移/截断」）。
 */
@RunWith(AndroidJUnit4::class)
class QaByteIntegrityDataSafetyTest {

    private val dbName = "yunian_database"
    private lateinit var context: Context
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
    fun schemaMismatchWithoutBackup_leavesMainDbByteIdentical() {
        // 1) 建真库 + 探针，然后关闭（触发 WAL checkpoint，落盘到主库）。
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('qa_probe','byte-safe',1)"
        )
        // 2) 注入 SCHEMA_MISMATCH：改坏 identity_hash。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE room_master_table SET identity_hash = 'qa0qa0qa0qa0qa0q' WHERE id = 42"
        )
        AppDatabase.shutdown()

        // 3) 破坏性恢复触发【前】的主库字节指纹。
        val beforeHash = sha256(dbFile)
        val beforeSize = dbFile.length()
        Log.i("QaByteProbe", "before: exists=${dbFile.exists()} size=$beforeSize sha256=$beforeHash")

        // 4) 真实触发恢复（无正式备份 → 不得删/覆盖主库）。
        val probe = runCatching { AppDatabase.verifyAndRecover(context) }
        Log.i("QaByteProbe", "verifyAndRecover threw=${probe.exceptionOrNull()}")

        // 5) 硬约束（字节级）：主库必须仍存在且逐字节不变。
        assertTrue("主库不得被删除", dbFile.exists())
        val afterHash = sha256(dbFile)
        Log.i("QaByteProbe", "after:  exists=${dbFile.exists()} size=${dbFile.length()} sha256=$afterHash")
        assertEquals("主库字节必须逐字节不变（sha256）", beforeHash, afterHash)
        assertEquals("主库尺寸必须不变", beforeSize, dbFile.length())

        // 6) 无损留证：recovery/ 下应有与破坏前逐字节相同的副本。
        val recoveryDir = File(dbFile.parentFile, "recovery")
        val quarantined = recoveryDir.listFiles()?.firstOrNull {
            it.name.startsWith("$dbName.corrupted_")
        }
        assertTrue("必须有 recovery/ 的 .corrupted_ 留证副本", quarantined != null)
        assertEquals(
            "留证副本必须与破坏前主库逐字节相同（无损复制）",
            beforeHash,
            sha256(quarantined!!)
        )
        AppDatabase.resetForTest()
    }

    @Test
    fun schemaMismatchWithValidBackup_restoresDataNotDelete() {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('qa_probe','survive',1)"
        )
        assertTrue("正式备份应成功", AppDatabase.backupDatabase(context))

        db.openHelper.writableDatabase.execSQL(
            "UPDATE room_master_table SET identity_hash = 'qa0qa0qa0qa0qa0q' WHERE id = 42"
        )
        AppDatabase.shutdown()

        val probe = runCatching { AppDatabase.verifyAndRecover(context) }
        Log.i("QaByteProbe", "A: verifyAndRecover threw=${probe.exceptionOrNull()}")

        assertTrue("主库必须存在", dbFile.exists())
        val value = kotlinx.coroutines.runBlocking {
            runCatching { AppDatabase.getDatabase(context).appMetaDao().get("qa_probe") }.getOrNull()
        }
        assertEquals("从合法整库备份恢复后数据必须仍在", "survive", value)
        AppDatabase.resetForTest()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
