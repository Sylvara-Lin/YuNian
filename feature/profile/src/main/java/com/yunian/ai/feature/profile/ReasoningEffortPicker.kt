package com.yunian.ai.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.common.ReasoningEffort
import com.yunian.ai.uicommon.theme.AppTheme

/**
 * 模型思考程度选择器（思考设置弹窗内）。
 *
 * 档位经 [ReasoningEffort] 归一；Rust 侧按 provider 能力门控注入请求参数，
 * 不支持的 provider 不注入（不影响可用性），因此这里无需按 provider 展示差异。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningEffortPicker(
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean = true,
) {
    val current = ReasoningEffort.fromWire(selected)
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "模型思考程度",
            fontSize = 15.sp,
            color = AppTheme.colors.onSurface,
        )
        Text(
            text = "调节模型思考的深度：档位越高回答越深思熟虑，但耗时与 token 消耗也更多。" +
                "仅对支持该参数的模型生效，其余模型保持默认。",
            fontSize = 12.sp,
            color = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReasoningEffort.entries.forEach { effort ->
                FilterChip(
                    selected = current == effort,
                    onClick = { onSelect(effort.wire) },
                    enabled = enabled,
                    label = { Text(effort.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppTheme.colors.primaryContainer,
                    ),
                )
            }
        }
    }
}
