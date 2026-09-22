package com.yunian.ai.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.yunian.ai.common.image.ImageFormatSniffer
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class StickerManager(private val context: Context) {

    private val stickersDir = File(context.filesDir, "stickers")
    private val importedDir = File(stickersDir, "imported")
    private val stickerRulesFile = File(importedDir, StickerRuleStore.RULES_FILE_NAME)

    /** JSON v2 持久化（原子写 / merge / aliasIndex 均收口到 store） */
    private val ruleStore = StickerRuleStore(stickerRulesFile)

    /** 内存态：description → StickerRule（保持既有对外结构不变） */
    private var stickerRules: Map<String, StickerRule> = emptyMap()

    /** 内存态：完整 v2 条目（语义 / 别名 / 时间戳 / 来源） */
    private var entries: List<StickerRuleStore.Entry> = emptyList()

    /** 内存态：fileName → Entry（按文件名索引，杜绝「重名 description 折叠」） */
    private var fileNameIndex: Map<String, StickerRuleStore.Entry> = emptyMap()

    /** 别名索引：alias → description（匹配链回退用，P6） */
    private var aliasIndex: Map<String, String> = emptyMap()

    /** 变更通知：import / rename / delete 后 +1，UI（StickerPanel）订阅刷新（修 P8） */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    /**
     * 文件系统变更回调（导入 / 删除成功后触发）。
     * 由应用启动时接线到 `StickerPreferenceFacade.syncMetadataFromFilesystem`，
     * 实现「文件系统 → DB 元数据」回填 + 偏好引擎 rebuild。
     */
    @Volatile
    var onStickerFilesChanged: (suspend () -> Unit)? = null

    init {
        stickersDir.mkdirs()
        importedDir.mkdirs()
        loadRules()
    }

    data class StickerRule(
        val description: String,
        val fileName: String,
        val path: String
    )

    private fun loadRules() {
        try {
            val loaded = ruleStore.load()
            entries = loaded
            fileNameIndex = ruleStore.buildFileNameIndex(loaded)
            aliasIndex = ruleStore.buildAliasIndex(loaded)
            logDuplicateDescriptions(loaded)
            stickerRules = loaded
                .filter { File(importedDir, it.fileName).exists() }
                .associate { entry ->
                    entry.description to StickerRule(
                        description = entry.description,
                        fileName = entry.fileName,
                        path = File(importedDir, entry.fileName).absolutePath
                    )
                }
            SecureLog.i("StickerManager", "Loaded ${stickerRules.size} sticker rules (${entries.size} entries)")
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load sticker rules", e)
            stickerRules = emptyMap()
            entries = emptyList()
            fileNameIndex = emptyMap()
            aliasIndex = emptyMap()
        }
    }

    /** 重名 description 留痕（重名会在 [stickerRules] 的 `associate` 中互相覆盖，必须可观测）。 */
    private fun logDuplicateDescriptions(source: List<StickerRuleStore.Entry>) {
        val duplicated = ruleStore.duplicateDescriptions(source)
        if (duplicated.isNotEmpty()) {
            SecureLog.w("StickerManager", "Duplicate sticker descriptions collapsed in rule map: $duplicated")
        }
    }

    /** 内存态统一刷新入口：Map + aliasIndex + fileNameIndex 重建 + version+1（修 P10：不再每次读盘） */
    private fun refreshState(newEntries: List<StickerRuleStore.Entry>) {
        entries = newEntries
        fileNameIndex = ruleStore.buildFileNameIndex(newEntries)
        aliasIndex = ruleStore.buildAliasIndex(newEntries)
        logDuplicateDescriptions(newEntries)
        stickerRules = newEntries
            .filter { File(importedDir, it.fileName).exists() }
            .associate { entry ->
                entry.description to StickerRule(
                    description = entry.description,
                    fileName = entry.fileName,
                    path = File(importedDir, entry.fileName).absolutePath
                )
            }
        _version.value += 1
    }

    suspend fun getAllStickers(): List<StickerInfo> = withContext(Dispatchers.IO) {
        val stickers = mutableListOf<StickerInfo>()

        try {
            val assetStickers = context.assets.list("stickers") ?: emptyArray()
            assetStickers.forEach { filename ->
                if (isImageFile(filename)) {
                    stickers.add(StickerInfo(
                        name = filename.substringBeforeLast("."),
                        path = "asset://stickers/$filename",
                        category = "default",
                        isBuiltIn = true
                    ))
                }
            }
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "No assets/stickers found")
        }

        val index = fileNameIndex
        importedDir.listFiles()?.forEach { file ->
            if (isImageFile(file.name)) {
                // 按 fileName 定位条目（而非按 description 建 Map.values 反查）：
                // 重名 description 折叠时，每个文件仍能取到自己的 description，不会被静默降级成内部文件名。
                val entry = index[file.name]
                val description = entry?.description?.takeIf { it.isNotBlank() }
                stickers.add(StickerInfo(
                    name = description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = description,
                    fileName = file.name
                ))
            }
        }

        stickers
    }

    /**
     * 规则 E 的「可用表情名」名单（提示词层共用的唯一装配入口）。
     *
     * 取代 `AiService` 三处各自实现的 `mapNotNull { …length<=20… }.distinct()`：
     * 后者会**静默丢弃**长度 > 20 的名字（无日志），使「规则丢失→退化成内部文件名」
     * 的表情对 AI 隐形。装配规则见 [StickerPromptNames]（不按长度丢弃、自定义优先）。
     */
    suspend fun getStickerNamesForPrompt(): List<String> {
        val all = getAllStickers()
        val names = StickerPromptNames.build(all)
        val blank = StickerPromptNames.blankNameCount(all)
        if (blank > 0) {
            SecureLog.w("StickerManager", "Dropped $blank sticker(s) with blank display name from prompt list")
        }
        if (names.size < all.size - blank) {
            SecureLog.d(
                "StickerManager",
                "Prompt sticker names capped (built-ins only): total=${all.size}, emitted=${names.size}"
            )
        }
        return names
    }

    fun findStickerByDescriptionExact(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        return null
    }

    /**
     * 别名精确匹配（P6 新增）：aliasIndex 命中后转精确查找。
     * 模型输出「绷不住了」能命中名为「裂开」的表情。
     */
    fun findStickerByAliases(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null
        val description = aliasIndex[keyword.trim()] ?: return null
        return findStickerByDescriptionExact(description)
    }

    fun findStickerByDescription(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        val directFile = File(importedDir, keyword)
        if (directFile.exists()) {
            val rule = rules.values.find { it.fileName == keyword }
            return StickerInfo(
                name = rule?.description ?: keyword.substringBeforeLast("."),
                path = directFile.absolutePath,
                category = "imported",
                isBuiltIn = false,
                description = rule?.description,
                fileName = keyword
            )
        }

        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        rules.entries.find { it.key.contains(keyword) || keyword.contains(it.key) }?.let { entry ->
            return StickerInfo(
                name = entry.value.description,
                path = entry.value.path,
                category = "imported",
                isBuiltIn = false,
                description = entry.value.description,
                fileName = entry.value.fileName
            )
        }

        importedDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension == keyword || file.name == keyword) {
                val rule = rules.values.find { it.fileName == file.name }
                return StickerInfo(
                    name = rule?.description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = rule?.description,
                    fileName = file.name
                )
            }
        }

        return null
    }

    /**
     * 读内存，不再每次重读磁盘（修 P10）。
     * 从 [entries] 派生（按 fileName 去重、只保留文件仍在者），杜绝「重名折叠」丢规则。
     */
    fun getAllRules(): List<StickerRule> {
        return fileBackedEntries()
            .map { entry ->
                StickerRule(
                    description = entry.description,
                    fileName = entry.fileName,
                    path = File(importedDir, entry.fileName).absolutePath
                )
            }
    }

    /** 名称唯一性查询（命名框实时校验用，修 P4） */
    fun isNameTaken(name: String): Boolean {
        return name.isNotBlank() && stickerRules.containsKey(name.trim())
    }

    /** 最新导入的文件名（StickerPanel 果冻入场定位用） */
    fun newestImportedFileName(): String? {
        return entries.maxByOrNull { it.createdAt }?.fileName
    }

    /** 按 fileName 查完整 v2 条目（重命名表单回填语义 / 别名用） */
    fun getEntry(fileName: String): StickerRuleStore.Entry? {
        return entries.find { it.fileName == fileName }
    }

    /**
     * 给提示词层用的结构化清单（按 createdAt 新→旧排序，供预算截断）。
     *
     * 只保留**文件仍存在且描述非空**的条目 —— 与反查侧（[stickerRules]）保持同一口径，
     * 保证「提示词里宣传的表情 ⇒ 发送侧一定能反查到」，修「E2 宣传了发不出去的表情」的不一致。
     * 按 fileName 去重，避免重名折叠重复宣传同一文件。
     */
    fun getPromptStickers(): List<PromptSticker> {
        return fileBackedEntries()
            .sortedByDescending { it.createdAt }
            .map { PromptSticker(name = it.description, semantic = it.semantic, aliases = it.aliases, isCustom = true) }
    }

    /** 文件仍在、描述非空、按 fileName 去重的条目（提示词侧与规则侧的公共真值来源）。 */
    private fun fileBackedEntries(): List<StickerRuleStore.Entry> {
        return entries
            .filter { it.description.isNotBlank() && File(importedDir, it.fileName).exists() }
            .distinctBy { it.fileName }
    }

    /**
     * 单文件导入（P0 新增）：从 SAF / 相册 / 拖放 Uri 拷贝到 importedDir，写 JSON v2 条目。
     * 失败原因：名称重复 / 非图片 / 读取失败 / 超过软上限。
     */
    suspend fun importStickerFile(
        uri: Uri,
        customName: String,
        semantic: String = "",
        aliases: List<String> = emptyList(),
    ): Result<StickerInfo> = withContext(Dispatchers.IO) {
        try {
            val name = sanitizeStickerNameText(customName)
            // 校验（单一来源，纯函数）：空名 / 系统保留名（保留名会被发送侧无条件跳过 → AI 可见却发不出）
            stickerNameValidationError(name)?.let {
                return@withContext Result.failure(IllegalArgumentException(it))
            }
            if (isNameTaken(name)) {
                return@withContext Result.failure(IllegalStateException("已有同名表情"))
            }
            if (entries.size >= MAX_IMPORTED_COUNT) {
                return@withContext Result.failure(IllegalStateException("自定义表情已达上限 ${MAX_IMPORTED_COUNT} 个，请先清理不需要的表情"))
            }
            // Bug2：一次打开 + mark/reset —— 先嗅探文件头（provider 无关），再完整拷贝。
            // 旧实现先 resolveImageExtension（仅 DISPLAY_NAME/MIME）再二次 openInputStream；
            // SAF/微信/QQ/华为相册等 provider 不给后缀或返回 octet-stream 时，
            // 合法 jpg/png 会被误判为「仅支持图片文件」直接拒绝。
            val rawInput = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("读取图片失败"))
            val bufferedInput = BufferedInputStream(rawInput)
            val header = try {
                bufferedInput.mark(SNIFF_MARK_LIMIT)
                val sniffed = readHeader(bufferedInput, ImageFormatSniffer.HEADER_SIZE)
                bufferedInput.reset()
                sniffed
            } catch (e: Exception) {
                runCatching { bufferedInput.close() }
                return@withContext Result.failure(IllegalStateException("读取图片失败"))
            }

            // 三级判定：DISPLAY_NAME 后缀 → MIME 映射 → 魔数嗅探
            val nameExt = resolveExtensionFromDisplayName(uri)
            val mimeExt = resolveExtensionFromMime(uri)
            val sniffExt = ImageFormatSniffer.detect(header)
            val extension = resolveImageExtension(nameExt, mimeExt, sniffExt)
            if (extension == null || extension !in IMAGE_EXTENSIONS) {
                runCatching { bufferedInput.close() }
                val detected = nameExt ?: mimeExt ?: sniffExt ?: "未知"
                return@withContext Result.failure(
                    IllegalArgumentException("无法识别的图片格式（检测到: $detected，仅支持 png/jpg/gif/webp）")
                )
            }

            val fileName = "custom_${System.currentTimeMillis()}_${(0..999).random()}.$extension"
            val destFile = File(importedDir, fileName)
            // reset 后流已回到头部，拷贝内容完整（嗅探不吃掉头部）
            try {
                bufferedInput.use { source ->
                    destFile.outputStream().use { output ->
                        source.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("保存图片失败: ${e.message}"))
            }

            val entry = StickerRuleStore.Entry(
                description = name,
                fileName = fileName,
                semantic = sanitizeStickerSemanticText(semantic),
                aliases = sanitizeStickerAliasList(aliases),
                createdAt = System.currentTimeMillis(),
                source = StickerRuleStore.SOURCE_FILE,
            )
            if (!ruleStore.save(entries + entry)) {
                // JSON 落盘失败：回滚图片，避免规则孤儿
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("保存表情规则失败"))
            }
            refreshState(entries + entry)
            SecureLog.i("StickerManager", "Imported sticker file: $name -> $fileName")
            Result.success(StickerInfo(
                name = name,
                path = destFile.absolutePath,
                category = "imported",
                isBuiltIn = false,
                description = name,
                fileName = fileName
            ))
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "importStickerFile failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 重命名（含语义 / 别名编辑）：同步更新 Map key、aliasIndex、JSON（修 P4 / P8）。
     * 失败：名称重复 / 条目不存在。
     */
    suspend fun renameImportedSticker(
        fileName: String,
        newName: String,
        semantic: String? = null,
        aliases: List<String>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val name = sanitizeStickerNameText(newName)
            // 校验（单一来源，纯函数）：空名 / 系统保留名（同导入，避免「AI 可见却永远发不出」）
            stickerNameValidationError(name)?.let {
                return@withContext Result.failure(IllegalArgumentException(it))
            }
            val target = entries.find { it.fileName == fileName }
                ?: return@withContext Result.failure(NoSuchElementException("表情条目不存在"))
            // 重命名时原名本身不算重复
            if (name != target.description && isNameTaken(name)) {
                return@withContext Result.failure(IllegalStateException("已有同名表情"))
            }
            val updated = target.copy(
                description = name,
                semantic = semantic?.let { sanitizeStickerSemanticText(it) } ?: target.semantic,
                aliases = aliases?.let { sanitizeStickerAliasList(it) } ?: target.aliases,
            )
            val newEntries = entries.map { if (it.fileName == fileName) updated else it }
            if (!ruleStore.save(newEntries)) {
                return@withContext Result.failure(IllegalStateException("保存表情规则失败"))
            }
            refreshState(newEntries)
            SecureLog.i("StickerManager", "Renamed sticker: $fileName -> $name")
            Result.success(Unit)
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "renameImportedSticker failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 修复版删除（修 P3）：删图片 + 同步移除 JSON 条目 + 刷内存。
     */
    suspend fun deleteImportedSticker(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(importedDir, fileName)
            val fileDeleted = if (file.exists()) file.delete() else false
            val target = entries.find { it.fileName == fileName }
            if (target != null) {
                val newEntries = entries.filterNot { it.fileName == fileName }
                if (!ruleStore.save(newEntries)) {
                    SecureLog.w("StickerManager", "Failed to save rules after delete: $fileName")
                    return@withContext false
                }
                refreshState(newEntries)
            }
            SecureLog.i("StickerManager", "Deleted sticker: $fileName (file=$fileDeleted, rule=${target != null})")
            fileDeleted || target != null
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to delete sticker", e)
            false
        }
    }

    /**
     * 删除全部导入表情（修 P1）：只删图片文件，写回空 JSON，保留目录与元数据文件本体。
     */
    suspend fun deleteAllImportedStickers(): Boolean = withContext(Dispatchers.IO) {
        try {
            var deleted = 0
            importedDir.listFiles()?.forEach { file ->
                // 仅清理图片，不动 custom_stickers.json 等元数据文件
                if (isImageFile(file.name) && file.delete()) deleted++
            }
            val saved = ruleStore.save(emptyList())
            if (saved) {
                refreshState(emptyList())
            }
            SecureLog.i("StickerManager", "Deleted all $deleted imported stickers (jsonSaved=$saved)")
            saved
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to delete all stickers", e)
            false
        }
    }

    /**
     * ZIP 导入（修 P2）：图片照旧解压；custom_stickers.json 不再整体覆盖写入，
     * 而是解析后与现有规则按 description 做 merge，防止二次导入丢掉旧规则。
     */
    suspend fun importStickerZip(zipPath: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        var rulesFileExtracted = false
        try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) {
                SecureLog.e("StickerManager", "Zip file not found: $zipPath")
                return@withContext 0
            }

            var incomingEntries: List<StickerRuleStore.Entry> = emptyList()
            var tmpRulesFile: File? = null

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.substringAfterLast("/")
                    when {
                        entryName == StickerRuleStore.RULES_FILE_NAME -> {
                            // 先解到临时文件，merge 成功后再写正式 JSON（不再直接覆盖）
                            val tmp = File(context.cacheDir, "sticker_rules_${System.currentTimeMillis()}.tmp")
                            tmp.outputStream().use { output -> zis.copyTo(output) }
                            tmpRulesFile = tmp
                            rulesFileExtracted = true
                        }

                        !entry.isDirectory && isImageFile(entryName) -> {
                            val destFile = File(importedDir, entryName)
                            destFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                            count++
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            if (rulesFileExtracted) {
                incomingEntries = tmpRulesFile?.let { StickerRuleStore(it).load() } ?: emptyList()
                tmpRulesFile?.delete()
                val mergeResult = ruleStore.mergeZipRules(incomingEntries, entries)
                if (ruleStore.save(mergeResult.merged)) {
                    refreshState(mergeResult.merged)
                    SecureLog.i("StickerManager", "Zip rules merged: added=${mergeResult.added}, skipped=${mergeResult.skipped}")
                } else {
                    SecureLog.w("StickerManager", "Zip rules merge save failed")
                }
            } else {
                SecureLog.w("StickerManager", "No rules file found in zip!")
                // 图片解出来了但没有规则：刷新内存，让新图片以文件名退化展示
                refreshState(entries)
            }

            SecureLog.i("StickerManager", "Import complete: $count stickers, rules=$rulesFileExtracted")
            count
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to import zip", e)
            count
        }
    }

    /**
     * 降采样加载（P11）：网格 / 气泡显示尺寸 ≤512px 即可，避免全尺寸位图内存抖动。
     *
     * 说明：早期存在一个全尺寸 `loadStickerBitmap(path)`（`decodeFile` 不做 `inSampleSize`），
     * 在 1080×2400 长图 / 截图类贴纸上会一次吃下十几 MB（MTK + MIUI 机型易触发内存清理/闪退），
     * 且已无任何调用方，故删除，统一收口到本降采样入口。
     */
    fun loadStickerBitmapSampled(stickerPath: String, maxDimension: Int = 512): Bitmap? {
        return try {
            if (stickerPath.startsWith("asset://")) {
                decodeSampledAsset(stickerPath.removePrefix("asset://"), maxDimension)
            } else {
                decodeSampledFile(stickerPath, maxDimension)
            }
        } catch (e: OutOfMemoryError) {
            // MIUI/MTK 等内存较激进的机型上，超大/畸形图片即便降采样仍可能 OOM（Error 非 Exception，
            // 不捕获会直接崩进程）；此处兜底为 null，由 UI 退化为占位文字。
            SecureLog.e("StickerManager", "Failed to load sampled bitmap (OOM): ${e.message}")
            null
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load sampled bitmap", e)
            null
        }
    }

    /** 文件降采样解码：两遍解码先读 bounds，按目标尺寸算 inSampleSize */
    fun decodeSampledFile(path: String, maxDimension: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calcSampleSizeForMaxDimension(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: OutOfMemoryError) {
            SecureLog.e("StickerManager", "decodeSampledFile OOM: $path")
            null
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "decodeSampledFile failed: ${e.message}")
            null
        }
    }

    /** assets 降采样解码：assets 流不可重读，需 open 两次 */
    fun decodeSampledAsset(assetPath: String, maxDimension: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calcSampleSizeForMaxDimension(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: OutOfMemoryError) {
            SecureLog.e("StickerManager", "decodeSampledAsset OOM: $assetPath")
            null
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "decodeSampledAsset failed: ${e.message}")
            null
        }
    }

    fun getImportedDir(): String = importedDir.absolutePath

    fun pickStickerForMood(text: String): StickerInfo? {
        if (text.isBlank()) return null

        val lowerText = text.lowercase()
        val rules = stickerRules
        val allAvailableStickers = mutableListOf<StickerInfo>()

        rules.values.forEach { rule ->
            allAvailableStickers.add(StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            ))
        }

        importedDir.listFiles()?.filter { isImageFile(it.name) }?.forEach { file ->
            val existingRule = rules.values.find { it.fileName == file.name }
            if (existingRule == null) {
                allAvailableStickers.add(StickerInfo(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    fileName = file.name
                ))
            }
        }

        try {
            context.assets.list("stickers")?.filter { isImageFile(it) }?.forEach { filename ->
                allAvailableStickers.add(StickerInfo(
                    name = filename.substringBeforeLast("."),
                    path = "asset://stickers/$filename",
                    category = "default",
                    isBuiltIn = true
                ))
            }
        } catch (_: Exception) {}

        if (allAvailableStickers.isEmpty()) return null

        val moodKeywords = listOf(
            "开心" to listOf("开心", "高兴", "快乐", "笑", "哈哈", "嘻嘻", "嘿嘿", "好耶", "太棒了", "棒", "赞", "好", "nice"),
            "委屈" to listOf("委屈", "难过", "伤心", "哭", "呜呜", "呜", "眼泪", "可怜", "心疼", "好惨", "不想", "难过"),
            "生气" to listOf("生气", "气", "哼", "烦", "讨厌", "滚", "不理你", "不想理", "气鼓鼓", "怒"),
            "害羞" to listOf("害羞", "脸红", "不好意思", "羞", "呀", "哎呀", "啊这", "那个", "嗯..."),
            "惊讶" to listOf("惊讶", "哇", "天哪", "什么", "真的吗", "不会吧", "居然", "竟然", "啊？"),
            "撒娇" to listOf("撒娇", "嘛", "啦", "呢", "呀", "人家", "求你", "好不好", "嘛~", "拜托", "亲"),
            "可爱" to listOf("可爱", "萌", "乖", "乖巧", "听话", "小", "宝贝", "宝宝", "亲亲", "抱抱", "喜欢"),
            "无奈" to listOf("无奈", "算了", "服了", "无语", "行吧", "好吧", "随你", "随便", "额"),
            "思考" to listOf("思考", "想想", "嗯", "让我想", "不知道", "好像", "也许", "可能", "这个"),
            "困倦" to listOf("困", "累", "睡", "瞌睡", "哈欠", "晚安", "早安", " tired ")
        )

        for ((mood, keywords) in moodKeywords) {
            for (keyword in keywords) {
                if (lowerText.contains(keyword)) {
                    val matched = allAvailableStickers.find {
                        (it.description?.contains(mood) == true) ||
                        (it.name.contains(mood)) ||
                        (it.fileName?.contains(mood) == true)
                    } ?: allAvailableStickers.find {
                        (it.description?.contains(keyword) == true) ||
                        (it.name.contains(keyword))
                    }
                    if (matched != null) {
                        SecureLog.d("StickerManager", "Mood match: keyword='$keyword' → sticker='${matched.name}'")
                        return matched
                    }
                }
            }
        }

        val picked = allAvailableStickers.random()
        SecureLog.d("StickerManager", "Random pick from ${allAvailableStickers.size} stickers: '${picked.name}'")
        return picked
    }

    /**
     * 三级解析导入图片的扩展名（Bug2 修复 + P2 内容优先）：
     *  ① DISPLAY_NAME 后缀（jpeg/jfif/jpe 归一为 jpg）
     *  ② MIME 映射（含 image/xxx 已知子类型）
     *  ③ 魔数嗅探兜底 [sniffedExtension]（来自文件头，与 provider 无关）
     *
     * 内容优先于文件名：三级信号**只接受白名单扩展名**（png/jpg/gif/webp）；
     * 后缀 / MIME 若不是白名单（如误命名的 .bmp / .heic），不短路、继续让后续信号说话。
     * 返回 null 表示白名单内均无法识别，由调用方 [importStickerFile] 依 [IMAGE_EXTENSIONS] 拒绝。
     */
    private fun resolveImageExtension(
        displayNameExtension: String?,
        mimeExtension: String?,
        sniffedExtension: String?
    ): String? {
        return selectStickerImageExtension(displayNameExtension, mimeExtension, sniffedExtension)
    }

    /** ① DISPLAY_NAME 后缀解析：未知后缀返回 null（交棒给 MIME / 魔数）。 */
    private fun resolveExtensionFromDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val candidate = cursor.getString(idx)?.substringAfterLast('.', "")
                        normalizeStickerImageExtension(candidate)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "query display name failed: ${e.message}")
            null
        }
    }

    /** ② MIME 映射：provider 返回的 content type → 扩展名。 */
    private fun resolveExtensionFromMime(uri: Uri): String? {
        val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        return mimeToExtension(mime)
    }

    /** MIME → 扩展名（含 image/xxx 已知子类型；未知返回 null）。 */
    private fun mimeToExtension(mime: String?): String? {
        return when (mime?.lowercase()?.substringBefore(';')?.trim()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> "heic"
            else -> null
        }
    }

    /** 读取文件头（最多 [maxBytes] 字节），不足则返回实际长度；读取异常不抛出，返回已读部分。 */
    private fun readHeader(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var offset = 0
        while (offset < maxBytes) {
            val read = try {
                input.read(buffer, offset, maxBytes - offset)
            } catch (e: Exception) {
                -1
            }
            if (read <= 0) break
            offset += read
        }
        return if (offset == maxBytes) buffer else buffer.copyOf(offset)
    }

    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast(".", "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    companion object {
        /** 自定义表情落盘软上限（主理人拍板：200 条） */
        const val MAX_IMPORTED_COUNT = 200

        /** 导入白名单（其余格式如 heic/bmp 仍按不支持处理，但拒绝提示明确）；与 [STICKER_IMAGE_WHITELIST] 同源 */
        private val IMAGE_EXTENSIONS = STICKER_IMAGE_WHITELIST

        /** 嗅探头部读取上限（mark/reset 的 readlimit，需 ≥ 魔数所需字节数） */
        private const val SNIFF_MARK_LIMIT = 64

        @Volatile
        private var instance: StickerManager? = null

        fun getInstance(context: Context): StickerManager {
            return instance ?: synchronized(this) {
                instance ?: StickerManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

data class StickerInfo(
    val name: String,
    val path: String,
    val category: String = "default",
    val isBuiltIn: Boolean = true,
    val description: String? = null,
    val fileName: String? = null,
    /**
     * 偏好引擎用的语义标签（情绪 / 场景），由描述或规则派生。
     * 内置表情包通常为 null（回落到名称/描述关键词匹配）。
     */
    val tags: List<String>? = null
)

// ================== 导入格式判定（纯函数，内容优先于文件名，便于 JVM 单测） ==================

/** 导入白名单（大小写已归一；jpeg 会先归并为 jpg）。 */
internal val STICKER_IMAGE_WHITELIST = setOf("png", "jpg", "jpeg", "gif", "webp")

/** 已知图片后缀（含 bmp/heic/heif，仅用于「检测到」提示；非白名单者不参与接受）。 */
private val STICKER_KNOWN_IMAGE_EXTENSIONS = setOf("png", "jpg", "gif", "webp", "bmp", "heic", "heif")

/** 后缀别名 → 规范后缀。 */
private val STICKER_EXTENSION_ALIASES = mapOf("jpeg" to "jpg", "jfif" to "jpg", "jpe" to "jpg")

/** 后缀归一：统一小写、别名映射（jpeg/jfif/jpe → jpg），仅保留已知图片后缀，其余返回 null。 */
internal fun normalizeStickerImageExtension(raw: String?): String? {
    val lower = raw?.trim()?.lowercase()?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: return null
    val normalized = STICKER_EXTENSION_ALIASES[lower] ?: lower
    return normalized.takeIf { it in STICKER_KNOWN_IMAGE_EXTENSIONS }
}

/**
 * 三级内容优先选择：DISPLAY_NAME 后缀 → MIME → 魔数嗅探，**只接受白名单扩展名**。
 *
 * 任一级若不是白名单（如误命名的 `.bmp` / `.heic`），**不短路**，继续让后续信号（最终是魔数嗅探）说话。
 * 例：真实 JPEG 但名为 `x.bmp` → 后缀 bmp（跳过）→ 嗅探 jpg → 返回 `jpg`。
 * 三者都不是白名单则返回 null（交由调用方按「无法识别」拒绝）。
 */
internal fun selectStickerImageExtension(
    displayNameExtension: String?,
    mimeExtension: String?,
    sniffedExtension: String?
): String? = listOf(displayNameExtension, mimeExtension, sniffedExtension)
    .firstOrNull { it != null && it in STICKER_IMAGE_WHITELIST }
