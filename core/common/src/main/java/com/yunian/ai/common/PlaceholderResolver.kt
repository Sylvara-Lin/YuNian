package com.yunian.ai.common

import android.content.Context
import com.yunian.ai.domain.PlaceholderContext
import com.yunian.ai.domain.PlaceholderProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PlaceholderResolver(
    private val context: Context
) : PlaceholderProvider {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun resolve(key: String): String? {
        val lowerKey = key.lowercase()
        return when (lowerKey) {
            "model_id", "model_name" -> null
            "locale" -> Locale.getDefault().toString()
            "timezone" -> TimeZone.getDefault().id
            "battery_level" -> getBatteryLevel().toString()
            else -> null
        }
    }

    override fun resolve(text: String, context: PlaceholderContext?): String {
        if (text.isBlank()) return text

        var result = text

        val charName = context?.charName ?: "角色"
        val userName = context?.userName ?: "用户"
        val curDate = context?.currentTimeMillis?.let { dateFormat.format(Date(it)) } ?: dateFormat.format(Date())
        val curTime = context?.currentTimeMillis?.let { timeFormat.format(Date(it)) } ?: timeFormat.format(Date())
        val modelId = context?.modelId ?: ""
        val modelName = context?.modelName ?: ""
        val locale = context?.locale ?: Locale.getDefault().toString()
        val timezone = context?.timezone ?: TimeZone.getDefault().id
        val batteryLevel = context?.batteryLevel?.toString() ?: getBatteryLevel().toString()

        result = result
            .replace("{{char}}", charName).replace("{char}", charName)
            .replace("{{CHAR}}", charName).replace("{CHAR}", charName)
            .replace("{{user}}", userName).replace("{user}", userName)
            .replace("{{USER}}", userName).replace("{USER}", userName)
            .replace("{{cur_date}}", curDate).replace("{cur_date}", curDate)
            .replace("{{cur_time}}", curTime).replace("{cur_time}", curTime)
            .replace("{{model_id}}", modelId).replace("{model_id}", modelId)
            .replace("{{model_name}}", modelName).replace("{model_name}", modelName)
            .replace("{{locale}}", locale).replace("{locale}", locale)
            .replace("{{timezone}}", timezone).replace("{timezone}", timezone)
            .replace("{{battery_level}}", batteryLevel).replace("{battery_level}", batteryLevel)
            .replace("{{nickname}}", userName).replace("{nickname}", userName)

        val placeholderRegex = "\\{\\{([^}]+)\\}\\}|\\{([^}]+)\\}".toRegex()
        return placeholderRegex.replace(result) { matchResult ->
            val key = matchResult.groupValues[1]?.ifBlank { matchResult.groupValues[2] }?.lowercase() ?: ""

            context?.extras?.get(key)

                ?: resolve(key)

                ?: matchResult.value
        }
    }

    private fun getBatteryLevel(): Int {
        try {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                return (level * 100 / scale).coerceIn(0, 100)
            }
        } catch (e: Exception) {

        }
        return 100
    }
}
