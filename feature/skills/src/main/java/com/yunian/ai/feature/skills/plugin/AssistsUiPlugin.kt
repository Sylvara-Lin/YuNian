package com.yunian.ai.feature.skills.plugin

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.skills.accessibility.AccessibilityBridge
import com.yunian.ai.feature.skills.tools.AccessibilityStatusTool
import com.yunian.ai.feature.skills.tools.GoHomeTool
import com.yunian.ai.feature.skills.tools.PressBackTool
import com.yunian.ai.feature.skills.tools.ScreenClickTextTool
import com.yunian.ai.feature.skills.tools.ScreenDumpUiTool
import com.yunian.ai.feature.skills.tools.ScreenInputTextTool
import com.yunian.ai.feature.skills.tools.ScreenReadTool
import com.yunian.ai.feature.skills.tools.ScreenSwipeTool
import com.yunian.ai.feature.skills.tools.ScreenTapTool

/**
 * 无障碍自动化插件（Cordis 代码插件，kind = **TOOL**）。
 *
 * 插件 id `ui.assists`、展示名「无障碍自动化」，**默认装载**（蓝图 `assets/blueprints/default.json`
 * 的装载项由 app 侧维护）。
 *
 * ## 为什么是「迁移替换」而不是「新增能力」
 *
 * 迁移前，「AI 控制手机」的 7 个工具（accessibility_status / screen_read / press_back /
 * go_home / screen_tap / screen_swipe / screen_click_text）由全局函数
 * `registerAccessibilityTools()` 在 Application 启动时**无条件**注册——那是硬编码在启动路径
 * 里的能力，用户既关不掉、也无从知道它存在。现在改为**本插件提供**：
 * 装载 → 工具进注册表；卸载 → 工具立刻消失。
 *
 * **对外契约一个字不改**：这 7 个既有工具的名字 / description / parametersJsonSchema /
 * systemPrompt() / 错误文案 / requiresConfirmation / toolsets（空 = 通用集）与迁移前逐字一致，
 * 变的只是「装配方式」。全局函数 `registerAccessibilityTools()` **已删除**。
 *
 * ## 最终 9 个工具（顺序 = 注册顺序 = [TOOL_NAMES]）
 *
 * `accessibility_status` / `screen_read` / `press_back` / `go_home` / `screen_tap` /
 * `screen_swipe` / `screen_click_text`（既有 7 个）+ `screen_input_text` / `screen_dump_ui`（新增）。
 * 命名上**只有一个 `screen_` 家族**，不引入 `ui_*` 前缀。
 *
 * ## 安全裁定（appLocalOnly）
 *
 * | 工具 | 为什么 |
 * |---|---|
 * | 既有 7 个 = `false` | 它们已经出现在外部桥接会话（QQ / 微信）的工具列表里；收窄可见性属于**行为变更**，不在本次迁移范围内 |
 * | `screen_input_text` = **`true`** | 能替用户往任意 App 的输入框写字（可冒充用户输入） |
 * | `screen_dump_ui` = **`true`** | 吐出结构化整窗节点树（包名 / viewId / 坐标 / 是否密码框），泄漏面远大于扁平读屏文本 |
 *
 * `appLocalOnly = true` 的工具默认不进任何面向模型的枚举面
 * （[com.yunian.ai.domain.ToolRegistry.availableTools] 第一道闸），执行侧还有第二道闸。
 *
 * ## 接缝注入：不 import assists
 *
 * 本插件只依赖 [AccessibilityBridge] 这个纯 Kotlin 接缝（真机实现见
 * `AssistsAccessibilityBridge`，由装载方注入）。因此本文件在 assists 依赖落地前后都能编译，
 * 也能在 JVM 单测里用假实现跑通全部契约。
 *
 * ## 卸载即摘工具（已接受的语义）
 *
 * [setup] 里每个工具注册后都配一条 `ctx.effect({ registry.unregister(name) }, "unregister:" + name)`，
 * 卸载按注册**逆序**执行（Cordis「卸载不留鸡毛」）。
 *
 * 语义后果是**有意为之**的：用户停用本插件 → 9 个工具从注册表消失 → AI 立即失去手机控制能力。
 * 这正是「无障碍自动化是一个可关掉的能力」的落地方式——与迁移前「注册了就无法收回」相反。
 * 不存在「插件已卸载、工具还挂在注册表里」的僵尸窗口。
 *
 * @param bridge 无障碍能力接缝；工具实例持有它，卸载后随工具一起被丢弃。
 */
class AssistsUiPlugin(private val bridge: AccessibilityBridge) : LianYuPlugin {

    companion object {
        /** 插件 id（蓝图 `assets/blueprints/default.json` 里引用的就是这个字符串）。 */
        const val ID: String = "ui.assists"

        /** 展示名。 */
        const val NAME: String = "无障碍自动化"

        /**
         * 本插件注册的 9 个 Agent 工具名，**顺序 = [setup] 的注册顺序**。
         *
         * 单一事实来源：单测用它断言「注册表里恰好出现这 9 个名字且顺序一致」。
         */
        val TOOL_NAMES: List<String> = listOf(
            "accessibility_status",
            "screen_read",
            "press_back",
            "go_home",
            "screen_tap",
            "screen_swipe",
            "screen_click_text",
            "screen_input_text",
            "screen_dump_ui",
        )

        /** 按 [TOOL_NAMES] 的顺序构造 9 个工具实例（每个都持有同一个 [bridge]）。 */
        internal fun tools(bridge: AccessibilityBridge): List<AiTool> = listOf(
            AccessibilityStatusTool(bridge),
            ScreenReadTool(bridge),
            PressBackTool(bridge),
            GoHomeTool(bridge),
            ScreenTapTool(bridge),
            ScreenSwipeTool(bridge),
            ScreenClickTextTool(bridge),
            ScreenInputTextTool(bridge),
            ScreenDumpUiTool(bridge),
        )
    }

    override val id: String = ID

    override val name: String = NAME

    override val kind: PluginKind = PluginKind.TOOL

    override val requires: Set<String> = setOf(PluginServices.TOOLS)

    override val configSchema: String? = null

    override val version: String = "1.0.0"

    /**
     * 「插件设置」页展示的一句话说明。
     *
     * 逐条对应 [setup] 装配的 9 个工具（见 [TOOL_NAMES]）：
     * 读屏 = `screen_read` / `screen_dump_ui`，点击 = `screen_tap` / `screen_click_text`，
     * 滑动 = `screen_swipe`，输入 = `screen_input_text`，其余三个是导航与状态
     * （`accessibility_status` / `press_back` / `go_home`）。
     *
     * 后半句「停用后 AI 立即失去这些能力」不是营销话术，而是本插件的**装配语义**：
     * 每个工具都配了一条 `ctx.effect` 注销副作用（见 [setup]），卸载即从注册表摘除，
     * 不存在「插件停了、工具还挂着」的僵尸窗口。
     */
    override val description: String =
        "让 AI 读屏、点击、滑动、输入并导出界面结构来控制手机；停用后立即失效。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = NAME,
        version = "1.0.0",
        kind = PluginKind.TOOL,
        requires = requires.sorted(),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        val registry = ctx.inject<ToolRegistry>(PluginServices.TOOLS)
        tools(bridge).forEach { tool ->
            registry.register(tool)
            // 注册与注销成对：卸载按逆序执行 effects，工具随之从注册表消失。
            ctx.effect({ registry.unregister(tool.name) }, "unregister:" + tool.name)
        }
    }
}
