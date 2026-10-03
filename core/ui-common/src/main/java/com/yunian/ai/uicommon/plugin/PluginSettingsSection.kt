package com.yunian.ai.uicommon.plugin

import androidx.compose.runtime.Composable

/**
 * 插件设置区在「设置」页里的归属分组。
 *
 * 只决定**渲染位置**（挂在哪一段列表里），不决定权限、不决定可见性：
 * 可见性由插件自身的装载状态决定——注册由插件的 `setup()` 发起、由 `ctx.effect` 撤销，
 * 所以插件一停用，它的设置区就自动从页面上消失（见 [PluginSettingsSections] 的生命周期说明）。
 */
enum class PluginSettingsCategory {
    /** 消息通道类插件（QQ / 微信等通道适配器）的设置。 */
    CHANNEL,

    /** 通用类插件的设置（工具、技能、表情包等非通道插件）。 */
    GENERAL,
}

/**
 * 插件设置区的**呈现方式**：设置页该把这块 UI 放在哪里。
 *
 * 这不是风格偏好，而是**安全属性**——选错会崩溃：
 *
 * | 取值 | 设置页怎么渲染 | 设置区必须满足 |
 * |---|---|---|
 * | [INLINE] | 直接内联在列表项的**展开区**里 | 自身**不能**带滚动容器 / `Scaffold` / 背景层 |
 * | [FULL_PAGE] | **整页**呈现（页内全屏浮层，自带顶栏与背景） | 无额外要求——它本来就是整页 |
 *
 * ## 判定标准（照抄就能选对）
 *
 * 只要设置区**满足下列任意一条**，就必须声明 [FULL_PAGE]：
 *
 * 1. 内部用了 `Scaffold`（含本仓库的 `GlassPageScaffold`）；
 * 2. 内部用了 `LazyColumn` / `LazyRow` 等**惰性**滚动容器；
 * 3. 内部用了 `Modifier.verticalScroll` / `horizontalScroll`（非惰性滚动容器同理）；
 * 4. 依赖 `LocalPageBackdrop`（液态玻璃的**同窗口**背景捕获源）才能正常出图。
 *
 * 第 1–3 条会让设置区自带一个**纵向滚动容器**：设置页把它塞进外层 `LazyColumn`
 * 的一个 item 里，内层滚动容器会拿到**无界最大高度约束**，运行期直接抛异常。
 * 第 4 条则是跨窗口采样失效——放进独立窗口（Dialog）后液态玻璃会整片变透明。
 *
 * 反过来说，只有**纯静态、无滚动容器、不依赖背景捕获**的设置区（例如几个开关 +
 * 一段说明文字）才适合 [INLINE]。拿不准就选 [FULL_PAGE]。
 *
 * ## 为什么默认值是 [FULL_PAGE]（fail-safe 方向）
 *
 * 契约把默认值定成 [FULL_PAGE]，**不是**因为它更常见，而是因为它是**安全的那一侧**：
 *
 * - 该 [FULL_PAGE] 却写成 [INLINE] → 整页设置区被塞进列表项 → **运行期崩溃**；
 * - 该 [INLINE] 却写成 [FULL_PAGE] → 只是多一层全屏浮层 → **不崩**，只多点一下。
 *
 * 两种误判的代价**不对称**，所以默认值必须落在「不会崩」的那一侧。于是 [INLINE]
 * 成了**必须显式声明**的 opt-in：忘记声明的实现方最坏只是呈现方式不够精致，
 * 不会把设置页打崩。
 *
 * 设置页在注册表里**查不到**某行对应的设置区时，也按 [FULL_PAGE] 处理
 * （见 `PluginSettingsBoard.buildRows`），与这里的默认值同向。
 */
enum class PluginSettingsPresentation {
    /**
     * 可安全**内联**渲染在列表项的展开区里。
     *
     * 声明它等于承诺：本设置区**没有** `Scaffold` / `LazyColumn` /
     * `Modifier.verticalScroll`，也**不依赖** `LocalPageBackdrop`。
     */
    INLINE,

    /**
     * **整页**设置区（自带 `Scaffold` / 滚动容器 / 背景层），必须整页呈现。
     *
     * 默认值：不确定就留在这里——最坏只是多一层浮层，不会崩。
     */
    FULL_PAGE,
}

/**
 * 插件设置区贡献契约：插件把自己的设置界面挂进「设置」页的一块自描述区块。
 *
 * ## 为什么本文件不引用 `core:domain`
 *
 * `core:ui-common` 只依赖 `core:common`（见 `core/ui-common/build.gradle.kts`），
 * **不依赖 `core:domain`**。因此本契约必须**自足**：
 *
 * - 归属用 [PluginSettingsCategory]（本模块自定义的渲染分组），
 *   而**不是** `com.yunian.ai.domain.plugin.PluginKind`（那个是装配语义分组，且跨模块不可见）；
 * - 插件身份只用 [pluginId] 这个字符串表达，不引用 `LianYuPlugin` / `PluginManifest` 等类型。
 *
 * 两边靠**同一个字符串 id** 对齐——这正是 `com.yunian.ai.domain.plugin.LianYuPlugin.id`
 * 的取值域，插件实现方自己保证一致。**当前 7 个插件的真实 id**：
 *
 * ```
 * channel.qqbot                  channel.wechat
 * automation.core                message.send
 * skill.builtin_chat_protocol    sticker.preference
 * ui.assists
 * ```
 *
 * ⚠️ **不要把插件 id 与「通道键」混为一谈**：通道键是
 * `com.yunian.ai.domain.ChannelKeys` 的取值域（`qqbot` / `wechat` / `app.chat`），
 * 与插件 id（`channel.qqbot` / `channel.wechat`）**不同**。本契约只认**插件 id**。
 *
 * ## 生命周期
 *
 * 实现方在插件 `setup()` 里注册，并用 `ctx.effect` 把注销登记为可逆副作用：
 *
 * ```kotlin
 * override fun setup(ctx: PluginContext) {
 *     PluginSettingsSections.register(MySettingsSection)
 *     ctx.effect({ PluginSettingsSections.unregister(id) }, "settings-section")
 * }
 * ```
 *
 * 于是「插件停用 → 设置区自动消失」，不需要设置页做任何特判。
 */
interface PluginSettingsSection {

    /**
     * 贡献者插件的唯一 id，必须与 `LianYuPlugin.id` **逐字一致**
     * （如 `channel.wechat` / `channel.qqbot` / `ui.assists`）。
     *
     * ⚠️ 不是通道键：`qqbot` / `wechat` 是 `ChannelKeys` 的取值域，写错会导致
     * [PluginSettingsSections.forPlugin] 永远查不到、设置区静默不显示。
     *
     * 该 id 同时是注册表的**幂等键**（同 id 重复注册 = 覆盖）与**排序键**
     * （[PluginSettingsSections.all] 按它升序，保证 Compose 列表顺序稳定不跳）。
     */
    val pluginId: String

    /** 渲染分组（决定挂到设置页的哪一段）。 */
    val category: PluginSettingsCategory

    /**
     * 本设置区的**呈现方式**（判定标准见 [PluginSettingsPresentation] 的 KDoc）。
     *
     * 默认 [PluginSettingsPresentation.FULL_PAGE] 是**安全默认**：忘记声明的实现方
     * 最坏只是让设置区以整页浮层呈现，**不会**像错选 `INLINE` 那样把设置页打崩。
     * 也就是说 `INLINE` 必须由实现方**显式声明**——那是一次有意的承诺。
     */
    val presentation: PluginSettingsPresentation
        get() = PluginSettingsPresentation.FULL_PAGE

    /**
     * 设置区内容。由设置页在**已成立的 Composition 作用域**内调用，因此实现方
     * 可以直接使用 `remember` / `collectAsState` 等组合期 API。
     *
     * 渲染位置由 [presentation] 决定：`INLINE` 内联进列表项的展开区，
     * `FULL_PAGE` 走页内全屏浮层。两种方式都在**同一个 Composition**、
     * **同一个窗口**里，所以 `LocalPageBackdrop` 这类同窗口资源都拿得到。
     *
     * 无参：区块自己从插件持有的状态或 `ServiceRegistry` 取数据；
     * 设置页不向插件传参，避免宿主与插件之间出现反向依赖。
     */
    @Composable
    fun Content()
}
