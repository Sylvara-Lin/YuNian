package com.yunian.ai.feature.settings.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation

/**
 * 「插件设置」页的**纯逻辑**：分类规则 / 搜索匹配 / 列表行构造 / 呈现方式分流 /
 * 插件 id 可见性分流 / 开关状态推导。
 *
 * 为什么单独成类：本仓库没有 Robolectric，Compose 也不做仪器测试，页面逻辑若写在
 * Composable 里就等于零覆盖（与 `capability/CapabilityGrantBoard.kt` 同一套理由）。
 * 这里全部是纯 Kotlin（只依赖 `core:domain` 的 [LianYuPlugin] / [PluginKind] 与
 * `core:ui-common` 的 [PluginSettingsCategory]，不碰 Android、不碰 Compose），
 * 因此可以在 `:feature:settings` 的 JVM 单测里逐条钉死。
 *
 * ## 分类规则：按 [PluginKind] 归，不设特例
 *
 * 用户裁定「cordis 万物皆是插件」，因此**没有任何按 id 写死的通道清单**：
 *
 * | [PluginKind] | 归属 Tab |
 * |---|---|
 * | [PluginKind.ADAPTER] | 消息通道 |
 * | [PluginKind.TOOL] / [SKILL] / [STICKER] / [PIPELINE] | 通用插件 |
 *
 * 这条规则同时覆盖**现在和将来**的插件——新增一个 TOOL 插件不需要动本文件，
 * 也不会因为漏改清单而在设置页里消失。
 *
 * ## 搜索范围：只在**当前 Tab 内**搜（不跨 Tab）
 *
 * 两种可选语义（任务书要求二选一）：
 *
 * 1. **跨 Tab 全局搜**：一搜就把两个 Tab 的命中合起来显示。省一次切 Tab，
 *    但会让 TabRow 变成「装饰」——当前高亮的 Tab 与列表内容不再对应，
 *    用户看到消息通道 Tab 亮着、列表里却是通用插件，这是比多点一次更坏的体验。
 * 2. **当前 Tab 内搜**（本实现）：查询词只过滤当前 Tab 的条目。
 *
 * 选 2 的理由：TabRow 与 HorizontalPager 是**同一份数据**的两个视图，
 * 过滤后每个 Tab 展示的都是「该分类 ∩ 查询词」，Tab 与内容始终一一对应，
 * 不会出现「高亮的 Tab 与列表不匹配」的错位。代价是搜一个不确定在哪个 Tab 的插件时
 * 可能需要切一次 Tab——但列表本身只有个位数条目（当前 7 个插件 + 1 个保留条目），
 * 且两个 Tab 各自都给出了空态提示（见 [EMPTY_SEARCH_HINT]），不会让人以为搜坏了。
 *
 * 匹配规则：对 [PluginSettingsRow.pluginId]、[PluginSettingsRow.title] **与**
 * [PluginSettingsRow.description] 做**不区分大小写**的包含匹配
 * （[String.contains] + [ignoreCase]）；查询词 trim 后为空视为「未搜索」，全部通过。
 *
 * 说明把 [PluginSettingsRow.description] 纳入匹配范围的理由：它是用户唯一能读到
 * 「这个插件到底做什么」的字段，搜「无障碍」「定时任务」这类**行为词**比背插件 id 现实得多。
 *
 * ## 开关只表达运行态
 *
 * ON 当且仅当宿主已装载。持久化意图不能覆盖运行态：保存失败或卸载失败时，
 * 仍在运行的插件必须保持 ON；未装载的插件无论存储是什么都必须 OFF。
 * 保存失败由切换事务单独反馈「当前生效但未保存，重启可能恢复」。
 */
object PluginSettingsBoard {

    /**
     * 「工具授权」保留条目的 id。
     *
     * 它**不是** [LianYuPlugin.id]——「能力授权」不是一个插件，而是并入通用插件分类的
     * 一个固定入口（用户裁定 Q2/Q6）。用 `tool.grant` 而不是 `capability.grant`：
     * 它的内容是「Agent 工具的能力授权清单」，`tool.` 前缀与 `tool` 语义对齐，
     * 也避免与 `skill.builtin_chat_protocol` 这类真实插件 id 前缀混淆。
     *
     * ⚠️ 该 id 位于**插件 id 的取值域内**，只是当前没有同名插件。若将来真的有插件
     * 注册成 `tool.grant`，[buildRows] 的 `availableSections` 会把保留条目让位给
     * 真实插件（真实插件优先），不会出现两行同名条目。
     */
    const val TOOL_GRANT_ID: String = "tool.grant"

    /** 「工具授权」保留条目的展示名。 */
    const val TOOL_GRANT_TITLE: String = "工具授权"

    /** 「工具授权」保留条目的副标题（说明它为什么在这里）。 */
    const val TOOL_GRANT_SUBTITLE: String = "管理需要确认的 Agent 工具能力"

    /** 空列表提示（该分类下确实没有插件）。 */
    const val EMPTY_HINT: String = "此分类下暂无插件"

    /** 空搜索提示（有插件但当前查询词没命中）。 */
    const val EMPTY_SEARCH_HINT: String = "没有匹配的插件"

    /**
     * Tab 顺序：索引 0 = 消息通道，索引 1 = 通用插件。
     *
     * [PluginSettingsCategory] 的**声明顺序**即 Tab 顺序，因此
     * [categoryOfTab] / [tabOfCategory] 互为逆映射，TabRow 与 HorizontalPager
     * 用的是同一个列表，不可能错位。
     */
    val TAB_CATEGORIES: List<PluginSettingsCategory> = PluginSettingsCategory.entries.toList()

    /**
     * 分类规则（唯一的 kind → 分类映射点）。
     *
     * 只有 [PluginKind.ADAPTER] 归「消息通道」，其余全部归「通用插件」——
     * 含 [PluginKind.PIPELINE]：管道是回合后处理逻辑，没有独立的用户界面概念，
     * 归到通用插件让「万物皆是插件」的裁定落到一个具体的、可见的位置。
     */
    fun categoryOf(kind: PluginKind): PluginSettingsCategory = when (kind) {
        PluginKind.ADAPTER -> PluginSettingsCategory.CHANNEL
        PluginKind.TOOL,
        PluginKind.SKILL,
        PluginKind.STICKER,
        PluginKind.PIPELINE -> PluginSettingsCategory.GENERAL
    }

    /** Tab 索引 → 分类；越界返回 null（Pager 与 TabRow 数量不一致时 fail-soft）。 */
    fun categoryOfTab(index: Int): PluginSettingsCategory? = TAB_CATEGORIES.getOrNull(index)

    /** 分类 → Tab 索引；分类缺失返回 0（回到第一个 Tab 而不是崩）。 */
    fun tabOfCategory(category: PluginSettingsCategory): Int =
        TAB_CATEGORIES.indexOf(category).takeIf { it >= 0 } ?: 0

    /** 该分类下的空态文案：有查询词时说「没匹配」，否则说「暂无」。 */
    fun emptyHint(query: String): String =
        if (query.isBlank()) EMPTY_HINT else EMPTY_SEARCH_HINT

    /**
     * 搜索匹配：对 id、展示名与**说明**做不区分大小写的包含匹配。
     *
     * 空白查询词（含只有空格）一律通过——用户在搜索框里敲了个空格不该看到空列表。
     *
     * @param description 插件的 [LianYuPlugin.description]；空串（未声明说明）时
     *   它对匹配结果没有任何贡献，等价于「这一项不存在」，不会把行变成恒真命中。
     */
    fun matches(id: String, title: String, description: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return id.contains(q, ignoreCase = true) ||
            title.contains(q, ignoreCase = true) ||
            description.contains(q, ignoreCase = true)
    }

    /**
     * 构造一个 Tab 的行列表：**分类过滤 → 搜索过滤 → 排序**。
     *
     * 排序（稳定，与注册顺序无关）：
     * 1. 「工具授权」保留条目**排在最前**——它是固定入口，位置固定才找得到；
     * 2. 其余按 [PluginSettingsRow.pluginId] 升序，与 `PluginHost.plugins()` 的排序一致。
     *
     * @param plugins 全部已注册插件（含未装载的——列表**不因未装载而消失**，
     *   否则用户没法把停用的插件重新打开）。行副标题取 [LianYuPlugin.description]，
     *   空串时回落为插件 id（见 [subtitleOf]）。
     * @param category 当前 Tab 的分类。
     * @param query 搜索词；空白 = 不过滤。命中范围 = id + 标题 + 说明。
     * ## 呈现方式在这里进入行模型
     *
     * 「点这一行会发生什么」由设置区的 [PluginSettingsPresentation] 决定，而这个判断
     * **必须留在这里**（纯逻辑、JVM 可测），不能下沉到 Composable 里——否则
     * 「FULL_PAGE 走整页浮层 / INLINE 走内联展开」这条**唯一**的分流规则就零覆盖了
     * （与 `capability/CapabilityGrantBoard.kt` 同一套理由）。
     * 于是 [PluginSettingsRow.action] 是纯推导属性，Composable 只照着画。
     *
     * @param availableSections 当前**已注册设置区**的 `pluginId → 呈现方式`
     *   （`PluginSettingsSections.all().associate { it.pluginId to it.presentation }`）。
     *   **不是**「已装载」集合：设置区由插件 `setup()` 注册、由 `ctx.effect` 撤销，
     *   所以它天然等于「已完成装配」，比装载态更贴近「展开后有没有内容」。
     */
    fun buildRows(
        plugins: List<LianYuPlugin>,
        category: PluginSettingsCategory,
        query: String,
        availableSections: Map<String, PluginSettingsPresentation>,
    ): List<PluginSettingsRow> {
        val grantRow = PluginSettingsRow(
            pluginId = TOOL_GRANT_ID,
            title = TOOL_GRANT_TITLE,
            subtitle = TOOL_GRANT_SUBTITLE,
            // 保留条目不是插件，没有 LianYuPlugin.description 可言；它的副标题是用途说明，
            // 由 PluginSettingsRows 用资源字符串渲染（**文案与行为一律不变**）。
            // 空串同时让它不参与「按说明搜索」——保留条目仍由 id / 标题命中。
            description = "",
            category = PluginSettingsCategory.GENERAL,
            isSynthetic = true,
            // 保留条目由设置页自己在 DisposableEffect 里注册（见 PluginSettingsScreen），
            // 所以注册表里**正常**查得到它。查不到时（本页第一帧，DisposableEffect 还没
            // 跑完）按契约的安全默认 FULL_PAGE 处理，与 PluginSettingsSection.presentation
            // 的默认值同向：宁可整页，不可崩溃。
            presentation = availableSections[TOOL_GRANT_ID] ?: PluginSettingsPresentation.FULL_PAGE,
        )

        val pluginRows = plugins
            // 防御性去重：host.plugins() 已是按 id 的唯一集合，这里只保证行 key 唯一。
            .distinctBy { it.id }
            .map { plugin ->
                PluginSettingsRow(
                    pluginId = plugin.id,
                    title = plugin.name,
                    // 副标题 = 面向用户的说明；**未声明说明（空串）时回落为插件 id**，
                    // 绝不渲染出空副标题（回落规则见 subtitleOf）。
                    subtitle = subtitleOf(plugin),
                    description = plugin.description,
                    category = categoryOf(plugin.kind),
                    isSynthetic = false,
                    // 没注册设置区 → null：行仍可点开，展开区里给「无可配置项」的兜底提示。
                    presentation = availableSections[plugin.id],
                )
            }

        val selected = pluginRows.filter { it.category == category }
        val hasGrant = category == PluginSettingsCategory.GENERAL &&
            // 真实插件优先：真的有插件叫 tool.grant 时，保留条目让位（见 TOOL_GRANT_ID 注释）。
            pluginRows.none { it.pluginId == TOOL_GRANT_ID }

        val ordered = if (hasGrant) listOf(grantRow) + selected else selected
        return ordered
            .filter { matches(it.pluginId, it.title, it.description, query) }
            .sortedWith(compareBy({ !it.isSynthetic }, { it.pluginId }))
    }

    /**
     * 行副标题：说明文案；**空串（未声明说明）时回落为插件 id**。
     *
     * 回落不是「顺手补一下」：副标题是行上唯一的次要文本，留空会让用户看到一行
     * 「标题下面什么都没有」的条目，既不像插件、也失去了排障用的 id。
     * 单独抽成函数是为了让回落规则**只有一处**，行构造与单测看到的是同一个判断。
     */
    fun subtitleOf(plugin: LianYuPlugin): String = plugin.description.ifEmpty { plugin.id }

    /** Runtime alone drives switches; disabledIds is retained for callers but never masks a running plugin. */
    @Suppress("UNUSED_PARAMETER")
    fun deriveSwitchStates(
        rows: List<PluginSettingsRow>,
        hostLoadedIds: Set<String>,
        disabledIds: Set<String>?,
    ): Map<String, Boolean> = rows
        .filterNot { it.isSynthetic }
        .associate { row -> row.pluginId to (row.pluginId in hostLoadedIds) }
}

/**
 * 点一行会发生什么——由该行设置区的 [PluginSettingsPresentation] 决定。
 *
 * 这是「呈现方式 → 页面行为」的**唯一**映射点（纯逻辑、JVM 可测）：
 * Composable 只读 [PluginSettingsRow.action]，不再自己判断走哪条路径。
 */
enum class PluginRowAction {
    /**
     * 在行内**展开 / 收起**设置区（[PluginSettingsPresentation.INLINE]）。
     *
     * 未注册设置区的行也走这里：展开区里显示「此插件无可配置项」而不是什么都不画——
     * 注册表变化但行未及时刷新时的兜底（见 [PluginSettingsRow.presentation]）。
     */
    TOGGLE_INLINE,

    /**
     * 打开**页内全屏浮层**渲染整个设置区（[PluginSettingsPresentation.FULL_PAGE]）。
     *
     * 为什么不能内联：这类设置区自带 `Scaffold` / 滚动容器，塞进外层
     * `LazyColumn` 的 item 里会拿到**无界最大高度约束** → 运行期抛异常
     * （判定标准见 [PluginSettingsPresentation] 的 KDoc）。
     */
    OPEN_FULL_PAGE,
}

/**
 * 「插件 id 在页面上出现在哪里」——由 [PluginSettingsRow.pluginIdPlacement] 纯推导，
 * Composable 只照着画。
 *
 * 为什么要有这条分流：行的**副标题**现在是说明文案（[LianYuPlugin.description]），
 * 未声明说明时才回落成 id。id 是排障时最需要的信息，任何一行都不许把它弄丢，
 * 但两种行的可用位置不同：
 *
 * | 取值 | 哪些行 | 画在哪 |
 * |---|---|---|
 * | [EXPANDED_SECTION] | 有展开区的行（[PluginRowAction.TOGGLE_INLINE]） | 展开区顶部一行 |
 * | [INLINE] | 没有展开区的行（[PluginRowAction.OPEN_FULL_PAGE]） | 行内副标题下方小字 |
 * | [HIDDEN] | 合成保留条目（[PluginSettingsBoard.TOOL_GRANT_ID]） | 不画 |
 *
 * [HIDDEN] 为什么是对的：保留条目**不是插件**，[PluginSettingsBoard.TOOL_GRANT_ID]
 * 是设置页自己造的入口 id，把它当「插件 id」印在行上只会误导；它的副标题已经说明了用途。
 * 这条同时保证保留条目的行内文案与行为**一处都没变**。
 *
 * 分流规则必须留在这里（纯逻辑、JVM 可测），不能写成 Composable 里的 if——
 * 否则「id 到底还在不在页面上」就零覆盖了（与 [PluginRowAction] 同一套理由）。
 */
enum class PluginIdPlacement {
    /** 展开区顶部一行（有展开区时才可达：收起时展开区整块不渲染）。 */
    EXPANDED_SECTION,

    /** 行内、副标题下方的小字。 */
    INLINE,

    /** 不画（合成保留条目没有插件 id 可言）。 */
    HIDDEN,
}

/**
 * 列表里的一行。
 *
 * [isSynthetic] 区分「保留条目」与「真实插件」：合成条目没有 [PluginHost] 记录，
 * 因此**没有开关**（开关映射 [PluginSettingsBoard.deriveSwitchStates] 会跳过它）。
 *
 * ## 「点这一行会怎样」全部由 [action] 表达
 *
 * [action] 是从 [presentation] 推导出来的纯属性，**不是**第二个可写字段——
 * 于是「行说自己有设置区」与「行知道该走哪条渲染路径」在结构上不可能不一致。
 *
 * ## 插件 id 去哪了：由 [pluginIdPlacement] 表达
 *
 * 副标题现在是**说明文案**（[description]），id 不再天然占着那一行。
 * 但 id 是排障时最需要的信息，**任何一行都不许把它弄丢**——于是它按行型换个位置显示，
 * 规则同样是一条纯推导属性（[pluginIdPlacement]），Composable 只照着画。
 */
data class PluginSettingsRow(
    /** 插件 id；合成条目为 [PluginSettingsBoard.TOOL_GRANT_ID]。 */
    val pluginId: String,
    /** 主标题（插件展示名）。 */
    val title: String,
    /**
     * 副标题：真实插件 = 说明文案（[LianYuPlugin.description]；空串时回落为插件 id，
     * 见 [PluginSettingsBoard.subtitleOf]）；合成条目 = 用途说明。
     *
     * 渲染时**合成条目走资源字符串**（见 PluginSettingsRows），真实插件直接用本字段。
     */
    val subtitle: String,
    /**
     * 插件的 [LianYuPlugin.description]（**逐字**带上来的原始值，不做回落）；
     * 合成条目恒为空串（它不是插件）。
     *
     * 与 [subtitle] 的分工：本字段是「插件声明了什么」的原始事实，[subtitle] 是
     * 「这一行该显示什么」的呈现结果。两者分开才能让「未声明说明」这一情形可断言，
     * 也是搜索能按说明命中的依据（见 [PluginSettingsBoard.matches]）。
     */
    val description: String,
    /** 归属 Tab。 */
    val category: PluginSettingsCategory,
    /** true = 保留条目（工具授权），不是 [PluginHost] 里的插件。 */
    val isSynthetic: Boolean,
    /**
     * 该行**已注册设置区**的呈现方式；null = 没注册设置区
     * （`PluginSettingsSections.forPlugin(pluginId) == null`）。
     *
     * 未注册时行仍可点开：展开区里显示「此插件无可配置项」而不是留白，
     * 否则用户会以为「点了没反应」——这条提示是给「注册表变化但行未及时刷新」的兜底。
     */
    val presentation: PluginSettingsPresentation?,
) {
    /** 是否已注册设置区。由 [presentation] 推导，两者结构上不可能不一致。 */
    val hasSection: Boolean
        get() = presentation != null

    /**
     * 插件 id 画在哪（映射规则见 [PluginIdPlacement]）。
     *
     * 判据只有两条：**这一行有没有展开区**（由 [action] 表达）与**它是不是插件**
     * （由 [isSynthetic] 表达）。刻意不去看 [subtitle] 是否恰好等于 [pluginId]——
     * 那种「字符串相等即隐式隐藏」的写法会让将来某个插件的说明正好等于自己的 id 时
     * 静默丢信息，而且无法在单测里表达意图。
     */
    val pluginIdPlacement: PluginIdPlacement
        get() = when {
            // 保留条目不是插件：它没有 LianYuPlugin.id，行的副标题已经说明用途。
            isSynthetic -> PluginIdPlacement.HIDDEN
            // 有展开区：id 放展开区顶部一行（收起时随展开区一起不渲染）。
            action == PluginRowAction.TOGGLE_INLINE -> PluginIdPlacement.EXPANDED_SECTION
            // 没有展开区（FULL_PAGE 走页内全屏浮层）：id 只能留在行内。
            else -> PluginIdPlacement.INLINE
        }

    /** 插件 id 是否画在**展开区顶部一行**（有展开区，且是真实插件）。 */
    val pluginIdInExpandedSection: Boolean
        get() = pluginIdPlacement == PluginIdPlacement.EXPANDED_SECTION

    /**
     * 行内**此刻**要不要真的画那行 id 小字。
     *
     * 判据 = 落点是行内（[pluginIdPlacement] == [PluginIdPlacement.INLINE]）
     * 且**行内还没有显示过 id**。
     *
     * 后一半为什么需要：插件未声明说明时 [subtitle] 回落成 [pluginId]，此时行内已经有 id 了，
     * 再画一行小字只是重复。注意这里看的是「插件声明了什么」（[description] 是否为空），
     * **不是**「副标题字符串是否恰好等于 id」——后者会把某个说明正好写成自己 id 的插件
     * 判成「已经显示过 id」而静默丢信息（同 [pluginIdPlacement] 的判据说明）。
     */
    val pluginIdInlineVisible: Boolean
        get() = pluginIdPlacement == PluginIdPlacement.INLINE && description.isNotEmpty()

    /**
     * 点这一行会发生什么（映射规则见 [PluginRowAction]）。
     *
     * 穷尽 `when`：将来给 [PluginSettingsPresentation] 加第三个取值，
     * 这里会**编译不过**，逼实现方明确它在页面上的行为，而不是悄悄落到某个分支。
     */
    val action: PluginRowAction
        get() = when (presentation) {
            PluginSettingsPresentation.FULL_PAGE -> PluginRowAction.OPEN_FULL_PAGE
            // INLINE → 内联展开；null（未注册）→ 也展开，让「无可配置项」的兜底提示可达。
            PluginSettingsPresentation.INLINE, null -> PluginRowAction.TOGGLE_INLINE
        }

    /**
     * 该行此刻是否要**内联**渲染设置区。
     *
     * 这是「FULL_PAGE 绝不内联」的**最后一道防线**（纯逻辑、JVM 可测）：即便某行在
     * FULL_PAGE 状态下意外拿到了 `expanded = true`（例如注册表在页面停留期间被换掉），
     * 也不会把整页设置区（自带 Scaffold / 滚动容器）塞进外层 `LazyColumn` 的 item 里
     * ——那正是本契约要修的崩溃。
     */
    fun rendersInline(expanded: Boolean): Boolean =
        expanded && action == PluginRowAction.TOGGLE_INLINE
}
