package com.yunian.ai.feature.skills.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yunian.ai.common.SecureLog

/**
 * 予念无障碍服务：AI 控制手机的执行通道。
 * 用户在系统设置中开启本服务后，AI 即可通过工具读屏、点击、滑动、执行全局导航。
 * 服务实例以进程级单例暴露给工具层；未开启时工具返回引导信息。
 */
class YuNianAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SecureLog.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** 读取当前屏幕全部可见文本（截断） */
    fun readScreenText(maxLength: Int = 3000): String {
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > NODE_MAX_DEPTH) return
            node.text?.takeIf { it.isNotBlank() }?.let {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(it)
            }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(it)
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (sb.length >= maxLength) return
            }
        }
        walk(rootInActiveWindow, 0)
        return sb.toString().take(maxLength)
    }

    /** 按文本查找可见节点并点击其中心 */
    fun findAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
            .orEmpty()
            .filter { it.isClickable || it.parent != null }
        val target = nodes.firstOrNull { it.isClickable }
            ?: nodes.firstOrNull()?.let { climbToClickable(it) }
            ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && !current.isClickable && depth < NODE_MAX_DEPTH) {
            current = current.parent
            depth++
        }
        return current?.takeIf { it.isClickable }
    }

    /** 手势点击屏幕坐标 */
    fun tap(x: Float, y: Float): Boolean = dispatchPathGesture(x, y, x, y, 60)

    /** 手势滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean =
        dispatchPathGesture(x1, y1, x2, y2, durationMs)

    private fun dispatchPathGesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50)))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                SecureLog.d(TAG, "gesture completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                SecureLog.w(TAG, "gesture cancelled")
            }
        }, null)
    }

    /** 全局导航动作（返回/主页/通知栏等，用 AccessibilityService 常量） */
    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    companion object {
        private const val TAG = "YuNianA11y"
        private const val NODE_MAX_DEPTH = 30

        @Volatile
        var instance: YuNianAccessibilityService? = null
            private set

        val isReady: Boolean get() = instance != null
    }
}
