package com.yunian.ai.domain

/**
 * 工具名 → 界面友好名（中文短名）的**全局唯一映射**。
 *
 * 归属 core:domain：`feature:chat`（消息流过程卡片）与 `feature:skills`（AI 控制手机悬浮窗）
 * 都要把工具原始名渲染成中文短名，而 **feature 之间不能互相依赖**，故此映射下沉到两者
 * 共同依赖的 core:domain，避免各自复制一份、口径漂移。
 *
 * 仅承载「字符串映射」这一最小事实，不含任何 UI/Android 语义。
 */
object ToolFriendlyNames {

    /** 未映射时回退到 [ToolRegistry] 描述首行的最大长度（保持与既有卡片一致）。 */
    private const val FALLBACK_DESCRIPTION_MAX = 24

    /**
     * 取工具友好名：优先命中显式映射；否则取该工具在 [ToolRegistry] 中描述的首个非空行
     * （截断）；再否则回退原始名。
     */
    fun of(toolName: String): String {
        MAP[toolName]?.let { return it }
        return ToolRegistry.get(toolName)?.let { tool ->
            tool.description.lineSequence().firstOrNull { it.isNotBlank() }?.take(FALLBACK_DESCRIPTION_MAX)
        } ?: toolName
    }

    private val MAP: Map<String, String> = mapOf(
        // ── Agent 原生工具（Rust / AgentToolHost 特判，不经过本地 ToolRegistry）──
        "load_skill" to "加载技能",
        "save_memory" to "记住这件事",
        "consolidate_memory" to "整理记忆",
        "emit_bubble" to "分条回复",
        "emit_segmented" to "分条回复",
        "send_sticker" to "发表情",
        "sticker_pick" to "挑表情",
        "delegate_task" to "安排子任务",
        "fetch_delegation_result" to "取回子任务结果",
        "core_status" to "查看运行状态",
        "commerce_buy" to "下单购买",
        "order_coffee" to "点咖啡",
        // ── 本地注册工具（走 ToolRegistry，此处提供短名以避免取整段描述）──
        "skillhub_search" to "搜索技能商店",
        "skill_install" to "安装技能",
        "skill_uninstall" to "卸载技能",
        "recall_memory" to "翻看记忆",
        "search_web" to "联网搜索",
        "web_fetch" to "读取网页",
        "recent_chats" to "翻看最近会话",
        "conversation_search" to "搜索历史对话",
        "device_open_app" to "打开应用",
        "device_open_url" to "打开网页",
        "device_get_clipboard" to "读取剪贴板",
        "device_set_clipboard" to "写入剪贴板",
        "device_set_alarm" to "设置闹钟",
        "device_notify" to "发送通知",
        "device_battery_status" to "查看电量",
        "device_get_time" to "看时间",
        "accessibility_status" to "检查手机控制",
        "screen_read" to "读取屏幕",
        "screen_tap" to "点击屏幕",
        "screen_swipe" to "滑动屏幕",
        "screen_click_text" to "点击屏幕元素",
        "press_back" to "按返回键",
        "go_home" to "回到主屏",
        "shizuku_status" to "检查 Shizuku",
        "automation_create" to "创建自动化",
        "automation_create_workflow" to "创建工作流",
        "automation_list" to "查看自动化",
        "automation_cancel" to "取消自动化",
        "automation_fire" to "触发自动化",
        "luckin_query_shops" to "查找咖啡门店",
        "luckin_search_products" to "搜索咖啡商品",
        "luckin_preview_order" to "预览咖啡订单",
        "luckin_create_order" to "下单咖啡",
        "luckin_query_order" to "查询咖啡订单",
        "luckin_cancel_order" to "取消咖啡订单",
    )
}
