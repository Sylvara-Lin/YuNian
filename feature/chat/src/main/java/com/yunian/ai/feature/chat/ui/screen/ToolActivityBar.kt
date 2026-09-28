package com.yunian.ai.feature.chat.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.chat.ui.viewmodel.ToolActivity
import com.yunian.ai.feature.chat.ui.viewmodel.ToolStatus
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme

/**
 * 消息流内的工具调用卡片组：渲染某一轮生成的全部工具活动。
 *
 * 卡片本体复用 [ToolActivityCard]（液态玻璃 + 状态图标 + 友好名）；RUNNING 卡片保留脉动动画，
 * 因此当前轮进行中时也能在消息流里实时刷新。条数过多时仅展示最近 [MAX_VISIBLE_TOOL_CARDS] 条并提示总数。
 */
@Composable
fun ToolActivityGroupItem(
    activities: List<ToolActivity>,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (activities.isEmpty()) return
    val visible = activities.takeLast(MAX_VISIBLE_TOOL_CARDS)
    val overflow = activities.size - visible.size
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(if (isLive) "tool_activity_live" else "tool_activity_group"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (overflow > 0) {
            Text(
                text = "共 ${activities.size} 条工具调用（仅显示最近 ${MAX_VISIBLE_TOOL_CARDS} 条）",
                fontSize = 11.sp,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        visible.forEach { activity ->
            ToolActivityCard(activity)
        }
    }
}

private const val MAX_VISIBLE_TOOL_CARDS = 4

@Composable
private fun ToolActivityCard(activity: ToolActivity) {
    val colors = AppTheme.colors
    val pulse = rememberPulseAlpha()
    val running = activity.status == ToolStatus.RUNNING

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(12.dp),
                surfaceColor = colors.surfaceVariant,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (activity.status) {
            ToolStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = colors.primary,
            )
            ToolStatus.DONE -> StatusIcon(AppIcons.Check, colors.success, pulse)
            ToolStatus.FAILED -> StatusIcon(AppIcons.X, colors.error, pulse)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = friendlyToolName(activity.toolName),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (activity.argsSummary.isNotBlank() && activity.argsSummary != "{}") {
                Text(
                    text = activity.argsSummary,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (running) {
            Text(
                text = "执行中",
                fontSize = 11.sp,
                color = colors.primary,
                modifier = Modifier.alpha(pulse),
            )
        } else {
            Text(
                text = if (activity.status == ToolStatus.DONE) "完成" else "失败",
                fontSize = 11.sp,
                color = if (activity.status == ToolStatus.DONE) colors.success else colors.error,
            )
        }
    }
}

/** 完成态的状态图标（Icon 天然几何居中，避免文本基线偏移） */
@Composable
private fun StatusIcon(icon: ImageVector, color: Color, pulse: Float) {
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(11.dp)
                .alpha(pulse),
        )
    }
}

/** RUNNING 状态的呼吸脉冲透明度 */
@Composable
private fun rememberPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "toolPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "toolPulseAlpha",
    )
    return alpha
}

/** 工具名 → 界面友好名（未映射的走 ToolRegistry 描述首行/原名） */
private fun friendlyToolName(toolName: String): String {
    val mapped = TOOL_FRIENDLY_NAMES[toolName]
    if (mapped != null) return mapped
    return ToolRegistry.get(toolName)?.let { tool ->
        tool.description.lineFirstOrNull() ?: toolName
    } ?: toolName
}

private fun String.lineFirstOrNull(): String? =
    lineSequence().firstOrNull { it.isNotBlank() }?.take(24)

private val TOOL_FRIENDLY_NAMES = mapOf(
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
