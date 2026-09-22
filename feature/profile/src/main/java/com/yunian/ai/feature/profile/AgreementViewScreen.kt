package com.yunian.ai.feature.profile
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.yunian.ai.feature.profile.R
import androidx.compose.ui.unit.dp
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgreementViewScreen(
    onNavigateBack: () -> Unit
) {
    val colorScheme = AppTheme.colors

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.agreement_title),
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(16.dp),
                        surfaceColor = AppTheme.colors.surfaceVariant
                    )
                    .padding(16.dp)
            ) {
                Column {
                    AgreementSection(sec1Title, sec1Body)
                    AgreementSection(sec2Title, sec2Body)
                    AgreementSection(sec3Title, sec3Body)
                    AgreementSection(sec4Title, sec4Body)
                    AgreementSection(sec5Title, sec5Body)
                    AgreementSection(sec6Title, sec6Body)
                    AgreementSection(sec7Title, sec7Body)
                    AgreementSection(sec8Title, sec8Body)
                    AgreementSection(sec9Title, sec9Body)
                    AgreementSection(sec10Title, sec10Body)
                    AgreementSection(sec11Title, sec11Body)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
