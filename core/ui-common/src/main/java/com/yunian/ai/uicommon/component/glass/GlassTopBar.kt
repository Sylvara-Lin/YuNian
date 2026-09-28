package com.yunian.ai.uicommon.component.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme

/**
 * 二级页面统一的「椭圆胶囊」液态玻璃返回栏。
 *
 * - 形态：28dp 圆角的胶囊栏，与聊天页顶栏一致。
 * - 背景：真液态玻璃（drawBackdrop + vibrancy/blur/lens），折射页面背景，非写死色块。
 * - 布局：返回按钮 + 居中标题 + 可选右侧动作（左右各 32dp 动作位，保证标题绝对居中）。
 * - 默认自行处理状态栏 inset（`statusBarsPadding = true`）。
 */
@Composable
fun GlassTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    surfaceColor: Color = AppTheme.colors.surfaceVariant,
    titleColor: Color = AppTheme.colors.onSurface,
    statusBarsPadding: Boolean = true,
    enabled: Boolean = true,
    actions: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (statusBarsPadding) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 胶囊玻璃栏：标题绝对居中，左右动作自由宽度（支持多动作）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(28.dp),
                    surfaceColor = surfaceColor
                )
                .height(48.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 56.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "返回",
                        tint = titleColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Box(
                modifier = Modifier.align(Alignment.CenterEnd),
                contentAlignment = Alignment.Center
            ) {
                actions()
            }
        }
    }
}
