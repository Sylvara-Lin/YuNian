package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * QA 对抗性验证（Edward）—— 独立于工程师的 ApiPenaltyTest。
 *
 * 目的：工程师单测只断言「助手自身把常量写进了一个全新 JSONObject」，
 * 无法证明 ① 字段名真的是 snake_case、② 真的进了**序列化后的请求体字符串**、
 * ③ AiService 的三处真实聊天构造点真的调用了它、④ 内部抽取类路径没被误注入、
 * ⑤ provider 门控对**全部**枚举穷尽。
 *
 * 本测试刻意从「序列化后的字符串」「AiService 源码」两个旁路取证，做交叉验证。
 */
class ApiPenaltyAdversarialTest {

    // ---------------- A1：序列化后的真实请求体字符串必须含 snake_case 字段 ----------------

    @Test
    fun `序列化请求体字符串真的含 presence_penalty 与 frequency_penalty`() {
        // 复刻真实聊天 body 的形态（model/messages/stream/temperature + 惩罚）
        val body = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
            .put("stream", true)
            .put("temperature", 0.8)
        val injected = body.applyRepetitionPenalty(ApiProvider.OPENAI)

        val serialized = body.toString()
        assertTrue("应注入", injected)
        assertTrue("序列化体必须含 \"presence_penalty\":0.4，实际=$serialized", serialized.contains("\"presence_penalty\":0.4"))
        assertTrue("序列化体必须含 \"frequency_penalty\":0.3，实际=$serialized", serialized.contains("\"frequency_penalty\":0.3"))
    }

    /** 每个 key 在序列化体中只能出现一次（防「两条路径叠加/大小写不同」产生重复键）。 */
    @Test
    fun `惩罚字段在序列化体中各只出现一次`() {
        val body = JSONObject().put("model", "gpt-4o-mini").put("stream", false)
        body.applyRepetitionPenalty(ApiProvider.OPENAI)
        // 幂等重入：同名 key 覆盖，不应产生第二个键
        body.applyRepetitionPenalty(ApiProvider.OPENAI)

        val serialized = body.toString()
        assertEquals("presence_penalty 只应出现一次", 1, countOccurrences(serialized, "\"presence_penalty\""))
        assertEquals("frequency_penalty 只应出现一次", 1, countOccurrences(serialized, "\"frequency_penalty\""))
        assertEquals("总字段数应为 model/stream + 2 惩罚 = 4", 4, body.length())
    }

    /** 绝不能写成 camelCase（否则字段被服务端忽略，修复静默失效）。 */
    @Test
    fun `绝不存在 camelCase 变体字段`() {
        val body = JSONObject().put("model", "gpt-4o-mini")
        body.applyRepetitionPenalty(ApiProvider.OPENAI)
        val serialized = body.toString()
        assertFalse("不应出现 presencePenalty", serialized.contains("presencePenalty"))
        assertFalse("不应出现 frequencyPenalty", serialized.contains("frequencyPenalty"))
    }

    /** 黑名单 provider 序列化体里不得出现这两个 key（防把可用端点弄 400）。E2：CUSTOM 已移入黑名单。 */
    @Test
    fun `黑名单 provider 序列化体不含惩罚字段`() {
        for (provider in listOf(ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.PARTNER, ApiProvider.ANTHROPIC, ApiProvider.CUSTOM)) {
            val body = JSONObject().put("model", "x")
            val injected = body.applyRepetitionPenalty(provider)
            val serialized = body.toString()
            assertFalse("$provider 不应注入", injected)
            assertFalse("$provider 序列化体不应含 presence_penalty", serialized.contains("presence_penalty"))
            assertFalse("$provider 序列化体不应含 frequency_penalty", serialized.contains("frequency_penalty"))
        }
    }

    // ---------------- A5：provider 门控对枚举穷尽 / 不重不漏 ----------------

    /**
     * E1/E2 后：可注入 = 9（7 个两轴 + IFLYTEK/DASHSCOPE 仅 frequency），排除 = 5（CUSTOM 移入黑名单）。
     *
     * 断言策略（对归属变化健壮，但不放宽）：
     *  ① 不变量：白+黑 == 全集，且两者互斥（不随门控成员变化而失效）；
     *  ② 归属显式：白名单集合、黑名单集合逐成员显式断言。
     */
    @Test
    fun `全部 ApiProvider 枚举恰好被门控覆盖一次`() {
        val all = ApiProvider.values().toList()
        val injectable = all.filter { resolveChatPenaltyParams(it) != null }
        val excluded = all.filter { resolveChatPenaltyParams(it) == null }

        // ① 不变量：并集==全集、互斥、无重复
        assertEquals("白+黑 必须覆盖全部枚举", all.size, injectable.size + excluded.size)
        assertEquals("白名单不应有重复", injectable.size, injectable.toSet().size)
        assertEquals("黑名单不应有重复", excluded.size, excluded.toSet().size)
        assertTrue("白名单与黑名单必须互斥（交集为空）", injectable.toSet().intersect(excluded.toSet()).isEmpty())
        assertTrue("并集必须等于全集", injectable.toSet().union(excluded.toSet()) == all.toSet())

        // ② 归属显式断言
        assertEquals(
            "可注入应为这 9 个（CUSTOM 已移出；IFLYTEK/DASHSCOPE 仅 frequency）",
            setOf(
                ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW,
                ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.XIAOMI,
                ApiProvider.IFLYTEK, ApiProvider.DASHSCOPE,
            ),
            injectable.toSet(),
        )
        assertEquals(
            "排除应为这 5 个（含 CUSTOM）",
            setOf(ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.PARTNER, ApiProvider.CUSTOM, ApiProvider.ANTHROPIC),
            excluded.toSet(),
        )
        println("[QA] ApiProvider 枚举总数=${all.size} 白=${injectable.size} 黑=${excluded.size}")
    }

    // ---------------- E1：字段级注入策略（逐轴独立） ----------------

    /**
     * E1 核心：IFLYTEK / DASHSCOPE 的 presence 服务端默认值 ≥ 0.4，注入即反向削弱。
     * 必须**逐字段**验证：序列化后的请求体含 frequency_penalty、**不含** presence_penalty。
     * 注意还须证明「不注入」不是以 null / 0 的形式写进去（写 0 = 直接退回旧 bug；写 null = 破坏默认值语义）。
     */
    @Test
    fun `仅 frequency 的 provider 序列化体只含 frequency 绝不写 presence`() {
        for (provider in listOf(ApiProvider.IFLYTEK, ApiProvider.DASHSCOPE)) {
            val body = JSONObject().put("model", "m").put("stream", true)
            val injected = body.applyRepetitionPenalty(provider)
            val serialized = body.toString()

            assertTrue("$provider 应至少注入 frequency", injected)
            assertTrue("$provider 序列化体应含 \"frequency_penalty\":0.3，实际=$serialized", serialized.contains("\"frequency_penalty\":0.3"))
            // 「不注入 presence」的三重防线：不以 key 存在、不以 null 形式存在、不以 0 形式存在
            assertFalse("$provider 不应含 presence_penalty 键，实际=$serialized", serialized.contains("presence_penalty"))
            assertFalse("$provider 不应写 presence_penalty:null", serialized.contains("\"presence_penalty\":null"))
            assertFalse("$provider 不应写 presence_penalty:0", serialized.contains("\"presence_penalty\":0"))
        }
    }

    /** 两轴 provider：序列化体必须同时含两个字段（E1 未误伤普通白名单）。 */
    @Test
    fun `两轴 provider 序列化体同时含 presence 与 frequency`() {
        for (provider in listOf(ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.ZHIPU, ApiProvider.XIAOMI)) {
            val body = JSONObject().put("model", "m")
            body.applyRepetitionPenalty(provider)
            val serialized = body.toString()
            assertTrue("$provider 应含 presence_penalty:0.4", serialized.contains("\"presence_penalty\":0.4"))
            assertTrue("$provider 应含 frequency_penalty:0.3", serialized.contains("\"frequency_penalty\":0.3"))
        }
    }

    /**
     * 逐字段策略的完整不变量（对全部枚举机器校验）：
     *  - 非 null 的轴必须被写进 body、且值 = 常量；null 的轴必须**不出现**在 body；
     *  - applyRepetitionPenalty 的返回值 == (resolveChatPenaltyParams != null)。
     */
    @Test
    fun `逐字段注入结果与解析策略严格一致`() {
        for (provider in ApiProvider.values()) {
            val params = resolveChatPenaltyParams(provider)
            val body = JSONObject()
            val injected = body.applyRepetitionPenalty(provider)

            assertEquals("$provider 返回值应与策略非空一致", params != null, injected)
            if (params == null) {
                assertEquals("$provider 不应写任何字段", 0, body.length())
                continue
            }
            assertEquals("$provider 是否含 presence 应与策略一致", params.presencePenalty != null, body.has("presence_penalty"))
            assertEquals("$provider 是否含 frequency 应与策略一致", params.frequencyPenalty != null, body.has("frequency_penalty"))
            params.presencePenalty?.let { assertEquals("$provider presence 值", it, body.getDouble("presence_penalty"), 1e-9) }
            params.frequencyPenalty?.let { assertEquals("$provider frequency 值", it, body.getDouble("frequency_penalty"), 1e-9) }
            assertTrue("$provider 至少应有一轴被注入", body.length() >= 1)
        }
    }

    // ---------------- A2/A6：源码级取证，证明注入点真的在真实聊天路径上 ----------------

    private fun locateAiServiceSource(): File? {
        val candidates = listOf(
            "src/main/java/com/yunian/ai/network/AiService.kt",
            "core/network/src/main/java/com/yunian/ai/network/AiService.kt",
            "../core/network/src/main/java/com/yunian/ai/network/AiService.kt",
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    @Test
    fun `AiService 恰好三处真实聊天构造点调用了注入`() {
        val src = locateAiServiceSource()
        assumeTrue("无法定位 AiService.kt（跳过源码级取证）", src != null)
        val text = src!!.readText().replace("\r\n", "\n")

        val callCount = countOccurrences(text, "jsonBody.applyRepetitionPenalty(config.provider)")
        assertEquals("应恰好 3 处注入（流式/工具/识图）", 3, callCount)

        // 三处必须分别位于三个真实聊天请求构造函数体内
        for (fn in listOf("private fun openAiCompatibleSseLineFlow", "private suspend fun callOpenAiCompatibleWithTools", "private suspend fun callOpenAiCompatibleVision")) {
            val idx = text.indexOf(fn)
            assertTrue("未找到函数 $fn", idx >= 0)
            val body = text.substring(idx, minOf(text.length, idx + 6000))
            assertTrue("$fn 内未调用 applyRepetitionPenalty", body.contains("jsonBody.applyRepetitionPenalty(config.provider)"))
        }
    }

    @Test
    fun `内部抽取类路径 callOpenAiCompatibleLight 未注入惩罚`() {
        val src = locateAiServiceSource()
        assumeTrue("无法定位 AiService.kt（跳过源码级取证）", src != null)
        val text = src!!.readText().replace("\r\n", "\n")

        val idx = text.indexOf("private suspend fun callOpenAiCompatibleLight")
        assertTrue("未找到 callOpenAiCompatibleLight", idx >= 0)
        // 截到下一个函数定义之前，检查该内部抽取路径体没有注入
        val body = text.substring(idx, minOf(text.length, idx + 4000))
        assertFalse("内部抽取路径不应注入惩罚（会损害摘要/分类质量）", body.contains("applyRepetitionPenalty"))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) break
            count++
            from = i + needle.length
        }
        return count
    }
}
