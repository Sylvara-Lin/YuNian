package com.yunian.ai.feature.chat.ui.message
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.feature.chat.ui.viewmodel.ChatIntent
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.PinkPrimary

@Composable
fun ChatMessageMenu(
    expanded: Boolean,
    message: ChatMessage,
    onDismiss: () -> Unit,
    onIntent: (ChatIntent) -> Unit,
    copyText: String? = null
) {
    val colors = AppTheme.colors
    val dimens = AppTheme.dimens

    val menuBg = colors.surface
    val contentColor = colors.menuContent
    val iconColor = colors.menuIcon
    val borderColor = PinkPrimary.copy(alpha = 0.22f)

    val iconSize = dimens.menuIconSize
    val labelSize = dimens.menuTextFontSize
    val itemHorizontalPadding = 12.dp
    val itemVerticalPadding = 7.dp
    val iconTextGap = 10.dp
    val menuMinWidth = 140.dp
    val menuMaxWidth = 168.dp
    val menuShape = RoundedCornerShape(14.dp)

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(extraSmall = menuShape)
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = menuMinWidth, max = menuMaxWidth)
                .clip(menuShape)
                // 液态玻璃：DropdownMenu 是独立 Popup window，LocalPageBackdrop 通常为 null，
                // 此时 drawGlass 会退化为纯色 surfaceColor，因此必须传 menuBg 兜底，否则菜单会近乎透明不可见。
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = menuShape,
                    surfaceColor = menuBg
                )
                .border(1.dp, borderColor, menuShape),
            offset = DpOffset(x = 0.dp, y = (-4).dp),

            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            val actions = buildList {
                add(
                    MenuAction(
                        text = "引用",
                        icon = AppIcons.Quote,
                        contentColor = contentColor,
                        iconColor = iconColor
                    ) { onIntent(ChatIntent.QuoteReply(message)) }
                )
                if (message.type == MessageType.IMAGE && message.linkString.isNotBlank()) {
                    add(
                        MenuAction(
                            text = "保存图片",
                            icon = AppIcons.Download,
                            contentColor = contentColor,
                            iconColor = iconColor
                        ) { onIntent(ChatIntent.SaveImage(message.linkString)) }
                    )
                }
                if (!message.isFromUser) {
                    add(
                        MenuAction(
                            text = "重新生成",
                            icon = AppIcons.RefreshCw,
                            contentColor = contentColor,
                            iconColor = iconColor
                        ) { onIntent(ChatIntent.Regenerate(message)) }
                    )
                }
                if (copyText != null) {
                    add(
                        MenuAction(
                            text = "复制",
                            icon = AppIcons.Copy,
                            contentColor = contentColor,
                            iconColor = iconColor
                        ) { onIntent(ChatIntent.CopyText(copyText)) }
                    )
                }
                add(
                    MenuAction(
                        text = "撤回",
                        icon = AppIcons.Trash2,
                        contentColor = colors.danger,
                        iconColor = colors.danger
                    ) { onIntent(ChatIntent.Recall(message)) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            ) {
                actions.forEach { action ->
                    ChatMenuItem(
                        text = action.text,
                        icon = action.icon,
                        contentColor = action.contentColor,
                        iconColor = action.iconColor,
                        iconSize = iconSize,
                        labelSize = labelSize,
                        horizontalPadding = itemHorizontalPadding,
                        verticalPadding = itemVerticalPadding,
                        iconTextGap = iconTextGap
                    ) {
                        onDismiss()
                        action.onClick()
                    }
                }
            }
        }
    }
}

private data class MenuAction(
    val text: String,
    val icon: ImageVector,
    val contentColor: Color,
    val iconColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun ChatMenuItem(
    text: String,
    icon: ImageVector,
    contentColor: Color,
    iconColor: Color,
    iconSize: Dp,
    labelSize: TextUnit,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    iconTextGap: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(iconTextGap))
        Text(
            text = text,
            color = contentColor,
            fontSize = labelSize,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
