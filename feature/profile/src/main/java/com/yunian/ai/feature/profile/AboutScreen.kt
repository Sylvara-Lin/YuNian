package com.yunian.ai.feature.profile


import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.profile.R
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import com.yunian.ai.uicommon.icon.AppIcons

/** 开源项目条目：名称 + 一句用途说明 */
private data class OssItem(val name: String, val desc: String)

/** 开源项目分组：分类标题 + 条目列表 */
private data class OssCategory(val title: String, val items: List<OssItem>)

/** 「关于应用」页展示的开源项目致谢清单（按分类分组） */
private val ossCategories = listOf(
    OssCategory(
        title = "参考与移植",
        items = listOf(
            OssItem("RikkaHub", "世界书、提示词处理与工具循环等实现的重要参考"),
            OssItem("Shizuku", "提供 ADB 特权授权通道（技能与自动化能力）")
        )
    ),
    OssCategory(
        title = "界面与体验",
        items = listOf(
            OssItem("Jetpack Compose", "声明式 UI 框架"),
            OssItem("Material Design 3", "设计组件与主题"),
            OssItem("Kyant Backdrop / Capsule", "液态玻璃效果"),
            OssItem("Haze", "模糊与毛玻璃效果"),
            OssItem("Coil", "图片加载"),
            OssItem("Lottie", "动效"),
            OssItem("IconSax Icons", "图标库")
        )
    ),
    OssCategory(
        title = "AI 与语音",
        items = listOf(
            OssItem("sherpa-onnx", "语音识别（ONNX Runtime）")
        )
    ),
    OssCategory(
        title = "网络通信",
        items = listOf(
            OssItem("OkHttp、Retrofit（Square）", "HTTP 客户端"),
            OssItem("Ktor Client", "网络客户端"),
            OssItem("Model Context Protocol SDK", "模型上下文协议")
        )
    ),
    OssCategory(
        title = "数据与安全",
        items = listOf(
            OssItem("Room", "本地数据库"),
            OssItem("DataStore", "偏好与配置存储"),
            OssItem("Tink（Google）", "加密库"),
            OssItem("AndroidX Security-Crypto", "加密存储"),
            OssItem("AndroidX / WorkManager / Navigation", "系统组件"),
            OssItem("Accompanist SystemUiController", "系统 UI 控制")
        )
    ),
    OssCategory(
        title = "其他工具",
        items = listOf(
            OssItem("kotlinx.serialization / kotlinx.coroutines", "序列化与协程"),
            OssItem("ZXing", "二维码/条码识别"),
            OssItem("org.json", "JSON 处理"),
            OssItem("retrofit2-kotlinx-serialization-converter（JakeWharton）", "序列化转换器")
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onNavigateBack: () -> Unit,
    onCheckUpdateClick: () -> Unit = {},
    onAgreementClick: () -> Unit = {},
    onTeamClick: () -> Unit = {}
) {
    val colorScheme = AppTheme.colors

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.about_title),
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = stringResource(R.string.app_brand),
                modifier = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.app_brand),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = 2.sp
                ),
                color = colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.app_slogan),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.version_format, versionName),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 检查新版本入口
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onCheckUpdateClick() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(18.dp),
                        surfaceColor = colorScheme.surfaceVariant
                    )
            ) {
                Icon(
                    imageVector = AppIcons.RefreshCw,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.check_new_version_entry),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AboutCard() {
                Text(
                    text = stringResource(R.string.about_desc_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard(onClick = onTeamClick) {
                Text(
                    text = stringResource(R.string.dev_team_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.dev_team_desc),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    // 7 人预览可能超出卡宽：横滑兜底防裁切/挤压；尺寸略降保证常见屏宽全量可见
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 12.dp)
                ) {
                    teamMembers.forEach { member ->
                        member.avatarRes?.let { avatarRes ->
                            Image(
                                painter = painterResource(id = avatarRes),
                                contentDescription = member.name,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard() {
                Text(
                    text = stringResource(R.string.features),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                FeatureItem(stringResource(R.string.feature_multi_ai), stringResource(R.string.feature_multi_ai_desc))
                FeatureItem(stringResource(R.string.feature_memory), stringResource(R.string.feature_memory_desc))
                FeatureItem(stringResource(R.string.feature_refresh), stringResource(R.string.feature_refresh_desc))
                FeatureItem(stringResource(R.string.feature_ui), stringResource(R.string.feature_ui_desc))
                FeatureItem(stringResource(R.string.feature_edit), stringResource(R.string.feature_edit_desc))
            }

            Spacer(modifier = Modifier.height(16.dp))

            AboutCard() {
                Text(
                    text = stringResource(R.string.opensource_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "感谢这些优秀的开源项目与社区",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(8.dp))

                ossCategories.forEach { category ->
                    Text(
                        text = "▸ ${category.title}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        ),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    category.items.forEach { item ->
                        OssItemRow(item)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.view_agreement),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                    color = AppTheme.colors.primary
                ),
                modifier = Modifier
                    .clickable { onAgreementClick() }
                    .padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Made with love",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp
                ),
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun AboutCard(
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = AppTheme.colors.surfaceVariant
            )
            .then(
                if (onClick != null) {
                    Modifier.clip(shape).clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
fun FeatureItem(title: String, description: String) {
    val colorScheme = AppTheme.colors
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp
            ),
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OssItemRow(item: OssItem) {
    val colorScheme = AppTheme.colors
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = colorScheme.onSurface
        )
        Text(
            text = item.desc,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.sp
            ),
            color = colorScheme.onSurfaceVariant
        )
    }
}
