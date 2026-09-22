package com.yunian.ai.agent.sticker

import android.content.Context
import android.util.Log
import com.yunian.ai.agent.uniffi.DriftStatus
import com.yunian.ai.agent.uniffi.StickerPreferenceEngine
import com.yunian.ai.agent.uniffi.StickerPreferenceParams
import com.yunian.ai.agent.uniffi.StickerPreferenceSnapshot
import com.yunian.ai.agent.uniffi.StickerSource
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.common.StickerManager
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.StickerEntryEntity
import com.yunian.ai.database.model.StickerTagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 表情包偏好层门面（Kotlin 侧唯一入口，决策全在 Rust）。
 *
 * 职责：
 * - 全局单例 [StickerPreferenceEngine] 懒创建（仿 AgentFacade.skillSelector 模式）
 * - 暴露采样 / 记录 / 快照 / 可用标签 / 漂移检测 / 重建
 * - `syncMetadataFromFilesystem`：文件系统 → DB 元数据回填（SHA-256 幂等 + 失效行清理 + rebuild）
 *
 * 参数为文档 §8 默认值：τ=30d、n0=20/n1=200、ε=0.05、α/β/γ=1.0/1.0/0.5、
 * W=100、θ=0.15、M=5、seed=20260810。
 */
object StickerPreferenceFacade {

    private const val TAG = "StickerPreferenceFacade"

    @Volatile
    private var engine: StickerPreferenceEngine? = null

    /** 文档 §8 参数表默认值 */
    private fun defaultParams(): StickerPreferenceParams = StickerPreferenceParams(
        decayHalfLifeMs = 30UL * 24UL * 60UL * 60UL * 1000UL,
        n0 = 20UL,
        n1 = 200UL,
        exploreEpsilon = 0.05,
        alpha = 1.0,
        beta = 1.0,
        gamma = 0.5,
        driftWindow = 100UL,
        driftThreshold = 0.15,
        maxClassSize = 5UL,
        sampleSeed = 20260810UL,
    )

    /**
     * 获取（惰性创建）共享 [StickerPreferenceEngine]（全局单例，一次 init）。
     * 引擎内部持 Rust 状态；Kotlin 侧只做参数与回调适配。
     */
    fun engine(context: Context): StickerPreferenceEngine =
        engine ?: synchronized(this) {
            engine ?: StickerPreferenceEngine(
                StickerPreferenceStoreImpl(context),
                defaultParams(),
            ).also { engine = it }
        }

    /**
     * OR 命中采样：`queryTags`（1~3 个精确 tag）任一命中即入候选，
     * top-M → Gibbs 加权随机 → ε 探索；返回按分数降序的 id 列表（至多 limit 个）。
     * 无命中返回空列表（Kotlin 侧触发"无匹配"回退）。
     * suspend + IO：避免在调用方协程（Main）上阻塞。
     */
    suspend fun sampleCandidates(
        context: Context,
        limit: Int,
        queryTags: List<String>,
    ): List<Long> = withContext(Dispatchers.IO) {
        engine(context)
            .sampleCandidates(limit.toULong().coerceAtLeast(1UL), queryTags)
            .map { it.toLong() }
    }

    /**
     * 记录一次使用：User 更新偏好缓存；Model 只记数不污染偏好。
     * suspend + IO：避免在调用方协程（Main）上阻塞。
     */
    suspend fun recordUsage(
        context: Context,
        stickerId: Long,
        source: StickerSource,
        contextTags: List<String>,
    ) = withContext(Dispatchers.IO) {
        engine(context).recordUsage(stickerId.toULong(), source, contextTags)
    }

    /** 偏好快照（tag 先验分布 + 有效样本 + 漂移状态），异常返回 null */
    fun snapshot(context: Context): StickerPreferenceSnapshot? = runBlockingOnIo {
        runCatching { engine(context).snapshot() }.getOrNull()
    }

    /** 可用标签 Top-N（按先验概率降序），注入系统提示用 */
    fun availableTags(context: Context, topN: Int = 30): List<String> {
        val prior = snapshot(context)?.tagPrior ?: return emptyList()
        return prior.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(topN.coerceAtLeast(0))
    }

    /**
     * 可用标签 Top-N（冷启动回退版）：先尝试偏好先验；
     * 为空（引擎无样本 / 先验空）时回退 tag 统计映射表（sticker_tags，按图片数降序）Top-N，
     * 保证系统提示 / syncRuntimeConfig 注入始终有值。
     */
    fun availableTagsWithFallback(context: Context, topN: Int = 30): List<String> {
        val fromPrior = availableTags(context, topN)
        if (fromPrior.isNotEmpty()) return fromPrior
        return runBlockingOnIo {
            AppDatabase.getDatabase(context.applicationContext).stickerTagDao()
                .getAll()
                .take(topN.coerceAtLeast(0))
                .map { it.tag }
        }
    }

    /**
     * 用户手动发送表情包（StickerPanel）→ 记录 USER 使用（偏好学习信号）。
     * 按 fileName 定位 DB 条目并派生 tags；未入库（内置表情包）则忽略（返回 false）。
     */
    suspend fun recordUserUsage(context: Context, sticker: StickerInfo): Boolean = withContext(Dispatchers.IO) {
        val fileName = sticker.fileName ?: return@withContext false
        val dao = AppDatabase.getDatabase(context.applicationContext).stickerEntryDao()
        val entry = dao.getByFileName(fileName) ?: return@withContext false
        val userTags = sticker.tags ?: emptyList()
        val tags = if (userTags.isNotEmpty()) {
            normalizeTags(userTags).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            deriveTags(sticker.description, fileName)
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        engine(context).recordUsage(entry.id.toULong(), StickerSource.USER, tags)
        true
    }

    /** 漂移检测：recent W 条 vs 更早 USER 记录的 JS 散度 */
    fun checkDrift(context: Context): DriftStatus? = runBlockingOnIo {
        runCatching { engine(context).checkDrift() }.getOrNull()
    }

    /**
     * 初始化（应用启动时由 LianYuApplication 调用一次）：
     * 1. 接线 StickerManager 文件系统变更钩子 → syncMetadataFromFilesystem
     * 2. 应用级后台作用域首启全量同步（文件系统 → DB → 引擎 rebuild），空导入也安全
     */
    fun ensureInitialized(context: Context) {
        val appContext = context.applicationContext
        StickerManager.getInstance(appContext).onStickerFilesChanged = {
            runCatching { syncMetadataFromFilesystem(appContext) }
                .onFailure { Log.e(TAG, "sticker files changed sync failed", it) }
        }
        com.yunian.ai.common.ApplicationScopeProvider.scope.launch {
            runCatching { syncMetadataFromFilesystem(appContext) }
                .onFailure { Log.e(TAG, "initial sticker sync failed", it) }
        }
    }

    /** 全量重建（应用启动 / 表情包导入后调用） */
    fun rebuild(context: Context) {
        runBlockingOnIo { engine(context).rebuild() }
    }

    /**
     * 文件系统 → DB 元数据同步（导入 / 删除 / 启动时调用）：
     * 1. 遍历 imported 表情包文件 → 计算 SHA-256 → 按 hash 幂等 upsert 元数据
     * 2. 清理 DB 中文件已不存在的行（用户删除）
     * 3. 引擎 rebuild（全量载入最新元数据）
     */
    suspend fun syncMetadataFromFilesystem(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val manager = StickerManager.getInstance(appContext)
        val dao = AppDatabase.getDatabase(appContext).stickerEntryDao()
        val stickers = manager.getAllStickers().filter { !it.isBuiltIn }
        val now = System.currentTimeMillis()
        val seenFiles = mutableSetOf<String>()

        stickers.forEach { info ->
            val file = File(info.path)
            if (!file.isFile) return@forEach
            val fileName = info.fileName ?: file.name
            seenFiles.add(fileName)
            val hash = sha256(file) ?: return@forEach
            val ruleTags = info.tags ?: emptyList()
            val tags = if (ruleTags.isNotEmpty()) {
                normalizeTags(ruleTags)
            } else {
                deriveTags(info.description, fileName)
            }
            val existing = dao.getByHash(hash)
            if (existing == null) {
                dao.insert(
                    StickerEntryEntity(
                        hash = hash,
                        description = info.description,
                        tags = tags,
                        fileName = fileName,
                        source = "imported",
                        fileSize = file.length(),
                        createdAt = now,
                        lastUsedAt = now,
                    )
                )
            } else {
                // 幂等：已有行仅刷新 description/tags（hash 相同视为同一文件）
                dao.update(
                    existing.copy(
                        description = info.description ?: existing.description,
                        tags = tags.ifEmpty { existing.tags },
                        fileName = fileName,
                        fileSize = file.length(),
                        lastUsedAt = now,
                    )
                )
            }
        }

        // 清理：DB 中 imported 行但文件已不存在（用户删除/覆盖导入）
        dao.getAll().forEach { row ->
            if (row.source == "imported" && row.fileName !in seenFiles) {
                dao.deleteByFileName(row.fileName)
            }
        }

        runCatching { engine(appContext).rebuild() }
            .onFailure { Log.e(TAG, "rebuild failed", it) }

        // 重建 tag 统计映射表（导入对话框候选：已有 tag + 图片数）
        runCatching { rebuildTagStats(appContext) }
            .onFailure { Log.e(TAG, "rebuild tag stats failed", it) }
    }

    /**
     * 从 sticker_entries 全量聚合重建 tag 统计映射表（sticker_tags）。
     * 幂等：先清空再批量插入；count = 关联图片数（一张图多 tag 分别计数）。
     * 供导入对话框展示「已有 tag + 图片数」供选择，避免每次全表扫描聚合。
     */
    suspend fun rebuildTagStats(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = AppDatabase.getDatabase(appContext)
        val counts = LinkedHashMap<String, Int>()
        db.stickerEntryDao().getAll().forEach { entry ->
            entry.tags.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { tag -> counts[tag] = (counts[tag] ?: 0) + 1 }
        }
        val tagDao = db.stickerTagDao()
        tagDao.deleteAll()
        counts.forEach { (tag, count) ->
            tagDao.insert(StickerTagEntity(tag = tag, stickerCount = count))
        }
        Log.i(TAG, "rebuildTagStats: ${counts.size} tags")
    }

    /** tags 规范化：trim、去空、去重、取≤3、逗号拼接（与 StickerManager.normalizeTags 语义一致） */
    private fun normalizeTags(tags: List<String>): String {
        return tags.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .joinToString(",")
    }


    /** tags 派生：description 按分隔符拆分 trim 过滤空取≤3；空则 fileName 去扩展名 */
    private fun deriveTags(description: String?, fileName: String): String {
        val base = description?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.').takeIf { it.isNotBlank() }
            ?: return ""
        return base.split(Regex("[,，、/;； ]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(3)
            .joinToString(",")
    }

    /** 流式 SHA-256（8KB buffer），失败返回 null */
    private fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e(TAG, "sha256 failed for ${file.name}", e)
        null
    }

    /** 阻塞桥（引擎调用发生在 IO 线程，避免主线程卡顿） */
    private inline fun <T> runBlockingOnIo(crossinline block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }
}
