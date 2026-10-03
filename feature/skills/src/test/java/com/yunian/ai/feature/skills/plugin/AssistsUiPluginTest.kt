package com.yunian.ai.feature.skills.plugin

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.skills.testing.FakeAccessibilityBridge
import com.yunian.ai.feature.skills.testing.FakePluginContext
import com.yunian.ai.feature.skills.tools.ScreenDumpUiTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ui.assists`（无障碍自动化）插件契约测试。
 *
 * 这是本轮的**验收面**：把「迁移替换」的对外契约钉死在 JVM 单测里——工具名 / 参数 schema /
 * 成功与失败的 JSON 文案 / requiresConfirmation / appLocalOnly / 卸载即摘工具。
 *
 * 为什么能跑在 JVM：工具层只依赖 [com.yunian.ai.feature.skills.accessibility.AccessibilityBridge]
 * 这个纯 Kotlin 接缝（零 Android 依赖、零 assists 依赖），所以注入
 * [FakeAccessibilityBridge] 即可覆盖全部路径。
 *
 * 关于真注册表：`ToolRegistry` 是 core:domain 的全局 object（纯 Kotlin，JVM 安全），
 * 因此本测试**用真的**——它正是「工具到底有没有进模型可见的工具列表」的验收对象；
 * 每个用例在 [tearDown] 里逆序执行 effects + 注销本插件注册的 9 个名字，绝不污染其他测试。
 *
 * 宿主一致性校验：`PluginHostImpl.register` 会 fail-closed 拒绝 manifest 与自描述
 * 不一致的插件，但其 `manifestMatchesSelfDescription` 是 `internal`
 * （跨模块在 JVM 单测里不可见），故本测试把同一组字段逐项对齐断言。
 */
class AssistsUiPluginTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val expectedToolNames = listOf(
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

    /**
     * **做就绪门控**的 8 个工具（显式列出全部名字，不用隐式过滤，改动立即暴露）。
     *
     * [AccessibilityStatusTool] 被排除：它是 9 个里**唯一不做就绪门控**的工具——迁移前原文
     * 就直接读 `isReady()` 并返回 `{"ok":true,"enabled":false,"hint":"未开启，…"}`，
     * 因为「如实报告未开启状态」正是它的职责；其余 8 个是执行类，未就绪时必须给开启指引。
     */
    private val gatedToolNames = listOf(
        "screen_read",
        "press_back",
        "go_home",
        "screen_tap",
        "screen_swipe",
        "screen_click_text",
        "screen_input_text",
        "screen_dump_ui",
    )

    private val pluginId = "ui.assists"

    /** 字面双引号字符：用于拼装「文案里含转义引号」的期望值，避免源码里叠反斜杠。 */
    private val QChar: String = "\""

    /** 服务未就绪时**其余 8 个**（做门控的）工具必须逐字返回的引导文案。 */
    private val serviceDisabledHint =
        "无障碍服务未开启：请在系统设置 → 无障碍 → 已下载的应用 → 「予念助手控制服务」中开启后重试"

    private lateinit var bridge: FakeAccessibilityBridge
    private lateinit var ctx: FakePluginContext

    @Before
    fun setUp() {
        bridge = FakeAccessibilityBridge()
        ctx = FakePluginContext(mapOf(PluginServices.TOOLS to ToolRegistry))
        AssistsUiPlugin(bridge).setup(ctx)
    }

    @After
    fun tearDown() {
        ctx.effects.asReversed().forEach { (_, disposer) -> disposer() }
        expectedToolNames.forEach { ToolRegistry.unregister(it) }
        ToolRegistry.invalidateAvailabilityCache()
    }

    // ---------- 装配：9 个工具进注册表 ----------

    @Test
    fun setup_registersExactlyNineTools_inDeclaredOrder() {
        assertEquals(expectedToolNames, AssistsUiPlugin.TOOL_NAMES)
        assertEquals(
            "setup 后注册表里恰好出现这 9 个名字，且与 TOOL_NAMES 一致",
            expectedToolNames,
            expectedToolNames.map { ToolRegistry.get(it)?.name },
        )
        assertEquals(
            "插件类与 companion 的 id / 展示名必须一致",
            pluginId + "|无障碍自动化",
            AssistsUiPlugin(bridge).id + "|" + AssistsUiPlugin.NAME,
        )
    }

    @Test
    fun pluginSelfDescription_matchesManifestContract() {
        val plugin = AssistsUiPlugin(bridge)
        assertEquals(pluginId, plugin.id)
        assertEquals("无障碍自动化", plugin.name)
        assertEquals(PluginKind.TOOL, plugin.kind)
        assertEquals(setOf(PluginServices.TOOLS), plugin.requires)
        assertEquals(null, plugin.configSchema)
        assertEquals("1.0.0", plugin.version)

        // 与 PluginHostImpl.register 的 fail-closed 校验逐项对齐：
        // manifest.id/name/kind/requires/configSchema 必须与自描述一致，且 requires 无重复项。
        val manifest: PluginManifest = plugin.manifest
        assertEquals(plugin.id, manifest.id)
        assertEquals(plugin.name, manifest.name)
        assertEquals(plugin.kind, manifest.kind)
        assertEquals(plugin.requires, manifest.requires.toSet())
        assertEquals(
            "manifest.requires 不得有重复项（宿主会拒绝注册）",
            manifest.requires.size,
            manifest.requires.toSet().size,
        )
        assertEquals(plugin.requires.sorted(), manifest.requires)
        assertEquals(plugin.configSchema, manifest.configSchema)
    }

    /**
     * 「插件设置」页的一句话说明（[com.yunian.ai.domain.plugin.LianYuPlugin.description]）。
     *
     * 两个钉子：
     * 1. **已落地**——空串 = 未声明说明，页面行副标题会回落成插件 id（见 LianYuPlugin.description
     *    的约定），因此「说明非空白」本身必须可断言，而不是靠人肉记得写；
     * 2. **与清单逐字一致**——[com.yunian.ai.agent.plugin.PluginHostImpl.manifestMatchesSelfDescription]
     *    把 description 纳入 fail-closed 校验（不一致即拒绝注册），本用例在 JVM 侧独立复核同一约束。
     */
    @Test
    fun pluginDescription_isDeclaredAndMatchesManifest() {
        val plugin = AssistsUiPlugin(bridge)

        assertTrue(
            "ui.assists 的一句话说明不得为空（空串 = 未声明，设置页会退化成显示插件 id）",
            plugin.description.isNotBlank(),
        )
        assertTrue(
            "manifest.description 同样不得为空",
            plugin.manifest.description.isNotBlank(),
        )
        assertEquals(
            "manifest.description 必须与插件自描述逐字一致，否则宿主 fail-closed 拒绝注册",
            plugin.description,
            plugin.manifest.description,
        )
    }

    // ---------- 安全标记 ----------

    @Test
    fun appLocalOnlyAndRequiresConfirmation_matchSecurityRuling() {
        val appLocal = mapOf(
            "accessibility_status" to false,
            "screen_read" to false,
            "press_back" to false,
            "go_home" to false,
            "screen_tap" to false,
            "screen_swipe" to false,
            "screen_click_text" to false,
            "screen_input_text" to true,
            "screen_dump_ui" to true,
        )
        val confirm = mapOf(
            "accessibility_status" to false,
            "screen_read" to false,
            "press_back" to false,
            "go_home" to false,
            "screen_tap" to true,
            "screen_swipe" to true,
            "screen_click_text" to true,
            "screen_input_text" to true,
            "screen_dump_ui" to false,
        )
        appLocal.forEach { (name, expected) ->
            assertEquals("appLocalOnly 不符：$name", expected, tool(name).appLocalOnly)
        }
        confirm.forEach { (name, expected) ->
            assertEquals("requiresConfirmation 不符：$name", expected, tool(name).requiresConfirmation)
        }
    }

    @Test
    fun appLocalOnlyTools_stayInvisibleOnExternalChannel() {
        // 注册表侧第一道闸：外部桥接会话（includeAppLocal = false）看不到新增的 2 个敏感工具。
        val defaultVisible = ToolRegistry.all().map { it.name }
        assertFalse("screen_input_text 不得进入外部会话工具列表", "screen_input_text" in defaultVisible)
        assertFalse("screen_dump_ui 不得进入外部会话工具列表", "screen_dump_ui" in defaultVisible)

        val defs = ToolRegistry.toolDefinitionsJson()
        assertFalse(defs.contains("screen_input_text"))
        assertFalse(defs.contains("screen_dump_ui"))

        // 本机会话（includeAppLocal = true）才可见。
        val appLocalVisible = ToolRegistry.all(includeAppLocal = true).map { it.name }
        assertTrue("screen_input_text 在本机会话必须可见", "screen_input_text" in appLocalVisible)
        assertTrue("screen_dump_ui 在本机会话必须可见", "screen_dump_ui" in appLocalVisible)
        assertTrue(ToolRegistry.toolDefinitionsJson(includeAppLocal = true).contains("screen_dump_ui"))
    }

    // ---------- 参数 schema ----------

    @Test
    fun everyTool_schemaIsObjectJson_withFrozenRequiredFields() {
        val expectedRequired = mapOf(
            "accessibility_status" to emptyList<String>(),
            "screen_read" to emptyList<String>(),
            "press_back" to emptyList<String>(),
            "go_home" to emptyList<String>(),
            "screen_tap" to listOf("x", "y"),
            "screen_swipe" to listOf("x1", "y1", "x2", "y2"),
            "screen_click_text" to listOf("text"),
            "screen_input_text" to listOf("text"),
            "screen_dump_ui" to emptyList<String>(),
        )
        expectedToolNames.forEach { name ->
            val schema = json.parseToJsonElement(tool(name).parametersJsonSchema).jsonObject
            assertEquals("schema 根 type 必须是 object：$name", "object", schema["type"]?.jsonPrimitive?.content)
            assertTrue("schema 必须有 properties 对象：$name", schema["properties"] is JsonObject)
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertEquals("required 不符：$name", expectedRequired.getValue(name), required)
        }

        val expectedProperties = mapOf(
            "screen_tap" to setOf("x", "y"),
            "screen_swipe" to setOf("x1", "y1", "x2", "y2", "durationMs"),
            "screen_click_text" to setOf("text"),
            "screen_input_text" to setOf("text"),
        )
        expectedProperties.forEach { (name, expected) ->
            val schema = json.parseToJsonElement(tool(name).parametersJsonSchema).jsonObject
            assertEquals("properties 不符：$name", expected, schema.getValue("properties").jsonObject.keys)
        }
    }

    // ---------- 参数非法：bridge 不得被调用 ----------

    @Test
    fun invalidTapArguments_failBeforeTouchingBridge() {
        assertEquals(missingArgError("x 缺失"), runTool("screen_tap", "{\"y\":10}"))
        assertEquals(missingArgError("y 缺失"), runTool("screen_tap", "{\"x\":1}"))
        assertEquals(missingArgError("x 缺失"), runTool("screen_tap", "{\"x\":\"abc\",\"y\":10}"))
        assertEquals(missingArgError("Invalid arguments"), runTool("screen_tap", "not json"))
        assertTrue("参数非法时绝不能触碰无障碍接缝，实际调用：${bridge.calls}", bridge.calls.isEmpty())
    }

    @Test
    fun invalidSwipeArguments_failBeforeTouchingBridge() {
        assertEquals(missingArgError("x1 缺失"), runTool("screen_swipe", "{\"y1\":1,\"x2\":2,\"y2\":3}"))
        assertEquals(missingArgError("y1 缺失"), runTool("screen_swipe", "{\"x1\":1,\"x2\":2,\"y2\":3}"))
        assertEquals(missingArgError("x2 缺失"), runTool("screen_swipe", "{\"x1\":1,\"y1\":2,\"y2\":3}"))
        assertEquals(missingArgError("y2 缺失"), runTool("screen_swipe", "{\"x1\":1,\"y1\":2,\"x2\":3}"))
        assertTrue("参数非法时绝不能触碰无障碍接缝，实际调用：${bridge.calls}", bridge.calls.isEmpty())
    }

    @Test
    fun blankTextArguments_failBeforeTouchingBridge() {
        assertEquals(missingArgError("text 不能为空"), runTool("screen_click_text", "{\"text\":\"   \"}"))
        assertEquals(missingArgError("text 不能为空"), runTool("screen_input_text", "{\"text\":\"\"}"))
        assertEquals(missingArgError("text 不能为空"), runTool("screen_input_text", "{\"other\":\"x\"}"))
        assertTrue("文本为空时绝不能触碰无障碍接缝，实际调用：${bridge.calls}", bridge.calls.isEmpty())
    }

    // ---------- 服务未开启：其余 8 个工具的引导文案（回归锁） ----------

    @Test
    fun whenServiceDisabled_theOtherEightToolsReturnVerbatimGuide() {
        bridge.ready = false
        val expected = missingArgError(serviceDisabledHint)
        gatedToolNames.forEach { name ->
            // 传**合法**参数：参数校验先于服务就绪校验（与迁移前逐字一致），
            // 传空参数只会撞上参数错误，测不到引导文案。
            assertEquals(
                "服务未开启时的引导文案不符（回归锁）：$name；" +
                    "accessibility_status 被排除在门控之外——它是查状态工具，未开启时返回 " +
                    "{\"ok\":true,\"enabled\":false}，不返回引导错误",
                expected,
                runTool(name, validArguments(name)),
            )
        }
    }

    @Test
    fun whenServiceDisabled_accessibilityStatusReportsDisabledInsteadOfGuide() {
        bridge.ready = false
        // 与迁移前 git HEAD 原文**逐字一致**（字段与顺序：ok → enabled → hint）：
        // ok 是 true ——「服务没开」对查状态工具是**成功**的结果，不是失败。
        val expected = "{\"ok\":true,\"enabled\":false,\"hint\":" +
            QChar + jsonEscape("未开启，需用户在系统设置中授权") + QChar + "}"
        assertEquals(
            "accessibility_status 必须如实报告未开启（不得退化成 ok=false 的引导错误，" +
                "更不得丢失 enabled / hint 字段）",
            expected,
            runTool("accessibility_status", "{}"),
        )
        assertTrue("必须真的问过接缝；实际：${bridge.calls}", bridge.calls.contains("isReady"))
    }

    @Test
    fun missingArguments_areReportedBeforeServiceReadiness() {
        bridge.ready = false
        assertEquals(missingArgError("x 缺失"), runTool("screen_tap", "{}"))
        assertEquals(missingArgError("text 不能为空"), runTool("screen_input_text", "{}"))
        assertTrue("参数非法时连 isReady 都不该问；实际：${bridge.calls}", bridge.calls.isEmpty())
    }

    // ---------- 成功路径 ----------

    @Test
    fun successPaths_returnFrozenJsonContract() {
        bridge.screenText = "首页\n搜索" // 真实换行：JSON 输出必须是字面 \\n
        assertEquals(
            "{\"ok\":true,\"enabled\":true,\"hint\":\"手机控制通道可用\"}",
            runTool("accessibility_status", "{}"),
        )
        assertEquals(
            successJson("screen", "首页\n搜索"),
            runTool("screen_read", "{}"),
        )
        assertEquals("{\"ok\":true,\"action\":\"back\"}", runTool("press_back", "{}"))
        assertEquals("{\"ok\":true,\"action\":\"home\"}", runTool("go_home", "{}"))
        assertEquals("{\"ok\":true,\"tapped\":\"12,34\"}", runTool("screen_tap", "{\"x\":12,\"y\":34}"))
        assertEquals(
            "{\"ok\":true,\"swiped\":\"(1,2)->(3,4)\"}",
            runTool("screen_swipe", "{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4,\"durationMs\":500}"),
        )
        assertEquals(
            "{\"ok\":true,\"clicked\":\"确定\"}",
            runTool("screen_click_text", "{\"text\":\" 确定 \"}"),
        )
        assertEquals(
            "{\"ok\":true,\"input\":\"你好\"}",
            runTool("screen_input_text", "{\"text\":\"你好\"}"),
        )
    }

    @Test
    fun screenTapAndSwipe_passArgumentsToBridge() {
        runTool("screen_tap", "{\"x\":12,\"y\":34}")
        assertTrue(
            "坐标必须原样传给接缝；实际：${bridge.calls}",
            bridge.calls.contains("tap(x=12.0,y=34.0)"),
        )
        bridge.calls.clear()
        runTool("screen_swipe", "{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4}")
        assertTrue(
            "未显式给 durationMs 时必须回落到默认 300ms；实际：${bridge.calls}",
            bridge.calls.contains("swipe(x1=1.0,y1=2.0,x2=3.0,y2=4.0,durationMs=300)"),
        )
    }

    // ---------- 失败路径的文案 ----------

    @Test
    fun bridgeFailure_returnsFrozenErrorText() {
        bridge.screenText = "   "
        assertEquals(
            missingArgError("未能读取屏幕内容（当前界面可能不支持读取）"),
            runTool("screen_read", "{}"),
        )

        bridge.backResult = false
        assertEquals(missingArgError("返回失败"), runTool("press_back", "{}"))

        bridge.homeResult = false
        assertEquals(missingArgError("返回主屏失败"), runTool("go_home", "{}"))

        bridge.tapResult = false
        assertEquals(missingArgError("点击失败"), runTool("screen_tap", "{\"x\":1,\"y\":2}"))

        bridge.swipeResult = false
        assertEquals(
            missingArgError("滑动失败"),
            runTool("screen_swipe", "{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4}"),
        )

        bridge.inputTextResult = false
        assertEquals(
            missingArgError("未找到可编辑的输入框（请先点击输入框再输入）"),
            runTool("screen_input_text", "{\"text\":\"你好\"}"),
        )
    }

    @Test
    fun screenClickText_bridgeFalse_mapsToElementNotFoundText() {
        bridge.clickTextResult = false
        // 期望文案里的引号是**字面转义序列**（生产把文本插进 JSON 字符串时转义了引号）：
        // 用运行时拼装而不是源码字面量，避免测试自身被反斜杠转义绕晕。
        val quoted = QChar + "确定" + QChar
        val expected = missingArgError("屏幕上未找到可点击的$quoted")
        assertEquals(expected, runTool("screen_click_text", "{\"text\":\"确定\"}"))
        assertTrue("必须真的问过接缝；实际：${bridge.calls}", bridge.calls.contains("clickText(text=确定)"))
    }

    // ---------- screen_dump_ui ----------

    @Test
    fun screenDumpUi_emptyTree_isExplicitFailure() {
        bridge.nodeTreeJson = ""
        assertEquals(
            missingArgError("未能读取界面节点树（当前界面可能不支持读取）"),
            runTool("screen_dump_ui", "{}"),
        )
    }

    @Test
    fun screenDumpUi_smallTree_isNotTruncated() {
        bridge.nodeTreeJson = "{\"packageName\":\"com.android.settings\",\"text\":\"设置\"}"
        val fields = keyValues(runTool("screen_dump_ui", "{}"))
        assertEquals("节点树 JSON 原样进 ui 字段", bridge.nodeTreeJson, fields["ui"])
        assertEquals("truncated 必须是字面 false", "false", fields["truncated"])
        assertEquals("ok", "true", fields["ok"])
    }

    @Test
    fun screenDumpUi_largeTree_isTruncatedAt12000Chars() {
        val big = "a".repeat(ScreenDumpUiTool.MAX_UI_CHARS + 1)
        bridge.nodeTreeJson = big
        val fields = keyValues(runTool("screen_dump_ui", "{}"))
        assertEquals("truncated=true", "true", fields["truncated"])
        assertEquals(ScreenDumpUiTool.MAX_UI_CHARS, fields.getValue("ui").length)
        assertEquals(big.take(ScreenDumpUiTool.MAX_UI_CHARS), fields.getValue("ui"))
    }

    // ---------- 卸载不留鸡毛 ----------

    @Test
    fun setup_registersOneUnregisterEffectPerTool_inRegistrationOrder() {
        assertEquals(expectedToolNames.size, ctx.effects.size)
        assertEquals(expectedToolNames.map { "unregister:$it" }, ctx.effects.map { it.first })
    }

    @Test
    fun disposingEffectsInReverseOrder_removesAllNineTools() {
        assertTrue(expectedToolNames.all { ToolRegistry.get(it) != null })

        ctx.effects.asReversed().forEach { (_, disposer) -> disposer() }

        val leftover = expectedToolNames.filter { ToolRegistry.get(it) != null }
        assertEquals("逆序执行 effects 后注册表必须干净，残留：$leftover", emptyList<String>(), leftover)
    }

    // ---------- helpers ----------

    private fun tool(name: String): AiTool =
        requireNotNull(ToolRegistry.get(name)) { "工具未注册：$name" }

    /** 每个工具的**合法**参数（用于把调用推进到「服务就绪校验」之后的路径）。 */
    private fun validArguments(name: String): String = when (name) {
        "screen_tap" -> "{\"x\":1,\"y\":2}"
        "screen_swipe" -> "{\"x1\":1,\"y1\":2,\"x2\":3,\"y2\":4}"
        "screen_click_text" -> "{\"text\":\"确定\"}"
        "screen_input_text" -> "{\"text\":\"你好\"}"
        else -> "{}"
    }

    private fun runTool(name: String, argumentsJson: String): String =
        runBlocking { tool(name).execute(argumentsJson) }

    /**
     * 失败契约的字面形态 `{"ok":false,"error":"<message>"}`。
     *
     * 这里**逐字**复刻生产的输出形态（同一套 JSON 转义语义）：工具返回的 error 文案里若含
     * \`"\` 或换行，被转义的是**字符串内容**而不是断言本身——所以不能手写期望字面量。
     */
    private fun missingArgError(message: String): String =
        "{\"ok\":false,\"error\":\"" + jsonEscape(message) + "\"}"

    /** 成功契约的**单字段**工具（screen_read / screen_dump_ui）的字面形态。 */
    private fun successJson(key: String, value: String): String =
        "{\"ok\":true,\"" + key + "\":\"" + jsonEscape(value) + "\"}"

    /** 与 kotlinx.serialization 输出一致的字符串转义（引用/反斜杠/换行/制表/回车）。 */
    private fun jsonEscape(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    /** 把工具返回的**扁平** JSON 读成 字段名→原始字面量（字符串字段取去引号后的内容）。 */
    private fun keyValues(resultJson: String): Map<String, String> =
        json.parseToJsonElement(resultJson).jsonObject.entries
            .filter { it.value is kotlinx.serialization.json.JsonPrimitive }
            .associate { (k, v) -> k to v.jsonPrimitive.content }
}
