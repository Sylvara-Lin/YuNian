package com.yunian.ai.feature.settings.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.settings.plugin.PluginIdPlacement
import com.yunian.ai.feature.settings.plugin.PluginRowAction
import com.yunian.ai.feature.settings.plugin.PluginSettingsBoard
import com.yunian.ai.feature.settings.plugin.PluginSettingsRow
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import com.yunian.ai.uicommon.theme.AppTheme

/** 行内图标底座的圆角（与 SettingsScreen 的入口卡片保持同一观感）。 */
private val RowIconShape = RoundedCornerShape(12.dp)

/** 行卡片的圆角。 */
private val RowCardShape = RoundedCornerShape(20.dp)

/**
 * 插件列表的一行：图标 + 标题 / 副标题 + 开关（真实插件）或箭头（保留条目）。
 *
 * 行的点击与开关是**两个独立的交互**：
 * - 点行 = 由 [PluginSettingsRow.action] 决定：INLINE 设置区就地展开 / 收起，
 *   FULL_PAGE 设置区打开**页内全屏浮层**（保留条目 `tool.grant` 也走这一条——
 *   它声明的正是 FULL_PAGE，于是「保留条目」与「真实插件」不再各有一套代码路径）；
 * - 点开关 = 启停插件。
 *
 * 这样布局下「想配置」和「想停用」不会互相误触。
 *
 * **本文件不做任何「呈现方式」判断**：走内联还是走浮层，全部由
 * [PluginSettingsBoard] 在行构造时算好（[PluginSettingsRow.action]），
 * 这里只照着 [PluginRowAction] 分支渲染。
 *
 * 展开区**不留白**：没有注册设置区时显示一行「此插件无可配置项」，
 * 否则用户会以为点了没反应（[PluginSettingsBoard] 只在有设置区时才显示动作提示，
 * 这条提示是给「注册表变化但行未及时刷新」的兜底）。
 *
 * **插件 id 画在哪也由纯逻辑决定**（[PluginSettingsRow.pluginIdPlacement]，规则见
 * [PluginIdPlacement]）：有展开区的行把 id 画在展开区顶部一行，没有展开区的行
 * （`FULL_PAGE` 走页内全屏浮层）把 id 留在行内副标题下方的小字里。
 * 本文件只做 when 分支渲染，不做任何「该不该显示」的判断。
 */
@Composable
internal fun PluginSettingsRowItem(
    row: PluginSettingsRow,
    enabled: Boolean,
    hostAvailable: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenFullPage: (String) -> Unit,
) {
    val colorScheme = AppTheme.colors

    // 「点这一行会发生什么」由 [PluginSettingsRow.action] 决定，本文件不判断呈现方式。
    val onRowClick: () -> Unit = when (row.action) {
        PluginRowAction.OPEN_FULL_PAGE -> ({ onOpenFullPage(row.pluginId) })
        PluginRowAction.TOGGLE_INLINE -> onToggleExpanded
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowCardShape)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RowCardShape,
                surfaceColor = colorScheme.surfaceVariant
            )
            .clickable(onClick = onRowClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RowIconShape)
                    .background(colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (row.isSynthetic) AppIcons.ShieldCheck else AppIcons.Settings,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // 副标题走资源：保留条目显示用途说明，真实插件显示说明文案
                    // （未声明说明时 PluginSettingsBoard 已回落为插件 id，不会是空串）。
                    text = if (row.isSynthetic) {
                        stringResource(R.string.plugin_settings_tool_grant_subtitle)
                    } else {
                        row.subtitle
                    },
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 没有展开区的行（FULL_PAGE）把插件 id 留在行内：副标题被说明文案占住后，
                // 这里是该行唯一能承载排障 id 的位置（副标题本身就是 id 时不重复画）。
                // 要不要画由 PluginSettingsRow.pluginIdInlineVisible 给出，本文件不判断。
                if (row.pluginIdInlineVisible) {
                    Text(
                        text = row.pluginId,
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (row.isSynthetic) {
                // 保留条目没有装载态，因此没有开关——只给一个「进入」的箭头。
                Icon(
                    imageVector = AppIcons.ArrowLeft,
                    contentDescription = stringResource(R.string.plugin_settings_open_tool_grants),
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(180f)
                )
            } else {
                Switch(
                    checked = enabled,
                    // host 缺失（插件系统未初始化）时开关禁用：显示真实状态，但不假装能改。
                    onCheckedChange = if (hostAvailable) onToggleEnabled else null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorScheme.onPrimary,
                        checkedTrackColor = colorScheme.primary,
                        uncheckedThumbColor = colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = colorScheme.surfaceVariant
                    )
                )
            }
        }

        // 行底的动作提示：**只画给真实插件**。
        // - 保留条目的「进入」箭头已经占住了行尾的开关槽（它没有开关），不必再画一个；
        // - 没有设置区的行点了只会展开出「无可配置项」提示，画箭头会指向一个不存在的动作。
        //
        // 图标跟着 [PluginRowAction] 走：INLINE 是「展开 / 收起」的 V 形箭头，
        // FULL_PAGE 换成**「进入」语义**的箭头（与保留条目行同一个 AppIcons.ArrowLeft
        // 旋转 180°，即向右箭头）——画 V 形箭头会让用户以为点下去是在本行展开。
        if (row.hasSection && !row.isSynthetic) {
            val opensFullPage = row.action == PluginRowAction.OPEN_FULL_PAGE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (row.action) {
                        PluginRowAction.OPEN_FULL_PAGE -> AppIcons.ArrowLeft
                        PluginRowAction.TOGGLE_INLINE ->
                            if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown
                    },
                    contentDescription = stringResource(
                        when (row.action) {
                            PluginRowAction.OPEN_FULL_PAGE -> R.string.plugin_settings_open_section
                            PluginRowAction.TOGGLE_INLINE ->
                                if (expanded) R.string.plugin_settings_collapse else R.string.plugin_settings_expand
                        }
                    ),
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (opensFullPage) 180f else 0f)
                )
            }
        }

        // 只有 INLINE 设置区才内联渲染；FULL_PAGE 走页内全屏浮层（见 PluginSettingsScreen）。
        // 这里的守卫是**最后一道防线**：万一某行在 FULL_PAGE 状态下拿到了 expanded = true，
        // 也绝不会把整页设置区（自带 Scaffold / 滚动容器）塞进外层 LazyColumn 的 item 里
        // ——那正是本次契约扩展要修的崩溃。
        AnimatedVisibility(visible = row.rendersInline(expanded)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // 展开区**顶部一行**：插件 id。副标题换成说明文案后，这里是排障 id 的落点
                // （要不要画由 PluginSettingsRow.pluginIdPlacement 决定，本文件不判断）。
                // 与下面的「此插件无可配置项」提示同处一块展开区，两种情形都能看到 id。
                if (row.pluginIdInExpandedSection) {
                    Text(
                        text = "插件 id：" + row.pluginId,
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val section = remember(row.pluginId) {
                    PluginSettingsSections.forPlugin(row.pluginId)
                }
                if (section != null) {
                    section.Content()
                } else {
                    // 没有注册设置区 → 一行提示，不留白。
                    Text(
                        text = stringResource(R.string.plugin_settings_no_config),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
