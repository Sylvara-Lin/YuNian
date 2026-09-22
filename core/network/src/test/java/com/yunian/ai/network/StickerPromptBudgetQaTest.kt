package com.yunian.ai.network

import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.PromptSticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 回归（core:network 层）：规则 E「可用表情名」清单的预算截断。
 *
 * 用**真实函数** [AiPromptBuilder.buildPersonaRules] 生成规则 E，检查其实际展示的名字集合。
 *
 * FIX-4 已删除 `AiPromptBuilder` 里那道二次 `availableStickers.take(50)`（原 AiPromptBuilder.kt:499），
 * 名单口径的**唯一截断点**收敛到 `core:common` 的
 * [com.yunian.ai.common.StickerPromptNames.build]（自定义全量保留、只截内置）。
 * 因此本层不再做任何 N 截断 —— 传入多少名字，规则 E 就展示多少。
 */
class StickerPromptBudgetQaTest {

    /** 从规则 E 段抽出 `[名字]` 列表（只取「可以用：…。发送格式为」之间，避免误抓其它段落）。 */
    private fun ruleENames(ruleText: String): List<String> {
        val seg = ruleText.substringAfter("可以用：", "").substringBefore("。发送格式为", "")
        return Regex("\\[([^\\[\\]]+?)\\]").findAll(seg).map { it.groupValues[1] }.toList()
    }

    private fun rule(persona: String, names: List<String>, custom: List<PromptSticker> = emptyList()): String =
        AiPromptBuilder.buildPersonaRules(
            persona = persona,
            availableStickers = names,
            stickerProbability = 50,
            innerThoughtEnabled = false,
            role = CompanionRole.GIRLFRIEND,
            customStickers = custom,
        )

    /** 【回归护栏】长度 > 20 的内部文件名必须真的进入提示词（不再被静默丢弃）。 */
    @Test
    fun ruleE_keepsLongInternalName_endToEnd() {
        val names = listOf("custom_1700000000000_123") // 24 字符（> 20）
        val listed = ruleENames(rule("温柔", names))
        assertEquals(names, listed)
    }

    /** 【回归护栏】空名不进入列表。 */
    @Test
    fun ruleE_emptyStickerList_omitsListSection() {
        val text = rule("温柔", emptyList())
        assertTrue(text.contains("当前没有可用表情包"))
    }

    /**
     * 【FIX-4 回归】规则 E 不再二次截断：传入 60 个名字 → 全部展示。
     * （截断口径已上移到 StickerPromptNames.build，本层零截断。）
     */
    @Test
    fun ruleE_noSecondTruncation_allNamesPassThrough() {
        val custom = (1..60).map { "自定义$it" }
        val listed = ruleENames(rule("温柔", custom))
        assertEquals("规则 E 应原样展示全部 60 个名字（本层不得再截断）", custom.toSet(), listed.toSet())
    }

    /**
     * 【对照】把 StickerPromptNames.build 的真实输出（自定义在前 + 内置补足到 50）喂进来，
     * 规则 E 应逐字展示该输出，不做任何额外裁剪。
     */
    @Test
    fun ruleE_reflectsUpstreamListExactly() {
        val upstream = (1..5).map { "自定义$it" } + (1..45).map { "内置$it" } // build() 的结果形状
        val listed = ruleENames(rule("温柔", upstream))
        assertEquals(50, listed.size)
        assertTrue(listed.containsAll((1..5).map { "自定义$it" }))
        assertEquals(upstream, listed)
    }
}
