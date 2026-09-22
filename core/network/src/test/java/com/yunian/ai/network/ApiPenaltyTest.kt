package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复读惩罚注入回归测试（Bug 根因 H1）。
 *
 * 根因备忘：`ChatCompletionRequest` 声明的 presence/frequency_penalty 从未随请求发出，
 * 6 处手写 JSON 请求体均未 `put`，导致复读惩罚恒为 0。本测试锁定修复后的关键契约：
 *  ① 可注入的 provider 会带上正确字段与数值；
 *  ② 不支持的 provider **不**注入（关键回归护栏：防止把可用 provider 弄成 400）；
 *  ③ 门控与解析函数一致；④ 取值落在保守区间且非零；
 *  ⑤ **字段级策略（E1）**：某轴「服务端默认值 ≥ 注入值」时该轴不注入（避免反向削弱）。
 *
 * 注入值依据与逐 provider 默认值审计见 `ApiPenalty.kt` 顶部取证记录。
 */
class ApiPenaltyTest {

    /** 两轴默认值均为 0 → 两轴都注入 0.4 / 0.3。 */
    private val bothAxisProviders = listOf(
        ApiProvider.OPENAI,
        ApiProvider.DEEPSEEK,
        ApiProvider.ZHIPU,
        ApiProvider.SILICONFLOW,
        ApiProvider.OPENROUTER,
        ApiProvider.GROQ,
        ApiProvider.XIAOMI,
    )

    /**
     * 仅 frequency 注入：其 presence 服务端默认值 ≥ 0.4（IFLYTEK 1.2；DASHSCOPE 随模型 1.5/0.5），
     * 注入 presence 会反向削弱该轴 → 该轴不注入。
     */
    private val frequencyOnlyProviders = listOf(
        ApiProvider.IFLYTEK,
        ApiProvider.DASHSCOPE,
    )

    /** 不支持 / 明确报错 / 无法确认 → 绝不注入。CUSTOM 因"任意 relay 不可知"被排除（E2）。 */
    private val unsupportedProviders = listOf(
        ApiProvider.KIMI,
        ApiProvider.GEMINI,
        ApiProvider.PARTNER,
        ApiProvider.CUSTOM,
        ApiProvider.ANTHROPIC,
    )

    /** ① 两轴 provider：带上两个字段且数值精确。 */
    @Test
    fun `两轴 provider 注入 presence 与 frequency 且数值正确`() {
        for (provider in bothAxisProviders) {
            val body = JSONObject()
            val injected = body.applyRepetitionPenalty(provider)

            assertTrue("$provider 应注入", injected)
            assertTrue("$provider 应含 presence_penalty", body.has("presence_penalty"))
            assertTrue("$provider 应含 frequency_penalty", body.has("frequency_penalty"))
            assertEquals("$provider presence_penalty", 0.4, body.getDouble("presence_penalty"), 1e-9)
            assertEquals("$provider frequency_penalty", 0.3, body.getDouble("frequency_penalty"), 1e-9)
        }
    }

    /** ⑤ 仅 frequency provider：只写 frequency_penalty，**绝不**写 presence_penalty（字段级防反向削弱）。 */
    @Test
    fun `仅 frequency provider 只注入 frequency 不注入 presence`() {
        for (provider in frequencyOnlyProviders) {
            val body = JSONObject()
            val injected = body.applyRepetitionPenalty(provider)

            assertTrue("$provider 应至少注入 frequency", injected)
            assertFalse("$provider 不应含 presence_penalty（默认值更高，注入即反向削弱）", body.has("presence_penalty"))
            assertTrue("$provider 应含 frequency_penalty", body.has("frequency_penalty"))
            assertEquals("$provider frequency_penalty", 0.3, body.getDouble("frequency_penalty"), 1e-9)
            assertEquals("$provider 请求体应恰好 1 个字段", 1, body.length())
        }
    }

    /** ② 不支持的 provider：绝不写入任何惩罚字段（防 400 回归护栏）。 */
    @Test
    fun `不支持的 provider 绝不注入 - 防 400 回归护栏`() {
        for (provider in unsupportedProviders) {
            val body = JSONObject()
            val injected = body.applyRepetitionPenalty(provider)

            assertFalse("$provider 不应注入", injected)
            assertFalse("$provider 不应含 presence_penalty", body.has("presence_penalty"))
            assertFalse("$provider 不应含 frequency_penalty", body.has("frequency_penalty"))
            assertEquals("$provider 请求体应保持空", 0, body.length())
        }
    }

    /** ③ 门控函数与解析函数口径一致（supports == resolve != null）。 */
    @Test
    fun `resolveChatPenaltyParams 与门控一致`() {
        for (provider in bothAxisProviders) {
            assertTrue("$provider 门控应为 true", supportsRepetitionPenalty(provider))
            assertEquals("$provider 解析值", PenaltyParams(0.4, 0.3), resolveChatPenaltyParams(provider))
        }
        for (provider in frequencyOnlyProviders) {
            assertTrue("$provider 门控应为 true（至少一轴可用）", supportsRepetitionPenalty(provider))
            assertEquals("$provider 解析值", PenaltyParams(presencePenalty = null, frequencyPenalty = 0.3), resolveChatPenaltyParams(provider))
        }
        for (provider in unsupportedProviders) {
            assertFalse("$provider 门控应为 false", supportsRepetitionPenalty(provider))
            assertNull("$provider 解析应为 null", resolveChatPenaltyParams(provider))
        }
    }

    /** ④ 取值保守且确实生效（非 0），并落在 OpenAI 合法区间内。 */
    @Test
    fun `取值落在保守区间且非零`() {
        val params = resolveChatPenaltyParams(ApiProvider.OPENAI)!!
        // 实际生效（修复前恒为 0，等于未抑制复读）
        assertTrue("presence_penalty 应 > 0", params.presencePenalty!! > 0.0)
        assertTrue("frequency_penalty 应 > 0", params.frequencyPenalty!! > 0.0)
        // OpenAI 文档区间 -2.0 ~ 2.0
        assertTrue("presence_penalty 应在合法区间", params.presencePenalty!! <= 2.0)
        assertTrue("frequency_penalty 应在合法区间", params.frequencyPenalty!! <= 2.0)
        // 轻量档（避免人设变味/语气变形）
        assertTrue("presence_penalty 应在 0.3~0.6", params.presencePenalty!! in 0.3..0.6)
        assertTrue("frequency_penalty 应在 0.2~0.4", params.frequencyPenalty!! in 0.2..0.4)
    }

    /** 两轴 provider 注入不产生额外字段，且不破坏已有请求体字段。 */
    @Test
    fun `注入仅追加两个字段`() {
        val body = JSONObject().put("model", "gpt-4o-mini").put("stream", false)
        body.applyRepetitionPenalty(ApiProvider.OPENAI)

        assertEquals(4, body.length())
        assertTrue(body.has("model"))
        assertTrue(body.has("stream"))
        assertTrue(body.has("presence_penalty"))
        assertTrue(body.has("frequency_penalty"))
    }

    /** 黑名单 provider 在已有请求体上也不追加字段。 */
    @Test
    fun `不支持的 provider 不追加字段到已有请求体`() {
        val body = JSONObject().put("model", "gemini-2.5-flash").put("stream", true)
        val injected = body.applyRepetitionPenalty(ApiProvider.GEMINI)

        assertFalse(injected)
        assertEquals(2, body.length())
    }
}
