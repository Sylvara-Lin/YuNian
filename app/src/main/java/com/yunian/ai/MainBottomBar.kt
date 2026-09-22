package com.yunian.ai
import com.yunian.ai.uicommon.icon.AppIcons


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.yunian.ai.R
import com.yunian.ai.uicommon.component.glass.LiquidBottomTab
import com.yunian.ai.uicommon.component.glass.LiquidBottomTabs
import com.kyant.backdrop.Backdrop

data class BottomNavItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String
)

@Composable
fun FloatingGlassBottomNav(
    items: List<BottomNavItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val contentColor = if (isDark) Color.White else Color.Black
    val iconColorFilter = ColorFilter.tint(contentColor)

    var visualSelectedIndex by rememberSaveable { mutableIntStateOf(currentIndex) }
    var pendingNavigationIndex by remember { mutableStateOf<Int?>(null) }
    val currentOnItemClick by rememberUpdatedState(onItemClick)

    LaunchedEffect(currentIndex) { visualSelectedIndex = currentIndex }
    LaunchedEffect(pendingNavigationIndex) {
        val index = pendingNavigationIndex ?: return@LaunchedEffect
        withFrameNanos {}
        currentOnItemClick(index)
        if (pendingNavigationIndex == index) pendingNavigationIndex = null
    }
    val selectTab: (Int) -> Unit = { index ->
        if (index != visualSelectedIndex) {
            visualSelectedIndex = index
            pendingNavigationIndex = index
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        LiquidBottomTabs(
            selectedTabIndex = { visualSelectedIndex },
            onTabSelected = selectTab,
            backdrop = backdrop,
            tabsCount = items.size,
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            isDark = isDark,
            containerHeight = 64.dp,
            contentPadding = 4.dp
        ) {
            items.forEachIndexed { index, item ->
                LiquidBottomTab(
                    onClick = { selectTab(index) },
                    modifier = Modifier.semantics {
                        selected = index == visualSelectedIndex
                    }
                ) {
                    Icon(
                        imageVector = if (index == visualSelectedIndex) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.title,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer(colorFilter = iconColorFilter)
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (index == visualSelectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 12.sp
                        ),
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
fun WeChatTopBar(
    title: String,
    isVisible: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val bgColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = Modifier
            .background(if (isVisible) bgColor else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    ),
                    color = textColor,
                    textAlign = TextAlign.Start
                )
                Row(
                    modifier = Modifier.padding(end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions
                )
            }
        }
    }
}

@Composable
fun HomeTopBarActions(navController: NavHostController) {
    val iconBgColor = MaterialTheme.colorScheme.surfaceVariant
    val iconTint = MaterialTheme.colorScheme.onBackground

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(iconBgColor)
                .clickable { navController.navigate("contacts") },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Users,
                contentDescription = stringResource(R.string.nav_contacts),
                modifier = Modifier.size(20.dp),
                tint = iconTint
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(iconBgColor)
                .clickable { navController.navigate("profile") },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.UserRound,
                contentDescription = stringResource(R.string.nav_profile),
                modifier = Modifier.size(20.dp),
                tint = iconTint
            )
        }
    }
}
