# AI Agent 工具完善（自动化任务 + 瑞幸确认卡片）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 在对话中通过工具创建/取消定时自动化（系统通知 + 伴侣聊天消息双重送达），并给瑞幸 `luckin_create_order` 加用户确认卡片；全程不动 Room 数据库（v19 不变）。

**Architecture:** 新增 `feature:automation` 模块（DataStore 存储 + WorkManager 调度），`AutomationTools` 注册进 `ToolRegistry`；`AiTool` 接口加 `requiresConfirmation`/`summarizeArguments` 默认实现，`AiToolLoopRunner` 注入 `ConfirmationGate`，`ChatGenerationManager` 实现 Gate 并通过 `StateFlow` 驱动 ChatScreen 弹确认卡片。

**Tech Stack:** Kotlin 2.2.10 / Compose / Room v19（不动）/ WorkManager 2.9 / DataStore 1.1 / kotlinx-serialization / JUnit4

## Global Constraints

- **Room AppDatabase v19 保持不变**：禁止新增 Entity/DAO/Migration。自动化存储一律走 DataStore。
- **feature 模块禁止依赖其他 feature 模块**（AGENTS.md）：`feature:automation` 通知自建渠道，不复用 `feature:notification` 的 `NotificationHelper`。
- 工具注册统一在 `YuNianApplication.registerServiceProviders`（app 启动时一次）。
- 新增超时常量必须进 `core/common/.../TimeoutBudgets.kt`，禁止硬编码。
- 伴侣聊天消息写入前必须过 `ContentFilter.checkOutputSafety`（对齐 `CompanionMessageWorker`，AI 输出违规不累计用户封禁）。
- 架构最小变更：不修改 `feature:notification` 保活/心跳/`CompanionMessageWorker` 调度逻辑。
- 中文文案（UI/通知）直接写在代码字符串中，不新增资源文件（与 `GeneralSettingsScreen.kt` 内既有中文硬编码一致）。
- 所有异步超时：确认卡片等待超时按「拒绝」处理。

---

### Task 1: 核心契约扩展（TimeoutBudgets + AiTool 接口）

**Files:**
- Modify: `core/common/src/main/java/com/yunian/ai/common/TimeoutBudgets.kt`
- Modify: `core/domain/src/main/java/com/yunian/ai/domain/AiTool.kt`
- Test: `core/domain/src/test/java/com/yunian/ai/domain/AiToolDefaultTest.kt`（若 core:domain 无 src/test，则跳过，由后续任务编译验证覆盖）

**Interfaces:**
- Consumes: 无
- Produces: `TimeoutBudgets.AUTOMATION_CONFIRM_TIMEOUT_MS: Long`；`AiTool.requiresConfirmation: Boolean`（默认 false）；`AiTool.summarizeArguments(argumentsJson: String): String`（默认返回截断 JSON）

- [ ] **Step 1: 在 TimeoutBudgets 增加确认超时**

在 `core/common/src/main/java/com/yunian/ai/common/TimeoutBudgets.kt` 的「=== 分岔点硬限制 ===」之前新增：

```kotlin
    // === AI 工具确认卡片 ===
    const val AUTOMATION_CONFIRM_TIMEOUT_MS = 60_000L  // 确认卡片等待上限，超时按拒绝处理
```

- [ ] **Step 2: 给 AiTool 接口增加带默认值的成员**

在 `core/domain/src/main/java/com/yunian/ai/domain/AiTool.kt` 的 `suspend fun execute(argumentsJson: String): String` 之后新增：

```kotlin
    /**
     * 涉及支付/创建等副作用，执行前需用户确认。
     * 默认 false；createOrder / automation_create 等覆写为 true。
     */
    val requiresConfirmation: Boolean get() = false

    /**
     * 生成确认卡片的人类可读摘要。
     * 默认返回参数 JSON 截断；各工具可按需覆写（如「每天 08:00 · 喝水」）。
     */
    fun summarizeArguments(argumentsJson: String): String = argumentsJson.take(120)
```

- [ ] **Step 3: 编译验证（不破坏现有 7 个工具实现）**

Run: `./gradlew :core:domain:compileKotlin :core:common:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（默认值保证现有实现无需改动）

- [ ] **Step 4: Commit**

```bash
git add core/common/src/main/java/com/yunian/ai/common/TimeoutBudgets.kt core/domain/src/main/java/com/yunian/ai/domain/AiTool.kt
git commit -m "feat(agent): AiTool 增加 requiresConfirmation 与 summarizeArguments 默认实现"
```

---

### Task 2: feature:automation 模块脚手架 + 数据模型 + AutomationStore

**Files:**
- Create: `feature/automation/build.gradle.kts`
- Create: `feature/automation/src/main/AndroidManifest.xml`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationDataStoreProvider.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/Automation.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationStore.kt`
- Modify: `settings.gradle.kts`（`include(":feature:automation")`）
- Modify: `app/build.gradle.kts`（`implementation(project(":feature:automation"))`）

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class Automation(id: String, title: String, companionId: Long, type: AutomationType, triggerAtMillis: Long, hourOfDay: Int, minuteOfHour: Int, dayOfWeek: Int?, message: String, enabled: Boolean, createdAt: Long)`
  - `enum class AutomationType { ONCE, DAILY, WEEKLY }`
  - `class AutomationStore(context: Context)`：`suspend fun list(): List<Automation>` / `suspend fun upsert(a: Automation)` / `suspend fun delete(id: String)` / `suspend fun setEnabled(id: String, enabled: Boolean)` / `fun flow(): Flow<List<Automation>>`
  - `object AutomationDataStoreProvider { fun get(context: Context): DataStore<Preferences> }`

- [ ] **Step 1: 注册模块到 settings.gradle.kts**

在 `settings.gradle.kts` 的 `include(":feature:coffee")` 之后新增：

```kotlin
include(":feature:automation")
```

- [ ] **Step 2: 创建模块 build.gradle.kts**

创建 `feature/automation/build.gradle.kts`：

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.yunian.ai.feature.automation"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:domain"))
    implementation(project(":core:ui-common"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
```

- [ ] **Step 3: 创建模块 AndroidManifest.xml**

创建 `feature/automation/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 4: 创建 DataStore Provider**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationDataStoreProvider.kt`：

```kotlin
package com.yunian.ai.feature.automation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 自动化数据 DataStore 唯一实例。
 * 文件名与其它模块错开，避免 "multiple DataStores active for the same file"。
 */
object AutomationDataStoreProvider {
    private const val NAME = "automation_store"

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = NAME)

    fun get(context: Context): DataStore<Preferences> = context.applicationContext.dataStore
}
```

- [ ] **Step 5: 创建数据模型**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/Automation.kt`：

```kotlin
package com.yunian.ai.feature.automation.data

import kotlinx.serialization.Serializable

enum class AutomationType { ONCE, DAILY, WEEKLY }

@Serializable
data class Automation(
    val id: String,
    val title: String,
    val companionId: Long,
    val type: AutomationType,
    val triggerAtMillis: Long,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val dayOfWeek: Int? = null,
    val message: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 6: 创建 AutomationStore**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationStore.kt`：

```kotlin
package com.yunian.ai.feature.automation.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AutomationStore(context: Context) {

    private val dataStore = AutomationDataStoreProvider.get(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val AUTOMATIONS_KEY = stringPreferencesKey("automations_json")
    }

    fun flow(): Flow<List<Automation>> = dataStore.data.map { prefs ->
        prefs[AUTOMATIONS_KEY]?.let(::decodeList) ?: emptyList()
    }

    suspend fun list(): List<Automation> = flow().first()

    suspend fun upsert(automation: Automation) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            val index = current.indexOfFirst { it.id == automation.id }
            if (index >= 0) current[index] = automation else current.add(automation)
            prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            current.removeAll { it.id == id }
            prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[AUTOMATIONS_KEY]?.let(::decodeList)?.toMutableList() ?: mutableListOf()
            val index = current.indexOfFirst { it.id == id }
            if (index >= 0) {
                current[index] = current[index].copy(enabled = enabled)
                prefs[AUTOMATIONS_KEY] = json.encodeToString(current)
            }
        }
    }

    private fun decodeList(raw: String): List<Automation> =
        runCatching { json.decodeFromString<List<Automation>>(raw) }.getOrElse { emptyList() }
}
```

- [ ] **Step 7: 在 app 模块添加依赖**

在 `app/build.gradle.kts` 的 dependencies 区块（`implementation(project(":feature:coffee"))` 之后）新增：

```kotlin
    implementation(project(":feature:automation"))
```

- [ ] **Step 8: 编译验证**

Run: `./gradlew :feature:automation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts feature/automation
git commit -m "feat(automation): 新模块 + 数据模型 + DataStore 存储（零 DB 变更）"
```

---

### Task 3: AutomationSchedulePolicy（纯函数）+ 单元测试

**Files:**
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationSchedulePolicy.kt`
- Test: `feature/automation/src/test/java/com/yunian/ai/feature/automation/AutomationSchedulePolicyTest.kt`

**Interfaces:**
- Consumes: `Automation`、`AutomationType`（Task 2）
- Produces: `object AutomationSchedulePolicy { fun nextTriggerAtMillis(a: Automation, now: Long): Long? }` —— 返回下次触发时刻；`null` 表示不再触发（ONCE 已过期）

- [ ] **Step 1: 写失败测试**

创建 `feature/automation/src/test/java/com/yunian/ai/feature/automation/AutomationSchedulePolicyTest.kt`：

```kotlin
package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Calendar

class AutomationSchedulePolicyTest {

    private fun automation(
        type: AutomationType,
        triggerAtMillis: Long = 0L,
        hourOfDay: Int = 0,
        minuteOfHour: Int = 0,
        dayOfWeek: Int? = null
    ) = Automation(
        id = "test", title = "喝水", companionId = 1L,
        type = type, triggerAtMillis = triggerAtMillis,
        hourOfDay = hourOfDay, minuteOfHour = minuteOfHour,
        dayOfWeek = dayOfWeek, message = "到点啦～该喝水啦"
    )

    @Test
    fun onceInFutureReturnsTriggerTime() {
        val now = 1_000_000L
        val a = automation(AutomationType.ONCE, triggerAtMillis = 2_000_000L)
        assertEquals(2_000_000L, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun onceInPastReturnsNull() {
        val now = 3_000_000L
        val a = automation(AutomationType.ONCE, triggerAtMillis = 2_000_000L)
        assertNull(AutomationSchedulePolicy.nextTriggerAtMillis(a, now))
    }

    @Test
    fun dailyBeforeTimeFiresToday() {
        // now = 2026-08-07 08:00，目标 09:30 → 今天 09:30
        val now = calendar(2026, 7, 7, 8, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 7, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun dailyAfterTimeFiresTomorrow() {
        // now = 2026-08-07 10:00，目标 09:30 → 明天 09:30
        val now = calendar(2026, 7, 7, 10, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 8, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyFiresNextMatchingDay() {
        // 目标 = 最近一个周一 08:00（Calendar 语义：周一=2）
        // now = 目标前一天（周日）12:00 → 下次触发 = 该周一 08:00
        val target = mondayAt(8, 0)
        val now = target - 24 * 3600_000L
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(target, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklySameDayBeforeTimeFiresToday() {
        // now = 周一 07:00，目标周一 08:00 → 今天（同一周一）08:00
        val now = mondayAt(7, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(mondayAt(8, 0), AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyAfterTimeFiresNextWeek() {
        // now = 周一 09:00，目标周一 08:00 → 下周一 08:00
        val now = mondayAt(9, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!
        assertTrue(next > now)
        assertEquals(now + 7 * 24 * 3600_000L - 3600_000L, next)
    }

    /** 最近一个周一（无论过去未来）在指定时刻的 epoch millis */
    private fun mondayAt(hour: Int, minute: Int): Long {
        val monday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return monday.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun calendar(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month0, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :feature:automation:testDebugUnitTest --tests "com.yunian.ai.feature.automation.AutomationSchedulePolicyTest" -i`
Expected: FAIL（`AutomationSchedulePolicy` 不存在，编译错误）

- [ ] **Step 3: 实现纯函数**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationSchedulePolicy.kt`：

```kotlin
package com.yunian.ai.feature.automation.data

import java.util.Calendar

/**
 * 计算自动化下次触发时刻（纯函数，无 Android 依赖，便于 JVM 单测）。
 * @return 下次触发 epoch millis；null 表示不再触发（ONCE 已过期）。
 */
object AutomationSchedulePolicy {

    fun nextTriggerAtMillis(a: Automation, now: Long): Long? {
        return when (a.type) {
            AutomationType.ONCE -> if (a.triggerAtMillis > now) a.triggerAtMillis else null
            AutomationType.DAILY -> nextDaily(a, now)
            AutomationType.WEEKLY -> nextWeekly(a, now)
        }
    }

    private fun nextDaily(a: Automation, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, a.hourOfDay)
        cal.set(Calendar.MINUTE, a.minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun nextWeekly(a: Automation, now: Long): Long {
        val targetDay = a.dayOfWeek?.coerceIn(1, 7) ?: return nextDaily(a, now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, a.hourOfDay)
        cal.set(Calendar.MINUTE, a.minuteOfHour)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        for (i in 0 until 7) {
            if (cal.timeInMillis > now && cal.get(Calendar.DAY_OF_WEEK) == targetDay) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
```

注意：`Calendar.DAY_OF_WEEK` 周日=1…周六=7，`dayOfWeek` 字段按此约定（1=周一由调用方换算，见 Task 5）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :feature:automation:testDebugUnitTest --tests "com.yunian.ai.feature.automation.AutomationSchedulePolicyTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add feature/automation/src/main/java/com/yunian/ai/feature/automation/data/AutomationSchedulePolicy.kt feature/automation/src/test
git commit -m "feat(automation): 下次触发时刻纯函数 + 单测（ONCE/DAILY/WEEKLY）"
```

---

### Task 4: AutomationNotifier + AutomationFireWorker + AutomationScheduler

**Files:**
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationNotifier.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationFireWorker.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationScheduler.kt`

**Interfaces:**
- Consumes: `AutomationStore`、`AutomationSchedulePolicy`、`TimeoutBudgets`（Task 1/2/3）、`MessageWriteCoordinator`（core:database）、`ContentFilter`（core:common）
- Produces:
  - `object AutomationNotifier { fun ensureChannel(context: Context); fun show(context: Context, title: String, content: String, companionId: Long) }`
  - `class AutomationFireWorker(context, params) : CoroutineWorker`
  - `object AutomationScheduler { fun reschedule(context: Context, automation: Automation); fun cancel(context: Context, id: String); fun rescheduleAll(context: Context, automations: List<Automation>) }`

- [ ] **Step 1: 创建 AutomationNotifier（自建通知渠道）**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationNotifier.kt`：

```kotlin
package com.yunian.ai.feature.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.yunian.ai.feature.automation.R

/**
 * 自动化到点通知（自建渠道，feature 间禁止互相依赖）。
 */
object AutomationNotifier {
    private const val CHANNEL_ID = "automation_channel"
    private const val NOTIFICATION_ID_BASE = 5000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动化提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "AI 设置的定时自动化到点提醒" }
        manager.createNotificationChannel(channel)
    }

    fun show(context: Context, title: String, content: String, companionId: Long) {
        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_BASE + (companionId % 1000).toInt(), notification)
    }
}
```

注：`R.drawable.ic_notification` 需在模块内存在——若资源不存在，改用系统可用图标常量 `android.R.drawable.ic_dialog_info`（见 Step 2 说明）。

- [ ] **Step 2: 若模块无通知图标，改用系统图标**

执行下面命令确认模块是否有 drawable 资源：

Run: `ls feature/automation/src/main/res 2>/dev/null || echo "NO_RES"`
若输出 `NO_RES`，将 Step 1 的 `.setSmallIcon(R.drawable.ic_notification)` 替换为 `.setSmallIcon(android.R.drawable.ic_dialog_info)`。

- [ ] **Step 3: 创建 AutomationFireWorker**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationFireWorker.kt`：

```kotlin
package com.yunian.ai.feature.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutomationFireWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_AUTOMATION_ID) ?: return@withContext Result.success()
        val store = AutomationStore(context)
        val automation = store.list().firstOrNull { it.id == id }
            ?: return@withContext Result.success()
        if (!automation.enabled) return@withContext Result.success()

        // 循环类先算下次触发并重排（ONCE 不重排）
        if (automation.type != com.yunian.ai.feature.automation.data.AutomationType.ONCE) {
            val next = AutomationSchedulePolicy.nextTriggerAtMillis(automation, System.currentTimeMillis())
            if (next != null) {
                val updated = automation.copy(triggerAtMillis = next)
                store.upsert(updated)
                AutomationScheduler.reschedule(context, updated)
            }
        }

        // 系统通知（title 为用户自己的任务名）
        AutomationNotifier.show(context, automation.title, automation.message, automation.companionId)

        // 伴侣聊天消息：写前过输出安全检查，违规则跳过消息（不累计封禁）
        val outputSafety = ContentFilter.checkOutputSafety(automation.message)
        if (outputSafety.isSafe) {
            runCatching {
                com.yunian.ai.domain.ServiceRegistry
                    .getOrThrow(MessageWriteCoordinator::class.java)
                    .enqueueChat(
                        ChatMessage(
                            companionId = automation.companionId,
                            content = automation.message,
                            isFromUser = false
                        )
                    )
            }.onFailure { SecureLog.e("AutomationFireWorker", "write chat message failed", it) }
        } else {
            SecureLog.w("AutomationFireWorker", "Automation message blocked by safety: ${outputSafety.reason}")
        }

        Result.success()
    }

    companion object {
        const val KEY_AUTOMATION_ID = "automation_id"
    }
}
```

- [ ] **Step 4: 创建 AutomationScheduler**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationScheduler.kt`：

```kotlin
package com.yunian.ai.feature.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import java.util.concurrent.TimeUnit

object AutomationScheduler {

    private fun workName(id: String) = "automation_$id"

    fun reschedule(context: Context, automation: Automation) {
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(automation, System.currentTimeMillis())
            ?: return
        val delayMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<AutomationFireWorker>()
            .setInputData(androidx.work.Data.Builder().putString(AutomationFireWorker.KEY_AUTOMATION_ID, automation.id).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(automation.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun rescheduleAll(context: Context, automations: List<Automation>) {
        automations.filter { it.enabled }.forEach { reschedule(context, it) }
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :feature:automation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationNotifier.kt feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationFireWorker.kt feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationScheduler.kt
git commit -m "feat(automation): 到点通知 + 聊天消息 + WorkManager 调度"
```

---

### Task 5: AutomationTools（AiTool 实现）+ 纯逻辑测试 + app 注册

**Files:**
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationToolLogic.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationTools.kt`
- Test: `feature/automation/src/test/java/com/yunian/ai/feature/automation/AutomationToolLogicTest.kt`
- Modify: `app/src/main/java/com/yunian/ai/YuNianApplication.kt`（注册 Store + Tools + 启动重建调度）

**Interfaces:**
- Consumes: `Automation`、`AutomationStore`、`AutomationScheduler`、`AutomationType`、`AiTool`（Task 1/2/4）
- Produces:
  - `object AutomationToolLogic`：`fun parseCreateParams(argsJson: String): CreateParams?`、`fun matchByTitle(automations: List<Automation>, keyword: String): List<Automation>`、`fun defaultMessage(title: String): String`
  - `data class CreateParams(title, companionId, type, triggerAtMillis, hourOfDay, minuteOfHour, dayOfWeek, message)`
  - `object AutomationTools { fun registerAll(store: AutomationStore, app: Application) }` —— 注册 `automation_create` / `automation_cancel` / `automation_list` 三个工具

- [ ] **Step 1: 写失败测试**

创建 `feature/automation/src/test/java/com/yunian/ai/feature/automation/AutomationToolLogicTest.kt`：

```kotlin
package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationToolLogicTest {

    @Test
    fun parseDailyCreateParams() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"喝水","companionId":1,"type":"daily","hour":8,"minute":0,"message":"到点啦"}"""
        )
        assertNotNull(params)
        assertEquals("喝水", params!!.title)
        assertEquals(AutomationType.DAILY, params.type)
        assertEquals(8, params.hourOfDay)
        assertEquals(0, params.minuteOfHour)
    }

    @Test
    fun parseOnceCreateParamsWithTriggerAt() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"开会","companionId":2,"type":"once","triggerAt":1700000000000,"message":"去开会"}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.ONCE, params!!.type)
        assertEquals(1_700_000_000_000L, params.triggerAtMillis)
    }

    @Test
    fun parseInvalidReturnsNull() {
        assertNull(AutomationToolLogic.parseCreateParams("""{"title":123}"""))
    }

    @Test
    fun weeklyMapsDayOfWeek() {
        // AI 约定：dayOfWeek 1=周一 .. 7=周日（与 Calendar.DAY_OF_WEEK 的 1=周日不一致，逻辑内换算）
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"健身","companionId":1,"type":"weekly","dayOfWeek":1,"hour":19,"minute":30}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.WEEKLY, params!!.type)
        // Calendar.DAY_OF_WEEK：周一 = 2
        assertEquals(2, params.dayOfWeekCalendar)
    }

    @Test
    fun matchByTitleUniqueHit() {
        val list = listOf(
            automation("a", "喝水"),
            automation("b", "健身"),
            automation("c", "健身")
        )
        assertEquals(listOf(list[0]), AutomationToolLogic.matchByTitle(list, "喝水"))
        assertEquals(2, AutomationToolLogic.matchByTitle(list, "健身").size)
        assertEquals(0, AutomationToolLogic.matchByTitle(list, "不存在").size)
    }

    private fun automation(id: String, title: String) = Automation(
        id = id, title = title, companionId = 1L, type = AutomationType.ONCE,
        triggerAtMillis = 1L, hourOfDay = 0, minuteOfHour = 0, message = "m"
    )
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :feature:automation:testDebugUnitTest --tests "com.yunian.ai.feature.automation.AutomationToolLogicTest"`
Expected: FAIL（`AutomationToolLogic` 不存在）

- [ ] **Step 3: 实现 AutomationToolLogic**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationToolLogic.kt`：

```kotlin
package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 自动化工具的纯逻辑（无 Android 依赖，便于 JVM 单测）。
 */
object AutomationToolLogic {

    private val json = Json { ignoreUnknownKeys = true }

    data class CreateParams(
        val title: String,
        val companionId: Long,
        val type: AutomationType,
        val triggerAtMillis: Long,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val dayOfWeekCalendar: Int?,  // Calendar.DAY_OF_WEEK 语义：1=周日..7=周六
        val message: String
    )

    /**
     * 解析 AI 的 automation_create 参数。
     * AI 约定：type = "once"|"daily"|"weekly"；dayOfWeek 1=周一..7=周日；
     * once 用 triggerAt（epoch ms）；daily/weekly 用 hour/minute（+dayOfWeek）。
     * @return 解析失败返回 null
     */
    fun parseCreateParams(argsJson: String): CreateParams? {
        return runCatching {
            val obj = json.parseToJsonElement(argsJson).jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val companionId = obj["companionId"]?.jsonPrimitive?.longOrNull ?: 0L
            if (companionId <= 0L) return null
            val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "once" -> AutomationType.ONCE
                "daily" -> AutomationType.DAILY
                "weekly" -> AutomationType.WEEKLY
                else -> return null
            }
            val triggerAt = obj["triggerAt"]?.jsonPrimitive?.longOrNull ?: 0L
            val hour = obj["hour"]?.jsonPrimitive?.intOrNull ?: 0
            val minute = obj["minute"]?.jsonPrimitive?.intOrNull ?: 0
            // AI 传 1=周一..7=周日 → Calendar 语义（1=周日）: +1，周日(7) → 1
            val dayOfWeekAi = obj["dayOfWeek"]?.jsonPrimitive?.intOrNull
            val dayOfWeekCalendar = dayOfWeekAi?.let { if (it in 1..7) (it % 7) + 1 else null }
            val message = obj["message"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: defaultMessage(title)
            CreateParams(
                title = title,
                companionId = companionId,
                type = type,
                triggerAtMillis = triggerAt,
                hourOfDay = hour.coerceIn(0, 23),
                minuteOfHour = minute.coerceIn(0, 59),
                dayOfWeekCalendar = dayOfWeekCalendar,
                message = message
            )
        }.getOrNull()
    }

    /** 按 title 模糊匹配（包含即命中） */
    fun matchByTitle(automations: List<Automation>, keyword: String): List<Automation> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return automations.filter { it.title.contains(kw, ignoreCase = true) }
    }

    fun defaultMessage(title: String): String = "到点啦～该$title啦"
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :feature:automation:testDebugUnitTest --tests "com.yunian.ai.feature.automation.AutomationToolLogicTest"`
Expected: 5 tests PASS

- [ ] **Step 5: 实现 AutomationTools**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationTools.kt`：

```kotlin
package com.yunian.ai.feature.automation

import android.app.Application
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationStore
import com.yunian.ai.feature.automation.data.AutomationType
import java.util.UUID

/**
 * 自动化 AI 工具集 —— 注册为 [AiTool]，供 AI 对话调用。
 * - automation_create  创建自动化（需确认卡片）
 * - automation_cancel  取消自动化（直接执行）
 * - automation_list    列出当前自动化
 */
object AutomationTools {

    fun registerAll(store: AutomationStore, app: Application) {
        ToolRegistry.register(CreateAutomationTool(store, app))
        ToolRegistry.register(CancelAutomationTool(store, app))
        ToolRegistry.register(ListAutomationsTool(store))
    }

    private class CreateAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_create"
        override val description = "创建定时自动化任务（如 每天早8点提醒喝水）。" +
            "参数：title 任务名，type=once|daily|weekly，" +
            "once 用 triggerAt(epoch毫秒)，daily/weekly 用 hour+minute（weekly 加 dayOfWeek，1=周一..7=周日），" +
            "message 为到点时伴侣发的自然文案（可选，缺省自动生成）。此操作需用户确认。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "title":{"type":"string","description":"任务名，如 喝水"},
                "companionId":{"type":"integer","description":"从哪个伴侣对话创建"},
                "type":{"type":"string","enum":["once","daily","weekly"]},
                "triggerAt":{"type":"integer","description":"ONCE 触发时间 epoch 毫秒"},
                "hour":{"type":"integer","description":"DAILY/WEEKLY 触发小时 0-23"},
                "minute":{"type":"integer","description":"DAILY/WEEKLY 触发分钟 0-59"},
                "dayOfWeek":{"type":"integer","description":"WEEKLY 周几，1=周一..7=周日"},
                "message":{"type":"string","description":"到点时伴侣发的自然文案"}
            },"required":["title","companionId","type"]}
        """.trimIndent()
        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson) ?: return argumentsJson.take(120)
            val whenText = when (p.type) {
                AutomationType.ONCE -> "一次（${formatOnce(p.triggerAtMillis)}）"
                AutomationType.DAILY -> "每天 ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
                AutomationType.WEEKLY -> "每周${weekdayName(p.dayOfWeekCalendar)} ${p.hourOfDay.toString().padStart(2, '0')}:${p.minuteOfHour.toString().padStart(2, '0')}"
            }
            return "自动化「${p.title}」· $whenText"
        }

        override suspend fun execute(argumentsJson: String): String {
            val p = AutomationToolLogic.parseCreateParams(argumentsJson)
                ?: return """{"error":"参数解析失败，请重试"}"""
            val automation = Automation(
                id = UUID.randomUUID().toString(),
                title = p.title,
                companionId = p.companionId,
                type = p.type,
                triggerAtMillis = p.triggerAtMillis,
                hourOfDay = p.hourOfDay,
                minuteOfHour = p.minuteOfHour,
                dayOfWeek = p.dayOfWeekCalendar,
                message = p.message
            )
            store.upsert(automation)
            AutomationScheduler.reschedule(app, automation)
            return """{"id":"${automation.id}","title":"${p.title}","created":true}"""
        }

        private fun formatOnce(ms: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "${cal.get(java.util.Calendar.MONTH) + 1}月${cal.get(java.util.Calendar.DAY_OF_MONTH)}日 " +
                "${cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')}:${cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')}"
        }

        private fun weekdayName(dayOfWeekCalendar: Int?): String = when (dayOfWeekCalendar) {
            2 -> "一"; 3 -> "二"; 4 -> "三"; 5 -> "四"; 6 -> "五"; 7 -> "六"; 1 -> "日"; else -> "?"
        }
    }

    private class CancelAutomationTool(
        private val store: AutomationStore,
        private val app: Application
    ) : AiTool {
        override val name = "automation_cancel"
        override val description = "取消定时自动化任务。参数 id 为自动化ID；若只给 title 则按名称模糊匹配，唯一命中时取消，多个匹配返回候选列表。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{
                "id":{"type":"string","description":"自动化ID"},
                "title":{"type":"string","description":"任务名（模糊匹配）"}
            }}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            val obj = runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(argumentsJson).jsonObject
            }.getOrNull() ?: return """{"error":"参数解析失败"}"""
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
            val all = store.list()
            if (!id.isNullOrBlank()) {
                val target = all.firstOrNull { it.id == id } ?: return """{"error":"自动化不存在"}"""
                store.delete(id)
                AutomationScheduler.cancel(app, id)
                return """{"cancelled":true,"title":"${target.title}"}"""
            }
            val matches = AutomationToolLogic.matchByTitle(all, title.orEmpty())
            return when {
                matches.isEmpty() -> """{"cancelled":false,"error":"没有找到匹配的自动化"}"""
                matches.size == 1 -> {
                    store.delete(matches[0].id)
                    AutomationScheduler.cancel(app, matches[0].id)
                    """{"cancelled":true,"title":"${matches[0].title}"}"""
                }
                else -> """{"cancelled":false,"candidates":[${matches.joinToString(",") { "\"${it.title}\"" }}]}"""
            }
        }
    }

    private class ListAutomationsTool(
        private val store: AutomationStore
    ) : AiTool {
        override val name = "automation_list"
        override val description = "列出当前全部定时自动化任务，返回 id、title、type、触发时间、启用状态。"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""

        override suspend fun execute(argumentsJson: String): String {
            val list = store.list()
            val sb = StringBuilder("[")
            list.forEachIndexed { i, a ->
                if (i > 0) sb.append(",")
                sb.append("""{"id":"${a.id}","title":"${a.title}","type":"${a.type.name.lowercase()}","enabled":${a.enabled}}""")
            }
            sb.append("]")
            return sb.toString()
        }
    }
}
```

- [ ] **Step 6: app 启动注册 + 调度重建**

在 `app/src/main/java/com/yunian/ai/YuNianApplication.kt` 的 `registerServiceProviders` 末尾（`MemoryRecallTools.registerAll(...)` 之后）新增：

```kotlin
            // ── 自动化工具（AI 对话可创建/取消定时自动化） ──
            ServiceRegistry.registerSingleton(AutomationStore::class.java) {
                com.yunian.ai.feature.automation.data.AutomationStore(app)
            }
            com.yunian.ai.feature.automation.AutomationTools.registerAll(
                ServiceRegistry.getOrThrow(AutomationStore::class.java),
                app
            )
            // 启动对账：重建全部启用自动化的 WorkManager 调度
            runCatching {
                val automations = ServiceRegistry.getOrThrow(AutomationStore::class.java).list()
                com.yunian.ai.feature.automation.AutomationScheduler.rescheduleAll(app, automations)
            }.onFailure {
                SecureLog.e("YuNianApplication", "Automation rescheduleAll failed", it)
            }
```

并在文件顶部 import 区添加：

```kotlin
import com.yunian.ai.feature.automation.data.AutomationStore
```

- [ ] **Step 7: 编译验证**

Run: `./gradlew :app:compileDebugKotlin :feature:automation:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，5 个 logic 测试 PASS

- [ ] **Step 8: Commit**

```bash
git add feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationToolLogic.kt feature/automation/src/main/java/com/yunian/ai/feature/automation/AutomationTools.kt feature/automation/src/test/java/com/yunian/ai/feature/automation/AutomationToolLogicTest.kt app/src/main/java/com/yunian/ai/YuNianApplication.kt
git commit -m "feat(automation): AI 工具 create/cancel/list + app 注册与调度重建"
```

---

### Task 6: 确认卡片机制（AiToolLoopRunner + ChatGenerationManager + 瑞幸 createOrder）

**Files:**
- Create: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ToolConfirmationRequest.kt`
- Modify: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiToolLoopRunner.kt`
- Modify: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt`
- Modify: `feature/coffee/src/main/java/com/yunian/ai/feature/coffee/LuckinCoffeeTools.kt`（CreateOrderTool 需确认 + 摘要）
- Test: `feature/chat/src/test/java/com/yunian/ai/feature/chat/AiToolLoopRunnerConfirmationTest.kt`

**Interfaces:**
- Consumes: `AiTool.requiresConfirmation`、`AiTool.summarizeArguments`、`TimeoutBudgets.AUTOMATION_CONFIRM_TIMEOUT_MS`（Task 1）
- Produces:
  - `data class ToolConfirmationRequest(id: Long, toolName: String, summary: String, argumentsJson: String)`
  - `fun interface ConfirmationGate { suspend fun requestConfirmation(toolName: String, argumentsJson: String): Boolean }`
  - `AiToolLoopRunner(aiService, confirmationGate: ConfirmationGate? = null)`
  - `ChatGenerationManager.confirmationRequest: StateFlow<ToolConfirmationRequest?>`、`fun respondToConfirmation(id: Long, confirmed: Boolean)`

- [ ] **Step 1: 创建 ToolConfirmationRequest**

创建 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ToolConfirmationRequest.kt`：

```kotlin
package com.yunian.ai.feature.chat.ui.viewmodel

/** AI 工具执行前的用户确认请求（驱动 ChatScreen 弹确认卡片）。 */
data class ToolConfirmationRequest(
    val id: Long,
    val toolName: String,
    val summary: String,
    val argumentsJson: String
)
```

- [ ] **Step 2: 写 AiToolLoopRunner 确认门控测试**

创建 `feature/chat/src/test/java/com/yunian/ai/feature/chat/AiToolLoopRunnerConfirmationTest.kt`：

```kotlin
package com.yunian.ai.feature.chat

import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.AiToolCall
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.chat.ui.viewmodel.AiToolLoopRunner
import com.yunian.ai.feature.chat.ui.viewmodel.ConfirmationGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiToolLoopRunnerConfirmationTest {

    private var gateCalls = 0
    private var gateResult = true
    private var toolExecuted = 0
    private var toolResultContent: String? = null

    @After
    fun tearDown() {
        ToolRegistry.clear()
    }

    private class FakeAiService(private val loopRunner: AiToolLoopRunnerConfirmationTest) : AiServiceProvider {
        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            tools: List<AiTool>?,
            extraSystemRules: String
        ): AiResponse {
            // 第一轮返回 tool_call，之后返回正常回复
            val hasToolResult = history.any { it.toolName != null }
            return if (!hasToolResult) {
                AiResponse(
                    content = "",
                    reasoningContent = null,
                    toolCalls = listOf(AiToolCall(id = "call_1", name = "confirm_me", arguments = """{"v":1}""")),
                    finishReason = "tool_calls"
                )
            } else {
                loopRunner.toolResultContent = history.lastOrNull()?.content
                AiResponse(
                    content = "完成",
                    reasoningContent = null,
                    toolCalls = null,
                    finishReason = "stop"
                )
            }
        }

        override suspend fun sendMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            extraSystemRules: String
        ): AiResponse =
            sendMessage(companion, history, stickerProbability, ntpTimeEnabled, null, extraSystemRules)

        override fun streamMessage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean,
            turnId: com.yunian.ai.domain.timeline.TurnId,
            startedAtMs: Long
        ): kotlinx.coroutines.flow.Flow<com.yunian.ai.domain.stream.AssistantStreamEvent> = error("not used")

        override suspend fun sendMessageWithImage(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            imagePath: String,
            stickerProbability: Int,
            ntpTimeEnabled: Boolean
        ): AiResponse = error("not used")

        override fun shouldProactivelyMessage(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>
        ): Boolean = false

        override suspend fun generateProactiveMessage(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>
        ): String? = null

        override suspend fun sendMessageWithCustomSystem(
            companion: AiCompanionInfo,
            history: List<AiChatMessage>,
            customSystemPrompt: String,
            stickerProbability: Int,
            companionNameMap: Map<Long, String>
        ): String = ""

        override suspend fun generateFollowUpQuestion(
            companion: AiCompanionInfo,
            recentMessages: List<AiChatMessage>,
            lastAiContent: String
        ): String? = null

        override suspend fun callJudge(prompt: String): String = ""

        override suspend fun callGeneration(prompt: String): String = ""
    }

    private class ConfirmMeTool(private val owner: AiToolLoopRunnerConfirmationTest) : AiTool {
        override val name = "confirm_me"
        override val description = "test"
        override val parametersJsonSchema = """{"type":"object","properties":{}}"""
        override val requiresConfirmation: Boolean get() = true
        override suspend fun execute(argumentsJson: String): String {
            owner.toolExecuted++
            return """{"ok":true}"""
        }
    }

    private fun companion() = AiCompanionInfo(id = 1L, name = "测试", personality = "")

    @Test
    fun confirmedToolExecutesAndResultFeedsBack() = runBlocking {
        ToolRegistry.register(ConfirmMeTool(this@AiToolLoopRunnerConfirmationTest))
        gateResult = true
        val gate = ConfirmationGate { _, _ ->
            gateCalls++
            gateResult
        }
        val runner = AiToolLoopRunner(FakeAiService(this@AiToolLoopRunnerConfirmationTest), gate)
        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all())
        assertEquals(1, gateCalls)
        assertEquals(1, toolExecuted)
        assertEquals("完成", resp.content)
        assertTrue(toolResultContent!!.contains("ok"))
    }

    @Test
    fun rejectedToolNotExecuted() = runBlocking {
        ToolRegistry.register(ConfirmMeTool(this@AiToolLoopRunnerConfirmationTest))
        gateResult = false
        val gate = ConfirmationGate { _, _ ->
            gateCalls++
            gateResult
        }
        val runner = AiToolLoopRunner(FakeAiService(this@AiToolLoopRunnerConfirmationTest), gate)
        val resp = runner.executeWithToolLoop(companion(), emptyList(), 0, false, ToolRegistry.all())
        assertEquals(1, gateCalls)
        assertEquals(0, toolExecuted)
        assertEquals("完成", resp.content)
        assertTrue(toolResultContent!!.contains("用户已取消"))
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :feature:chat:testDebugUnitTest --tests "com.yunian.ai.feature.chat.AiToolLoopRunnerConfirmationTest"`
Expected: FAIL（`ConfirmationGate` 不存在，编译错误）

- [ ] **Step 4: 改造 AiToolLoopRunner**

修改 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiToolLoopRunner.kt`：

1) 类声明改为：

```kotlin
/** 工具执行前的用户确认门控；返回 true 继续执行，false 表示用户拒绝。 */
fun interface ConfirmationGate {
    suspend fun requestConfirmation(toolName: String, argumentsJson: String): Boolean
}

class AiToolLoopRunner(
    private val aiService: AiServiceProvider,
    private val confirmationGate: ConfirmationGate? = null
) {
```

2) 将循环内工具执行段（`for (toolCall in activeToolCalls)` 内部）替换为：

```kotlin
            for (toolCall in activeToolCalls) {
                val tool = ToolRegistry.get(toolCall.name)
                val arguments = argumentsForTool(toolCall.name, toolCall.arguments, companionInfo, groupId)
                val result = when {
                    tool == null -> "工具 ${toolCall.name} 不存在"
                    tool.requiresConfirmation && confirmationGate != null -> {
                        val confirmed = runCatching {
                            withTimeoutOrNull(TimeoutBudgets.AUTOMATION_CONFIRM_TIMEOUT_MS) {
                                confirmationGate.requestConfirmation(toolCall.name, arguments)
                            } ?: false
                        }.getOrDefault(false)
                        if (confirmed) {
                            executeTool(tool, arguments)
                        } else {
                            "用户已取消操作"
                        }
                    }
                    else -> executeTool(tool, arguments)
                }
                ChatDebugLog.log("[ToolLoop] ${toolCall.name} executed, resultLen=${result.length}")

                mutableHistory.add(
                    com.yunian.ai.domain.AiDialogueHistoryPolicy.toolResultMessage(
                        toolName = toolCall.name,
                        result = result,
                        companionId = companionInfo.id
                    )
                )
            }
```

3) 新增私有方法 `executeTool`：

```kotlin
    private suspend fun executeTool(tool: AiTool, arguments: String): String =
        runCatching {
            withTimeoutOrNull(TimeoutBudgets.MCP_READ_MS) {
                tool.execute(arguments)
            } ?: "工具执行超时"
        }.getOrElse {
            "工具执行失败: ${it.message}"
        }
```

4) 扩展 `argumentsForTool`，让 `automation_create` 注入 companionId：

```kotlin
    private fun argumentsForTool(
        toolName: String,
        argumentsJson: String,
        companionInfo: AiCompanionInfo,
        groupId: Long?
    ): String {
        if (toolName != "recall_memory" && toolName != "automation_create") return argumentsJson
        val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return argumentsJson
        return JsonObject(
            buildJsonObject {
                obj.forEach { (key, value) ->
                    if (key != "companionId" && key != "groupId") put(key, value)
                }
                if (groupId != null && toolName == "recall_memory") {
                    put("groupId", groupId)
                } else {
                    put("companionId", companionInfo.id)
                }
            }
        ).toString()
    }
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :feature:chat:testDebugUnitTest --tests "com.yunian.ai.feature.chat.AiToolLoopRunnerConfirmationTest"`
Expected: 2 tests PASS

- [ ] **Step 6: ChatGenerationManager 实现 Gate**

修改 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt`：

1) 构造 toolLoopRunner 处（`private val toolLoopRunner = AiToolLoopRunner(aiService)`）改为：

```kotlin
    private val toolLoopRunner = AiToolLoopRunner(aiService, confirmationGate = ::requestToolConfirmation)
```

2) 新增状态与响应方法（放在 `val events` 声明之后）：

```kotlin
    private val _confirmationRequest = MutableStateFlow<ToolConfirmationRequest?>(null)
    /** AI 工具确认卡片请求；非空时 ChatScreen 弹确认 Dialog */
    val confirmationRequest: StateFlow<ToolConfirmationRequest?> = _confirmationRequest.asStateFlow()
    private val confirmationChannel = Channel<Boolean>(capacity = 1)

    private suspend fun requestToolConfirmation(toolName: String, argumentsJson: String): Boolean {
        val tool = ToolRegistry.get(toolName)
        val summary = tool?.summarizeArguments(argumentsJson) ?: argumentsJson.take(120)
        val request = ToolConfirmationRequest(
            id = System.currentTimeMillis(),
            toolName = toolName,
            summary = summary,
            argumentsJson = argumentsJson
        )
        typingState.stopTyping()
        _confirmationRequest.value = request
        return try {
            confirmationChannel.receive()
        } finally {
            if (_confirmationRequest.value?.id == request.id) {
                _confirmationRequest.value = null
            }
            if (activeRequests.get() > 0) typingState.startTyping()
        }
    }

    fun respondToConfirmation(id: Long, confirmed: Boolean) {
        val current = _confirmationRequest.value
        if (current?.id != id) return
        _confirmationRequest.value = null
        confirmationChannel.trySend(confirmed)
    }
```

注意：`ToolRegistry` 已 import；`activeRequests`、`typingState` 为类内已有成员。

- [ ] **Step 7: 瑞幸 createOrder 需确认 + 摘要**

修改 `feature/coffee/src/main/java/com/yunian/ai/feature/coffee/LuckinCoffeeTools.kt` 的 `CreateOrderTool` 类：新增两个覆写：

```kotlin
        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val deptId = obj?.get("deptId")?.jsonPrimitive?.longOrNull
            val count = obj?.get("productList")?.let { el ->
                runCatching { (el as kotlinx.serialization.json.JsonArray).size }.getOrDefault(0)
            } ?: 0
            return "瑞幸下单 · 门店 $deptId · 商品 $count 件（将生成支付二维码）"
        }
```

（`json` 为 LuckinCoffeeTools 内已有私有解析器。）

- [ ] **Step 8: 编译 + 全部相关单测**

Run: `./gradlew :feature:chat:testDebugUnitTest :feature:coffee:compileDebugKotlin :feature:automation:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，确认门控 2 测试 + 逻辑 5 测试 PASS

- [ ] **Step 9: Commit**

```bash
git add feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ToolConfirmationRequest.kt feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/AiToolLoopRunner.kt feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatGenerationManager.kt feature/chat/src/test/java/com/yunian/ai/feature/chat/AiToolLoopRunnerConfirmationTest.kt feature/coffee/src/main/java/com/yunian/ai/feature/coffee/LuckinCoffeeTools.kt
git commit -m "feat(agent): 确认卡片门控 — createOrder/automation_create 需用户确认"
```

---

### Task 7: ChatViewModel + ChatScreen 确认卡片 UI

**Files:**
- Modify: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt`
- Modify: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatGenerationManager.confirmationRequest`、`respondToConfirmation`、`ToolConfirmationRequest`（Task 6）
- Produces: ChatViewModel 暴露 `confirmationRequest: StateFlow<ToolConfirmationRequest?>`、`fun respondToConfirmation(id: Long, confirmed: Boolean)`；ChatScreen 显示确认 Dialog

- [ ] **Step 1: ChatViewModel 暴露确认状态**

在 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt` 的 `val ttsState: StateFlow<ChatTtsState> = generation.ttsState` 之后新增：

```kotlin
    val confirmationRequest: StateFlow<ToolConfirmationRequest?> = generation.confirmationRequest

    fun respondToConfirmation(id: Long, confirmed: Boolean) = generation.respondToConfirmation(id, confirmed)
```

- [ ] **Step 2: ChatScreen 弹确认 Dialog**

在 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt`：

1) 在 `val viewModel: ChatViewModel = viewModel(...)` 之后新增收集：

```kotlin
    val confirmationRequest by viewModel.confirmationRequest.collectAsStateWithLifecycle()
```

2) 在 Composable 末尾（Scaffold 闭合之后、函数体结束之前）新增 Dialog 渲染：

```kotlin
    confirmationRequest?.let { request ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.respondToConfirmation(request.id, confirmed = false) },
            title = {
                androidx.compose.material3.Text(
                    when (request.toolName) {
                        "automation_create" -> "AI 请求创建自动化"
                        "luckin_create_order" -> "AI 请求确认下单"
                        else -> "AI 请求执行操作"
                    }
                )
            },
            text = {
                androidx.compose.material3.Text(request.summary)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.respondToConfirmation(request.id, confirmed = true) }
                ) { androidx.compose.material3.Text("确认") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.respondToConfirmation(request.id, confirmed = false) }
                ) { androidx.compose.material3.Text("取消") }
            }
        )
    }
```

若 `collectAsStateWithLifecycle` 未 import，在文件 import 区添加 `androidx.lifecycle.compose.collectAsStateWithLifecycle`。

- [ ] **Step 3: 编译验证**

Run: `./gradlew :feature:chat:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatViewModel.kt feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/screen/ChatScreen.kt
git commit -m "feat(agent): ChatScreen 确认卡片 UI（创建自动化 / 瑞幸下单）"
```

---

### Task 8: ChatToolIntent 关键词扩充

**Files:**
- Modify: `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatToolIntent.kt`

**Interfaces:**
- Consumes: 无
- Produces: 扩充后的 `ChatToolIntent.keywords`

- [ ] **Step 1: 扩充关键词列表**

修改 `feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatToolIntent.kt` 的 keywords：

```kotlin
    private val keywords = listOf(
        "记忆", "记得", "回忆", "想起", "以前", "之前", "偏好", "喜欢什么",
        "咖啡", "瑞幸", "luckin", "拿铁", "美式", "生椰", "门店", "下单", "订单", "取餐", "取消订单", "支付", "价格",
        "提醒", "定时", "几点", "每天", "每周", "明天", "明早", "闹钟", "待办", "别忘了", "自动化"
    )
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew :feature:chat:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add feature/chat/src/main/java/com/yunian/ai/feature/chat/ui/viewmodel/ChatToolIntent.kt
git commit -m "feat(agent): 工具门控扩充自动化触发词"
```

---

### Task 9: AutomationListScreen + 路由 + 设置入口

**Files:**
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/ui/AutomationListViewModel.kt`
- Create: `feature/automation/src/main/java/com/yunian/ai/feature/automation/ui/AutomationListScreen.kt`
- Modify: `app/src/main/java/com/yunian/ai/MainRoute.kt`
- Modify: `app/src/main/java/com/yunian/ai/MainNavGraph.kt`
- Modify: `feature/profile/src/main/java/com/yunian/ai/feature/profile/GeneralSettingsScreen.kt`

**Interfaces:**
- Consumes: `AutomationStore`（Task 2）、`AutomationScheduler`（Task 4）
- Produces: `MainRoute.Automation`（路由 `"automation"`）；`ToolsSettingsScreen` 增加 `onAutomationClick` 参数；`AutomationListScreen` 展示列表

- [ ] **Step 1: 创建 AutomationListViewModel**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/ui/AutomationListViewModel.kt`：

```kotlin
package com.yunian.ai.feature.automation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.feature.automation.AutomationScheduler
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationListViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AutomationStore(application)

    val automations: StateFlow<List<Automation>> = store.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            store.setEnabled(id, enabled)
            val target = store.list().firstOrNull { it.id == id } ?: return@launch
            if (enabled) {
                AutomationScheduler.reschedule(getApplication(), target)
            } else {
                AutomationScheduler.cancel(getApplication(), id)
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            AutomationScheduler.cancel(getApplication(), id)
        }
    }
}
```

- [ ] **Step 2: 创建 AutomationListScreen**

创建 `feature/automation/src/main/java/com/yunian/ai/feature/automation/ui/AutomationListScreen.kt`：

```kotlin
package com.yunian.ai.feature.automation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import com.yunian.ai.uicommon.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(onNavigateBack: () -> Unit) {
    val viewModel: AutomationListViewModel = viewModel()
    val automations by viewModel.automations.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<Automation?>(null) }
    val colorScheme = AppTheme.colors

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("自动化", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        if (automations.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有自动化任务\n在对话里告诉 AI「每天早8点提醒我喝水」试试吧",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(automations, key = { it.id }) { automation ->
                    AutomationRow(
                        automation = automation,
                        onToggle = { viewModel.toggleEnabled(automation.id, it) },
                        onDelete = { deleteTarget = automation }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除自动化") },
            text = { Text("确定删除「${target.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val colorScheme = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                automation.title,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                triggerText(automation),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = automation.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                "删除",
                tint = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun triggerText(a: Automation): String {
    val repeat = when (a.type) {
        AutomationType.ONCE -> "一次"
        AutomationType.DAILY -> "每天"
        AutomationType.WEEKLY -> {
            val name = when (a.dayOfWeek) {
                2 -> "周一"; 3 -> "周二"; 4 -> "周三"; 5 -> "周四"; 6 -> "周五"; 7 -> "周六"; 1 -> "周日"; else -> "每周"
            }
            name
        }
    }
    val time = if (a.type == AutomationType.ONCE) {
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(a.triggerAtMillis))
    } else {
        "${a.hourOfDay.toString().padStart(2, '0')}:${a.minuteOfHour.toString().padStart(2, '0')}"
    }
    return "$repeat · $time · ${if (a.enabled) "启用" else "停用"}"
}
```

- [ ] **Step 3: MainRoute 增加路由**

在 `app/src/main/java/com/yunian/ai/MainRoute.kt` 的「=== 瑞幸咖啡 ===」区块之后新增：

```kotlin
    // === 自动化 ===
    object Automation : MainRoute("automation")
```

并在 `fromRoute` 的 when 中（`route == "coffee_order" -> CoffeeOrderQuery` 之后）新增：

```kotlin
            route == "automation" -> Automation
```

- [ ] **Step 4: MainNavGraph 注册页面 + 设置入口**

在 `app/src/main/java/com/yunian/ai/MainNavGraph.kt`：

1) import 区新增：

```kotlin
import com.yunian.ai.feature.automation.ui.AutomationListScreen
```

2) 在 `composable(MainRoute.SettingsTools.route)` 内 `onCoffeeClick` 之后新增：

```kotlin
                onAutomationClick = { navController.navigate(MainRoute.Automation.route) }
```

3) 在 Coffee 相关 composable 之后新增：

```kotlin
        composable(MainRoute.Automation.route) {
            AutomationListScreen(onNavigateBack = { navController.popBackStack() })
        }
```

- [ ] **Step 5: ToolsSettingsScreen 增加入口项**

在 `feature/profile/src/main/java/com/yunian/ai/feature/profile/GeneralSettingsScreen.kt`：

1) `ToolsSettingsScreen` 签名改为：

```kotlin
fun ToolsSettingsScreen(
    onNavigateBack: () -> Unit,
    onCoffeeClick: () -> Unit,
    onAutomationClick: () -> Unit = {}
)
```

2) `SettingsCategoryList` 的 items 中，在咖啡项之后新增：

```kotlin
                    MenuItemData(
                        Icons.Filled.Alarm,
                        "自动化",
                        "AI 设置的定时提醒与自动化任务",
                        onAutomationClick
                    )
```

并在文件顶部 import 添加 `androidx.compose.material.icons.filled.Alarm`。

- [ ] **Step 6: 编译验证**

Run: `./gradlew :app:compileDebugKotlin :feature:automation:compileDebugKotlin :feature:profile:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/yunian/ai/MainRoute.kt app/src/main/java/com/yunian/ai/MainNavGraph.kt feature/profile/src/main/java/com/yunian/ai/feature/profile/GeneralSettingsScreen.kt feature/automation/src/main/java/com/yunian/ai/feature/automation/ui
git commit -m "feat(automation): 自动化列表页 + 路由 + 设置入口"
```

---

### Task 10: 全量构建 + 手工验证清单

**Files:**
- 无（验证任务）

- [ ] **Step 1: 全量单测 + 编译**

Run: `./gradlew :feature:automation:testDebugUnitTest :feature:chat:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL（app 组装通过；两个模块单测全部 PASS）

- [ ] **Step 2: 手工验证清单（真机/模拟器）**

1. 对话输入「每天早8点提醒我喝水」→ 出现确认卡片「自动化「喝水」· 每天 08:00」→ 点确认 → AI 回复已设置。
2. 设置 → 工具 → 自动化 → 列表可见该任务，开关/删除可用。
3. 到点（可设 1 分钟后验证）→ 收到系统通知 + 伴侣在聊天里发「到点啦～该喝水啦」。
4. 对话输入「把喝水的自动化取消了」→ AI 调 `automation_cancel` → 列表移除。
5. 瑞幸对话下单 → 预览后 `luckin_create_order` 弹确认卡片 → 点取消不下单；点确认生成支付二维码。
6. 熄屏等待到点 → 通知仍送达（WorkManager 后台执行）。
7. 杀进程重启 → 已设自动化仍在列表，且到点仍触发（`rescheduleAll` 对账）。

- [ ] **Step 3: 收尾提交（若手工验证有修正）**

```bash
git add -A
git commit -m "fix(automation): 手工验证修正"
```
