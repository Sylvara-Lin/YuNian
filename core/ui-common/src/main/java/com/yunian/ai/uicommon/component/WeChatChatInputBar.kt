@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
package com.yunian.ai.uicommon.component
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.uicommon.R
import com.yunian.ai.uicommon.model.ApiProviderInfo
import com.yunian.ai.uicommon.theme.AdaptiveSizing
import com.yunian.ai.uicommon.theme.rememberAdaptiveSizing

@Composable
fun WeChatChatInputBar(
    onSendMessage: (String) -> Unit,
    isLoading: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    availableApis: List<ApiProviderInfo> = emptyList(),
    currentApi: ApiProviderInfo? = null,
    onSwitchApi: ((ApiProviderInfo) -> Unit)? = null,
    onPlusClick: (() -> Unit)? = null,
    onVoiceRecordStart: (() -> Unit)? = null,
    onVoiceRecordStop: (() -> Unit)? = null,
    onVoiceRecordCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showApiSelector by remember { mutableStateOf(false) }
    val adaptiveSizing = rememberAdaptiveSizing()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val controlHeight = adaptiveSizing.inputBarHeight

    Column(modifier = modifier) {
        AnimatedVisibility(
            visible = showApiSelector && availableApis.size > 1,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200))
        ) {
            ApiSelectorPanel(
                availableApis = availableApis,
                currentApi = currentApi,
                onSwitchApi = { api ->
                    onSwitchApi?.invoke(api)
                    showApiSelector = false
                },
                adaptiveSizing = adaptiveSizing
            )
        }

        Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(
                    onClick = { onPlusClick?.invoke() },
                    modifier = Modifier.size(controlHeight)
                ) {
                    Icon(
                        imageVector = AppIcons.Plus,
                        contentDescription = stringResource(R.string.more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(adaptiveSizing.iconSize)
                    )
                }

                Box(
                    modifier = Modifier.weight(1f).heightIn(min = controlHeight, max = 120.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .drawGlass(
                            backdrop = LocalPageBackdrop.current,
                            shape = RoundedCornerShape(21.dp),
                            surfaceColor = surfaceVariant
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = text, onValueChange = onTextChange,
                            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty()) {
                                        Text(stringResource(R.string.input_message_hint),
                                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
                                            color = hintColor,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    innerTextField()
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (text.isNotBlank()) {
                                        onSendMessage(text.trim())
                                    }
                                }
                            ),
                            maxLines = 5,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp, color = textColor)
                        )

                        if (onVoiceRecordStart != null && text.isEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            VoiceHoldButton(
                                onRecordStart = { onVoiceRecordStart() },
                                onRecordStop = { onVoiceRecordStop?.invoke() },
                                onRecordCancel = { onVoiceRecordCancel?.invoke() },
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                val canSend = text.isNotBlank()
                val sendBg = if (canSend) MaterialTheme.colorScheme.primary else surfaceVariant
                val sendIconTint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier.size(controlHeight).clip(CircleShape)
                        .background(sendBg)
                        .combinedClickable(
                            enabled = canSend || availableApis.size > 1,
                            onClick = {
                                if (canSend) {
                                    onSendMessage(text.trim())
                                }
                            },
                            onLongClick = {
                                if (availableApis.size > 1) showApiSelector = true
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = AppIcons.Send, contentDescription = stringResource(R.string.send),
                        tint = sendIconTint,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun VoiceHoldButton(
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isRecording by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var shouldCancel by remember { mutableStateOf(false) }

    val iconTint = when {
        isRecording -> Color(0xFFFF3B30)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .pointerInput(onRecordStart, onRecordStop, onRecordCancel) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragOffset = Offset.Zero
                        shouldCancel = false
                        isRecording = true
                        onRecordStart()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        shouldCancel = dragOffset.y < -80.dp.toPx()
                    },
                    onDragEnd = {
                        if (shouldCancel) onRecordCancel() else onRecordStop()
                        isRecording = false
                        dragOffset = Offset.Zero
                        shouldCancel = false
                    },
                    onDragCancel = {
                        if (isRecording) onRecordCancel()
                        isRecording = false
                        dragOffset = Offset.Zero
                        shouldCancel = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AppIcons.Mic,
            contentDescription = "按住录音",
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApiSelectorPanel(
    availableApis: List<ApiProviderInfo>,
    currentApi: ApiProviderInfo?,
    onSwitchApi: (ApiProviderInfo) -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = "切换模型:",
            fontSize = adaptiveSizing.fontSizeSmall.sp,
            color = onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableApis.forEach { api ->
                val isSelected = api.name == currentApi?.name
                val chipBg = if (isSelected) api.color else surfaceVariant.copy(alpha = 0.6f)
                val chipText = if (isSelected) Color.White else onSurfaceVariant
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(chipBg)
                        .clickable { onSwitchApi(api) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = api.displayName,
                        fontSize = adaptiveSizing.fontSizeSmall.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = chipText
                    )
                }
            }
        }
    }
}
