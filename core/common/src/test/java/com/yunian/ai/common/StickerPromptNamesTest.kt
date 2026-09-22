package com.yunian.ai.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StickerPromptNames 纯函数单测：锁定「提示词里给 AI 的表情名」装配口径。
 *
 * 核心回归（本次 bug：用户导入「仔细思考」AI 却找不到）：
 *  - 不按长度静默丢弃（含「规则丢失 → 退化成内部文件名」的 27 字符名）；
 *  - 自定义表情优先占位，预算截断只作用于内置，自定义永不被 take(N) 挤掉。
 */
class StickerPromptNamesTest {

    private fun imported(name: String, description: String? = null) = StickerInfo(
        name = name,
        path = "/data/imported/$name",
        category = "imported",
        isBuiltIn = false,
        description = description,
        fileName = name,
    )

    private fun builtin(name: String) = StickerInfo(
        name = name,
        path = "asset://stickers/$name.png",
        category = "default",
        isBuiltIn = true,
    )

    @Test
    fun displayName_prefersDescription() {
        assertEquals("仔细思考", StickerPromptNames.displayName(imported("custom_1_1.png", description = "仔细思考")))
    }

    @Test
    fun displayName_fallsBackToName_withoutStickerPrefixAndPngSuffix() {
        assertEquals("开心", StickerPromptNames.displayName(imported("sticker_开心.png", description = null)))
        assertEquals("mua", StickerPromptNames.displayName(imported("mua", description = null)))
    }

    @Test
    fun displayName_blank_returnsNull() {
        assertNull(StickerPromptNames.displayName(imported("", description = null)))
        assertNull(StickerPromptNames.displayName(imported("   ", description = "   ")))
    }

    /**
     * 回归：规则丢失时 `getAllStickers()` 会把展示名退化成内部文件名（≈27 字符）。
     * 旧实现按 `length <= 20` 静默丢弃 → 该表情对 AI 隐形。此处断言**必须保留**。
     */
    @Test
    fun build_longInternalFallbackName_isNotDropped() {
        val orphan = imported("custom_1726000000000_123") // 27 字符，无 description（无规则）
        val names = StickerPromptNames.build(listOf(orphan))
        assertEquals(listOf("custom_1726000000000_123"), names)
        assertFalse("长度 > 20 不得被丢弃", names.isEmpty())
    }

    /** 回归：即使 description 本身超长（如 ZIP 注入），也不得被长度过滤丢弃，否则反查两侧不一致。 */
    @Test
    fun build_longDescription_isKept() {
        val long = "这是一个非常非常非常长的自定义表情名称超过二十个字符"
        val names = StickerPromptNames.build(listOf(imported("custom_1_1.png", description = long)))
        assertEquals(listOf(long), names)
    }

    @Test
    fun build_blankNames_skippedAndCounted() {
        val list = listOf(
            imported("custom_1_1.png", description = "仔细思考"),
            imported("", description = null),
        )
        assertEquals(listOf("仔细思考"), StickerPromptNames.build(list))
        assertEquals(1, StickerPromptNames.blankNameCount(list))
    }

    @Test
    fun build_customFirst_builtinsCapped() {
        val customs = (1..3).map { imported("custom_$it.png", description = "自定义$it") }
        val builtins = (1..100).map { builtin("内置$it") }
        val names = StickerPromptNames.build(customs + builtins, maxNames = 50)

        assertEquals(50, names.size)
        // 自定义在前且全量保留
        assertEquals(listOf("自定义1", "自定义2", "自定义3"), names.take(3))
        assertEquals("内置47", names.last()) // 余量 50-3=47
    }

    /** 自定义数量超过预算时，内置被完全挤出，但自定义一个都不能少。 */
    @Test
    fun build_customExceedsBudget_allCustomsKept() {
        val customs = (1..60).map { imported("custom_$it.png", description = "自定义$it") }
        val builtins = (1..10).map { builtin("内置$it") }
        val names = StickerPromptNames.build(customs + builtins, maxNames = 50)

        assertEquals(60, names.size)
        assertTrue(names.containsAll(customs.map { it.description!! }))
        assertFalse("预算耗尽后不得再放内置", names.any { it.startsWith("内置") })
    }

    @Test
    fun build_deduplicatesNames() {
        val list = listOf(
            imported("a.png", description = "开心"),
            imported("b.png", description = "开心"),
            builtin("开心"),
        )
        assertEquals(listOf("开心"), StickerPromptNames.build(list))
    }
}
