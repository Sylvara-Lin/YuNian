package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.BlueprintPluginRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginBlueprintParser] 的**纯 JVM**契约测试（债务 D4 / B2）。
 *
 * ## 为什么这个测试值得存在
 * 解析器是「默认蓝图 → 7 个插件装载」链路的**第一个环节**，但它此前零测试：
 * `app/src/main/.../YuNianApplication.kt` 的 `loadDefaultBlueprint` 读
 * `assets/blueprints/default.json` 后直接调它，一旦它抛异常，**全部插件静默不装载**
 * （调用点被 `runCatching` 包着，只剩一条日志）。本类把它的行为钉死：
 * 合法输入解析出什么、非法 / 缺字段 / 空内容时是抛异常还是返回默认值。
 *
 * ## JSON 库（重要前提，已实测）
 * 实现用的是 **`org.json`**（`JSONObject` / `JSONArray`），不是 `kotlinx.serialization`。
 * Android 自带的 `org.json` 在纯 JVM 单测里是抛 `Stub!` 的空壳，因此
 * `core/agent/build.gradle.kts` 早已用 `testImplementation(libs.org.json)` 挂上真实实现
 * （版本目录 `org-json`）——本测试因此**不需要**任何 Robolectric / `returnDefaultValues`，
 * 也不需要改一行 `build.gradle.kts`。见该文件第 40-42 行的既有注释。
 *
 * ## 行为速查（本类钉住的语义）
 * | 输入 | 行为 |
 * |------|------|
 * | 顶层不是 JSON 对象（空串 / 空白 / 数组 / `null` 字面量 / 顶层字符串） | 抛 [IllegalArgumentException]，消息前缀 `蓝图 JSON 非法` |
 * | 语法错误（缺冒号 / 缺括号等） | 同上 |
 * | 缺 `id` | `id = "unnamed"` |
 * | 缺 `name` | `name = id` |
 * | 缺 `plugins` / `patches` / `inserts` | 当作空数组，不抛 |
 * | `plugins[i]` 不是对象 / 缺 `id`（含 `id` 为空白串） | 抛 [IllegalArgumentException]，消息含下标 |
 * | `enabled` 缺失 | `true`（基准列表缺省启用） |
 * | `config` 缺失或不是对象 | `configJson = null` |
 */
class PluginBlueprintParserTest {

    // ── 1. 合法输入：字段与顺序 ──

    @Test
    fun `合法 JSON 解析出 id name 与按顺序的插件列表`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "id": "default",
              "name": "默认蓝图",
              "plugins": [
                { "id": "automation.core" },
                { "id": "channel.qqbot" },
                { "id": "message.send" }
              ]
            }
            """.trimIndent()
        )

        assertEquals("default", blueprint.id)
        assertEquals("默认蓝图", blueprint.name)
        // 顺序即装载顺序（sticker 引擎依赖此顺序），必须逐字保留而不是按 id 排序
        assertEquals(
            listOf("automation.core", "channel.qqbot", "message.send"),
            blueprint.plugins.map { it.id },
        )
    }

    @Test
    fun `缺省 enabled 为 true 缺省 config 为 null`() {
        val blueprint = PluginBlueprintParser.parse(
            """{"id":"x","plugins":[{"id":"a"}]}"""
        )

        val ref = blueprint.plugins.single()
        assertTrue("enabled 缺省必须为 true（基准列表缺省启用）", ref.enabled)
        assertNull("config 缺省必须是 null（= 插件默认行为）", ref.configJson)
    }

    @Test
    fun `enabled 与 config 显式给出时原样解析`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {"id":"x","plugins":[{"id":"a","enabled":false,"config":{"k":1,"nested":{"s":"v"}}}]}
            """.trimIndent()
        )

        val ref = blueprint.plugins.single()
        assertEquals(false, ref.enabled)
        val config = JSONObject(ref.configJson!!)
        assertEquals(1, config.getInt("k"))
        assertEquals("v", config.getJSONObject("nested").getString("s"))
    }

    @Test
    fun `真实默认蓝图资产能被解析且产出 7 个插件 id`() {
        // 与 app/src/main/assets/blueprints/default.json **当前内容一致**（含 comment 键）。
        // 本测试不读文件系统（避免依赖 :app 的 assets 路径），断言的是
        // 「这 7 个 id 必须解析得出来」——正是 B2 里会静默消失的那 7 个。
        val blueprint = PluginBlueprintParser.parse(
            """
            {
                "id": "default",
                "name": "默认蓝图",
                "comment": "对应 cordis.yml 组合语义：plugins 基准列表 + patches 覆盖 + inserts 追加。",
                "plugins": [
                    { "id": "skill.builtin_chat_protocol" },
                    { "id": "sticker.preference" },
                    { "id": "automation.core" },
                    { "id": "channel.qqbot" },
                    { "id": "channel.wechat" },
                    { "id": "message.send" },
                    { "id": "ui.assists" }
                ],
                "patches": [],
                "inserts": []
            }
            """.trimIndent()
        )

        assertEquals("default", blueprint.id)
        assertEquals("默认蓝图", blueprint.name)
        assertEquals(
            listOf(
                "skill.builtin_chat_protocol",
                "sticker.preference",
                "automation.core",
                "channel.qqbot",
                "channel.wechat",
                "message.send",
                "ui.assists",
            ),
            blueprint.plugins.map { it.id },
        )
        assertTrue("默认蓝图里 7 个插件全部启用", blueprint.plugins.all { it.enabled })
    }

    @Test
    fun `未知顶层键被忽略`() {
        val blueprint = PluginBlueprintParser.parse(
            """{"id":"x","comment":"任意注释","extra":[1,2],"plugins":[{"id":"a"}]}"""
        )
        assertEquals(listOf("a"), blueprint.plugins.map { it.id })
    }

    // ── 2. 缺 id / name 的兜底 ──

    @Test
    fun `缺 id 与 name 时兜底为 unnamed`() {
        val blueprint = PluginBlueprintParser.parse("""{"plugins":[]}""")
        assertEquals("unnamed", blueprint.id)
        assertEquals("unnamed", blueprint.name)
    }

    @Test
    fun `id 为空白串等同缺失 name 缺省跟随 id`() {
        val blueprint = PluginBlueprintParser.parse("""{"id":"   ","plugins":[]}""")
        assertEquals("unnamed", blueprint.id)
        assertEquals("unnamed", blueprint.name)
    }

    @Test
    fun `name 缺省时取 id 而不是 unnamed`() {
        val blueprint = PluginBlueprintParser.parse("""{"id":"prod","plugins":[]}""")
        assertEquals("prod", blueprint.id)
        assertEquals("prod", blueprint.name)
    }

    // ── 3. 缺数组 = 空数组（不抛） ──

    @Test
    fun `plugins patches inserts 全部缺失时得到空列表`() {
        val blueprint = PluginBlueprintParser.parse("""{"id":"x"}""")
        assertEquals("x", blueprint.id)
        assertTrue("缺 plugins 必须是空列表而不是异常", blueprint.plugins.isEmpty())
    }

    @Test
    fun `空对象与空数组都不抛`() {
        assertEquals(0, PluginBlueprintParser.parse("{}").plugins.size)
        assertEquals(0, PluginBlueprintParser.parse("""{"plugins":[]}""").plugins.size)
        assertEquals(0, PluginBlueprintParser.parse("""{"plugins":[],"patches":[],"inserts":[]}""").plugins.size)
    }

    // ── 4. 非法 JSON / 非法条目：明确抛 IllegalArgumentException ──

    @Test
    fun `空内容抛 IllegalArgumentException 而不是静默返回空蓝图`() {
        // 这是 B2 的关键分支：资产为空 / 读成空串时必须**抛**，
        // 这样调用方才能记录到一次失败（而不是「成功装载 0 个」被当成正常）。
        for (bad in listOf("", "   ", "\n\t ")) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                PluginBlueprintParser.parse(bad)
            }
            assertTrue(
                "异常消息必须带「蓝图 JSON 非法」前缀以便定位，实际=" + e.message,
                e.message!!.startsWith("蓝图 JSON 非法"),
            )
        }
    }

    @Test
    fun `语法错误抛 IllegalArgumentException`() {
        // 只列**真的**解析不了的内容（已用 org.json 20231013 实测，不是推断）。
        // 注意：单引号键与尾随逗号**不在此列**——见下面那条「宽松方言」测试。
        for (bad in listOf("{not json", """{"id":}""", """{"id" "x"}""", "{,}")) {
            assertThrows(
                "必须被拒绝: " + bad,
                IllegalArgumentException::class.java,
            ) { PluginBlueprintParser.parse(bad) }
        }
    }

    @Test
    fun `JSON 库的宽松方言被接受：单引号键与尾随逗号不抛`() {
        // ⚠️ 实测事实（不是推断）：本仓库挂的 org.json 20231013 接受**非标准 JSON**：
        //   - 单引号键：  {'id':'x'}      → 解析成功，id = "x"；
        //   - 尾随逗号：  {"id":"x",}     → 解析成功，id = "x"；
        //   - 闭合后还有内容："{}\n尾随内容" → 解析成功（'{' 之后的对象被解析，尾部被忽略）。
        // 这条测试不是「认可」这种写法，而是把**真实容错边界**钉住：
        // 若有人把 org.json 换成严格解析器，这里会红，提醒他蓝图资产的容错面变窄了。
        // 不要因为「这看起来不合法」就删掉它——它记录的是实现真实行为。
        assertEquals("x", PluginBlueprintParser.parse("{'id':'x'}").id)
        assertEquals("x", PluginBlueprintParser.parse("""{"id":"x",}""").id)
        assertEquals("x", PluginBlueprintParser.parse("{\"id\":\"x\"}\n尾随内容").id)
    }

    @Test
    fun `顶层不是对象一律抛 IllegalArgumentException`() {
        // org.json 的 JSONObject(String) 只接受 '{' 开头；数组 / null 字面量 / 顶层字符串 /
        // 数字 / 布尔都抛 JSONException，被实现统一包成 IllegalArgumentException。
        for (bad in listOf("[1,2,3]", "null", "\"hi\"", "123", "true")) {
            assertThrows(
                "顶层 " + bad + " 必须被拒绝",
                IllegalArgumentException::class.java,
            ) { PluginBlueprintParser.parse(bad) }
        }
    }

    @Test
    fun `plugins 元素不是对象时抛异常且消息带下标`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PluginBlueprintParser.parse("""{"plugins":[1,2]}""")
        }
        assertTrue(
            "消息必须定位到 plugins[0]，实际=" + e.message,
            e.message!!.contains("plugins[0]"),
        )
    }

    @Test
    fun `plugins 元素缺 id 时抛异常且消息带下标`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PluginBlueprintParser.parse("""{"plugins":[{"id":"a"},{"enabled":true}]}""")
        }
        assertTrue(
            "消息必须定位到 plugins[1]，实际=" + e.message,
            e.message!!.contains("plugins[1]"),
        )
    }

    @Test
    fun `plugins 元素 id 为空白串等同缺失`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PluginBlueprintParser.parse("""{"plugins":[{"id":"  "}]}""")
        }
        assertTrue(e.message!!.contains("plugins[0]"))
    }

    @Test
    fun `config 不是对象时视为无配置而不是抛异常`() {
        // optJSONObject 对非对象返回 null —— 记录既有行为，防止无意间改变容错边界。
        val blueprint = PluginBlueprintParser.parse(
            """{"plugins":[{"id":"a","config":"not-an-object"},{"id":"b","config":[1,2]}]}"""
        )
        assertEquals(2, blueprint.plugins.size)
        assertNull(blueprint.plugins[0].configJson)
        assertNull(blueprint.plugins[1].configJson)
    }

    // ── 5. patches：按 id 覆盖 ──

    @Test
    fun `patch 覆盖既有条目的 enabled 并保留原顺序`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "plugins": [{"id":"a"},{"id":"b"},{"id":"c"}],
              "patches": [{"id":"b","enabled":false}]
            }
            """.trimIndent()
        )

        assertEquals("patch 不得改变列表顺序", listOf("a", "b", "c"), blueprint.plugins.map { it.id })
        assertEquals(true, blueprint.plugins[0].enabled)
        assertEquals("patch 的 enabled 必须覆盖基准", false, blueprint.plugins[1].enabled)
        assertEquals(true, blueprint.plugins[2].enabled)
    }

    @Test
    fun `patch 不带 enabled 时按 true 覆盖基准的 false`() {
        // 实现注释明写「patch 显式携带 enabled（默认 true 视为覆盖）」——
        // 即 optBoolean 的缺省值 true 会**真的覆盖**基准的 false。这是刻意语义，钉住它。
        val blueprint = PluginBlueprintParser.parse(
            """{"plugins":[{"id":"a","enabled":false}],"patches":[{"id":"a"}]}"""
        )
        assertEquals(
            "patch 缺省 enabled=true 视为覆盖（cordis patch 语义）",
            true,
            blueprint.plugins.single().enabled,
        )
    }

    @Test
    fun `patch 的 config 与基准 config 深合并且 patch 侧胜出`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "plugins": [{"id":"a","config":{"keep":1,"over":1,"nested":{"x":1,"y":2}}}],
              "patches": [{"id":"a","config":{"over":2,"added":3,"nested":{"y":9,"z":8}}}]
            }
            """.trimIndent()
        )

        val config = JSONObject(blueprint.plugins.single().configJson!!)
        assertEquals("基准独有的键必须保留", 1, config.getInt("keep"))
        assertEquals("patch 同名键必须胜出", 2, config.getInt("over"))
        assertEquals("patch 新键必须加入", 3, config.getInt("added"))
        val nested = config.getJSONObject("nested")
        assertEquals("嵌套层同样深合并：基准独有保留", 1, nested.getInt("x"))
        assertEquals("嵌套层 patch 胜出", 9, nested.getInt("y"))
        assertEquals("嵌套层 patch 新键加入", 8, nested.getInt("z"))
    }

    @Test
    fun `patch 引用不存在的插件时按 insert 语义追加到尾部`() {
        val blueprint = PluginBlueprintParser.parse(
            """{"plugins":[{"id":"a"}],"patches":[{"id":"late"}]}"""
        )
        assertEquals(listOf("a", "late"), blueprint.plugins.map { it.id })
    }

    // ── 6. inserts：追加到尾部 ──

    @Test
    fun `inserts 追加到列表尾部并保持声明顺序`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "plugins": [{"id":"a"}],
              "inserts": [{"id":"i1"},{"id":"i2"}]
            }
            """.trimIndent()
        )
        assertEquals(listOf("a", "i1", "i2"), blueprint.plugins.map { it.id })
    }

    @Test
    fun `insert 命中既有 id 时原位覆盖而不是追加第二份`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "plugins": [{"id":"a","config":{"keep":1}},{"id":"b"}],
              "inserts": [{"id":"a","enabled":false,"config":{"over":2}}]
            }
            """.trimIndent()
        )

        assertEquals("不得出现重复 id", listOf("a", "b"), blueprint.plugins.map { it.id })
        val a = blueprint.plugins[0]
        assertEquals(false, a.enabled)
        val config = JSONObject(a.configJson!!)
        assertEquals(1, config.getInt("keep"))
        assertEquals(2, config.getInt("over"))
    }

    @Test
    fun `patches 与 inserts 组合：覆盖在追加之前`() {
        val blueprint = PluginBlueprintParser.parse(
            """
            {
              "plugins": [{"id":"a"}],
              "patches": [{"id":"a","enabled":false}],
              "inserts": [{"id":"z"}]
            }
            """.trimIndent()
        )
        assertEquals(listOf("a", "z"), blueprint.plugins.map { it.id })
        assertEquals(false, blueprint.plugins[0].enabled)
    }

    // ── 7. 重复 id：按 id 去重（后者胜出，位置取首次出现） ──

    @Test
    fun `plugins 内重复 id 去重且保留首次出现的位置`() {
        val blueprint = PluginBlueprintParser.parse(
            """{"plugins":[{"id":"a","enabled":false},{"id":"b"},{"id":"a","enabled":true}]}"""
        )

        assertEquals("同 id 只保留一条", listOf("a", "b"), blueprint.plugins.map { it.id })
        assertEquals("后出现的同名条目胜出", true, blueprint.plugins[0].enabled)
    }

    // ── 8. deepMergeJson 的边界（internal，同模块可见） ──

    @Test
    fun `deepMergeJson 一侧为 null 时返回另一侧`() {
        assertNull(PluginBlueprintParser.deepMergeJson(null, null))
        assertEquals("""{"a":1}""", PluginBlueprintParser.deepMergeJson(null, """{"a":1}"""))
        assertEquals("""{"a":1}""", PluginBlueprintParser.deepMergeJson("""{"a":1}""", null))
    }

    @Test
    fun `deepMergeJson 任一侧不是 JSON 对象时返回 b`() {
        // 记录既有容错：解析不出来就整段用 b，不做部分合并、不抛。
        assertEquals("not json", PluginBlueprintParser.deepMergeJson("""{"a":1}""", "not json"))
        assertEquals("""{"b":2}""", PluginBlueprintParser.deepMergeJson("not json", """{"b":2}"""))
    }

    @Test
    fun `deepMergeJson 非对象值直接覆盖不递归`() {
        val merged = JSONObject(
            PluginBlueprintParser.deepMergeJson("""{"a":{"x":1}}""", """{"a":[1,2]}""")!!
        )
        assertTrue("数组必须整体替换掉对象", merged.get("a") is org.json.JSONArray)
    }

    // ── 9. 结果类型自洽 ──

    @Test
    fun `解析结果可直接交给 PluginHost 装载的形状`() {
        val blueprint = PluginBlueprintParser.parse("""{"id":"x","plugins":[{"id":"a","enabled":false}]}""")
        val ref: BlueprintPluginRef = blueprint.plugins.single()
        assertNotNull(ref)
        assertEquals("a", ref.id)
    }
}
