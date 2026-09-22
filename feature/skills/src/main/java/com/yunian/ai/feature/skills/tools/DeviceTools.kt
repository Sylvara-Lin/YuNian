package com.yunian.ai.feature.skills.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 设备接管工具集（第一批，均为低风险操作）：
 * 把常用设备能力以工具形式暴露给 AI，使其能在对话中主动操作设备。
 * 全部通过系统标准 API 实现，敏感动作（闹钟）只预填 UI、由用户确认保存。
 */
private val deviceJson = Json { ignoreUnknownKeys = true }

private fun parseArgs(argumentsJson: String) =
    runCatching { deviceJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

private fun errorResult(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

private fun okResult(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
    put("ok", true)
    pairs.forEach { (k, v) -> when (v) {
        is String? -> put(k, v)
        is Int? -> put(k, v)
        is Boolean? -> put(k, v)
    } }
}.toString()

/** 打开应用：按应用名或包名匹配桌面应用并启动 */
class DeviceOpenAppTool(private val context: Context) : AiTool {
    override val name = "device_open_app"
    override val description = "打开手机上的应用。参数 {name?: string(应用名), package?: string(包名)}，二选一。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"name":{"type":"string","description":"应用显示名，如 微信"},"package":{"type":"string","description":"完整包名，如 com.tencent.mm"}}}
    """.trimIndent()
    override fun systemPrompt() = "device_open_app: 打开手机应用。参数 {name?: string, package?: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorResult("Invalid arguments")
        val pkg = args["package"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (pkg.isBlank() && name.isBlank()) return errorResult("需要提供 name 或 package")

        val pm = context.applicationContext.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        if (pkg.isNotBlank()) {
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return errorResult("未找到可打开的应用包：$pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching {
                context.applicationContext.startActivity(intent)
                okResult("opened" to pkg)
            }.getOrElse { errorResult("启动失败: ${it.message}") }
        }

        val candidates = pm.queryIntentActivities(launchIntent, 0)
        val matched = candidates.filter { resolveInfo ->
            resolveInfo.loadLabel(pm).toString().contains(name, ignoreCase = true)
        }
        if (matched.isEmpty()) return errorResult("未找到名称包含\"$name\"的应用")
        val target = matched.first()
        val intent = pm.getLaunchIntentForPackage(target.activityInfo.packageName)
            ?: return errorResult("该应用不可直接打开")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.applicationContext.startActivity(intent)
            okResult(
                "opened" to target.activityInfo.packageName,
                "label" to target.loadLabel(pm).toString(),
            )
        }.getOrElse { errorResult("启动失败: ${it.message}") }
    }
}

/** 用系统浏览器打开网页 */
class DeviceOpenUrlTool(private val context: Context) : AiTool {
    override val name = "device_open_url"
    override val description = "用系统浏览器打开一个网页链接。参数 {url: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"url":{"type":"string","description":"完整 URL，以 http/https 开头"}},"required":["url"]}
    """.trimIndent()
    override fun systemPrompt() = "device_open_url: 打开网页。参数 {url: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorResult("Invalid arguments")
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errorResult("url 必须以 http:// 或 https:// 开头")
        }
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.applicationContext.startActivity(intent)
            okResult("opened" to url)
        }.getOrElse { errorResult("打开失败: ${it.message}") }
    }
}

/** 读取剪贴板文本 */
class DeviceGetClipboardTool(private val context: Context) : AiTool {
    override val name = "device_get_clipboard"
    override val description = "读取剪贴板文本内容（无参数）。应用在前台时才能读取。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "device_get_clipboard: 读剪贴板文本。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return errorResult("剪贴板服务不可用")
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
            ?: return errorResult("剪贴板为空或不可读（应用需在前台）")
        return okResult("text" to text.take(2000))
    }
}

/** 写入剪贴板文本 */
class DeviceSetClipboardTool(private val context: Context) : AiTool {
    override val name = "device_set_clipboard"
    override val description = "把文本写入剪贴板。参数 {text: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}
    """.trimIndent()
    override fun systemPrompt() = "device_set_clipboard: 写剪贴板。参数 {text: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorResult("Invalid arguments")
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("text 不能为空")
        val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return errorResult("剪贴板服务不可用")
        cm.setPrimaryClip(ClipData.newPlainText("lianyu", text))
        return okResult("copied" to true, "length" to text.length)
    }
}

/** 预填系统闹钟（只跳转到闹钟 UI，由用户确认保存，不会静默设闹钟） */
class DeviceSetAlarmTool(private val context: Context) : AiTool {
    override val name = "device_set_alarm"
    override val description = "预填一个系统闹钟（会打开系统闹钟界面，由用户确认保存）。参数 {hour: 0-23, minute: 0-59, message?: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"message":{"type":"string"}},"required":["hour","minute"]}
    """.trimIndent()
    override fun systemPrompt() = "device_set_alarm: 预填系统闹钟（用户确认后生效）。参数 {hour: int, minute: int, message?: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorResult("Invalid arguments")
        val hour = args["hour"]?.jsonPrimitive?.intOrNull
        val minute = args["minute"]?.jsonPrimitive?.intOrNull
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return errorResult("hour 需 0-23，minute 需 0-59")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            args["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.applicationContext.startActivity(intent)
            okResult("alarm_prefilled" to "%02d:%02d".format(hour, minute))
        }.getOrElse { errorResult("设置失败（设备可能没有时钟应用）: ${it.message}") }
    }
}

/** 发一条系统通知 */
class DeviceNotifyTool(private val context: Context) : AiTool {
    override val name = "device_notify"
    override val description = "发送一条系统通知。参数 {title: string, body?: string}。"
    override val parametersJsonSchema = """
        {"type":"object","properties":{"title":{"type":"string"},"body":{"type":"string"}},"required":["title"]}
    """.trimIndent()
    override fun systemPrompt() = "device_notify: 发系统通知。参数 {title: string, body?: string}。"
    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = parseArgs(argumentsJson) ?: return errorResult("Invalid arguments")
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return errorResult("title 不能为空")
        val body = args["body"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return errorResult("通知服务不可用")
        val channelId = "lianyu_device_agent"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "予念助手通知", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = NotificationCompat.Builder(context.applicationContext, channelId)
            .setSmallIcon(context.applicationContext.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        return runCatching {
            nm.notify(NOTIFY_ID_BASE + (title.hashCode() and 0xFFFF), notification)
            okResult("notified" to true)
        }.getOrElse {
            SecureLog.w("DeviceTools", "notify failed: ${it.message}")
            errorResult("通知发送失败：若未授予通知权限，请先在系统设置中允许")
        }
    }

    private companion object {
        const val NOTIFY_ID_BASE = 20_000
    }
}

/** 电量与充电状态 */
class DeviceBatteryStatusTool(private val context: Context) : AiTool {
    override val name = "device_battery_status"
    override val description = "查询手机电量与充电状态（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "device_battery_status: 查电量/充电状态。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val intent = context.applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
            ?: return errorResult("无法获取电池状态")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return errorResult("电池数据异常")
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return okResult("percent" to percent, "charging" to charging)
    }
}

/** 当前日期时间与星期 */
class DeviceGetTimeTool : AiTool {
    override val name = "device_get_time"
    override val description = "获取当前日期、时间和星期（无参数）。"
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""
    override fun systemPrompt() = "device_get_time: 获取当前日期时间星期。无参数。"
    override val requiresConfirmation = false

    override suspend fun execute(@Suppress("UNUSED_PARAMETER") argumentsJson: String): String {
        val now = LocalDateTime.now()
        val week = when (now.dayOfWeek.value) {
            1 -> "星期一"; 2 -> "星期二"; 3 -> "星期三"; 4 -> "星期四"
            5 -> "星期五"; 6 -> "星期六"; else -> "星期日"
        }
        val text = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return okResult("datetime" to text, "weekday" to week)
    }
}

/** 注册全部设备工具。在 Application 组装阶段调用一次。 */
fun registerDeviceTools(context: Context) {
    val appContext = context.applicationContext
    ToolRegistry.register(DeviceOpenAppTool(appContext))
    ToolRegistry.register(DeviceOpenUrlTool(appContext))
    ToolRegistry.register(DeviceGetClipboardTool(appContext))
    ToolRegistry.register(DeviceSetClipboardTool(appContext))
    ToolRegistry.register(DeviceSetAlarmTool(appContext))
    ToolRegistry.register(DeviceNotifyTool(appContext))
    ToolRegistry.register(DeviceBatteryStatusTool(appContext))
    ToolRegistry.register(DeviceGetTimeTool())
    SecureLog.i("DeviceTools", "Device tools registered: 8")
}
