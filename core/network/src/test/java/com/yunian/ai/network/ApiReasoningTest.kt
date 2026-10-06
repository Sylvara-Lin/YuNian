package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型思考程度（reasoning effort）门控单测。
 *
 * 锁死三条契约（与 Rust `native_gateway.rs` 同一证据集）：
 * 1. **默认零行为变化**：off / 缺省 / 非法值一律不注入任何字段；
 * 2. **provider 白名单 + 模型名门控**：只有 OPENAI（推理模型名）/
 *    DEEPSEEK / OPENROUTER 注入 `reasoning_effort`，其余全枚举不注入；
 * 3. **wire 值逐字节**：off/low/medium/high snake_case 字符串。
 */
class ApiReasoningTest {

    // ---------------- 默认零行为变化 ----------------

    @Test
    fun `off 与缺省非法值一律不注入`() {
        for (provider in listOf(
            ApiProvider.OPENAI,
            ApiProvider.DEEPSEEK,
            ApiProvider.OPENROUTER,
        )) {
            for (effort in listOf("off", "", "ultra", "OFF", "medium ")) {
                // "medium " 带空格应归一为 medium（trim 后合法）——单独断言
                val expectedInject = effort.trim().equals("medium", ignoreCase = true)
                val body = JSONObject().put("model", "o3")
                val injected = body.applyReasoningEffort(provider, "o3", effort)
                assertEquals(
                    "provider=$provider effort='$effort' 注入判定与预期不符",
                    expectedInject,
                    injected,
                )
            }
        }
    }

    @Test
    fun `off 不写任何字段且返回 false`() {
        val body = JSONObject().put("model", "deepseek-v4-pro")
        val injected = body.applyReasoningEffort(ApiProvider.DEEPSEEK, "deepseek-v4-pro", "off")
        assertFalse(injected)
        assertEquals(1, body.length())
        assertFalse(body.toString().contains("reasoning"))
    }

    // ---------------- provider 白名单 + wire 值 ----------------

    @Test
    fun `白名单 provider 按档位注入 snake_case wire 值`() {
        for (effort in listOf("low", "medium", "high")) {
            for (provider in listOf(ApiProvider.DEEPSEEK, ApiProvider.OPENROUTER)) {
                val body = JSONObject().put("model", "any-model")
                val injected = body.applyReasoningEffort(provider, "any-model", effort)
                assertTrue("provider=$provider effort=$effort 应注入", injected)
                assertTrue(
                    "序列化体必须含 \"reasoning_effort\":\"$effort\"，实际=${body}",
                    body.toString().contains("\"reasoning_effort\":\"$effort\""),
                )
            }
        }
    }

    @Test
    fun `OPENAI 模型名门控_非推理模型不注入`() {
        for (model in listOf("gpt-4o-mini", "gpt-4.1", "gpt-4o", "chatgpt-4o-latest")) {
            val body = JSONObject().put("model", model)
            val injected = body.applyReasoningEffort(ApiProvider.OPENAI, model, "high")
            assertFalse("OPENAI model=$model 不应注入 reasoning_effort", injected)
            assertFalse(body.toString().contains("reasoning_effort"))
        }
    }

    @Test
    fun `OPENAI 模型名门控_推理模型注入`() {
        for (model in listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5.1", "GPT-5-turbo")) {
            val body = JSONObject().put("model", model)
            val injected = body.applyReasoningEffort(ApiProvider.OPENAI, model, "medium")
            assertTrue("OPENAI model=$model 应注入 reasoning_effort", injected)
            assertTrue(body.toString().contains("\"reasoning_effort\":\"medium\""))
        }
    }

    // ---------------- 黑名单：全枚举穷尽 ----------------

    @Test
    fun `非白名单 provider 全枚举不注入`() {
        val whitelist = setOf(ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.OPENROUTER)
        val blacklisted = ApiProvider.entries.filterNot { it in whitelist }
        assertTrue("黑名单不应为空", blacklisted.isNotEmpty())
        for (provider in blacklisted) {
            val body = JSONObject().put("model", "whatever")
            val injected = body.applyReasoningEffort(provider, "whatever", "high")
            assertFalse("provider=$provider 不应注入 reasoning_effort", injected)
            assertEquals(1, body.length())
        }
    }

    // ---------------- wire 归一 ----------------

    @Test
    fun `wire 值大小写与空白归一`() {
        val body = JSONObject()
        assertTrue(body.applyReasoningEffort(ApiProvider.DEEPSEEK, "m", " HIGH "))
        assertTrue(body.toString().contains("\"reasoning_effort\":\"high\""))
    }
}
