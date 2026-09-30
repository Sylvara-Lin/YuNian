package com.yunian.ai.common.crash

import com.yunian.ai.common.SecureLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * QA（严过关/Edward）独立补充：**release 语义证明**。
 *
 * 本功能全部意义在于 release（`isDebug=false`）。模拟器无法跑 release（安全守卫会自毁），
 * 故此处用 JVM 单测证明：当 [SecureLog] 处于 `isDebug=false`（等价 release）时，
 * 每个 `SecureLog.x(...)` 调用**仍然写入** [CrashBreadcrumbs]；`android.util.Log` 因被
 * `isDebug` 守卫而**不会**被调用（因此本测试无需 mock android.util.Log 即可运行）。
 *
 * 若某天有人把 `CrashBreadcrumbs.add(...)` 挪回 `if (isDebug)` 之内，本测试会立刻失败。
 */
class QaCrashReleaseSemanticsTest {

    @Before
    fun setUp() {
        CrashBreadcrumbs.clear()
        SecureLog.init(debug = false) // 等价 release：isDebug=false
    }

    @Test
    fun releaseMode_secureLogStillRecordsBreadcrumbs() {
        SecureLog.d("QA-D", "release-d-marker")
        SecureLog.i("QA-I", "release-i-marker")
        SecureLog.w("QA-W", "release-w-marker")
        SecureLog.e("QA-E", "release-e-marker")
        SecureLog.api("QA-A", "release-api-marker")
        SecureLog.network("QA-N", "release-net-marker")
        SecureLog.security("release-sec-marker")

        val snap = CrashBreadcrumbs.snapshot()
        assertTrue("d() breadcrumb missing in release mode", snap.contains("release-d-marker"))
        assertTrue("i() breadcrumb missing in release mode", snap.contains("release-i-marker"))
        assertTrue("w() breadcrumb missing in release mode", snap.contains("release-w-marker"))
        assertTrue("e() breadcrumb missing in release mode", snap.contains("release-e-marker"))
        assertTrue("api() breadcrumb missing in release mode", snap.contains("release-api-marker"))
        assertTrue("network() breadcrumb missing in release mode", snap.contains("release-net-marker"))
        assertTrue("security() breadcrumb missing in release mode", snap.contains("release-sec-marker"))
    }

    @Test
    fun releaseMode_throwableOverloadStillRecordsBreadcrumbs() {
        SecureLog.e("QA-E2", "boom", IllegalStateException("kaboom"))
        val snap = CrashBreadcrumbs.snapshot()
        assertTrue("e(msg, throwable) breadcrumb missing in release mode", snap.contains("boom"))
        assertTrue("throwable summary missing", snap.contains("IllegalStateException"))
    }

    @Test
    fun releaseMode_breadcrumbsStayBounded() {
        repeat(500) { SecureLog.d("QA", "bulk-$it") }
        val snap = CrashBreadcrumbs.snapshot()
        assertTrue("newest entry must survive ring overwrite", snap.contains("bulk-499"))
        assertTrue("dump must stay bounded", snap.length < 64 * 1024)
    }

    @Test
    fun releaseMode_redactionAppliesOnSnapshot() {
        SecureLog.d("QA", "post api_key=SUPERSECRET777")
        val snap = CrashBreadcrumbs.snapshot()
        assertFalse("secret must be redacted in release dump", snap.contains("SUPERSECRET777"))
        assertTrue(snap.contains("[REDACTED]"))
    }
}
