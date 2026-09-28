package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop


import com.yunian.ai.uicommon.component.AppListItemLayout
import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.feature.profile.R
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

data class TeamMember(
    val name: String,
    val role: String,
    val description: String,
    val color: Color,
    val avatarRes: Int? = null
)

val teamMembers = listOf(
    TeamMember(
        name = "\u6797\u6893\u6DB5",
        role = "\u521B\u5EFA\u8005 & \u5168\u6808",
        description = "\u67B6\u6784\u8BBE\u8BA1 \u00B7 \u5168\u6808\u5F00\u53D1",
        color = Color(0xFF07C160),
        avatarRes = R.drawable.team_linruoxo
    ),
    TeamMember(
        name = "\u7948\u613F\u5C0F\u82CF",
        role = "\u5B89\u5168\u52A0\u5BC6",
        description = "JNI \u5B89\u5168 \u00B7 \u540E\u7AEF\u670D\u52A1",
        color = Color(0xFF3498DB),
        avatarRes = R.drawable.team_qiyuanxiaosu
    ),
    TeamMember(
        name = "\u9E22\u7940",
        role = "\u591A\u6A21\u6001",
        description = "\u56FE\u50CF\u5904\u7406 \u00B7 \u8BED\u97F3\u8BC6\u522B",
        color = Color(0xFF9B59B6),
        avatarRes = R.drawable.team_yuansi
    ),
    TeamMember(
        name = "\u9752\u601D\u96E8",
        role = "PC \u7AEF\u5F00\u53D1",
        description = "\u684C\u9762\u5BA2\u6237\u7AEF \u00B7 \u8DE8\u5E73\u53F0",
        color = Color(0xFFCCA8E9),
        avatarRes = R.drawable.team_qingsiyu
    ),
    TeamMember(
        name = "Clove.",
        role = "\u8F6F\u4EF6\u57FA\u7840\u5F00\u53D1",
        description = "\u5E95\u5C42\u5F00\u53D1",
        color = Color(0xFFE67E22),
        avatarRes = R.drawable.team_clove
    ),
    TeamMember(
        name = "\u5BD2\u62C2\u6674ColdBreeze",
        role = "\u529F\u80FD\u5F00\u53D1",
        description = "\u8BED\u97F3\u901A\u8BDD \u00B7 bug\u4FEE\u590D",
        color = Color(0xFFE85D75),
        avatarRes = R.drawable.team_hanfuqing
    ),
    TeamMember(
        name = "\u4E0B\u5317\u6CFD\u4F20\u5947",
        role = "\u529F\u80FD\u5F00\u53D1",
        description = "\u529F\u80FD\u5F00\u53D1",
        color = Color(0xFF14B8A6),
        avatarRes = R.drawable.team_xiabeize
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    onNavigateBack: () -> Unit
) {
    val colorScheme = AppTheme.colors

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.dev_team_title),
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
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.dev_team_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                ),
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            teamMembers.forEachIndexed { index, member ->
                TeamMemberCard(
                    member = member,
                    delayIndex = index
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.dev_team_bottom),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                ),
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun TeamMemberCard(
    member: TeamMember,
    delayIndex: Int
) {
    val colorScheme = AppTheme.colors
    Column(
        modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = colorScheme.surfaceVariant
                )
                .padding(20.dp)
    ) {
        AppListItemLayout(
            isStartAligned = true,
            startSlot = {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    member.color.copy(alpha = 0.3f),
                                    member.color.copy(alpha = 0.15f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (member.avatarRes != null) {
                        Image(
                            painter = painterResource(id = member.avatarRes),
                            contentDescription = member.name,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            },
            endSlot = {},
            slotGap = 16.dp
        ) {
            Column {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    ),
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = member.role,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp
                    ),
                    color = member.color
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = member.description,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 20.sp
            ),
            color = colorScheme.onSurfaceVariant
        )
    }
}
