package com.yunian.ai.agent.skill

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Agent 技能文件存储（混合存储之"内容"侧）。
 *
 * 目录布局（filesDir/agent_skills/）：
 * ```
 * agent_skills/
 *   <skillId>/            # skillId = UUID，唯一
 *     content.md          # 技能正文（UTF-8）
 *     meta.json           # 冗余元数据快照（Room 索引可随时从 FS 重建）
 * ```
 *
 * 一致性协议：
 * - 写入：先 content.md，再 meta.json（FS 完成 → 调用方再写 Room 索引）
 * - 读取：调用方读索引 → 本类读文件 → 校验 SHA-256
 * - 文件系统是唯一事实源；Room 索引损坏时可按 skillId 目录重建
 */
class SkillFileStore(private val rootDir: File) {

    init {
        // 懒创建：首次写入才建目录，不依赖 Room 回调（覆盖安装安全）
        runCatching { rootDir.mkdirs() }
    }

    /** 技能正文文件 */
    fun contentFile(skillId: String): File = File(rootDir, "$skillId/content.md")

    /** 元数据快照文件 */
    fun metaFile(skillId: String): File = File(rootDir, "$skillId/meta.json")

    /** 技能目录是否存在 */
    fun exists(skillId: String): Boolean = File(rootDir, skillId).isDirectory

    /** 读取正文，不存在返回 null */
    fun readContent(skillId: String): String? = runCatching {
        contentFile(skillId).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrElse {
        Log.w(TAG, "readContent failed for $skillId", it)
        null
    }

    /** 写正文（先写临时文件再原子重命名，避免半写状态） */
    fun writeContent(skillId: String, content: String): Boolean = runCatching {
        val dir = File(rootDir, skillId).apply { mkdirs() }
        val target = contentFile(skillId)
        val tmp = File(dir, "content.md.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // Windows 上 renameTo 可能失败，退化为直接写
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        true
    }.getOrElse {
        Log.e(TAG, "writeContent failed for $skillId", it)
        false
    }

    /** 写元数据快照 */
    fun writeMeta(skillId: String, metaJson: String): Boolean = runCatching {
        val dir = File(rootDir, skillId).apply { mkdirs() }
        metaFile(skillId).writeText(metaJson, Charsets.UTF_8)
        true
    }.getOrElse {
        Log.e(TAG, "writeMeta failed for $skillId", it)
        false
    }

    /** 删除技能目录（含正文与快照） */
    fun deleteDir(skillId: String): Boolean = runCatching {
        File(rootDir, skillId).deleteRecursively()
    }.getOrElse {
        Log.e(TAG, "deleteDir failed for $skillId", it)
        false
    }

    /** 计算 UTF-8 正文的 SHA-256（hex） */
    fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 列出所有技能目录名（启动巡检用：扫描 FS 对账 Room 索引） */
    fun listSkillDirs(): List<String> =
        runCatching {
            rootDir.listFiles { f -> f.isDirectory }?.map { it.name } ?: emptyList()
        }.getOrElse {
            Log.w(TAG, "listSkillDirs failed", it)
            emptyList()
        }

    /** 重建用：从 meta.json 快照恢复元数据（Room 索引丢失时的兜底） */
    fun readMeta(skillId: String): String? = runCatching {
        metaFile(skillId).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrElse {
        Log.w(TAG, "readMeta failed for $skillId", it)
        null
    }

    private companion object {
        const val TAG = "AgentSkillFileStore"
    }
}
