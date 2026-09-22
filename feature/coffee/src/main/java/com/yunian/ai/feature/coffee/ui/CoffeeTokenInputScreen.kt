package com.yunian.ai.feature.coffee.ui
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

private val LuckinBlue = Color(0xFF0066CC)
private val LuckinDarkBlue = Color(0xFF003D7A)
private val LuckinLightBlue = Color(0xFFE6F0FF)
private val LuckinRed = Color(0xFFE1251B)
private val LuckinGray = Color(0xFFF5F5F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoffeeTokenInputScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: CoffeeViewModel = viewModel(factory = CoffeeViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var tokenInput by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.refreshTokenStatus() }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = if (uiState.isTokenConfigured) "替换 Token" else "配置 Token",
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LuckinGray)
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                AppIcons.Coffee,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = LuckinBlue
            )

            Spacer(Modifier.height(16.dp))
            Text(
                if (uiState.isTokenConfigured) "替换瑞幸 MCP Token" else "配置瑞幸 MCP Token",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LuckinDarkBlue
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "访问 open.lkcoffee.com/mcp 登录后复制 Token\n有效期约 30 天，与瑞幸账号绑定",
                fontSize = 13.sp,
                color = Color.Gray,
                lineHeight = 20.sp
            )

            if (uiState.isTokenConfigured) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前已有一个 Token，保存将覆盖旧值",
                    fontSize = 12.sp,
                    color = LuckinRed
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Bearer Token") },
                placeholder = { Text("粘贴你的瑞幸 MCP Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 6,

                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    val token = tokenInput.trim()
                    if (token.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar("Token 不能为空") }
                        return@Button
                    }
                    saving = true

                    scope.launch {
                        viewModel.saveToken(token).join()
                        saving = false
                        snackbarHostState.showSnackbar("Token 已保存")
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(12.dp),
                enabled = !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(0.dp))
                    Text("  保存中…", fontWeight = FontWeight.Bold)
                } else {
                    Icon(AppIcons.Check, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("保存并替换", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("安全说明", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "• Token 与瑞幸账号会话绑定，严禁泄露\n" +
                            "• 存储在应用私有目录，卸载后清除\n" +
                            "• 过期后需重新登录获取，不影响瑞幸账号",
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
