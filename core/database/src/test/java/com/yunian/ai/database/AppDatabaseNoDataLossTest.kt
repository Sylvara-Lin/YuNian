package com.yunian.ai.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「任何 DB 异常路径都不允许删/覆盖主库」的源码级回归护栏。
 *
 * 与 [AppDatabaseConfigurationTest] 同一套路（对源文件做文本断言，避免依赖 Android 运行时）。
 * 一旦有人重新引入「找不到备份就删库 / 用附属文件覆盖主库」的写法，本测试立即失败。
 */
class AppDatabaseNoDataLossTest {

    private val source: String = java.io.File(
        "src/main/java/com/yunian/ai/database/AppDatabase.kt"
    ).readText()

    @Test
    fun `recovery path never deletes the main database file`() {
        assertFalse(
            "恢复路径不得出现 deleteDatabaseFiles（旧实现会在无备份时删库）",
            source.contains("deleteDatabaseFiles")
        )
        assertFalse(
            "恢复路径不得直接 delete 主库文件",
            source.contains("dbFile.delete()")
        )
        assertFalse(
            "恢复路径不得出现旧的 recoverDatabase（会选择附属文件覆盖主库）",
            source.contains("recoverDatabase(")
        )
        assertFalse(
            "恢复路径不得出现旧的 tryRecoverFromBackup / tryRepairWalFiles（会删 WAL/SHM）",
            source.contains("tryRecoverFromBackup(") || source.contains("tryRepairWalFiles(")
        )
    }

    @Test
    fun `recovery is gated by classification and validates snapshot role`() {
        assertTrue(
            "打开失败必须经 DatabaseRecoveryPolicy 分类后才决定是否恢复",
            source.contains("DatabaseRecoveryPolicy.classify(")
        )
        assertTrue(
            "只有结构不兼容/损坏才允许进入恢复",
            source.contains("DatabaseRecoveryPolicy.shouldAttemptRecovery(")
        )
        assertTrue(
            "恢复源必须经 MainDb 角色/尺寸/魔数校验",
            source.contains("DatabaseRecoveryPolicy.isRestorableMainDb(")
        )
        assertTrue(
            "恢复只从正式备份目录写回（主库 + 同批附属文件对位）",
            source.contains("restoreMainDbFromBestBackup(")
        )
    }
}
