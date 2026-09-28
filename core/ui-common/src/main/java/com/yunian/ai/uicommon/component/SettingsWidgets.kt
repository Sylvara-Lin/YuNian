package com.yunian.ai.uicommon.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.theme.AppTheme

/**
 * 设置类页面的通用行组件（2026-09-16 精简批次：由 ChatDetail/Dnd/QQBot/WeChat 四处
 * 逐字相同的私有实现合并而来，视觉行为与合并前保持一致）。
 *
 * 注意：各处的 `SectionTitle` 视觉变体（有无图标/padding/字重）**不是**重复实现，
 * 属不同视觉设计，未合并。
 */

/** 图标 + 标题/副标题 + 尾部插槽的设置行（原 QQBot/WeChat 的 SettingItem）。 */
@Composable
fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(text = title, fontSize = 14.sp, color = AppTheme.colors.onSurface, maxLines = 1)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 1
            )
        }
        trailing()
    }
}

/** 标题/副标题 + 右侧「›」的可点击设置行（原 ChatDetail 的 SettingsRow / Dnd 的 DndSettingsRow）。 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = title,
                fontSize = 15.sp,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.4f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = colors.metadataContent.copy(alpha = if (enabled) 1f else 0.4f)
                )
            }
        }
        Text(text = "›", fontSize = 18.sp, color = colors.outline.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** 标题/副标题 + 右侧 Switch 的设置行（原 ChatDetail 的 SettingsToggleRow / Dnd 的 DndSettingsToggleRow）。 */
@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = colors.onSurface)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = colors.metadataContent)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.primary,
                checkedTrackColor = colors.primary.copy(alpha = 0.5f)
            )
        )
    }
}
