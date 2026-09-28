package com.yunian.ai.feature.skills.tools

import com.yunian.ai.domain.SkillManager

/**
 * 技能工具装配（Q6 技能体系收敛后的**空实现**）。
 *
 * ## 为什么这个函数只做「什么都不做」
 *
 * 本地 `use_skill`（走 `ToolRegistry`，受 `useTools` 门控）与 Rust `load_skill`
 * （`AgentToolHost` 特判，无条件可用）在 Cordis Agent 架构下并存时，模型可能
 * **同时调用**二者 → 重复加载同一技能正文 + 提示词污染 + token 浪费。
 *
 * 因此技能收敛方案的裁决是：**退役 `use_skill`，统一为 Rust `load_skill`**。
 * 技能正文改由 Rust `SkillSelector` 按需读取（渐进式披露 L1 目录 / L2 按需加载），
 * 其 `SkillStore` 回调由 [com.yunian.ai.feature.skills.repository.SkillStoreAdapter]
 * 桥接到本地 `assets/skills` + `filesDir/external_skills`（含技能市场新装技能），
 * 由默认蓝图在**首次 Agent 回合之前**装配完毕。
 *
 * ## 为什么保留这个空函数
 *
 * 保留为**接线锚点**：`app/YuNianApplication` 的技能装配区有一段连续的
 * `registerSkillTools` → `registerSkillMarketTools` → `registerDeviceTools` →
 * `registerAccessibilityTools` → `registerShizukuTools` 调用序列，函数保留可以让
 * 这段装配代码的形态与语义保持稳定，也作为本决策的记录点。若未来需要恢复本地
 * 技能工具，在此处注册即可。
 *
 * @param skillManager 本地技能管理器；当前不注册任何工具，仅作占位。
 */
@Suppress("UNUSED_PARAMETER")
fun registerSkillTools(skillManager: SkillManager) {
    // 有意留空：技能正文与目录改由 Rust `SkillSelector` 承担，见上方 KDoc。
}
