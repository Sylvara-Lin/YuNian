package com.yunian.ai.feature.chat.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest

/**
 * 第一人称思考插件（kind = PIPELINE，纯提示词注入，不改 Rust）。
 *
 * ## 它做什么
 *
 * 开启后，让模型的**思考过程（reasoning / CoT）用角色本人的第一人称**写，
 * 而不是英文 + 第三人称的任务规划（"The user said... I should respond..."）。
 *
 * 注入通道是**仓库原版的 `_agent_preserve_system`**（世界书同一条）：
 * 在 `history_json` 底部、紧贴当前 user 消息前，塞一条带
 * `_agent_preserve_system: true` 的 system 消息，Rust `replace_persona_system`
 * 保留它、发送前清除标记。**完全不碰 Rust 提示词编排层**。
 *
 * ## 为什么用底部注入
 *
 * 编排器 persona system 排在 messages[0]；保留型 system 只能排在它之后。
 * 底部（当前 user 消息前）是不改 Rust 前提下**权重最高**的位置——DeepSeek
 * 对贴近当前输入的指令遵循度最高。
 *
 * ## 开关语义
 *
 * [setup] 把全局开关 [FirstPersonReasoningRules.enabled] 置 true，[PluginContext.effect]
 * 登记卸载时置 false——开关状态即注入状态。组装侧（ChatGenerationManager.serializeHistoryJson）
 * 每次组 history 时读这个开关，关 → 不注入（messages 逐字节不变）。
 */
class FirstPersonReasoningPlugin : LianYuPlugin {

    companion object {
        /** 插件 id（蓝图 `assets/blueprints/default.json` 里引用的就是这个字符串）。 */
        const val ID: String = "chat.first_person_reasoning"
    }

    override val id: String = ID

    override val name: String = "第一人称思考"

    override val kind: PluginKind = PluginKind.PIPELINE

    override val requires: Set<String> = emptySet()

    override val configSchema: String? = null

    override val description: String =
        "让模型的思考过程用角色本人的第一人称（内心独白），而不是英文第三人称任务规划。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "第一人称思考",
        version = "1.0.0",
        kind = PluginKind.PIPELINE,
        requires = requires.sorted(),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        FirstPersonReasoningRules.enabled = true
        ctx.effect({ FirstPersonReasoningRules.enabled = false }, "first-person-reasoning:disable")
    }
}

/**
 * 第一人称思考规则（全局开关 + 规则文本的唯一来源）。
 *
 * 规则文本对齐爱语（逆向 `com.shuoshuo.aiyu` dex 实证）——爱语把这条作为
 * `【思维链提示】` 独立区块硬编码，对 DeepSeek 实测有效（第一人称 + 意识流 + 不分点）。
 */
object FirstPersonReasoningRules {

    /** 全局开关：插件 load → true，unload → false（见 [FirstPersonReasoningPlugin.setup]）。 */
    @Volatile
    var enabled: Boolean = false

    /**
     * 规则全文（唯一副本；单行、无换行——它独占一条 system 消息，分段交给组装侧）。
     *
     * 写作要点（逆向爱语 + 用户实测）：
     * - 「禁止首字输出"用户"」直接对抗 "The user said..." 的英文开头；
     * - 「用平时叫对方的称呼替代"用户"」强制代入角色关系（叫"宝宝"）；
     * - 「意识流…严禁分点」对抗英文任务规划（check-list 结构）；
     * - 【思维链提示】标题头让它成为独立的【】区块，跟人设/记忆等区块并列。
     */
    const val RULE_TEXT: String =
        "【思维链提示】思维链 (CoT) 内部必须以你的第一人称视角进行思考，禁止首字输出“用户”，" +
        "用平时叫对方的称呼 (如有) 或名字替代“用户”这一冷冰冰的词汇 (若无，直接开始思考)，" +
        "严格按照你的第一人称视角进行思考，思考期间要像意识流一样思维自然涌动，严禁分点，" +
        "思考完毕后必须明确闭合思考过程，随后才能输出正式回复内容"

    /** 关闭时返回空串：组装侧据此不注入（messages 逐字节不变）。 */
    fun systemRules(): String = if (enabled) RULE_TEXT else ""
}
