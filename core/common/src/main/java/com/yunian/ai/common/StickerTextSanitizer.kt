package com.yunian.ai.common

/**
 * 表情名称 / 语义 / 别名的统一清洗函数（纯函数，JVM 可测）。
 *
 * 单一来源：单文件导入、重命名、ZIP 合并、提示词装配都从这里取口径，
 * 避免"某一入口漏清洗"导致名字里的 `[` / `]` 破坏提示词 `[名字]` 解析（见 FIX-3）。
 */

/** 名称 / 别名中必须剔除的字符：方括号会破坏 `[名字]` 解析，换行/制表符会破坏逐行拼装。 */
private val STICKER_ILLEGAL_CHARS = Regex("[\\[\\]\\n\\r\\t]")

/** 连续空白（用于压缩为单个空格）。 */
private val STICKER_WHITESPACE_RUN = Regex("\\s+")

/** 名称清洗：剥方括号/控制字符 → 压缩空白 → 去首尾 → ≤20 字。 */
internal fun sanitizeStickerNameText(raw: String): String =
    raw.replace(STICKER_ILLEGAL_CHARS, "")
        .replace(STICKER_WHITESPACE_RUN, " ")
        .trim()
        .take(20)

/** 语义清洗：剥方括号/换行 → 压缩空白 → 去首尾 → ≤40 字。 */
internal fun sanitizeStickerSemanticText(raw: String): String =
    raw.replace(Regex("[\\[\\]\\n\\r]"), " ")
        .replace(STICKER_WHITESPACE_RUN, " ")
        .trim()
        .take(40)

/** 别名清洗：剥方括号/控制字符 → 去空 → 去重 → ≤5 个、每个 ≤12 字。 */
internal fun sanitizeStickerAliasList(raw: List<String>): List<String> =
    raw.map { it.replace(STICKER_ILLEGAL_CHARS, "").trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(5)
        .map { it.take(12) }

/**
 * 提示词展示用的轻量清洗：只剥方括号与控制字符、压缩空白，**不做长度截断**
 * （限长由各预算层自行负责，避免破坏"规则丢失时保留完整内部文件名"的既有不变量）。
 */
internal fun stripPromptUnsafeChars(raw: String): String =
    raw.replace(STICKER_ILLEGAL_CHARS, "")
        .replace(STICKER_WHITESPACE_RUN, " ")
        .trim()

/**
 * 名称合法性校验（纯函数，单文件导入 / 重命名共用）：
 *  - 空 → "表情名称不能为空"；
 *  - 系统保留名（[StickerReservedNames.SYSTEM_TAGS]）→ 明确中文提示；
 *  - 合法 → `null`。
 *
 * 传入的 [name] 应已 [sanitizeStickerNameText] 清洗。保留名必须拒绝，否则会「AI 可见却永远发不出」。
 */
internal fun stickerNameValidationError(name: String): String? = when {
    name.isBlank() -> "表情名称不能为空"
    StickerReservedNames.isReserved(name) -> "「$name」是系统保留名称，请换一个名字"
    else -> null
}
