package com.yunian.ai.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThanksData 数据完整性单测（纯数据，无 Android 逻辑）。
 * 守护团队手工维护的赞助者列表：防止空名/重名/非法 rank 混入。
 */
class ThanksDataTest {

    @Test
    fun `sponsors list is non-empty`() {
        assertTrue("sponsor list must not be empty", sponsors.isNotEmpty())
    }

    @Test
    fun `sponsor names are non-blank`() {
        sponsors.forEach { sponsor ->
            assertTrue(
                "sponsor name must not be blank: $sponsor",
                sponsor.name.isNotBlank()
            )
        }
    }

    @Test
    fun `sponsor names are unique`() {
        // 真实数据中存在多个匿名赞助者统一记作 "." 的占位条目（允许重复），
        // 因此唯一性约束只作用于长度 > 1 的真实显示名。
        val duplicated = sponsors
            .map { it.name.trim() }
            .filter { it.length > 1 }
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
        assertEquals("duplicated sponsor names: $duplicated", emptySet<String>(), duplicated)
    }

    @Test
    fun `ranks are non-negative`() {
        sponsors.forEach { sponsor ->
            assertTrue(
                "rank must be non-negative: ${sponsor.name}=${sponsor.rank}",
                sponsor.rank >= 0
            )
        }
    }
}
