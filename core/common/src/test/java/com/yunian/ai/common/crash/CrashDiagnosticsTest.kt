package com.yunian.ai.common.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CrashRedactor] 与 [CrashBreadcrumbs] 的纯 JVM 单测（不依赖 Android Runtime）。
 */
class CrashDiagnosticsTest {

    @Before
    fun setUp() {
        CrashBreadcrumbs.clear()
    }

    @Test
    fun redactor_masksSkStyleKey() {
        val out = CrashRedactor.redact("config sk-abc123456789DEFghi used")
        assertFalse(out.contains("sk-abc123456789DEFghi"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_masksJwt() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = CrashRedactor.redact("Authorization token=$jwt")
        assertFalse(out.contains(jwt))
    }

    @Test
    fun redactor_masksKeyValueSecrets() {
        val out = CrashRedactor.redact("api_key=SECRETVALUE123 & token: \"tok_9f8e7d6c\"")
        assertFalse(out.contains("SECRETVALUE123"))
        assertFalse(out.contains("tok_9f8e7d6c"))
        assertTrue(out.contains("api_key="))
    }

    @Test
    fun redactor_masksBearer() {
        val out = CrashRedactor.redact("header Bearer abc.def.ghi")
        assertFalse(out.contains("abc.def.ghi"))
        assertTrue(out.lowercase().contains("bearer"))
    }

    @Test
    fun redactor_masksBareKeyAssignments() {
        // 覆盖项目里真实存在的 SecureLog 调用形态（AiService / SettingsViewModel）。
        val out = CrashRedactor.redact(
            "Final config: model=m, key=deadbeef1234..., userKey=cafe0123..., with key: feed9876..."
        )
        assertFalse("bare key= leak", out.contains("deadbeef1234"))
        assertFalse("userKey= leak", out.contains("cafe0123"))
        assertFalse("key: leak", out.contains("feed9876"))
    }

    @Test
    fun redactor_masksTruncatedSkFragment() {
        // QA 报告的真实泄漏形态（take(8) 后只剩 5 字符，旧 sk-{8,} 规则漏掉）。
        val out = CrashRedactor.redact("[AiService] Key失败冷却5s: sk-12345...")
        assertFalse("truncated sk- fragment leak", out.contains("sk-12345"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_masksContentFields() {
        // PushMessageDispatcher / Diary / Summary / TextProcessor 等内容类调用点。
        val out = CrashRedactor.redact(
            "Message from vendor: title=今晚吃什么 content=宝贝你在吗 body={\"err\":\"secret\"}"
        )
        assertFalse("title leak", out.contains("今晚吃什么"))
        assertFalse("content leak", out.contains("宝贝你在吗"))
        assertFalse("body leak", out.contains("secret"))
    }

    @Test
    fun redactor_masksOriginalQuotedContent() {
        val out = CrashRedactor.redact("WARNING: blank output. Original: '这是一段AI原文', stickers sent: 2")
        assertFalse("original content leak", out.contains("这是一段AI原文"))
        assertTrue(out.contains("[REDACTED]"))
    }

    @Test
    fun redactor_keepsPlainText() {
        val plain = "user opened settings page"
        assertEquals(plain, CrashRedactor.redact(plain))
    }

    @Test
    fun breadcrumbs_keepsOrderAndContent() {
        CrashBreadcrumbs.add("T1", "first")
        CrashBreadcrumbs.add("T2", "second")
        val snap = CrashBreadcrumbs.snapshot()
        val firstIdx = snap.indexOf("first")
        val secondIdx = snap.indexOf("second")
        assertTrue("first entry present", firstIdx >= 0)
        assertTrue("second entry present", secondIdx >= 0)
        assertTrue("order preserved", firstIdx < secondIdx)
        assertTrue(snap.contains("[T1]"))
        assertTrue(snap.contains("[T2]"))
    }

    @Test
    fun breadcrumbs_isBounded_ringOverwrites() {
        val huge = "x".repeat(5000)
        repeat(1000) { CrashBreadcrumbs.add("T", "entry-$it-$huge") }
        val snap = CrashBreadcrumbs.snapshot()
        // 最新一条一定在，最早的已被环形覆盖。
        assertTrue(snap.contains("entry-999"))
        assertFalse(snap.contains("entry-0-"))
        // dump 有上界。
        assertTrue(snap.length < 64 * 1024)
    }

    @Test
    fun breadcrumbs_truncatesLongEntry() {
        CrashBreadcrumbs.add("T", "y".repeat(1000))
        val snap = CrashBreadcrumbs.snapshot()
        assertTrue(snap.length < 1000)
    }

    @Test
    fun breadcrumbs_redactsOnSnapshot() {
        CrashBreadcrumbs.add("NET", "posted api_key=SUPERSECRET999")
        val snap = CrashBreadcrumbs.snapshot()
        assertFalse(snap.contains("SUPERSECRET999"))
        assertTrue(snap.contains("[REDACTED]"))
    }

    @Test
    fun breadcrumbs_emptyReturnsPlaceholder() {
        assertEquals("(no breadcrumbs)", CrashBreadcrumbs.snapshot())
    }

    @Test
    fun breadcrumbs_versionIncrementsOnAdd() {
        val before = CrashBreadcrumbs.version()
        CrashBreadcrumbs.add("T", "x")
        CrashBreadcrumbs.add("T", "y")
        assertEquals(before + 2, CrashBreadcrumbs.version())
    }

    @Test
    fun exitPolicy_flagsCrashLikeReasonsAtUserFacingImportance() {
        // 期望值**直接取框架常量**，杜绝「把错误数字写死进断言」——
        // 上一版正是把 isNotable(8) 写死为 true（8 实为 PERMISSION_CHANGE），21/21 全绿却语义错误。
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_SIGNALED, fg))               // 2
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, fg))             // 3
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH, fg))                  // 4
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH_NATIVE, fg))           // 5
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_ANR, fg))                    // 6
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, fg)) // 7
        // ★ 权限变更（8）不是崩溃 —— 必须排除（上一版误报的根因）。
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PERMISSION_CHANGE, fg))     // 8
    }

    @Test
    fun exitPolicy_ignoresNormalExits() {
        val fg = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_UNKNOWN, fg))                  // 0
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_EXIT_SELF, fg))                // 1
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PERMISSION_CHANGE, fg))        // 8
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, fg)) // 9
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_USER_REQUESTED, fg))           // 10
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_USER_STOPPED, fg))             // 11
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_DEPENDENCY_DIED, fg))          // 12
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_OTHER, fg))                    // 13
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_FREEZER, fg))                  // 14
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, fg))     // 15
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_PACKAGE_UPDATED, fg))          // 16
    }

    @Test
    @Suppress("DEPRECATION") // IMPORTANCE_BACKGROUND / IMPORTANCE_EMPTY 是能力过滤用的稳定语义值
    fun exitPolicy_lowMemoryNarrowedToForegroundOrForegroundService() {
        // 用户确实在前台 / 前台服务 → 提示
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE))
        // 纯后台被回收 → 不提示（避免噪声化骚扰）
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND))
        assertFalse(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY))
        // 非 LOW_MEMORY 不受重要性影响：后台发生 native 崩溃仍要提示
        assertTrue(ApplicationExitPolicy.isNotable(ApplicationExitInfo.REASON_CRASH_NATIVE, ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND))
    }
}
