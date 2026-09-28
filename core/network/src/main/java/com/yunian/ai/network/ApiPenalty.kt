package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider
import org.json.JSONObject

/**
 * 复读惩罚（repetition penalty）适配助手（core:network 内部可见）。
 *
 * 背景（Bug 根因 H1：AI 回复「复述」之前说过的内容）：
 * `ChatCompletionRequest`（见 `AiDtos.kt`）虽声明了 `presence_penalty` / `frequency_penalty`，
 * 但该 data class **从未被用于真正发请求**（对应 Retrofit 接口 `OpenAiApi` 只 create 未调用）；
 * 真实请求体在 [AiService] 里手写 JSON，历史上**从未** `put` 过这两个字段
 *   → 复读惩罚恒为 0，对「换种说法把同一件事再讲一遍」的语义级复述零抑制。
 *
 * `presence_penalty` / `frequency_penalty` 是 OpenAI 语义下「惩罚已出现在上下文中的 token」的
 * 标准采样参数，与 `temperature` 同属采样侧。本助手与 [ApiTemperature.kt] 的
 * [toApiTemperature] 同构：**所有**把惩罚值写进 JSON 的点都必须经由 [applyRepetitionPenalty]，
 * 禁止在请求构造点散落魔法数字。
 *
 * === provider 能力门控（关键安全约束）===
 * 不同端点的参数支持度不同，非标准 OpenAI 语义的路径可能因**未知字段直接 400**，
 * 从而把一个原本可用的 provider 弄挂。因此只对「明确为标准 OpenAI 语义且官方文档确认接受这两个
 * 字段」的 provider 注入；无法确认 / 明确会报错的一律**不注入**（宁可不发，也不破坏可用性）。
 *
 * === 字段级注入原则（默认值反向削弱，E1）===
 * 只"落在合法区间"**不足以**判定该注入：若某 provider 该字段的**服务端默认值 ≥ 我们要注入的值**，
 * 显式注入等于把这个轴的惩罚**反向调低**，比不注入**更利于复述**（典型：IFLYTEK
 * `presence_penalty` 默认 1.2，注入 0.4 即从 1.2 降到 0.4）。因此**逐字段**判定：
 * 该字段「注入值 ≥ 默认值」才注入，否则**不注入该字段**（交给更强的服务端默认）。
 * 注意：门控只能拿到 provider、拿不到 model，凡"默认值随 model 变化且存在 > 注入值的情形"，
 * 一律保守按"不注入该轴"处理。
 * 本注释即「能力门控 + 默认值审计的取证记录」，逐 provider 一行依据（URL + 关键句 + 结论）。
 *
 * —— 白名单：官方文档确认可安全注入（至少一个轴），且该轴 注入值 ≥ 服务端默认值 ——
 *  - [ApiProvider.OPENAI]：原生定义 presence/frequency ∈ [-2.0, 2.0]，**默认均为 0**；注入 0.4/0.3 ≥ 0 → 两轴注入。
 *  - [ApiProvider.DEEPSEEK]：api.deepseek.com OpenAI 兼容，两字段 ∈ [-2, 2]，**默认均为 0** → 两轴注入。
 *  - [ApiProvider.ZHIPU]：open.bigmodel.cn（GLM v4/v5）两字段 ∈ [-2, 2]，**默认均为 0.0**（GLM-4-Plus / GLM-4.6 / GLM-5.3 文档一致）→ 两轴注入。
 *  - [ApiProvider.SILICONFLOW]：聚合网关，标准 OpenAI 参数透传、未识别参数不报错，**默认 0** → 两轴注入。
 *  - [ApiProvider.OPENROUTER]：openrouter.ai/docs/parameters —「frequency_penalty ... -2.0 to 2.0, Default 0」「presence_penalty ... Default 0」→ 两轴注入。
 *  - [ApiProvider.GROQ]：console.groq.com OpenAI 兼容，两字段 ∈ [-2.0, 2.0]、**默认 0** → 两轴注入。
 *  - [ApiProvider.XIAOMI]：小米 MiMo（mimo-v2.com / platform.xiaomimimo.com）两字段 ∈ [-2.0, 2.0]、**默认 0** → 两轴注入。
 *  - [ApiProvider.DASHSCOPE]：阿里云帮助文档（兼容模式 / model-studio）—presence_penalty「取值范围 [-2.0, 2.0]」且**默认值随模型**：
 *    「qwen-max、Qwen3 系、qwen-vl 系 … **1.5**；qwen-plus/qwen-turbo（思考模式）**0.5**；其余均为 0.0」；
 *    frequency_penalty 未列出非零默认（**默认 0**）。→ presence 存在默认 1.5/0.5 > 0.4，门控看不到 model →
 *    **presence 不注入**（避免反向削弱）；**frequency 注入**（0.3 ≥ 0）。
 *  - [ApiProvider.IFLYTEK]：讯飞星火 HTTP 文档（xfyun.cn / docs.iflyaicloud.com）—`presence_penalty` 取值范围 [0, 2]、
 *    **默认 1.2**；`frequency_penalty` 取值范围 [0, 1]、**默认 0.02**。→ presence 默认 1.2 > 0.4 → **presence 不注入**；
 *    **frequency 注入**（0.3 > 0.02）。
 *
 * —— 黑名单：不支持 / 明确会报错 / 无法确认，**绝不注入** ——
 *  - [ApiProvider.KIMI]：platform.kimi.com（模型参数参考）—本项目 Kimi 默认模型 `kimi-k2.6` 的
 *    presence/frequency「固定 0」，且注明「传入其他值会报错，建议不要显式传入」；仅旧 `moonshot-v1` 系列支持。
 *    门控拿不到 model → 保守排除（保持修复前原行为，零回归）。
 *  - [ApiProvider.GEMINI]：Google 官方 OpenAI 兼容层对 frequency_penalty/presence_penalty
 *    「会抛出有效的 API 错误」（Google AI Forum / cloud.google 文档）→ 排除。
 *  - [ApiProvider.PARTNER]：Clove 私有网关（suflow.cloud），无公开参数文档，历史上对字段极严（曾因 temperature 精度直接 400）→ 排除。
 *  - [ApiProvider.CUSTOM]：用户自填的**任意** OpenAI 兼容 relay，是否接受这两个字段**不可知**（与 PARTNER 同类风险，
 *    此前误判为"必然 openai 格式"已修正——`sendMessageWithTools` 按 provider 枚举分派、不查 `usesAnthropicProtocol`，
 *    「CUSTOM + tools」仍会走到标准注入路径）。惩罚参数只是边际改善，而未知字段 400 会让该用户聊天直接不可用 →
 *    按"不冒险破坏原本可用功能"的既有红线**排除**。
 *  - [ApiProvider.ANTHROPIC]：Messages API 无此参数，走独立数据类路径（不经过本助手）→ 排除。
 */

/**
 * 聊天生成路径的 `presence_penalty`（用于「该字段默认值 ≤ 此值」的 provider）。
 *
 * 取值理由：presence_penalty 惩罚「任何已出现过的 token」，是抑制复述的主杠杆，故略高于
 * frequency_penalty；0.4 属 OpenAI 文档区间（-2.0~2.0）的轻量档，只压制「又说一遍」，
 * 不会让人设固定词/语气词被过度规避而导致语气变形。
 */
internal const val CHAT_PRESENCE_PENALTY: Double = 0.4

/**
 * 聊天生成路径的 `frequency_penalty`。
 *
 * 取值理由：按 token 出现频次加权惩罚，用于打散同句内的高频复读；0.3 为轻量档，
 * 与 presence_penalty 组合后抑制复述但保留正常口语重复（如「嗯……嗯」这类自然语气）。
 */
internal const val CHAT_FREQUENCY_PENALTY: Double = 0.3

/**
 * 一个 provider 的复读惩罚注入策略（**逐字段**独立决定）。
 *
 * 字段为 `null` 表示**不注入该字段**（交给服务端默认值）。之所以需要字段级开关，是因为有些
 * provider 该字段的**服务端默认值 ≥ 我们要注入的值**——此时注入等于把该轴惩罚"反向调低"，
 * 比不注入更利于复述（见文件顶部「字段级注入原则」）。
 */
internal data class PenaltyParams(
    val presencePenalty: Double?,
    val frequencyPenalty: Double?,
)

/**
 * 该 provider 的 OpenAI 兼容 `/chat/completions` 端点是否可安全携带复读惩罚字段
 * （即：至少有一个字段满足「注入值 ≥ 服务端默认值」）。
 *
 * 逐 provider 的纳入/排除依据见本文件顶部的能力门控 + 默认值审计取证记录（每个 provider 一行）。
 */
internal fun supportsRepetitionPenalty(provider: ApiProvider): Boolean =
    resolveChatPenaltyParams(provider) != null

/**
 * 解析该 provider 应注入的惩罚参数（逐字段：null = 不注入该字段）；
 * 完全不可安全注入时返回 null（调用方据此不写任何字段）。
 *
 * 取值依据见文件顶部取证记录。要点：只"落在合法区间"不够，必须**逐字段确认注入值 ≥ 服务端默认值**，
 * 否则该轴不注入（避免反向削弱）。
 */
internal fun resolveChatPenaltyParams(provider: ApiProvider): PenaltyParams? = when (provider) {
    // 两轴默认值均为 0 → 两轴都注入 0.4 / 0.3（均 ≥ 默认）。
    ApiProvider.OPENAI,
    ApiProvider.DEEPSEEK,
    ApiProvider.ZHIPU,
    ApiProvider.SILICONFLOW,
    ApiProvider.OPENROUTER,
    ApiProvider.GROQ,
    ApiProvider.XIAOMI -> PenaltyParams(
        presencePenalty = CHAT_PRESENCE_PENALTY,
        frequencyPenalty = CHAT_FREQUENCY_PENALTY,
    )

    // IFLYTEK：presence 默认 1.2 > 0.4 → 不注入该轴（否则反向削弱）；frequency 默认 0.02 < 0.3 → 注入。
    ApiProvider.IFLYTEK -> PenaltyParams(
        presencePenalty = null,
        frequencyPenalty = CHAT_FREQUENCY_PENALTY,
    )

    // DASHSCOPE：presence 默认随模型为 1.5 / 0.5 / 0.0（存在 > 0.4 的情形）→ 不注入该轴；
    //            frequency 默认 0 → 注入。
    ApiProvider.DASHSCOPE -> PenaltyParams(
        presencePenalty = null,
        frequencyPenalty = CHAT_FREQUENCY_PENALTY,
    )

    // 黑名单：完全不注入（不支持 / 明确报错 / 无法确认）。
    ApiProvider.KIMI,
    ApiProvider.GEMINI,
    ApiProvider.PARTNER,
    ApiProvider.CUSTOM,
    ApiProvider.ANTHROPIC -> null
}

/**
 * 按 provider 门控把复读惩罚**逐字段**写入请求体；某字段策略为 null 或 provider 不支持则不写该字段。
 *
 * 这是**唯一**的惩罚字段写入点（单一门控原则）：禁止在请求构造点散落字段判断或魔法数字。
 *
 * @return `true` 表示至少注入了 `presence_penalty` 或 `frequency_penalty` 之一；
 *         `false` 表示未注入任何字段（provider 不支持，保证不会因未知字段被 400）。
 */
internal fun JSONObject.applyRepetitionPenalty(provider: ApiProvider): Boolean {
    val params = resolveChatPenaltyParams(provider) ?: return false
    var injected = false
    params.presencePenalty?.let {
        put("presence_penalty", it)
        injected = true
    }
    params.frequencyPenalty?.let {
        put("frequency_penalty", it)
        injected = true
    }
    return injected
}
