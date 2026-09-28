package com.yunian.ai.feature.settings.worldbook

import com.yunian.ai.agent.worldbook.WorldbookJsonCodec
import com.yunian.ai.database.model.EntryRole
import com.yunian.ai.database.model.InjectionPosition
import com.yunian.ai.database.model.LorebookEntryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书导入/导出的 JSON 传输格式
 * 采用 ExportSerializer 模式：格式标识 + 版本号 + 数据体
 *
 * 该结构仍是**导入侧的生态兼容格式**（阶段 5g §5.10）；导出默认改走
 * ST World Info JSON（[WorldbookTransfer.serializeSt]），导入则由
 * [WorldbookTransfer.parseAny] 自动识别两种格式。
 */
@Serializable
data class WorldbookEntryDto(
    val keywords: List<String> = emptyList(),
    val content: String = "",
    val injectionPosition: String = "AFTER_SYSTEM_PROMPT",
    val role: String = "SYSTEM",
    val priority: Int = 0,
    val injectDepth: Int? = null,
    val scanDepth: Int = 10,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val constantActive: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class WorldbookExportDto(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String = "",
    val description: String = "",
    val entries: List<WorldbookEntryDto> = emptyList()
) {
    companion object {
        const val FORMAT = "lianyu-worldbook"
    }
}

object WorldbookTransfer {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(dto: WorldbookExportDto): String = json.encodeToString(WorldbookExportDto.serializer(), dto)

    /** 解析导入文件内容；校验格式标识（容许缺省以兼容手工编辑的文件） */
    fun parse(text: String): Result<WorldbookExportDto> = runCatching {
        val dto = json.decodeFromString(WorldbookExportDto.serializer(), text)
        require(dto.name.isNotBlank()) { "世界书名称为空" }
        require(dto.entries.isNotEmpty()) { "文件中没有条目" }
        dto
    }

    // ────────────────────── 阶段 5g §5.10：ST World Info JSON ──────────────────────

    /**
     * 导出为 **ST World Info JSON**（与 `worldbooks.json` 列、Rust 引擎输入同格式）。
     *
     * 采用 map 格式 `entries`（见 [WorldbookJsonCodec] 的类型注释），因此导出的文件
     * 既能在本应用内无损回导，也能直接投喂给 SillyTavern / Rust 侧。
     *
     * @param dto 传出结构（UI 组装）；`injectionPosition` / `role` 为枚举名字符串
     */
    fun serializeSt(dto: WorldbookExportDto): String {
        val entities = dto.entries.mapIndexed { idx, e -> e.toEntity(index = idx) }
        val objects = entities.mapIndexed { idx, e ->
            WorldbookJsonCodec.entryToJson(
                entry = e,
                // 单文件导出：`insertion_order` 取 1..N，保证 Rust 侧排序确定
                insertionOrder = (idx + 1).toLong(),
            )
        }
        return WorldbookJsonCodec.assemble(
            name = dto.name,
            description = dto.description.takeIf { it.isNotBlank() },
            // 顶层 scan_depth 取条目众数，与 `bookToJson` 一致（而非硬编码默认值）
            scanDepth = WorldbookJsonCodec.dominantScanDepth(entities),
            tokenBudget = WorldbookJsonCodec.DEFAULT_TOKEN_BUDGET,
            entryObjects = objects,
        )
    }

    /**
     * 自动识别格式并解析。
     *
     * - `entries` 为 **对象(map)** → ST World Info JSON
     * - `entries` 为 **数组** 且首元素含 `keys`/`insertion_order` → ST 数组变体
     * - 其余 → 旧 `lianyu-worldbook` DTO（保留兼容）
     */
    fun parseAny(text: String): Result<WorldbookExportDto> = runCatching {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null && looksLikeSt(root)) parseSt(root) else parse(text).getOrThrow()
    }

    private fun looksLikeSt(root: JSONObject): Boolean {
        // 旧 DTO 带显式 format 标识，优先让路
        if (root.optString("format", "") == WorldbookExportDto.FORMAT) return false
        return when (val c = root.opt("entries")) {
            is JSONObject -> true
            is JSONArray -> c.optJSONObject(0)?.let { first ->
                first.has("keys") || first.has("insertion_order")
            } ?: false
            else -> root.has("scan_depth") || root.has("token_budget")
        }
    }

    private fun parseSt(root: JSONObject): WorldbookExportDto {
        val decoded = WorldbookJsonCodec.entries(root.toString())
        require(decoded.isNotEmpty()) { "文件中没有条目" }
        return WorldbookExportDto(
            name = root.optString("name", "").ifBlank { "导入的世界书" },
            description = root.optString("description", ""),
            entries = decoded.map { s ->
                WorldbookEntryDto(
                    keywords = s.keywords,
                    content = s.content,
                    injectionPosition = s.position.name,
                    role = s.role.name,
                    priority = s.priority,
                    injectDepth = if (s.position == InjectionPosition.AT_DEPTH) s.depth.toInt() else null,
                    scanDepth = s.scanDepth.toInt(),
                    caseSensitive = s.caseSensitive,
                    useRegex = s.useRegex,
                    constantActive = s.constant,
                    enabled = s.enabled,
                    sortOrder = s.sortOrder,
                )
            },
        )
    }

    /** DTO → 待编码条目（id/时间戳此时无意义，仅用于 `entryToJson` 的字段映射）。 */
    private fun WorldbookEntryDto.toEntity(index: Int): LorebookEntryEntity = LorebookEntryEntity(
        id = (index + 1).toLong(),
        lorebookId = 0L,
        keywordsJson = WorldbookJsonCodec.encodeKeywords(keywords),
        content = content,
        injectionPosition = runCatching { InjectionPosition.valueOf(injectionPosition) }
            .getOrDefault(InjectionPosition.AFTER_SYSTEM_PROMPT),
        priority = priority,
        injectDepth = injectDepth,
        role = runCatching { EntryRole.valueOf(role) }.getOrDefault(EntryRole.SYSTEM),
        caseSensitive = if (caseSensitive) 1 else 0,
        useRegex = if (useRegex) 1 else 0,
        sortOrder = sortOrder,
        scanDepth = scanDepth.coerceAtLeast(1),
        constantActive = if (constantActive) 1 else 0,
        enabled = if (enabled) 1 else 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    fun defaultFileName(name: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        return "lianyu_worldbook_${safe}.json"
    }
}
