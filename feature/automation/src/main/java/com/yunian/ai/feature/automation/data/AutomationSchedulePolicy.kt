package com.yunian.ai.feature.automation.data

import java.util.Calendar

object AutomationSchedulePolicy {

    fun shouldFire(a: Automation, now: Long): Boolean {
        return a.enabled &&
            a.triggerAtMillis > 0 &&
            now >= a.triggerAtMillis &&
            a.stats.lastScheduledFiredAt < a.triggerAtMillis
    }

    fun normalizedAutomation(a: Automation, now: Long): Automation {
        if (a.type == AutomationType.ONCE || a.triggerAtMillis > 0) return a
        val next = nextTriggerAtMillis(a, now) ?: return a
        return a.copy(triggerAtMillis = next)
    }

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
