package com.yunian.ai.common

/**
 * 提示词层「可用表情名」装配的**纯函数**收口（不依赖 android.*，JVM 可测）。
 *
 * 背景（本次 bug：用户导入「仔细思考」，AI 却「翻遍了也没找着」）：
 * 提示词里给 AI 的表情名，必须能反查到文件（发送侧 `findStickerByDescription*` 命中），
 * 即**双向一致**。旧实现把「展示名」的推导与「长度 ≤20 静默丢弃」逻辑散落在 `AiService`
 * 三处调用点上，且：
 *  1. **长度 > 20 的名字被 `null` 静默丢弃**（无日志）——当某条目的规则丢失、
 *     展示名退化成内部文件名 `custom_<ts>_<rand>.png`（去扩展名后约 22~24 字符）时，该表情会对 AI
 *     **彻底隐形**（但面板仍可见、用户手动仍可发）。
 *  2. 规则 E 用 `availableStickers.take(50)`，且 `getAllStickers()` 内置在前、导入在后，
 *     自定义表情可能被 `take(50)` 挤掉。
 *
 * 本对象固化两条不变量：
 *  - **不按长度丢弃**：只有「展示名为空」才跳过（调用方负责打日志留痕，不再静默）；
 *  - **自定义优先占位**：预算截断只作用于**内置**表情，用户自定义表情永不被 `take(N)` 挤掉。
 */
object StickerPromptNames {

    /** 规则 E 名单软上限（沿用旧值 50；仅对内置表情生效，自定义表情不受此限） */
    const val MAX_PROMPT_NAMES = 50

    /**
     * 计算单条表情对 AI 的展示名：
     * 优先 `description`（用户命名 / 语义名），其次 `name`（去 `sticker_` 前缀与 `.png` 后缀）。
     * 两者都为空 → 返回 null（调用方跳过并留痕）。
     *
     * 展示名统一经 [stripPromptUnsafeChars] 剥方括号/换行（修 FIX-3 防御层）：
     * 名字里的 `[` / `]` 会破坏发送侧 `STICKER_REGEX = \[([^\[\]]+?)\]` 的 `[名字]` 解析，
     * 导致「AI 看到名字却发不出」；此处只剥非法字符、**不做长度截断**（不变量：不按长度丢弃）。
     */
    internal fun displayName(sticker: StickerInfo): String? {
        val desc = sticker.description?.let { stripPromptUnsafeChars(it) }
        if (!desc.isNullOrBlank()) return desc
        val raw = stripPromptUnsafeChars(
            sticker.name
                .removePrefix("sticker_")
                .removeSuffix(".png")
        )
        return raw.ifBlank { null }
    }

    /**
     * 组装规则 E 的名单。
     *
     * @param stickers [StickerManager.getAllStickers] 的结果（内置 + 导入）
     * @param maxNames 软上限；**只截断内置**部分，自定义表情全量保留
     * @return 去重后的展示名列表：**自定义在前、内置在后**
     */
    fun build(stickers: List<StickerInfo>, maxNames: Int = MAX_PROMPT_NAMES): List<String> {
        val custom = LinkedHashSet<String>()
        val builtin = LinkedHashSet<String>()
        for (sticker in stickers) {
            val name = displayName(sticker) ?: continue
            if (sticker.isBuiltIn) builtin.add(name) else custom.add(name)
        }
        // 自定义优先且全量保留；内置按余量补足，且与自定义跨桶去重（同名时以自定义为准）。
        val result = LinkedHashSet<String>(custom)
        for (name in builtin) {
            if (result.size >= maxNames) break
            result.add(name)
        }
        return result.toList()
    }

    /** 展示名为空、将被跳过的条目数（供调用方日志留痕，避免「静默丢弃」复发）。 */
    fun blankNameCount(stickers: List<StickerInfo>): Int =
        stickers.count { displayName(it) == null }
}
