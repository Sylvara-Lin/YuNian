package com.yunian.ai.feature.chat.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.common.StickerInfo
import com.yunian.ai.feature.chat.ui.message.QuoteMediaThumbnail
import com.yunian.ai.feature.chat.ui.viewmodel.QuoteReply
import com.yunian.ai.feature.chat.voice.ChatTtsState
import com.yunian.ai.uicommon.component.ChatInputExtensionPanel
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.StickerPanel
import com.yunian.ai.uicommon.component.WeChatChatInputBar
import com.yunian.ai.uicommon.model.ApiProviderInfo
import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.LocalChatGlassBackdrop

@Composable
fun ChatInputRegion(
    isBlocked: Boolean,
    isLoading: Boolean,
    quoteReply: QuoteReply?,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") ttsState: ChatTtsState,
    showStickerPanel: Boolean,
    showExtensionPanel: Boolean,
    availableApis: List<ApiProviderInfo>,
    currentApi: ApiProviderInfo?,
    @Suppress("UNUSED_PARAMETER") onStopTtsClick: () -> Unit,
    onStickerClick: (StickerInfo) -> Unit,
    onImportStickersClick: () -> Unit,
    onDeleteAllStickersClick: () -> Unit,
    onSwitchApi: (ApiProviderInfo) -> Unit,
    onClearQuoteReply: () -> Unit,
    onAlbumClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: () -> Unit,
    onTtsModeClick: () -> Unit,
    onLocationClick: () -> Unit,
    onVoiceInputClick: () -> Unit,
    onStickerPanelClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onPlusClick: () -> Unit,
    onVoiceRecordStart: () -> Unit,
    onVoiceRecordStop: () -> Unit,
    onVoiceRecordCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {

        if (isBlocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(28.dp),
                        surfaceColor = colors.surfaceVariant
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "你已拉黑该联系人",
                    fontSize = 14.sp,
                    color = colors.error,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(28.dp),
                        surfaceColor = colors.surfaceVariant
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WeChatChatInputBar(
                        onSendMessage = onSendMessage,
                        isLoading = isLoading,
                        text = inputText,
                        onTextChange = onInputTextChange,
                        availableApis = availableApis,
                        currentApi = currentApi,
                        onSwitchApi = onSwitchApi,
                        onPlusClick = onPlusClick,
                        onVoiceRecordStart = onVoiceRecordStart,
                        onVoiceRecordStop = onVoiceRecordStop,
                        onVoiceRecordCancel = onVoiceRecordCancel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (quoteReply != null) {
                        QuoteReplyPreview(
                            quoteReply = quoteReply,
                            onClearClick = onClearQuoteReply
                        )
                    }
                }
            }
        }

        StickerPanel(
            isVisible = showStickerPanel,
            onStickerClick = onStickerClick,
            onImportClick = onImportStickersClick,
            onDeleteAllClick = onDeleteAllStickersClick,
            // 液态玻璃：取样聊天页背景 backdrop（null 时面板内部自动回退纯色）
            backdrop = LocalChatGlassBackdrop.current
        )

        ChatInputExtensionPanel(
            isVisible = showExtensionPanel,
            availableApis = availableApis,
            currentApi = currentApi,
            onSwitchApi = onSwitchApi,
            onAlbumClick = onAlbumClick,
            onCameraClick = onCameraClick,
            onVideoCallClick = onVideoCallClick,
            onVoiceCallClick = onVoiceCallClick,
            onTtsModeClick = onTtsModeClick,
            onLocationClick = onLocationClick,
            onVoiceInputClick = onVoiceInputClick,
            onStickerClick = onStickerPanelClick
        )
    }
}

@Composable
private fun QuoteReplyPreview(
    quoteReply: QuoteReply,
    onClearClick: () -> Unit
) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colors.surface
            )
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (quoteReply.hasMediaThumbnail) {
            QuoteMediaThumbnail(
                quote = quoteReply,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "引用 ${quoteReply.authorName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary
            )
            Text(
                text = quoteReply.previewText,
                fontSize = 12.sp,
                color = colors.metadataContent,
                maxLines = 1
            )
        }
        IconButton(
            onClick = onClearClick,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = "取消引用",
                tint = colors.metadataContent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
