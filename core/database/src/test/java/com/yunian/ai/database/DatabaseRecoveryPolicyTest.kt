package com.yunian.ai.database

import com.yunian.ai.database.DatabaseRecoveryPolicy.ArtifactRole
import com.yunian.ai.database.DatabaseRecoveryPolicy.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 「数据不可丢」恢复策略的纯逻辑测试。
 *
 * 这些用例把历史上的两处致命缺陷钉死为回归护栏：
 *  1. 只有角色=主库、且尺寸/魔数均合法的**整库**才能当恢复源——
 *     绝不把 `-wal`/`-shm`/`-journal` 附属文件写回主库（旧实现会拿共享内存文件覆盖真正的库）。
 *  2. 只有结构不兼容/损坏才允许恢复；锁/忙/IO 等瞬态错误一律不动主库。
 */
class DatabaseRecoveryPolicyTest {

    private val dbName = "yunian_database"

    // ── 失败分类 ─────────────────────────────────────────────────────────────

    @Test
    fun `identity hash maps to schema mismatch`() {
        val kind = DatabaseRecoveryPolicy.classify(
            listOf("Room cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number. ... identity hash ...")
        )
        assertEquals(FailureKind.SCHEMA_MISMATCH, kind)
    }

    @Test
    fun `integrity message maps to corruption`() {
        val kind = DatabaseRecoveryPolicy.classify(
            listOf("Cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number.")
        )
        assertEquals(FailureKind.CORRUPTION, kind)
    }

    @Test
    fun `malformed message maps to corruption`() {
        assertEquals(FailureKind.CORRUPTION, DatabaseRecoveryPolicy.classify(listOf("database disk image is malformed")))
    }

    @Test
    fun `not a database message maps to corruption (self-heal)`() {
        assertEquals(
            FailureKind.CORRUPTION,
            DatabaseRecoveryPolicy.classify(listOf("file is not a database (code 26)"))
        )
    }

    @Test
    fun `unsupported file format maps to corruption (self-heal)`() {
        assertEquals(
            FailureKind.CORRUPTION,
            DatabaseRecoveryPolicy.classify(listOf("unsupported file format"))
        )
    }

    @Test
    fun `locked message maps to transient (must not recover)`() {
        assertEquals(FailureKind.TRANSIENT, DatabaseRecoveryPolicy.classify(listOf("database is locked (code 5)")))
        assertEquals(FailureKind.TRANSIENT, DatabaseRecoveryPolicy.classify(listOf("SQLiteDatabaseLockedException: database is busy")))
    }

    @Test
    fun `disk io error maps to transient (must not recover)`() {
        assertEquals(FailureKind.TRANSIENT, DatabaseRecoveryPolicy.classify(listOf("disk I/O error")))
        assertEquals(FailureKind.TRANSIENT, DatabaseRecoveryPolicy.classify(listOf("database or disk is full")))
    }

    @Test
    fun `unknown and empty classify as unknown`() {
        assertEquals(FailureKind.UNKNOWN, DatabaseRecoveryPolicy.classify(listOf("some weird failure")))
        assertEquals(FailureKind.UNKNOWN, DatabaseRecoveryPolicy.classify(emptyList()))
    }

    @Test
    fun `only schema mismatch and corruption may trigger recovery`() {
        assertTrue(DatabaseRecoveryPolicy.shouldAttemptRecovery(FailureKind.SCHEMA_MISMATCH))
        assertTrue(DatabaseRecoveryPolicy.shouldAttemptRecovery(FailureKind.CORRUPTION))
        assertFalse(DatabaseRecoveryPolicy.shouldAttemptRecovery(FailureKind.TRANSIENT))
        assertFalse(DatabaseRecoveryPolicy.shouldAttemptRecovery(FailureKind.UNKNOWN))
    }

    // ── 角色判定 ─────────────────────────────────────────────────────────────

    @Test
    fun `role of plain db file is main db`() {
        assertEquals(ArtifactRole.MAIN_DB, DatabaseRecoveryPolicy.roleOf(dbName, dbName))
    }

    @Test
    fun `role of sidecar files is never main db`() {
        assertEquals(ArtifactRole.WAL, DatabaseRecoveryPolicy.roleOf("$dbName-wal", dbName))
        assertEquals(ArtifactRole.SHM, DatabaseRecoveryPolicy.roleOf("$dbName-shm", dbName))
        assertEquals(ArtifactRole.JOURNAL, DatabaseRecoveryPolicy.roleOf("$dbName-journal", dbName))
    }

    @Test
    fun `role of quarantine copies follows their base file`() {
        assertEquals(ArtifactRole.MAIN_DB, DatabaseRecoveryPolicy.roleOf("$dbName.corrupted_123", dbName))
        assertEquals(ArtifactRole.WAL, DatabaseRecoveryPolicy.roleOf("$dbName-wal.corrupted_123", dbName))
        assertEquals(ArtifactRole.SHM, DatabaseRecoveryPolicy.roleOf("$dbName-shm.corrupted_123", dbName))
    }

    // ── SQLite 魔数 ──────────────────────────────────────────────────────────

    @Test
    fun `valid sqlite header is accepted`() {
        val header = DatabaseRecoveryPolicy.SQLITE_MAGIC + ByteArray(120)
        assertTrue(DatabaseRecoveryPolicy.hasSqliteHeader(header))
    }

    @Test
    fun `non sqlite header is rejected`() {
        assertFalse(DatabaseRecoveryPolicy.hasSqliteHeader(ByteArray(16)))
        assertFalse(DatabaseRecoveryPolicy.hasSqliteHeader("not-a-database!!".toByteArray()))
        assertFalse(DatabaseRecoveryPolicy.hasSqliteHeader(ByteArray(4)))
    }

    // ── 恢复源筛选（核心回归）────────────────────────────────────────────────

    @Test
    fun `full snapshot requires main-db role, size and magic`() {
        assertTrue(DatabaseRecoveryPolicy.isRestorableMainDb(mainDb(size = 4096, header = true)))
        assertFalse("sidecar must never be a restore source", DatabaseRecoveryPolicy.isRestorableMainDb(mainDb(size = 4096, header = true, role = ArtifactRole.WAL)))
        assertFalse(DatabaseRecoveryPolicy.isRestorableMainDb(mainDb(size = 100, header = true)))
        assertFalse(DatabaseRecoveryPolicy.isRestorableMainDb(mainDb(size = 4096, header = false)))
    }

    @Test
    fun `select never returns a sidecar even if it is the newest and largest`() {
        // 模拟旧缺陷：-shm(32KB) 是最新且最大的文件，-wal 也很大；
        // 真正的主库快照更小、更旧。策略必须挑主库 —— 否则就是拿共享内存覆盖真库。
        val shm = DatabaseRecoveryPolicy.Candidate(
            name = "$dbName-shm.corrupted_200", sizeBytes = 32768, headerValid = false,
            lastModifiedMs = 300, role = ArtifactRole.SHM
        )
        val wal = DatabaseRecoveryPolicy.Candidate(
            name = "$dbName-wal.corrupted_200", sizeBytes = 65536, headerValid = false,
            lastModifiedMs = 250, role = ArtifactRole.WAL
        )
        val db = mainDb(size = 8192, header = true, lastModified = 100)

        val chosen = DatabaseRecoveryPolicy.selectRestorableMainDb(listOf(shm, wal, db))
        assertSame(db, chosen)
    }

    @Test
    fun `select returns null when only sidecars exist (never delete-path)`() {
        val shm = DatabaseRecoveryPolicy.Candidate(
            name = "$dbName-shm", sizeBytes = 32768, headerValid = false,
            lastModifiedMs = 300, role = ArtifactRole.SHM
        )
        val wal = DatabaseRecoveryPolicy.Candidate(
            name = "$dbName-wal", sizeBytes = 65536, headerValid = false,
            lastModifiedMs = 250, role = ArtifactRole.WAL
        )
        assertNull(DatabaseRecoveryPolicy.selectRestorableMainDb(listOf(shm, wal)))
    }

    @Test
    fun `select picks newest valid main db among several`() {
        val older = mainDb(size = 8192, header = true, lastModified = 100)
        val newer = mainDb(size = 16384, header = true, lastModified = 900)
        assertSame(newer, DatabaseRecoveryPolicy.selectRestorableMainDb(listOf(older, newer)))
    }

    // ── 非破坏性文件移动（P2-1 回归：verify-then-delete）──────────────────────────

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `move aside keeps source bytes when copy cannot be verified`() {
        // 复刻 P2-1：目标目录不可写/非法 → 改名与复制都失败 → 必须**保留原文件**。
        // 旧实现会在复制失败后仍无条件 delete()，导致 sidecar 字节丢失；本用例会变红。
        val source = tempFolder.newFile("$dbName-wal").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val notADir = tempFolder.newFile("not-a-dir")
        val badTarget = File(notADir, "$dbName-wal.stale_1") // 父级是普通文件 → copyTo 必然失败
        val before = sha256(source)

        val moved = DatabaseRecoveryPolicy.moveAsideNonDestructive(source, badTarget)

        assertFalse("复制未获证实时不得报告已安全移动", moved)
        assertTrue("复制失败必须保留原文件（绝不丢字节）", source.exists())
        assertEquals("原文件字节必须逐字节不变", before, sha256(source))
    }

    @Test
    fun `move aside relocates source and preserves bytes on success`() {
        val source = tempFolder.newFile("$dbName-wal").apply {
            writeBytes(ByteArray(4096) { (it % 251).toByte() })
        }
        val before = sha256(source)
        val target = File(tempFolder.newFolder("recovery"), "$dbName-wal.stale_1")

        val moved = DatabaseRecoveryPolicy.moveAsideNonDestructive(source, target)

        assertTrue("移动应成功", moved)
        assertTrue("目标副本必须存在", target.exists())
        assertEquals("目标副本必须与原文件逐字节相同", before, sha256(target))
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

    private fun mainDb(
        size: Long,
        header: Boolean,
        lastModified: Long = 1L,
        role: ArtifactRole = ArtifactRole.MAIN_DB
    ) = DatabaseRecoveryPolicy.Candidate(
        name = if (role == ArtifactRole.MAIN_DB) dbName else "$dbName-${role.name.lowercase()}",
        sizeBytes = size,
        headerValid = header,
        lastModifiedMs = lastModified,
        role = role
    )
}
