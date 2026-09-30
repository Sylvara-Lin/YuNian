package com.yunian.ai.database

import java.io.File

/**
 * 数据库打开失败的分类与「非破坏性」恢复判定（纯逻辑，无 Android 依赖，可脱离设备直测）。
 *
 * 背景（数据不可丢原则）：历史实现把「打开失败」一律当成损坏，并会
 *  - 用 recovery/ 里的 `-wal`/`-shm`/`-journal` 附属文件覆盖真正的主库；
 *  - 或在找不到「备份」时直接删除主库。
 * 二者都会造成不可逆的数据丢失。本对象把决策收敛为可测的纯函数：
 *  - 只有 [FailureKind.SCHEMA_MISMATCH] / [FailureKind.CORRUPTION] 才允许考虑恢复；
 *  - 只有 role 为 [ArtifactRole.MAIN_DB] 且尺寸、SQLite 魔数均合法的 **整库快照** 才能作为恢复源。
 */
internal object DatabaseRecoveryPolicy {

    /** 打开失败的种类。只有 [SCHEMA_MISMATCH]/[CORRUPTION] 才值得尝试恢复。 */
    enum class FailureKind { SCHEMA_MISMATCH, CORRUPTION, TRANSIENT, UNKNOWN }

    /** 某个库文件在磁盘上的角色。只有 [MAIN_DB] 才能作为整库恢复源或写成主库。 */
    enum class ArtifactRole { MAIN_DB, WAL, SHM, JOURNAL, UNKNOWN }

    /** SQLite 文件头魔数：`"SQLite format 3\0"`。 */
    val SQLITE_MAGIC: ByteArray = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00
    )

    /** 小于该尺寸的文件不可能是合法 SQLite 主库（空库也 > 512B）。 */
    const val MIN_DB_SIZE_BYTES: Long = 512L

    /**
     * 只把「明确表示损坏或结构不兼容」的错误当成可恢复；「锁/IO/忙」等瞬态错误一律不动主库。
     * 判定基于异常消息子串（Room/SQLite 的稳定文案），大小写不敏感。
     */
    private val TRANSIENT_MARKERS: List<String> = listOf(
        "database is locked",
        "database table is locked",
        "database is busy",
        "sqlite_busy",
        "cannot start a transaction within a transaction",
        "disk i/o error",
        "disk i/o",
        "database or disk is full",
        "disk full",
        "out of memory",
        "read-only database",
        "attempt to write a readonly database",
        "unable to open database",
        "can't open database",
        "cannot open database",
    )

    /** 分类：先看结构性错误（可恢复），再看瞬态错误（不可恢复），其余归 [FailureKind.UNKNOWN]。 */
    fun classify(messages: List<String>): FailureKind {
        val joined = messages.filterNotNull().joinToString("\n").lowercase()
        if (joined.isBlank()) return FailureKind.UNKNOWN
        if (joined.contains("identity hash")) return FailureKind.SCHEMA_MISMATCH
        if (joined.contains("cannot verify the data integrity")) return FailureKind.CORRUPTION
        if (joined.contains("malformed")) return FailureKind.CORRUPTION
        // SQLITE_NOTADB(26)：文件头不是合法 SQLite 库（真损坏/被非法文件覆盖）。
        if (joined.contains("file is not a database")) return FailureKind.CORRUPTION
        // Android SQLite/Room 对非 SQLite 文件格式的稳定文案。
        if (joined.contains("unsupported file format")) return FailureKind.CORRUPTION
        if (TRANSIENT_MARKERS.any { joined.contains(it) }) return FailureKind.TRANSIENT
        return FailureKind.UNKNOWN
    }

    /** 只有结构不兼容/损坏才允许进入恢复；瞬态与未知错误必须原样保留主库。 */
    fun shouldAttemptRecovery(kind: FailureKind): Boolean =
        kind == FailureKind.SCHEMA_MISMATCH || kind == FailureKind.CORRUPTION

    /** 依据文件名判定角色；兼容 recovery/ 目录里的 `<name>.corrupted_*`、`<name>.broken.*` 变体。 */
    fun roleOf(fileName: String, dbName: String): ArtifactRole {
        if (fileName == dbName) return ArtifactRole.MAIN_DB
        return when {
            fileName.startsWith("$dbName-wal") -> ArtifactRole.WAL
            fileName.startsWith("$dbName-shm") -> ArtifactRole.SHM
            fileName.startsWith("$dbName-journal") -> ArtifactRole.JOURNAL
            // 主库的留证副本：<db>.corrupted_<ts> / <db>.broken.<ts> / <db>.pre_restore_<ts>
            fileName.startsWith("$dbName.corrupted_") ||
                fileName.startsWith("$dbName.broken.") ||
                fileName.startsWith("$dbName.pre_restore_") -> ArtifactRole.MAIN_DB
            else -> ArtifactRole.UNKNOWN
        }
    }

    /** 字节头部是否为合法 SQLite 魔数。 */
    fun hasSqliteHeader(bytes: ByteArray): Boolean {
        if (bytes.size < SQLITE_MAGIC.size) return false
        for (i in SQLITE_MAGIC.indices) {
            if (bytes[i] != SQLITE_MAGIC[i]) return false
        }
        return true
    }

    /** 恢复候选（磁盘文件的最小可测抽象）。 */
    data class Candidate(
        val name: String,
        val sizeBytes: Long,
        val headerValid: Boolean,
        val lastModifiedMs: Long,
        val role: ArtifactRole
    )

    /** 是否能作为「整库恢复源」：必须是主库、尺寸达标、且头部是合法 SQLite。 */
    fun isRestorableMainDb(candidate: Candidate): Boolean =
        candidate.role == ArtifactRole.MAIN_DB &&
            candidate.sizeBytes > MIN_DB_SIZE_BYTES &&
            candidate.headerValid

    /**
     * 从候选中挑最新的合法整库快照；返回 null 表示「没有任何可安全恢复的整库」——
     * 此时调用方必须保留原文件，绝不删除。
     */
    fun selectRestorableMainDb(candidates: List<Candidate>): Candidate? =
        candidates.filter(::isRestorableMainDb).maxByOrNull { it.lastModifiedMs }

    // ── 非破坏性文件移动（数据不可丢：verify-then-delete）────────────────────────

    /**
     * 非破坏性地把 [source] 移到 [target]，**绝不丢弃字节**。
     *
     *  - 优先原子改名（[File.renameTo] 成功即字节已随之转移，返回 true）；
     *  - 改名失败才退化为「复制 + 校验（目标存在且尺寸与原文件一致）+ 删除」；
     *  - **只有在复制被证明确实成功后才允许删除原文件**；复制未获证实（例如目标目录不可写、
     *    磁盘写满导致拷贝异常）时**保留原文件不动**并返回 false —— 宁可不清理，也绝不丢字节。
     *
     * @return true 表示 source 已被安全处置（改名成功，或复制经校验成功后删除）；
     *         false 表示 source 仍原样保留（字节未丢）。
     */
    fun moveAsideNonDestructive(source: File, target: File): Boolean {
        if (runCatching { source.renameTo(target) }.getOrDefault(false)) return true

        val sourceLength = source.length()
        val copied = runCatching {
            source.copyTo(target, overwrite = true)
            target.exists() && target.length() == sourceLength
        }.getOrDefault(false)
        if (!copied) return false

        runCatching { source.delete() }
        return true
    }
}
