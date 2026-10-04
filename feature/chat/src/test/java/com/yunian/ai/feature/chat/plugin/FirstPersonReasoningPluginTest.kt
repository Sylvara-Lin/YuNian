package com.yunian.ai.feature.chat.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FirstPersonReasoningPlugin] / [FirstPersonReasoningRules] 的单元测试。
 *
 * 锁三件事：
 * 1. **开关语义**——插件 setup → enabled=true，effect（卸载）→ enabled=false；
 * 2. **规则文本**——爱语对齐的【思维链提示】区块，单行、无换行、关闭时空串；
 * 3. **底部注入**——规则塞到 history_json 最后一条 user 消息前，带 _agent_preserve_system 标记。
 */
class FirstPersonReasoningPluginTest {

    @After
    fun tearDown() {
        FirstPersonReasoningRules.enabled = false
    }

    // ── 1. 开关语义 ─────────────────────────────────────────────

    @Test
    fun `默认未开启`() {
        assertFalse(FirstPersonReasoningRules.enabled)
        assertEquals("", FirstPersonReasoningRules.systemRules())
    }

    @Test
    fun `开启时给出规则文本，关闭时为空串`() {
        FirstPersonReasoningRules.enabled = true
        assertTrue(FirstPersonReasoningRules.systemRules().isNotBlank())
        FirstPersonReasoningRules.enabled = false
        assertEquals("", FirstPersonReasoningRules.systemRules())
    }

    // ── 2. 规则文本 ─────────────────────────────────────────────

    @Test
    fun `规则文本是爱语对齐的思维链提示区块`() {
        val rules = FirstPersonReasoningRules.RULE_TEXT
        assertTrue("必须带【思维链提示】标题头", rules.startsWith("【思维链提示】"))
        assertTrue("必须要求第一人称", rules.contains("第一人称"))
        assertTrue("必须禁止首字输出用户", rules.contains("禁止首字输出“用户”"))
        assertTrue("必须用称呼替代用户", rules.contains("替代“用户”"))
        assertTrue("必须意识流、严禁分点", rules.contains("意识流") && rules.contains("严禁分点"))
    }

    @Test
    fun `规则文本单行无换行`() {
        val rules = FirstPersonReasoningRules.RULE_TEXT
        assertEquals("规则不能带换行", -1, rules.indexOf('\n'))
        assertEquals("规则不能带首尾空白", rules.trim(), rules)
    }

    // ── 3. 底部注入接线（源码级断言：org.json 在 JVM 单测是 stub，无法驱动实例）──

    private fun sourceFile(relPath: String): String =
        java.io.File(System.getProperty("user.dir")).let { root ->
            // Gradle 单测工作目录是模块目录；这里向上两级到仓库根。
            sequenceOf(root, root.parentFile, root.parentFile?.parentFile)
                .filterNotNull()
                .map { java.io.File(it, relPath) }
                .firstOrNull { it.exists() }
                ?.readText()
                ?: error("找不到源文件: " + relPath)
        }

    @Test
    fun `ChatGenerationManager 必须经保留标记底部注入第一人称规则`() {
        val source = sourceFile(
            "feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt",
        )
        assertTrue(
            "必须读取本插件规则",
            source.contains("FirstPersonReasoningRules.systemRules()"),
        )
        assertTrue(
            "必须打保留标记（否则 Rust 会把它当角色 system 清掉）",
            source.contains("\"_agent_preserve_system\""),
        )
        assertTrue(
            "标记值必须是布尔 true（字符串会被 Rust 当无标记条目清掉）",
            source.contains("put(\"_agent_preserve_system\", true)"),
        )
        assertTrue(
            "必须塞到最后一条 user 消息前（底部注入权重最高）",
            source.contains("lastUserIdx"),
        )
    }
}
