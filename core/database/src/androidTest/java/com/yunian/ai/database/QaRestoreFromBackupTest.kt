package com.yunian.ai.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * QA 独立验证 P2-2：手动恢复入口 `AppDatabase.restoreFromBackup` 的校验是否真正生效。
 * （工程师本轮未给 P2-2 添加任何测试，故由 QA 补齐。）
 *
 * 三个场景：
 *  A. 合法整库备份 → 恢复，主库内容 = 备份主库快照；
 *  B. 备份目录里的 `yunian_database` 非法（非 SQLite 魔数）→ 拒绝，主库**逐字节不变**；
 *  C. 备份目录里混入 `<db>.corrupted_*` 留证副本 → **绝不覆盖主库**（写入主库的是合法同名快照）。
 */
@RunWith(AndroidJUnit4::class)
class QaRestoreFromBackupTest {

    private val dbName = "yunian_database"
    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var backupRoot: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.resetForTest()
        dbFile = context.getDatabasePath(dbName)
        backupRoot = File(context.filesDir, "db_backup")
        backupRoot.deleteRecursively()
        File(dbFile.parentFile, "recovery").deleteRecursively()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTest()
        context.deleteDatabase(dbName)
        File(dbFile.parentFile, "recovery").deleteRecursively()
        backupRoot.deleteRecursively()
    }

    @Test
    fun validBackup_restoresMainDbSnapshot() {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('qa_probe','valid',1)"
        )
        assertTrue("正式备份应成功", AppDatabase.backupDatabase(context))
        AppDatabase.shutdown()

        val backupMain = newestBackupDir()?.let { File(it, dbName) }
        assertTrue("备份目录应含主库快照", backupMain != null && backupMain!!.exists())
        val expected = sha256(backupMain!!)

        val result = runCatching { AppDatabase.restoreFromBackup(context) }
        assertTrue("合法备份应恢复成功: ${result.exceptionOrNull()}", result.getOrDefault(false))
        assertEquals("恢复后主库必须等于备份里的整库快照", expected, sha256(dbFile))
        AppDatabase.resetForTest()
    }

    @Test
    fun bogusMainBackup_isRejected_mainDbByteIdentical() {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('qa_probe','keep',1)"
        )
        AppDatabase.shutdown()
        val beforeHash = sha256(dbFile)

        // 构造一个名为 yunian_database 但内容非法的备份（无 SQLite 魔数）
        val dir = File(backupRoot, "backup_99999999999999"); dir.mkdirs()
        val bogus = File(dir, dbName)
        bogus.writeBytes(ByteArray(4096) { 0x41 })

        val result = runCatching { AppDatabase.restoreFromBackup(context) }
        assertFalse("非法备份必须被拒绝", result.getOrDefault(true))
        assertTrue("主库不得被删除", dbFile.exists())
        assertEquals("主库必须逐字节不变", beforeHash, sha256(dbFile))
        AppDatabase.resetForTest()
    }

    @Test
    fun quarantineCopyInBackupDir_neverOverwritesMainDb() {
        val db = AppDatabase.getDatabase(context)
        db.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO app_meta(`key`,`value`,`updatedAt`) VALUES('qa_probe','x',1)"
        )
        AppDatabase.shutdown()

        val dir = File(backupRoot, "backup_88888888888888"); dir.mkdirs()
        val validMain = File(dir, dbName)
            .apply { writeBytes(sqliteLike(8192, 0xBB)) }        // 合法同名整库快照（魔数+尺寸）
        val corrupted = File(dir, "$dbName.corrupted_123")
            .apply { writeBytes(sqliteLike(4096, 0xCC)) }        // 留证副本（roleOf 归 MAIN_DB 但不是同名）

        val result = runCatching { AppDatabase.restoreFromBackup(context) }
        assertTrue("合法备份应恢复成功", result.getOrDefault(false))
        assertArrayEquals(
            "主库必须来自合法同名快照（0xBB），绝不来自 .corrupted_ 留证副本（0xCC）",
            validMain.readBytes(), dbFile.readBytes()
        )
        assertNotEquals(
            "主库不得等于 .corrupted_ 留证副本",
            sha256(corrupted), sha256(dbFile)
        )
        AppDatabase.resetForTest()
    }

    private fun newestBackupDir(): File? =
        backupRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("backup_") }
            ?.maxByOrNull { it.name }

    /** 造一个「像 SQLite 的文件」：16B 魔数 + 指定填充字节，总长为 size。 */
    private fun sqliteLike(size: Int, fill: Int): ByteArray {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(size).also { java.util.Arrays.fill(it, fill.toByte()) }
        System.arraycopy(magic, 0, out, 0, magic.size)
        return out
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n > 0) { md.update(buf, 0, n); n = input.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
