package com.yunian.ai.network

import com.yunian.ai.common.CompanionRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FIX-4 回归：规则 E「可用表情名」清单**只有一处截断口径**（[com.yunian.ai.common.StickerPromptNames.build]）。
 *
 * 旧实现 [AiPromptBuilder.buildPersonaRules] 内还有第二道 `availableStickers.take(50)`，
 * 会让超过 50 个的自定义表情被二次截断丢弃（`MAX_IMPORTED_COUNT = 200`，可达）。
 * 本测试锁定：传进来的名单由上层（StickerPromptNames）决定，规则 E 不再私自截断。
 */
class StickerRuleECustomBudgetTest {

    /** 从规则 E 段抽出 `[名字]` 列表。 */
    private fun ruleENames(ruleText: String): List<String> {
        val seg = ruleText.substringAfter("可以用：", "").substringBefore("。发送格式为", "")
        return Regex("\\[([^\\[\\]]+?)\\]").findAll(seg).map { it.groupValues[1] }.toList()
    }

    private fun rule(names: List<String>): String = AiPromptBuilder.buildPersonaRules(
        persona = "温柔",
        availableStickers = names,
        stickerProbability = 50,
        innerThoughtEnabled = false,
        role = CompanionRole.GIRLFRIEND,
    )

    @Test
    fun ruleE_keepsAllCustomNamesBeyond50() {
        val custom = (1..60).map { "自定义$it" } // StickerPromptNames.build 会原样返回全部 60
        val listed = ruleENames(rule(custom))
        assertEquals("规则 E 不得二次截断自定义名单", custom.toSet(), listed.toSet())
        assertEquals(60, listed.size)
    }

    @Test
    fun ruleE_preservesOrderAndDedup() {
        // 上层已去重排序；规则 E 只负责按序展示
        val names = listOf("自定义A", "自定义B", "自定义C")
        assertEquals(names, ruleENames(rule(names)))
    }
}
