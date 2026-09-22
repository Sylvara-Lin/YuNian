package com.yunian.ai.feature.settings.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme

/** 液态玻璃编辑弹窗统一圆角：与顶栏 GlassTopBar 保持一致。 */
private val GlassEditDialogShape = RoundedCornerShape(28.dp)

/** 内容区最大高度，超出后内部滚动，避免键盘弹出时按钮被挤出屏幕。 */
private val GlassEditDialogContentMaxHeight = 460.dp

/** 弹窗最大宽度：平板 / 横屏下不至于被拉伸得过长，影响可读性。 */
private val GlassEditDialogMaxWidth = 420.dp

/**
 * 液态玻璃材质的编辑弹窗容器。
 *
 * - 背景：复用 [LocalPageBackdrop] 采样当前页面背景，配合 [drawGlass] 做 vibrancy + blur + lens；
 *   backdrop 为 null 时 [drawGlass] 内部自动退化为纯色，不会崩溃。
 * - 形状：固定 28dp 圆角（[GlassEditDialogShape]），满足 lens 效果只支持 CornerBasedShape 的约束。
 * - 结构：标题 / 可滚动内容区 / 底部按钮行，按钮行由调用方通过 [actions] 自行编排。
 */
@Composable
internal fun GlassEditDialog(
    onDismissRequest: () -> Unit,
    title: String,
    titleColor: Color,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val backdrop = LocalPageBackdrop.current
    val surfaceColor = AppTheme.colors.surfaceVariant
    val outlineColor = AppTheme.colors.outline

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                // widthIn 必须写在 fillMaxWidth 之前：fillMaxWidth 会把 min/max 钉成同一个值，
                // 之后的 widthIn(max) 会被 coerceIn 吃掉而不生效
                .widthIn(max = GlassEditDialogMaxWidth)
                .fillMaxWidth(0.94f)
                .clip(GlassEditDialogShape)
                .drawGlass(
                    backdrop = backdrop,
                    shape = GlassEditDialogShape,
                    surfaceColor = surfaceColor
                )
                .border(1.dp, outlineColor.copy(alpha = 0.35f), GlassEditDialogShape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                color = titleColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = GlassEditDialogContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}
