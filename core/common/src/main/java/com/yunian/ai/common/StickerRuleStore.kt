package com.yunian.ai.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 表情包规则（JSON v2）持久化存储。
 *
 * 纯 Kotlin 实现：不依赖 android.content.Context / android.util.Log，
 * 文件对象由外部注入，便于 JVM 单测。
 *
 * JSON v2 结构（顶层保持 JSONArray，向后兼容 v1 的 {description, fileName}）：
 * 每项 { description, fileName, semantic, aliases, createdAt, source }
 * 读取端全部带默认值 → 旧 JSON 加载行为完全不变。
 */
class StickerRuleStore(private val rulesFile: File) {

    /** 单条表情规则（JSON v2） */
    @Serializable
    data class Entry(
        @SerialName("description") val description: String = "",
        @SerialName("fileName") val fileName: String = "",
        @SerialName("semantic") val semantic: String = "",
        @SerialName("aliases") val aliases: List<String> = emptyList(),
        @SerialName("createdAt") val createdAt: Long = 0L,
        @SerialName("source") val source: String = SOURCE_FILE,
    )

    /** ZIP 合并结果：merged 为合并后的完整列表，added/skipped 用于统计与日志 */
    data class MergeResult(val merged: List<Entry>, val added: Int, val skipped: Int)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读取全部规则；文件不存在 / 内容为空 / 解析失败均返回空列表（不抛异常） */
    fun load(): List<Entry> {
        return try {
            if (!rulesFile.exists()) return emptyList()
            val text = rulesFile.readText()
            if (text.isBlank()) return emptyList()
            val loaded = json.decodeFromString(ListSerializer(Entry.serializer()), text)
                .filter { it.description.isNotBlank() && it.fileName.isNotBlank() }
                // 修 FIX-6：先按**展示侧同口径**归一 description（清洗 + 遗留保留名改名），**再**消歧。
                // 顺序必须是「先清洗、后消歧」——清洗会把不同名洗成同名（"开心[笑]" 与 "开心笑"），
                // 只有紧随其后的消歧才能兜住，保证每个文件仍可达。
                .map { it.copy(description = normalizeLoadedDescription(it.description)) }
                .filter { it.description.isNotBlank() }
            // 重名 description 消歧（修 FIX-5）：旧数据 / 手工 JSON 可能带重名，直接建 Map 会互相覆盖，
            // 使被覆盖的文件对 AI 永久不可达（面板可见、手动可发）。此处确定性追加序号保证每个文件可达。
            dedupeDescriptions(loaded)
        } catch (e: Exception) {
            SecureLog.w("StickerRuleStore", "load rules failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 载入期 description 归一（修 FIX-6）：
     *
     *  1. **与展示侧 [stripPromptUnsafeChars] 同口径清洗**（剥方括号/换行、压缩空白），且**不截断**——
     *     与 [StickerPromptNames.displayName] 逐字符一致，避免破坏「不按长度丢弃 / 不截断用户数据」的既有不变量。
     *     旧版未清洗 ZIP 落盘的 `开心[笑]`，展示侧会剥成 `开心笑`；若磁盘仍存 `开心[笑]`，
     *     则「提示词名字=开心笑、反查键=开心[笑]」不一致 → AI 可见却发不出（用户原报障复发）。
     *  2. **遗留保留名**（磁盘上已存在的 `红包` 等）按 [StickerReservedNames.resolveForImport] 自动改名，
     *     与 ZIP 导入路径 [mergeZipRules] 及发送侧无条件跳过保留标签的语义一致，
     *     杜绝「AI 可见却永远发不出」。（改名后再清洗一次，纯幂等兜底。）
     */
    private fun normalizeLoadedDescription(raw: String): String {
        val cleaned = stripPromptUnsafeChars(raw)
        if (StickerReservedNames.isReserved(cleaned)) {
            return stripPromptUnsafeChars(StickerReservedNames.resolveForImport(cleaned))
        }
        return cleaned
    }

    /**
     * 原子写：先写 .tmp 再 renameTo，避免写一半损坏（修 P2 的写入端）。
     * Windows 上目标文件已存在时 renameTo 会失败，故先删旧文件再 rename，
     * rename 仍失败时退化为直接写（尽力而为）。
     */
    fun save(entries: List<Entry>): Boolean {
        val tmp = File(rulesFile.parentFile, rulesFile.name + ".tmp")
        return try {
            tmp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
            if (rulesFile.exists() && !rulesFile.delete()) {
                SecureLog.w("StickerRuleStore", "delete old rules file failed: ${rulesFile.name}")
            }
            if (!tmp.renameTo(rulesFile)) {
                // rename 失败兜底：直接落盘正式文件
                rulesFile.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            SecureLog.w("StickerRuleStore", "save rules failed: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * ZIP 合并（修 P2 覆盖丢失）：以 description 为 key 做 merge 而非整体覆盖。
     * 同名 → 保留旧文件与旧规则，跳过并计数；新名称 → 追加。
     *
     * 入口统一清洗（修 FIX-2 / FIX-3）：ZIP 里的 description / semantic / aliases 不经过
     * 单文件导入的 sanitize 路径，必须在此补齐——
     *  - description 剥方括号/换行、压缩空白、≤20 字（否则名字里的 `[]` 会破坏 `[名字]` 解析）；
     *  - 系统保留名（语音/图片/…/红包/转账）自动改名，避免「AI 可见却永远发不出」；
     *  - semantic / aliases 同样剥方括号与换行（否则会破坏 E2「[name]=semantic（别名：…）」整行）。
     */
    fun mergeZipRules(incoming: List<Entry>, existing: List<Entry>): MergeResult {
        val byDescription = existing.associateBy { it.description }.toMutableMap()
        var added = 0
        var skipped = 0
        for (rawEntry in incoming) {
            if (rawEntry.description.isBlank() || rawEntry.fileName.isBlank()) continue
            var description = sanitizeStickerNameText(rawEntry.description)
            if (description.isBlank()) continue
            // 系统保留名：自动改名（保留名会被发送侧无条件跳过 → 表情永远发不出）
            if (StickerReservedNames.isReserved(description)) {
                description = sanitizeStickerNameText(StickerReservedNames.resolveForImport(description))
            }
            if (byDescription.containsKey(description)) {
                skipped++
            } else {
                // ZIP 来源条目补默认值：无时间戳的取导入时刻，保证新→旧排序稳定
                val normalized = rawEntry.copy(
                    description = description,
                    semantic = sanitizeStickerSemanticText(rawEntry.semantic),
                    aliases = sanitizeStickerAliasList(rawEntry.aliases),
                    createdAt = if (rawEntry.createdAt <= 0L) System.currentTimeMillis() else rawEntry.createdAt,
                    source = SOURCE_ZIP,
                )
                byDescription[description] = normalized
                added++
            }
        }
        return MergeResult(byDescription.values.toList(), added, skipped)
    }

    /**
     * 重名 description 消歧（修 FIX-5）：对**第 2 个及以后**的同名条目确定性追加 `(2)`/`(3)`… 后缀，
     * 保证「每个文件的展示名唯一、可被提示词宣传并被发送侧反查到自身」。先出现者保留原名。
     * 幂等：已唯一时原样返回。
     */
    fun dedupeDescriptions(entries: List<Entry>): List<Entry> {
        val used = HashSet<String>()
        return entries.map { entry ->
            val base = entry.description
            if (base.isBlank() || used.add(base)) return@map entry
            var n = 2
            var candidate = "$base($n)"
            while (!used.add(candidate)) {
                n++
                candidate = "$base($n)"
            }
            entry.copy(description = candidate)
        }
    }

    /**
     * 文件名索引：fileName → Entry（按 fileName 去重，**先出现者优先**）。
     *
     * 与「按 description 建 Map」不同：即使两条 Entry 描述重名（历史数据 / 手工导入），
     * 每个文件名仍能各自定位到**自己的**条目，杜绝「重名折叠」导致某个文件查不到规则、
     * 进而退化出内部文件名对 AI 隐形（修 P-bug：重名折叠）。
     */
    fun buildFileNameIndex(entries: List<Entry>): Map<String, Entry> {
        val index = LinkedHashMap<String, Entry>()
        for (entry in entries) {
            if (entry.fileName.isBlank()) continue
            index.putIfAbsent(entry.fileName, entry)
        }
        return index
    }

    /** 重名的 description 列表（去重，首次出现顺序）；长度 >1 的即被折叠的键，供日志留痕。 */
    fun duplicateDescriptions(entries: List<Entry>): List<String> {
        val seen = LinkedHashSet<String>()
        val duplicated = LinkedHashSet<String>()
        for (entry in entries) {
            val desc = entry.description
            if (desc.isBlank()) continue
            if (!seen.add(desc)) duplicated.add(desc)
        }
        return duplicated.toList()
    }

    /** 别名索引：alias → description（loadRules/import/rename/delete 时重建或增量维护） */
    fun buildAliasIndex(entries: List<Entry>): Map<String, String> {
        val index = mutableMapOf<String, String>()
        for (entry in entries) {
            for (alias in entry.aliases) {
                val trimmed = alias.trim()
                if (trimmed.isNotEmpty()) {
                    index.putIfAbsent(trimmed, entry.description)
                }
            }
        }
        return index
    }

    companion object {
        const val SOURCE_FILE = "file"
        const val SOURCE_ZIP = "zip"
        const val RULES_FILE_NAME = "custom_stickers.json"
    }
}

/**
 * 提示词层使用的结构化表情清单（core:network 与 feature:chat 共用）。
 */
data class PromptSticker(
    val name: String,
    val semantic: String = "",
    val aliases: List<String> = emptyList(),
    val isCustom: Boolean = false,
)

/**
 * 自定义表情 E2 提示词段的统一拼装入口（云端 / 本地模型路径共用）。
 * 预算：≤30 条；每条语义 ≤40 字；整段 ≤1200 字符；超限按 createdAt 新→旧截断
 * （调用方传入的列表需已按 createdAt 新→旧排序）。
 */
object CustomStickerPrompt {

    const val MAX_COUNT = 30
    const val MAX_CHARS = 1200
    const val MAX_SEMANTIC_LENGTH = 40

    /**
     * 生成 E2 段内容行：「[名称]=语义（别名：a、b）」。
     * 空列表返回空列表（调用方整段不拼，保证提示词逐字节零变化）。
     */
    fun buildLines(stickers: List<PromptSticker>): List<String> {
        if (stickers.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var budget = MAX_CHARS
        for (sticker in stickers.take(MAX_COUNT)) {
            if (budget <= 0) break
            // 防御性清洗（修 FIX-3）：名字 / 语义 / 别名里的方括号或换行会破坏本行的 `[name]=…` 结构，
            // 从而让该表情在提示词里不可解析；此处与数据层同源清洗，双保险。
            val name = stripPromptUnsafeChars(sticker.name)
            if (name.isBlank()) continue
            val semantic = sanitizeStickerSemanticText(sticker.semantic).take(MAX_SEMANTIC_LENGTH)
            val semanticPart = semantic.ifBlank { "（未提供语义，按名称理解）" }
            val cleanAliases = sanitizeStickerAliasList(sticker.aliases)
            val aliasPart = if (cleanAliases.isNotEmpty()) {
                "（别名：${cleanAliases.joinToString("、")}）"
            } else ""
            val line = "[$name]=$semanticPart$aliasPart"
            if (line.length > budget) break
            lines.add(line)
            budget -= line.length
        }
        return lines
    }
}
