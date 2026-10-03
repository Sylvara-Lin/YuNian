package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginSettingsBoard] 的 JVM 单测：把「按 kind 分类」「搜索匹配」「行构造与排序」
 * 「呈现方式分流」「插件说明与 id 可见性」「开关状态推导」六条纯逻辑逐条钉死。
 *
 * 「开关状态推导」一组（第 4 节）钉的是**债务 D3** 的修复：开关的轴是
 * **运行状态**（`PluginHost.loadedIds()`），不是持久化的用户意图。
 * 核心不变量 `states[id] == true ⇒ id ∈ hostLoadedIds`——**开关显示为开的插件必然已装载**。
 * 选择理由见 [PluginSettingsBoard] 的 KDoc。
 *
 * 纯 JVM（junit）：不碰 Android、不碰 Compose，因此 `android.util.Log` / `org.json`
 * 这些 Android stub 空壳不会参与——本测试只依赖 `core:domain` 与 `core:ui-common` 的
 * 纯 Kotlin 契约（与 `capability/CapabilityGrantBoardTest` 同一套做法）。
 *
 * 夹具用**当前真实的 7 个插件 id**（`channel.qqbot` / `channel.wechat` /
 * `automation.core` / `message.send` /
 * `skill.builtin_chat_protocol` / `sticker.preference` / `ui.assists`），
 * 让「页面会长什么样」在测试里就是可读的。
 *
 * ⚠️ 夹具里**不再有 `coffee.luckin`**：该插件（瑞幸咖啡）已于 2026-10-03 随功能下线删除，
 * 页面夹具不能锚在一个已不存在的 id 上。相关断言一律改用 `automation.core`（同为 TOOL 类插件），
 * 断言强度不变——只是换了名字，没有放宽成恒真。
 */
class PluginSettingsBoardTest {

    // ── 夹具 ──

    private class FakePlugin(
        override val id: String,
        override val name: String,
        override val kind: PluginKind,
        /** 插件说明；默认空串 = **未声明说明**（这正是回落分支要覆盖的情形）。 */
        override val description: String = "",
    ) : LianYuPlugin {
        override val requires: Set<String> = emptySet()
        override val configSchema: String? = null
        override fun setup(ctx: PluginContext) = Unit
    }

    /**
     * 当前仓库里真实的 7 个插件（id / name / kind 与实现逐字一致）。
     *
     * 说明（[FakePlugin.description]）留空——「未声明说明」是**默认夹具**：
     * 它让绝大多数既有断言继续按「副标题回落为 id」的口径断言，
     * 声明了说明的那条路径由下面 [describedPlugins] 单独覆盖。
     */
    private val realPlugins: List<LianYuPlugin> = listOf(
        FakePlugin("channel.qqbot", "QQ 机器人", PluginKind.ADAPTER),
        FakePlugin("channel.wechat", "微信", PluginKind.ADAPTER),
        FakePlugin("automation.core", "自动化工具", PluginKind.TOOL),
        FakePlugin("message.send", "消息发送", PluginKind.TOOL),
        FakePlugin("skill.builtin_chat_protocol", "内置聊天工具协议技能", PluginKind.SKILL),
        FakePlugin("sticker.preference", "表情包偏好引擎", PluginKind.STICKER),
        FakePlugin("ui.assists", "无障碍自动化", PluginKind.TOOL),
    )

    /**
     * 声明了说明的夹具：用来断言副标题、搜索命中与 id 可见性分流。
     *
     * id / name / kind 与 [realPlugins] 里同名条目逐字一致——只有说明一项不同。
     * 这样「同一条目、有说明 / 没说明」的对照才有意义（见 `搜索能按说明命中`）。
     */
    private val describedPlugins: List<LianYuPlugin> = listOf(
        // 说明里刻意放**标题与 id 里都没有**的行为词（「读屏」「定时任务」），
        // 这样「搜得到」只能归因于说明本身，见 `搜索能按说明命中` 的对照组。
        FakePlugin("ui.assists", "无障碍自动化", PluginKind.TOOL, "让 AI 读屏、点击、滑动并输入来控制手机"),
        FakePlugin("automation.core", "自动化工具", PluginKind.TOOL, "让 AI 创建定时任务与工作流"),
        FakePlugin("message.send", "消息发送", PluginKind.TOOL, "让 AI 经指定通道主动发出消息"),
        FakePlugin("channel.qqbot", "QQ 机器人", PluginKind.ADAPTER, "通过 QQ 机器人收发消息"),
        FakePlugin("channel.wechat", "微信", PluginKind.ADAPTER, "通过微信收发消息"),
    )

    /**
     * 已注册设置区的夹具：`pluginId → 呈现方式`。
     *
     * 参数类型从 `Set<String>` 换成 Map 是 T4 契约扩展的**连带改动**：
     * 行的分流（内联 / 整页浮层）需要知道每个设置区的 [PluginSettingsPresentation]，
     * 只传 id 集合就表达不了。既有断言一条没动，只是构造夹具时多给了呈现方式。
     */
    private fun general(
        query: String = "",
        sections: Map<String, PluginSettingsPresentation> = emptyMap(),
    ) = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.GENERAL, query, sections)

    private fun channel(
        query: String = "",
        sections: Map<String, PluginSettingsPresentation> = emptyMap(),
    ) = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.CHANNEL, query, sections)

    // ── 1. 分类规则：按 kind 归，不设特例 ──

    @Test
    fun `ADAPTER 归消息通道`() {
        assertEquals(PluginSettingsCategory.CHANNEL, PluginSettingsBoard.categoryOf(PluginKind.ADAPTER))
    }

    @Test
    fun `非 ADAPTER 的四种 kind 全部归通用插件`() {
        listOf(PluginKind.TOOL, PluginKind.SKILL, PluginKind.STICKER, PluginKind.PIPELINE).forEach {
            assertEquals("kind=$it 应归通用插件", PluginSettingsCategory.GENERAL, PluginSettingsBoard.categoryOf(it))
        }
    }

    @Test
    fun `每个 kind 都恰好归入一个 Tab`() {
        // 分类函数返回非空类型，所以「覆盖全部 kind」由 **when 穷尽性**在编译期保证：
        // 将来给 PluginKind 加成员而忘了补映射，PluginSettingsBoard 直接编译不过，
        // 不会出现「新 kind 的插件在设置页里静默消失」。
        // 这条断言锁住的是运行期结果：每个 kind 都能落到 TAB_CATEGORIES 里的某一项。
        PluginKind.entries.forEach { kind ->
            assertTrue(
                "kind=$kind 归到了 TAB_CATEGORIES 之外的分类",
                PluginSettingsBoard.categoryOf(kind) in PluginSettingsBoard.TAB_CATEGORIES,
            )
        }
    }

    @Test
    fun `只有两个 Tab 且索引与分类互逆`() {
        assertEquals(2, PluginSettingsBoard.TAB_CATEGORIES.size)
        PluginSettingsBoard.TAB_CATEGORIES.forEachIndexed { index, category ->
            assertEquals(category, PluginSettingsBoard.categoryOfTab(index))
            assertEquals(index, PluginSettingsBoard.tabOfCategory(category))
        }
    }

    @Test
    fun `Tab 索引越界返回 null 而不是抛异常`() {
        assertNull(PluginSettingsBoard.categoryOfTab(2))
        assertNull(PluginSettingsBoard.categoryOfTab(-1))
    }

    // ── 2. 列表行构造与排序 ──

    @Test
    fun `消息通道 Tab 只含两个 ADAPTER 插件且按 id 升序`() {
        assertEquals(listOf("channel.qqbot", "channel.wechat"), channel().map { it.pluginId })
    }

    @Test
    fun `消息通道 Tab 不含工具授权保留条目`() {
        assertTrue(channel().none { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
    }

    @Test
    fun `通用插件 Tab 含其余全部插件`() {
        assertEquals(
            listOf(
                "automation.core",
                "message.send",
                "skill.builtin_chat_protocol",
                "sticker.preference",
                "ui.assists",
            ),
            general().filterNot { it.isSynthetic }.map { it.pluginId },
        )
    }

    @Test
    fun `工具授权保留条目排在通用插件列表最前`() {
        val rows = general()
        assertEquals(PluginSettingsBoard.TOOL_GRANT_ID, rows.first().pluginId)
        assertTrue(rows.first().isSynthetic)
        assertEquals(PluginSettingsCategory.GENERAL, rows.first().category)
    }

    @Test
    fun `工具授权保留条目标记为合成条目且恒有设置区`() {
        val grant = general().first()
        assertTrue(grant.isSynthetic)
        assertTrue("保留条目必须报告 hasSection，否则不会渲染出可展开箭头", grant.hasSection)
    }

    @Test
    fun `两个 Tab 的插件集合不重叠且合起来等于全部插件`() {
        val all = (channel() + general()).filterNot { it.isSynthetic }.map { it.pluginId }
        assertEquals(realPlugins.size, all.size)
        assertEquals(realPlugins.map { it.id }.sorted(), all.sorted())
    }

    @Test
    fun `行列表顺序稳定：与传入插件顺序无关`() {
        val shuffled = realPlugins.reversed()
        val a = PluginSettingsBoard.buildRows(shuffled, PluginSettingsCategory.GENERAL, "", emptyMap())
        val b = PluginSettingsBoard.buildRows(realPlugins, PluginSettingsCategory.GENERAL, "", emptyMap())
        assertEquals(b.map { it.pluginId }, a.map { it.pluginId })
    }

    @Test
    fun `未装载的插件仍然出现在列表里`() {
        // 列表只来自 host.plugins()，不读装载态——否则停用后无法再启用。
        assertEquals(7, (channel() + general()).filterNot { it.isSynthetic }.size)
    }

    @Test
    fun `hasSection 跟随已注册设置区集合`() {
        val sections = mapOf(
            "automation.core" to PluginSettingsPresentation.INLINE,
            PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE,
        )
        val rows = general(sections = sections)
        assertTrue(rows.first { it.pluginId == "automation.core" }.hasSection)
        assertFalse(rows.first { it.pluginId == "message.send" }.hasSection)
        assertEquals(
            "hasSection 与 presentation 必须同进同退（presentation 是唯一事实来源）",
            rows.first { it.pluginId == "automation.core" }.presentation,
            PluginSettingsPresentation.INLINE,
        )
        assertNull(rows.first { it.pluginId == "message.send" }.presentation)
    }

    @Test
    fun `同名真实插件存在时保留条目让位`() {
        val plugins = realPlugins + FakePlugin(PluginSettingsBoard.TOOL_GRANT_ID, "真插件", PluginKind.TOOL)
        val rows = PluginSettingsBoard.buildRows(plugins, PluginSettingsCategory.GENERAL, "", emptyMap())
        assertEquals(1, rows.count { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
        assertFalse("让位后应由真实插件占位", rows.first { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID }.isSynthetic)
    }

    @Test
    fun `副标题：未声明说明的真实插件回落为插件 id`() {
        val row = general().first { it.pluginId == "message.send" }
        assertEquals("", row.description)
        assertEquals(
            "未声明说明时必须回落为插件 id——副标题是行上唯一的次要文本，不能留空",
            "message.send",
            row.subtitle,
        )
        assertEquals("消息发送", row.title)
    }

    @Test
    fun `副标题：声明了说明的真实插件显示说明`() {
        val row = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.GENERAL, "", emptyMap(),
        ).first { it.pluginId == "ui.assists" }
        assertEquals("让 AI 读屏、点击、滑动并输入来控制手机", row.description)
        assertEquals("让 AI 读屏、点击、滑动并输入来控制手机", row.subtitle)
        assertEquals("无障碍自动化", row.title)
    }

    @Test
    fun `副标题回落规则只有一处：subtitleOf 与行构造结果一致`() {
        // 回落若在行构造里各写一遍，两处口径迟早会漂移；这条断言把两者绑在一起。
        (realPlugins + describedPlugins).distinctBy { it.id }.forEach { plugin ->
            // 分类必须跟着 kind 走：ADAPTER 只出现在消息通道 Tab，写死 GENERAL 会查不到行。
            // 按 id 取行而不是取「唯一非合成行」——buildRows 还会带上「工具授权」保留条目。
            val row = PluginSettingsBoard.buildRows(
                listOf(plugin), PluginSettingsBoard.categoryOf(plugin.kind), "", emptyMap(),
            ).single { it.pluginId == plugin.id }
            assertEquals("plugin=${plugin.id}", PluginSettingsBoard.subtitleOf(plugin), row.subtitle)
            assertEquals(
                "说明为空才回落，非空必须原样显示：plugin=${plugin.id}",
                if (plugin.description.isEmpty()) plugin.id else plugin.description,
                row.subtitle,
            )
        }
    }

    // ── 3. 搜索匹配 ──

    @Test
    fun `空查询词全部通过`() {
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "", ""))
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "", "   "))
        // 空白查询词一律通过，与「这一行有没有说明」无关。
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "有说明", "  "))
    }

    @Test
    fun `按 id 匹配且不区分大小写`() {
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "", "QQBOT"))
        assertTrue(PluginSettingsBoard.matches("channel.qqbot", "QQ 机器人", "", "channel"))
    }

    @Test
    fun `按展示名匹配`() {
        assertTrue(PluginSettingsBoard.matches("automation.core", "自动化工具", "", "自动化"))
    }

    @Test
    fun `不匹配时返回 false`() {
        assertFalse(PluginSettingsBoard.matches("automation.core", "自动化工具", "", "微信"))
        // 说明不参与命中时不得把它变成恒真：查询词不在 id / 标题 / 说明里就必须为 false。
        assertFalse(PluginSettingsBoard.matches("automation.core", "自动化工具", "定时任务", "咖啡"))
    }

    @Test
    fun `搜索只在当前 Tab 内过滤`() {
        // 同一个查询词分别作用在两个 Tab 上：命中集合是「该分类 ∩ 查询词」，
        // 不会把另一个 Tab 的命中混进来（TabRow 的高亮与列表内容始终一一对应）。
        assertEquals(listOf("channel.qqbot", "channel.wechat"), channel("channel").map { it.pluginId })
        assertTrue("通用插件里没有 id/名字含 channel 的条目", general("channel").isEmpty())

        // 反过来：搜一个通用插件（「定时任务」只出现在它的说明里），
        // 消息通道 Tab 必须为空、通用 Tab 必须恰好命中它。
        val crossTab = describedPlugins
        assertTrue(
            PluginSettingsBoard.buildRows(
                crossTab, PluginSettingsCategory.CHANNEL, "定时任务", emptyMap(),
            ).isEmpty(),
        )
        assertEquals(
            listOf("automation.core"),
            PluginSettingsBoard.buildRows(
                crossTab, PluginSettingsCategory.GENERAL, "定时任务", emptyMap(),
            ).map { it.pluginId },
        )
    }

    @Test
    fun `查询词命中插件 id 的中段`() {
        assertEquals(listOf("automation.core"), general("automation").map { it.pluginId })
        assertEquals(listOf("ui.assists"), general("assists").map { it.pluginId })
    }

    @Test
    fun `搜索能按说明命中`() {
        // 说明是用户唯一能读到「这个插件到底做什么」的字段：搜行为词必须命中，
        // 否则用户只能靠背插件 id 找到条目。
        //
        // ⚠️ 用的词（「读屏」「定时任务」）**只出现在说明里**——「无障碍」「自动化」都
        // 出现在 id / 标题里，拿它们当查询词会让断言被 id 命中侥幸通过，测不到说明。
        val byReading = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.GENERAL, "读屏", emptyMap(),
        )
        assertEquals(listOf("ui.assists"), byReading.map { it.pluginId })

        val bySchedule = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.GENERAL, "定时任务", emptyMap(),
        )
        assertEquals(listOf("automation.core"), bySchedule.map { it.pluginId })

        // 对照组：**同一条目**（id / 标题 / kind 都相同）只是没声明说明时搜不到——
        // 这证明命中确实来自说明，而不是 id 或标题里恰好有这几个字。
        val withoutDescription = PluginSettingsBoard.buildRows(
            realPlugins, PluginSettingsCategory.GENERAL, "读屏", emptyMap(),
        )
        assertTrue(
            "空说明不得让条目变成恒真命中；实际命中：" + withoutDescription.map { it.pluginId },
            withoutDescription.isEmpty(),
        )
        assertTrue(
            "对照组必须包含同名条目，否则「按说明命中」无从对照",
            realPlugins.any { it.id == "ui.assists" },
        )
    }

    @Test
    fun `搜索按说明命中也区分大小写不敏感`() {
        val rows = PluginSettingsBoard.buildRows(
            listOf(FakePlugin("t.x", "某插件", PluginKind.TOOL, "Screen Assist Bridge")),
            PluginSettingsCategory.GENERAL,
            "screen assist",
            emptyMap(),
        )
        assertEquals(listOf("t.x"), rows.map { it.pluginId })
    }

    @Test
    fun `搜索可以命中保留条目`() {
        assertTrue(general("tool.grant").any { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
        assertTrue(general("工具授权").any { it.pluginId == PluginSettingsBoard.TOOL_GRANT_ID })
    }

    @Test
    fun `搜不到时返回空列表（由页面渲染空态）`() {
        assertTrue(general("绝对不存在的插件").isEmpty())
        assertTrue(channel("绝对不存在的插件").isEmpty())
    }

    @Test
    fun `空态文案区分「没匹配」与「暂无插件」`() {
        assertEquals(PluginSettingsBoard.EMPTY_SEARCH_HINT, PluginSettingsBoard.emptyHint("自动化"))
        assertEquals(PluginSettingsBoard.EMPTY_HINT, PluginSettingsBoard.emptyHint(""))
        assertEquals(PluginSettingsBoard.EMPTY_HINT, PluginSettingsBoard.emptyHint("   "))
    }

    // ── 4. 开关状态推导（债务 D3）──
    //
    // 规则是**合取**：开 = 已装载 且 用户没停用。
    // 与旧口径（开 = 已装载 或 id ∉ 停用集合）的差别只有一处，但那一处正是本次修复：
    // 「用户意图为启用、但当前没装载」现在显示为**关**——插件装载失败后界面不再宣称它开着。

    @Test
    fun `宿主说它活着且用户没停用，开关就显示为开`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("automation.core"),
            disabledIds = null,
        )
        assertTrue(states.getValue("automation.core"))
    }

    @Test
    fun `未装载的插件一律显示为关——即使存储里没有任何停用记录`() {
        // ⚠️ 这条断言在 D3 之前是 `states.values.all { it }`（全部为开），本次**有意收紧**。
        //
        // 旧口径把「不在停用集合里」当成「显式启用」并直接渲染成 ON，于是
        // 「宿主一个插件都没装载」（典型场景：蓝图解析失败，见债务 D4）时，
        // 整页开关全是 ON 而插件一个都没跑——这就是「假装成功」。
        //
        // 保留的**意图**仍然是「不得把开关锁死」：存储未注册 / 读失败（disabledIds = null）
        // 时必须能正常出结果，且一旦宿主装载成功、开关立刻能变成开（见下一条断言）。
        // 加强的部分：未装载的插件不得显示为开。
        val states = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertEquals(5, states.size)
        assertTrue(
            "未装载的插件显示为开就是「假装成功」：开关说开着、插件却没跑",
            states.values.none { it },
        )
        // 「不锁死」的可断言形式：同一份 rows、存储同样读不到，只要宿主装载了，开关就能开。
        assertTrue(
            "存储读不到不得把开关锁死：宿主装载成功即应显示为开",
            PluginSettingsBoard.deriveSwitchStates(general(), setOf("automation.core"), null)
                .getValue("automation.core"),
        )
    }

    @Test
    fun `装载失败的核心场景：意图为启用但宿主没装载 → 关`() {
        // 债务 D3 的原始症状：插件装载失败（缺依赖服务 / 清单不一致 / 装配异常 /
        // 蓝图解析失败导致整批没装载）之后，setEnabled 会把该 id 从停用集合里移除，
        // 于是「未装载 + 不在停用集合里」= 用户意图为启用。
        // 旧口径对这一行渲染成 ON；本实现渲染成 OFF——插件没跑，开关就不许说它开着。
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = emptySet(),
            disabledIds = emptySet(),
        )
        assertFalse(
            "装载失败后显示为开，就是本次要修的「假装成功」",
            states.getValue("automation.core"),
        )
        assertFalse(states.getValue("message.send"))
    }

    @Test
    fun `开关显示为开的插件必然已装载（D3 的可断言不变量）`() {
        // 这是本次修复最凝练的形式化表达，也覆盖了「能显示 ON 的充要条件」：
        //   开关为开  ⟺  已装载
        // 持久化意图不得掩盖运行事实。
        val rows = general()
        val id = "automation.core"
        val cases = listOf(
            Triple(true, false, true),   // 已装载 + 未停用 → 开
            Triple(true, true, true),    // 已装载 + 存储停用 → 仍在运行，必须显示开
            Triple(false, false, false), // 未装载 + 未停用 → 关（D3 修复点）
            Triple(false, true, false),  // 未装载 + 已停用 → 关
        )
        cases.forEach { (loaded, disabled, expected) ->
            val states = PluginSettingsBoard.deriveSwitchStates(
                rows = rows,
                hostLoadedIds = if (loaded) setOf(id) else emptySet(),
                disabledIds = if (disabled) setOf(id) else emptySet(),
            )
            assertEquals(
                "loaded=" + loaded + " disabled=" + disabled + " 时开关应为 " + expected,
                expected,
                states.getValue(id),
            )
        }
        // 不变量本身：任何显示为开的插件都必须在 hostLoadedIds 里。
        listOf(emptySet(), setOf("automation.core"), setOf("message.send", "automation.core")).forEach { loaded ->
            val states = PluginSettingsBoard.deriveSwitchStates(rows, loaded, emptySet())
            val onButNotLoaded = states.filterValues { it }.keys - loaded
            assertTrue(
                "开关为开但宿主没装载: " + onButNotLoaded,
                onButNotLoaded.isEmpty(),
            )
        }
    }

    @Test
    fun `停用集合里的 id 显示为关`() {
        // 已装载的插件被用户显式停用（写存储成功、本次会话还没重启）→ 显示为关。
        // 装载态给的是 `automation.core`，所以这条断言单独钉住**停用集合**那一侧，
        // 不会因为「未装载」这个原因而侥幸通过。
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("automation.core"),
            disabledIds = setOf("message.send"),
        )
        assertFalse(states.getValue("message.send"))
        assertTrue(states.getValue("automation.core"))
    }

    @Test
    fun `保存失败保留旧停用意图时仍运行插件必须显示开`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            general(), setOf("automation.core"), setOf("automation.core", "message.send"),
        )
        assertTrue(states.getValue("automation.core"))
        assertFalse(states.getValue("message.send"))
    }

    @Test
    fun `空停用集合等价于 store 缺失`() {
        val a = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), emptySet())
        val b = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertEquals(b, a)
    }

    @Test
    fun `合成条目没有开关`() {
        val states = PluginSettingsBoard.deriveSwitchStates(general(), emptySet(), null)
        assertFalse(
            "保留条目不是插件，不该出现在开关映射里",
            states.containsKey(PluginSettingsBoard.TOOL_GRANT_ID),
        )
    }

    @Test
    fun `开关映射的键集合等于行里的真实插件`() {
        val rows = general()
        val states = PluginSettingsBoard.deriveSwitchStates(rows, emptySet(), null)
        assertEquals(rows.filterNot { it.isSynthetic }.map { it.pluginId }.toSet(), states.keys)
    }

    @Test
    fun `宿主加载集合里多余的 id 不会凭空造出行`() {
        val states = PluginSettingsBoard.deriveSwitchStates(
            rows = general(),
            hostLoadedIds = setOf("不存在的插件"),
            disabledIds = null,
        )
        assertFalse(states.containsKey("不存在的插件"))
    }

    // ── 5. 呈现方式分流（T4：PluginSettingsPresentation → 页面行为）──
    //
    // 这一组是**崩溃防线**的纯逻辑落点：整页设置区（自带 Scaffold / 滚动容器）
    // 一旦被内联进外层 LazyColumn 的 item，内层滚动容器会拿到无界最大高度约束、
    // 运行期抛异常。分流规则必须在这里（纯逻辑）钉死，不能只活在 Composable 里。

    @Test
    fun `INLINE 设置区：点行是行内展开`() {
        val row = general(sections = mapOf("automation.core" to PluginSettingsPresentation.INLINE))
            .first { it.pluginId == "automation.core" }
        assertTrue(row.hasSection)
        assertEquals(PluginSettingsPresentation.INLINE, row.presentation)
        assertEquals(PluginRowAction.TOGGLE_INLINE, row.action)
    }

    @Test
    fun `FULL_PAGE 设置区：点行是打开整页浮层`() {
        val row = general(sections = mapOf("automation.core" to PluginSettingsPresentation.FULL_PAGE))
            .first { it.pluginId == "automation.core" }
        assertTrue(row.hasSection)
        assertEquals(PluginSettingsPresentation.FULL_PAGE, row.presentation)
        assertEquals(
            "整页设置区必须走浮层：内联渲染会因无界高度约束在运行期抛异常",
            PluginRowAction.OPEN_FULL_PAGE,
            row.action,
        )
    }

    @Test
    fun `两种呈现方式映射到两个不同的动作`() {
        fun actionOf(presentation: PluginSettingsPresentation) =
            general(sections = mapOf("automation.core" to presentation))
                .first { it.pluginId == "automation.core" }
                .action

        assertEquals(PluginRowAction.TOGGLE_INLINE, actionOf(PluginSettingsPresentation.INLINE))
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, actionOf(PluginSettingsPresentation.FULL_PAGE))
        assertEquals(
            "两种呈现方式必须映射到不同动作，否则「分流」就等于没做",
            2,
            PluginSettingsPresentation.entries.map(::actionOf).toSet().size,
        )
    }

    @Test
    fun `整页设置区即使拿到 expanded = true 也绝不内联渲染`() {
        // 最后一道防线：即便注册表在页面停留期间被换掉、某行意外带着 expanded = true，
        // FULL_PAGE 设置区也绝不会被塞进外层 LazyColumn 的 item 里。
        val fullPage = general(sections = mapOf("automation.core" to PluginSettingsPresentation.FULL_PAGE))
            .first { it.pluginId == "automation.core" }
        assertFalse("FULL_PAGE 被内联渲染就是本次要修的那个崩溃", fullPage.rendersInline(expanded = true))
        assertFalse(fullPage.rendersInline(expanded = false))

        val inline = general(sections = mapOf("automation.core" to PluginSettingsPresentation.INLINE))
            .first { it.pluginId == "automation.core" }
        assertTrue(inline.rendersInline(expanded = true))
        assertFalse("收起状态不渲染", inline.rendersInline(expanded = false))
    }

    @Test
    fun `未注册设置区：仍是行内展开，让「无可配置项」的兜底提示可达`() {
        val row = general().first { it.pluginId == "message.send" }
        assertFalse(row.hasSection)
        assertNull(row.presentation)
        assertEquals(
            "未注册设置区的行点了要能展开出「此插件无可配置项」，不能变成点了没反应的死行",
            PluginRowAction.TOGGLE_INLINE,
            row.action,
        )
        assertTrue("没有设置区时展开区里显示兜底提示，不是死行", row.rendersInline(expanded = true))
    }

    @Test
    fun `两个通道设置区都走 FULL_PAGE 浮层`() {
        // 与 feature:wechat / feature:qqbot 侧的同名断言成对：那两个设置区渲染的是
        // 自带 GlassPageScaffold 的整页 Screen。这里从**页面侧**再钉一次——
        // 它们声明 FULL_PAGE，buildRows 就必须把它们分流到浮层，绝不内联。
        val rows = channel(
            sections = mapOf(
                "channel.wechat" to PluginSettingsPresentation.FULL_PAGE,
                "channel.qqbot" to PluginSettingsPresentation.FULL_PAGE,
            )
        )
        assertEquals(listOf("channel.qqbot", "channel.wechat"), rows.map { it.pluginId })
        assertTrue(
            "通道设置区必须全部走整页浮层: " + rows.map { it.pluginId to it.action },
            rows.all { it.action == PluginRowAction.OPEN_FULL_PAGE },
        )
        assertTrue(rows.none { it.rendersInline(expanded = true) })
    }

    @Test
    fun `工具授权保留条目走 FULL_PAGE 分支`() {
        // T4 之前这是页面里硬编码的 showToolGrants 分支；现在它只是一个普通的
        // FULL_PAGE 设置区——页面不再认识任何具体插件 id。
        val grant = general(
            sections = mapOf(PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE)
        ).first()
        assertTrue(grant.isSynthetic)
        assertTrue(grant.hasSection)
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, grant.action)
        assertFalse(grant.rendersInline(expanded = true))
    }

    @Test
    fun `工具授权保留条目在注册表为空时也按 FULL_PAGE 的安全默认处理`() {
        // 本页第一帧（DisposableEffect 还没跑完）注册表里查不到 tool.grant。
        // 按契约的安全默认 FULL_PAGE 处理，与 PluginSettingsSection.presentation 的
        // 默认值同向：宁可整页，不可崩溃。
        val grant = general(sections = emptyMap()).first()
        assertTrue("保留条目必须报告 hasSection，否则它会变成点了没反应的死行", grant.hasSection)
        assertEquals(PluginSettingsPresentation.FULL_PAGE, grant.presentation)
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, grant.action)
    }

    @Test
    fun `hasSection 与 presentation 不可能不一致`() {
        val sections = mapOf(
            "automation.core" to PluginSettingsPresentation.INLINE,
            "message.send" to PluginSettingsPresentation.FULL_PAGE,
            PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE,
        )
        val rows = general(sections = sections) + channel(sections = sections)
        rows.forEach { row ->
            assertEquals(
                "行 ${row.pluginId} 的 hasSection 与 presentation 不一致",
                row.presentation != null,
                row.hasSection,
            )
        }
    }

    // ── 6. 插件说明与插件 id 的可见性（本次契约扩展）──
    //
    // 副标题从「插件 id」换成「插件说明」之后，id 不再是行上天然可见的文本。
    // 这一组把「id 永远还在页面上」钉死：有展开区的行进展开区顶部，没有展开区的行留在行内，
    // 合成保留条目本来就不是插件、没有 id 可显示。分流是纯推导属性，Composable 只照着画。

    @Test
    fun `TOGGLE_INLINE 的行把插件 id 放进展开区`() {
        val row = general(sections = mapOf("automation.core" to PluginSettingsPresentation.INLINE))
            .first { it.pluginId == "automation.core" }
        assertEquals(PluginRowAction.TOGGLE_INLINE, row.action)
        assertEquals(
            "有展开区的行：id 画在展开区顶部一行",
            PluginIdPlacement.EXPANDED_SECTION,
            row.pluginIdPlacement,
        )
        assertFalse(
            "展开区已经有 id 了，行内不再重复画一次",
            row.pluginIdInlineVisible,
        )
    }

    @Test
    fun `未注册设置区的行也把插件 id 放进展开区`() {
        // presentation = null 同样走 TOGGLE_INLINE（展开区里给「无可配置项」的兜底提示），
        // 因此 id 必须画在那块展开区里，否则这一行在任何状态下都看不到 id。
        val row = general().first { it.pluginId == "message.send" }
        assertNull(row.presentation)
        assertEquals(PluginRowAction.TOGGLE_INLINE, row.action)
        assertEquals(PluginIdPlacement.EXPANDED_SECTION, row.pluginIdPlacement)
        assertFalse(row.pluginIdInlineVisible)
    }

    @Test
    fun `OPEN_FULL_PAGE 的行没有展开区，插件 id 留在行内`() {
        // 用带说明的夹具：副标题被说明占住，id 的唯一落点就是行内那行小字。
        val row = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.CHANNEL, "",
            mapOf("channel.qqbot" to PluginSettingsPresentation.FULL_PAGE),
        ).first { it.pluginId == "channel.qqbot" }
        assertEquals("通过 QQ 机器人收发消息", row.subtitle)
        assertEquals(PluginRowAction.OPEN_FULL_PAGE, row.action)
        assertFalse("没有展开区，id 只能留在行内", row.rendersInline(expanded = true))
        assertEquals(
            "没有展开区的行：id 画在行内副标题下方",
            PluginIdPlacement.INLINE,
            row.pluginIdPlacement,
        )
        assertTrue(row.pluginIdInlineVisible)
        assertFalse(row.pluginIdInExpandedSection)
    }

    @Test
    fun `两个通道设置区都是 FULL_PAGE，所以插件 id 全部留在行内`() {
        // 与 feature:wechat / feature:qqbot 侧的同名断言成对：那两个设置区渲染的是整页 Screen，
        // 声明 FULL_PAGE → 没有展开区 → 两行的 id 都必须在行内可见（否则通道行的 id 就丢了）。
        val sections = mapOf(
            "channel.wechat" to PluginSettingsPresentation.FULL_PAGE,
            "channel.qqbot" to PluginSettingsPresentation.FULL_PAGE,
        )
        // 用**声明了说明**的夹具：这样副标题是说明、id 只能靠行内那行小字，
        // 「id 还在不在页面上」才有可证伪的内容（否则副标题本身就是 id，断言会变恒真）。
        val rows = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.CHANNEL, "", sections,
        )
        assertEquals(listOf("channel.qqbot", "channel.wechat"), rows.map { it.pluginId })
        rows.forEach { row ->
            assertEquals("row=${row.pluginId}", PluginIdPlacement.INLINE, row.pluginIdPlacement)
            assertTrue("row=${row.pluginId}", row.pluginIdInlineVisible)
            assertFalse("row=${row.pluginId}", row.pluginIdInExpandedSection)
        }
    }

    @Test
    fun `合成保留条目不画插件 id`() {
        // 保留条目不是插件：tool.grant 是设置页自己造的入口 id，印在行上只会误导；
        // 它的副标题已经说明了用途。这条同时保证保留条目的行内文案与行为一处都没变。
        val grant = general(sections = emptyMap()).first()
        assertTrue(grant.isSynthetic)
        assertEquals(PluginIdPlacement.HIDDEN, grant.pluginIdPlacement)
        assertFalse("保留条目不该在行内画 id", grant.pluginIdInlineVisible)
        assertFalse("保留条目不该在展开区画 id", grant.pluginIdInExpandedSection)
        assertEquals("", grant.description)
    }

    @Test
    fun `插件 id 可见性：要么在展开区，要么在行内，二者互斥且不丢`() {
        val sections = mapOf(
            "automation.core" to PluginSettingsPresentation.INLINE,
            "message.send" to PluginSettingsPresentation.FULL_PAGE,
            PluginSettingsBoard.TOOL_GRANT_ID to PluginSettingsPresentation.FULL_PAGE,
        )
        // 通用 Tab 用「带说明」的夹具（含 message.send）：副标题是说明，id 只能靠这条分流
        // 才能看到，于是下面的「不丢」断言有可证伪的内容；
        // 消息通道 Tab 用真实夹具，覆盖「未声明说明 → 副标题就是 id」那条分支。
        val rows = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.GENERAL, "", sections,
        ) + channel(sections = sections)
        rows.forEach { row ->
            assertFalse(
                "row=${row.pluginId}：id 不可能同时出现在两处",
                row.pluginIdInlineVisible && row.pluginIdInExpandedSection,
            )
            if (!row.isSynthetic && row.description.isNotEmpty()) {
                assertTrue(
                    "row=${row.pluginId}：副标题是说明时，id 必须能在页面上看到（展开区或行内）",
                    row.pluginIdInlineVisible || row.pluginIdInExpandedSection,
                )
            }
        }
        // 具体落点（不是只断言「存在」）：INLINE 行进展开区，FULL_PAGE 行留行内。
        val inlineRow = rows.first { it.pluginId == "automation.core" }
        assertTrue(inlineRow.pluginIdInExpandedSection)
        assertFalse(inlineRow.pluginIdInlineVisible)
        val fullPageRow = rows.first { it.pluginId == "message.send" }
        assertEquals("让 AI 经指定通道主动发出消息", fullPageRow.subtitle)
        assertTrue(fullPageRow.pluginIdInlineVisible)
        assertFalse(fullPageRow.pluginIdInExpandedSection)
    }

    @Test
    fun `插件 id 的落点只由 action 与 isSynthetic 决定，与副标题文案无关`() {
        // 刻意不看「subtitle 是否恰好等于 pluginId」：那种隐式判断会让某个插件的说明
        // 正好等于自己的 id 时静默丢信息。这里用两条说明不同的行钉住这条规则。
        val withDescription = PluginSettingsBoard.buildRows(
            describedPlugins, PluginSettingsCategory.GENERAL, "", emptyMap(),
        ).first { it.pluginId == "ui.assists" }
        val withoutDescription = PluginSettingsBoard.buildRows(
            realPlugins, PluginSettingsCategory.GENERAL, "", emptyMap(),
        ).first { it.pluginId == "ui.assists" }

        assertEquals("ui.assists", withoutDescription.subtitle)
        assertEquals("让 AI 读屏、点击、滑动并输入来控制手机", withDescription.subtitle)
        assertEquals(
            "说明变了，id 的落点不该跟着变",
            withoutDescription.pluginIdPlacement,
            withDescription.pluginIdPlacement,
        )
        assertEquals(PluginIdPlacement.EXPANDED_SECTION, withDescription.pluginIdPlacement)
    }

    @Test
    fun `说明为空的行：副标题回落为 id，展开区里才不再重复画`() {
        // 空说明 ⇒ 副标题回落成 id ⇒ 有展开区的行里 id 已经可见，展开区顶部不再重复画一遍；
        // 而 FULL_PAGE 行**没有别的落点**（没有展开区），行内那行小字必须画。
        val inlineRow = general().first { it.pluginId == "message.send" }
        assertEquals("message.send", inlineRow.subtitle)
        assertEquals("", inlineRow.description)
        assertEquals(PluginIdPlacement.EXPANDED_SECTION, inlineRow.pluginIdPlacement)
        assertFalse("副标题已经是 id，展开区里不重复画", inlineRow.pluginIdInlineVisible)

        // 没有展开区时 id 的落点仍是行内；但这一行的副标题已经是 id，
        // 所以行内那行小字不重复画——「落点」与「是否渲染」是两件事。
        val fullPageRow = general(sections = mapOf("message.send" to PluginSettingsPresentation.FULL_PAGE))
            .first { it.pluginId == "message.send" }
        assertEquals("message.send", fullPageRow.subtitle)
        assertEquals(PluginIdPlacement.INLINE, fullPageRow.pluginIdPlacement)
        assertFalse("副标题已经是 id，行内不重复画", fullPageRow.pluginIdInlineVisible)
    }
}
