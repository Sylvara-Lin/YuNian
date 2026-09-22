package com.yunian.ai.feature.skills.tools

import com.yunian.ai.feature.skills.accessibility.YuNianAccessibilityService
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AI 控制手机工具集（基于无障碍服务）。
 * 读屏/导航类低风险直接执行；触屏操作类（点击/滑动/按文本点击）需用户确认后执行。
 * 服务未开启时所有工具返回开启指引。
 */
private val a11yJson = Json { ignoreUnknownKeys = true }

private fun a11yArgs(argumentsJson: String) =
    runCatching { a11yJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

private fun a11yError(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

private fun a11yOk(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
    put("ok", true)
    pairs.forEach { (k, v) -> when (v) {
        is String? -> put(k, v)
        is Int? -> put(k, v)
        is Boolean? -> put(k, v)
    } }
}.toString()

/** 无障碍服务是否就绪；未就绪时返回引导错误 */
private inline fun withService(onReady: (YuNianAccessibilityService) -> String): String {
    val service = YuNianAccessibilityService.instance
        ?: return a11yError(
            "无障碍服务未开启：请在系统设置 → 无障碍 → 已下载的应用 → 「予念助手控制服务」中开启后重试",
        )
    return onReady(service)
}

/** 查询无障碍服务状态 */
class AccessibilityStatusTool : AiTool {
    override val name = "accessibility_status"
    override val description = "查询 AI 控制手机的无障碍服务是否已开启（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "accessibility_status: 查询手机控制通道状态。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val ready = YuNianAccessibilityService.isReady
        return a11yOk(
            "enabled" to ready,
            "hint" to if (ready) "手机控制通道可用" else "未开启，需用户在系统设置中授权",
        )
    }
}

/** 读取当前屏幕可见文本 */
class ScreenReadTool : AiTool {
    override val name = "screen_read"
    override val description = "读取当前手机屏幕上的可见文本（无参数），用于理解当前界面内容。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "screen_read: 读取当前屏幕文本。无参数。先用它了解界面再操作。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        withService { service ->
            val text = service.readScreenText()
            if (text.isBlank()) a11yError("未能读取屏幕内容（当前界面可能不支持读取）")
            else a11yOk("screen" to text)
        }
}

/** 按返回键 */
class PressBackTool : AiTool {
    override val name = "press_back"
    override val description = "执行系统返回（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "press_back: 按返回键。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        withService { service ->
            if (service.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                a11yOk("action" to "back")
            } else a11yError("返回失败")
        }
}

/** 回到主屏幕 */
class GoHomeTool : AiTool {
    override val name = "go_home"
    override val description = "回到手机主屏幕（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "go_home: 回到主屏幕。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String =
        withService { service ->
            if (service.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                a11yOk("action" to "home")
            } else a11yError("返回主屏失败")
        }
}

/** 点击屏幕坐标（需用户确认） */
class ScreenTapTool : AiTool {
    override val name = "screen_tap"
    override val description = "点击手机屏幕坐标。参数 {x: int, y: int}。先用 screen_read 了解界面再操作。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_tap: 点击屏幕坐标（需用户确认）。参数 {x: int, y: int}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val x = args["x"]?.jsonPrimitive?.intOrNull ?: return a11yError("x 缺失")
        val y = args["y"]?.jsonPrimitive?.intOrNull ?: return a11yError("y 缺失")
        return withService { service ->
            if (service.tap(x.toFloat(), y.toFloat())) a11yOk("tapped" to "$x,$y")
            else a11yError("点击失败")
        }
    }
}

/** 滑动手势（需用户确认） */
class ScreenSwipeTool : AiTool {
    override val name = "screen_swipe"
    override val description = "在手机屏幕上滑动。参数 {x1,y1,x2,y2: int, durationMs?: int}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"x1":{"type":"integer"},"y1":{"type":"integer"},"x2":{"type":"integer"},"y2":{"type":"integer"},"durationMs":{"type":"integer"}},"required":["x1","y1","x2","y2"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_swipe: 滑动手势（需用户确认）。参数 {x1,y1,x2,y2: int, durationMs?: int}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val x1 = args["x1"]?.jsonPrimitive?.intOrNull ?: return a11yError("x1 缺失")
        val y1 = args["y1"]?.jsonPrimitive?.intOrNull ?: return a11yError("y1 缺失")
        val x2 = args["x2"]?.jsonPrimitive?.intOrNull ?: return a11yError("x2 缺失")
        val y2 = args["y2"]?.jsonPrimitive?.intOrNull ?: return a11yError("y2 缺失")
        val duration = args["durationMs"]?.jsonPrimitive?.intOrNull ?: 300
        return withService { service ->
            if (service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())) {
                a11yOk("swiped" to "($x1,$y1)->($x2,$y2)")
            } else a11yError("滑动失败")
        }
    }
}

/** 按文本查找并点击（需用户确认） */
class ScreenClickTextTool : AiTool {
    override val name = "screen_click_text"
    override val description = "在当前屏幕上按文本查找并点击对应元素。参数 {text: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"text":{"type":"string","description":"要点击的按钮/元素的可见文本"}},"required":["text"]}
    """.trimIndent()
    override fun systemPrompt() = "screen_click_text: 按可见文本点击屏幕元素（需用户确认）。参数 {text: string}。"
    override val requiresConfirmation = true

    override suspend fun execute(argumentsJson: String): String {
        val args = a11yArgs(argumentsJson) ?: return a11yError("Invalid arguments")
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isBlank()) return a11yError("text 不能为空")
        return withService { service ->
            if (service.findAndClick(text)) a11yOk("clicked" to text)
            else a11yError("屏幕上未找到可点击的\"$text\"")
        }
    }
}

/** 注册 AI 控制手机工具集 */
fun registerAccessibilityTools() {
    ToolRegistry.register(AccessibilityStatusTool())
    ToolRegistry.register(ScreenReadTool())
    ToolRegistry.register(PressBackTool())
    ToolRegistry.register(GoHomeTool())
    ToolRegistry.register(ScreenTapTool())
    ToolRegistry.register(ScreenSwipeTool())
    ToolRegistry.register(ScreenClickTextTool())
}
