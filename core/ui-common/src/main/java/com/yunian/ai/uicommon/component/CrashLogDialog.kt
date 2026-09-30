package com.yunian.ai.uicommon.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「上次闪退日志」弹窗。
 *
 * 纯展示组件：**不依赖任何 ViewModel / Repository / DB**（崩溃很可能由 DB 引起），
 * 只接收一段已格式化好的日志文本与三个回调。这样即使数据库处于损坏状态，
 * 弹窗也能安全显示，用户可「一键复制」把日志发给开发者。
 *
 * @param logText   要展示的崩溃报告全文（由 core:common 的 CrashLogStore 提供）。
 * @param onDismiss 用户点「关闭」——仅关闭本次展示，记录保留到下次启动再提示。
 * @param onClear   用户点「清除记录」——删除落盘记录，之后不再提示。
 * @param onCopied  复制成功后的回调（可选）。
 */
@Composable
fun CrashLogDialog(
    logText: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onCopied: () -> Unit = {},
    title: String = "上次运行发生了闪退",
    subtitle: String = "以下是上次闪退的日志。请点「复制日志」，把它发给开发者即可帮助定位问题。",
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = logText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, logText)
                Toast.makeText(context, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                onCopied()
            }) {
                Text(text = "复制日志")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(text = "关闭") }
                TextButton(onClick = onClear) { Text(text = "清除记录") }
            }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("YuNian Crash Log", text)
        clipboard.setPrimaryClip(clip)
    }
}
