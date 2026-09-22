package com.yunian.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.yunian.ai.common.PerformanceTrace
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.edit
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.FrameRateManager
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.feature.notification.CompanionKeepAliveService
import com.yunian.ai.feature.notification.CompanionMessageWorker
import com.yunian.ai.feature.profile.AgreementScreen
import com.yunian.ai.feature.profile.ProfileViewModel
import com.yunian.ai.feature.profile.RoleSelectionScreen
import com.yunian.ai.feature.update.AppUpdateManager
import com.yunian.ai.uicommon.component.YuNianToastHost
import com.yunian.ai.uicommon.component.WindowMainBackground
import com.yunian.ai.uicommon.theme.YuNianTheme
import com.yunian.ai.uicommon.theme.ThemeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    val updateManager by lazy { AppUpdateManager(this) }
    private val appScope = CoroutineScope(Dispatchers.Main)
    private var memoryAlertActive = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            prefs.edit { putBoolean("notification_permission_denied", true) }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(SystemBarController.applyBaseContextLocale(newBase))
        // 追加嫌疑打点：applyBaseContextLocale 内读 SP `theme_prefs`（首载），位于 attach 早期。
        PerformanceTrace.markStartupStage("act_attach_locale_done")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        lastOnCreateStartedNanos = SystemClock.elapsedRealtimeNanos()
        PerformanceTrace.startStartup(lastOnCreateStartedNanos)
        PerformanceTrace.markStartupStage("act_oncreate_begin")
        super.onCreate(savedInstanceState)
        PerformanceTrace.markStartupStage("act_super_done")

        try { androidx.work.WorkManager.initialize(this, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) } catch (_: Exception) {}
        PerformanceTrace.markStartupStage("act_workmanager_init")
        enableEdgeToEdge()
        window.decorView.post { SystemBarController.applySystemBars(this) }
        PerformanceTrace.markStartupStage("act_systembar_posted")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.let { disp ->
                val preferredMode = disp.supportedModes
                    .filter { it.refreshRate >= 90f }
                    .maxByOrNull { it.refreshRate }
                    ?: disp.supportedModes.maxByOrNull { it.refreshRate }
                preferredMode?.let { mode ->
                    val lp = window.attributes
                    lp.preferredDisplayModeId = mode.modeId
                    window.attributes = lp
                }
            }
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        WindowMainBackground.applyFromPrefs(this)
        PerformanceTrace.markStartupStage("act_window_bg_prefs")

        val activity = this
        setContent {
            val agreementPrefs = getSharedPreferences("agreement_prefs", android.content.Context.MODE_PRIVATE)
            val agreementAccepted = agreementPrefs.getBoolean("agreement_accepted", false)
            remember { PerformanceTrace.markStartupStage("compose_agreement_prefs"); true }
            val userPrefs = getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
            val roleSelected = userPrefs.contains("selected_role")
            remember { PerformanceTrace.markStartupStage("compose_user_prefs"); true }
            val themeViewModel: ThemeViewModel = viewModel()
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            val profileViewModel: ProfileViewModel = viewModel()
            var showRoleSelection by remember { mutableStateOf(agreementAccepted && !roleSelected) }

            val isServiceReady by ServiceRegistry.initialized.collectAsStateWithLifecycle()

            YuNianTheme(themeMode = themeMode) {

                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            !agreementAccepted -> {
                                AgreementScreen(
                                    onAgree = {
                                        agreementPrefs.edit()
                                            .putBoolean("agreement_accepted", true)
                                            .putLong("agreement_time", System.currentTimeMillis())
                                            .apply()
                                        activity.recreate()
                                    },
                                    onDisagree = { activity.finishAffinity() }
                                )
                            }
                            showRoleSelection -> {
                                RoleSelectionScreen(
                                    onRoleSelected = { role ->
                                        profileViewModel.switchRole(role) {
                                            showRoleSelection = false
                                        }
                                    },
                                    onSkip = {
                                        profileViewModel.switchRole(CompanionRole.GIRLFRIEND) {
                                            showRoleSelection = false
                                        }
                                    }
                                )
                            }
                            !isServiceReady -> {

                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            else -> {
                                remember { PerformanceTrace.markStartupStage("compose_service_ready"); true }
                                remember { PerformanceTrace.markStartupStage("compose_mainscreen_enter"); true }
                                MainScreen(activity)
                            }
                        }

                        YuNianToastHost(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp)
                                .zIndex(100f)
                        )
                    }
                }
            }
        }
        PerformanceTrace.markStartupStage("act_setcontent_return")

        requestNotificationPermission()
        CompanionKeepAliveService.start(this)
        PerformanceTrace.markStartupStage("act_fgs_start")
        CompanionMessageWorker.schedule(this)
        PerformanceTrace.markStartupStage("act_worker_schedule")

        scheduleIqooKeepAliveJob()
        PerformanceTrace.markStartupStage("act_job_scheduler")

        KeepAliveAlarmScheduler.scheduleNext(this)
        PerformanceTrace.markStartupStage("act_alarm_schedule")

        PerformanceTrace.markStartupStage("act_update_check")
        appScope.launch { updateManager.checkForUpdates() }
        startMemoryMonitor()
        PerformanceTrace.markStartupStage("act_oncreate_end")

        // 系统 FullyDrawn 口径（进程创建→首帧可见），可与 PerformanceTrace 交叉校验。
        // minSdk=26，Activity.reportFullyDrawn()（API 19+）可直接调用。
        window.decorView.post { reportFullyDrawn() }
    }

    private fun scheduleIqooKeepAliveJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val jobScheduler = getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE)
                        as android.app.job.JobScheduler
                jobScheduler.cancel(IQOO_KEEP_ALIVE_JOB_ID)
                val componentName = android.content.ComponentName(this, IqooKeepAliveJobService::class.java)
                val builder = android.app.job.JobInfo.Builder(IQOO_KEEP_ALIVE_JOB_ID, componentName)
                    .setPeriodic(15 * 60 * 1000L)
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                // ⚠️ 禁止对周期作业设置高优先级：JobInfo.Builder.build() 自 API 26 起对
                // (isPeriodic && priority >= PRIORITY_HIGH) 直接抛 IllegalArgumentException
                // "Periodic jobs cannot be high priority"。此处历史上调用过 setPriority(PRIORITY_HIGH)，
                // 使本层 JobScheduler 兜底保活在所有设备上从未注册成功（且 release 包中 Log.w 被 R8
                // 的 -assumenosideeffects 剥离 → 完全静默）。故此处不再设置 priority。
                val result = jobScheduler.schedule(builder.build())
                android.util.Log.i("MainActivity", "IQOO keep-alive JobScheduler scheduled, result=$result")
            } catch (e: Exception) {
                // 注意：app/proguard-rules.pro 的 -assumenosideeffects 会剥离 android.util.Log 的
                // v/d/w/e 级别，失败信息必须用 i 级打印，否则在 release 包中完全不可见。
                android.util.Log.i(
                    "MainActivity",
                    "Failed to schedule IQOO keep-alive job: ${e.javaClass.name}: ${e.message}",
                )
            }
        }
    }

    companion object {
        private const val IQOO_KEEP_ALIVE_JOB_ID = 10001

        @Volatile
        var lastOnCreateStartedNanos: Long = 0L
            private set
    }

    private fun startMemoryMonitor() {
        appScope.launch {
            val runtime = Runtime.getRuntime()
            val maxMem = runtime.maxMemory()
            while (isActive) {
                delay(3000)
                val used = runtime.totalMemory() - runtime.freeMemory()
                val ratio = used.toFloat() / maxMem.toFloat()

                if (ratio > 0.90f) {
                    android.util.Log.w(
                        "MemoryMonitor",
                        "CRITICAL: ${(ratio * 100).toInt()}% — clearing MessageCache/HomeListCache"
                    )
                    runCatching {
                        com.yunian.ai.database.cache.MessageCache.clearAll()
                        com.yunian.ai.database.cache.HomeListCache.clear()
                    }
                    memoryAlertActive = true
                } else if (ratio > 0.85f && !memoryAlertActive) {
                    android.util.Log.w(
                        "MemoryMonitor",
                        "HIGH: ${(ratio * 100).toInt()}% — pressure elevated (no cache clear yet)"
                    )
                    memoryAlertActive = true
                } else if (ratio < 0.60f && memoryAlertActive) {
                    android.util.Log.i("MemoryMonitor", "RECOVERED: ${(ratio * 100).toInt()}%")
                    memoryAlertActive = false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        appScope.launch(Dispatchers.IO) {
            runCatching {
                com.yunian.ai.feature.wechat.service.WeChatChannelKeeper.ensureRunning(applicationContext)
            }
        }
        window.decorView.post {
            SystemBarController.applySystemBars(this)
            val savedRate = FrameRateManager.getSavedFrameRate(this)
            FrameRateManager.applyFrameRate(window, savedRate)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    val prefs = getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    val deniedBefore = prefs.getBoolean("notification_permission_denied", false)
                    if (!deniedBefore) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

}
