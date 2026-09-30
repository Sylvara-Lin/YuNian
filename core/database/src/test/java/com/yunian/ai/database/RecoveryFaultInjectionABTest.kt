package com.yunian.ai.database

import com.yunian.ai.database.DatabaseRecoveryPolicy.ArtifactRole
import com.yunian.ai.database.DatabaseRecoveryPolicy.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 故障注入 A/B：在同一组磁盘候选上，重现「旧选择算法」与「新策略」的差异。
 *
 * 注入的故障 = 一次启动期 `backupBeforeRecovery` 之后磁盘上的真实形态：
 * 主库副本 + `-wal`/`-shm`/`-journal` 副本，按 db→wal→shm→journal 顺序写出，故附属文件 mtime 更新。
 * 期望：
 *  - 旧算法会挑「最新且 >1024B」的文件 → 命中 `-shm`/`-wal` 副本 → 把附属文件写回主库 → 数据丢失；
 *  - 新策略只认 role=主库 且尺寸/魔数合法的整库 → 绝不返回附属文件，找不到就返回 null（调用方零删除）。
 */
class RecoveryFaultInjectionABTest {

    private val dbName = "yunian_database"

    /** 旧实现的选择（与历史 AppDatabase.recoverDatabase / tryRecoverFromBackup 等价）。 */
    private fun legacyPick(files: List<Candidate>): Candidate? =
        files
            .filter { it.name.endsWith(".db") || it.name.contains("corrupted") }
            .sortedByDescending { it.lastModifiedMs }
            .firstOrNull()
            ?.takeIf { it.sizeBytes > 1024 }

    private fun sidecar(
        suffix: String,
        size: Long,
        mtime: Long,
        role: ArtifactRole
    ): Candidate = Candidate(
        name = "$dbName$suffix.corrupted_100",
        sizeBytes = size,
        headerValid = false, // 附属文件不是 SQLite 主库
        lastModifiedMs = mtime,
        role = role
    )

    private fun mainDbSnapshot(size: Long, mtime: Long, headerValid: Boolean = true): Candidate = Candidate(
        name = "$dbName.corrupted_100",
        sizeBytes = size,
        headerValid = headerValid,
        lastModifiedMs = mtime,
        role = ArtifactRole.MAIN_DB
    )

    @Test
    fun `case A - old algorithm overwrites main db with newest sidecar, new policy never does`() {
        val files = listOf(
            mainDbSnapshot(size = 8192, mtime = 100),
            sidecar("-wal", size = 65536, mtime = 101, role = ArtifactRole.WAL),
            sidecar("-shm", size = 32768, mtime = 102, role = ArtifactRole.SHM)
        )

        val legacy = legacyPick(files)
        assertEquals("旧算法会挑最新附属文件", ArtifactRole.SHM, legacy?.role)

        val chosen = DatabaseRecoveryPolicy.selectRestorableMainDb(files)
        assertNotEquals("新策略绝不能返回附属文件", ArtifactRole.SHM, chosen?.role)
        assertEquals("新策略返回合法整库", ArtifactRole.MAIN_DB, chosen?.role)
    }

    @Test
    fun `case B - only sidecars on disk, old algorithm still overwrites, new policy refuses`() {
        val files = listOf(
            sidecar("-wal", size = 65536, mtime = 201, role = ArtifactRole.WAL),
            sidecar("-shm", size = 32768, mtime = 202, role = ArtifactRole.SHM)
        )

        val legacy = legacyPick(files)
        assertEquals("旧算法会拿附属文件去覆盖主库", ArtifactRole.SHM, legacy?.role)

        assertNull("新策略：没有合法整库 → 返回 null → 调用方保持原文件不删", DatabaseRecoveryPolicy.selectRestorableMainDb(files))
    }

    @Test
    fun `case C - a valid full-db backup is preferred for restore`() {
        val suspectQuarantine = mainDbSnapshot(size = 8192, mtime = 100, headerValid = false)
        val walCopy = sidecar("-wal", size = 65536, mtime = 102, role = ArtifactRole.WAL)
        val goodBackup = Candidate(
            name = dbName, // db_backup/backup_<ts>/yunian_database（正式备份，名字就是主库名）
            sizeBytes = 16384,
            headerValid = true,
            lastModifiedMs = 500,
            role = ArtifactRole.MAIN_DB
        )

        val chosen = DatabaseRecoveryPolicy.selectRestorableMainDb(listOf(suspectQuarantine, walCopy, goodBackup))
        assertSame(goodBackup, chosen)
    }
}
