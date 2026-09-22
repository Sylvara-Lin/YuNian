package com.yunian.ai.feature.wechat.ui


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import android.graphics.Bitmap
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeChatBindScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: WeChatViewModel = viewModel(factory = remember { WeChatViewModelFactory(context.applicationContext as android.app.Application) })
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WeChatEvent.LoginSuccess -> {
                    onNavigateBack()
                }
                else -> {}
            }
        }
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "绑定微信",
                onBack = onNavigateBack
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                uiState.isLoggedIn -> {

                    Text(
                        text = "✅ 微信已绑定",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.success
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bot ID: ${uiState.account?.ilinkBotId ?: ""}",
                        fontSize = 14.sp,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.logout() },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("解除绑定")
                    }
                }

                uiState.showQrCode && uiState.qrCodeContent != null -> {
                    val qrCodeContent = uiState.qrCodeContent.orEmpty()
                    Text(
                        text = "请使用微信扫码",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val qrBitmap = rememberQrBitmap(qrCodeContent, size = 240)
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.colors.staticWhite)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "微信登录二维码",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "生成二维码中...",
                                fontSize = 12.sp,
                                color = AppTheme.colors.staticBlack,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "请在手机上打开微信，扫描上方二维码并确认登录",
                        fontSize = 14.sp,
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.cancelQrLogin() },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("取消")
                    }
                }

                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = AppTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在获取二维码...",
                        fontSize = 16.sp,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "绑定微信个人号",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "通过微信 ilink 协议，你的 AI 伴侣可以直接在微信中与你对话。",
                        fontSize = 14.sp,
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = { viewModel.startQrCodeLogin() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("开始扫码绑定")
                    }
                }
            }

            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = error,
                    fontSize = 14.sp,
                    color = AppTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun rememberQrBitmap(content: String, size: Int): Bitmap? {
    return remember(content, size) {
        generateQrBitmap(content, size)
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return try {
        val writer = MultiFormatWriter()
        val bitMatrix: BitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
