package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationSchedulePolicy
import com.yunian.ai.feature.automation.data.AutomationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class AutomationSchedulePolicyTest {

    private val referenceMonday: LocalDate = LocalDate.of(2026, 8, 10)

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun referenceDay(offsetDays: Int, hour: Int, minute: Int): Long =
        referenceMonday.plusDays(offsetDays.toLong())
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

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
    fun onceTriggerAtNowReturnsNull() {

        val now = 2_000_000L
        val a = automation(AutomationType.ONCE, triggerAtMillis = 2_000_000L)
        assertNull(AutomationSchedulePolicy.nextTriggerAtMillis(a, now))
    }

    @Test
    fun dailyBeforeTimeFiresToday() {

        val now = calendar(2026, 7, 7, 8, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 7, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun dailyAfterTimeFiresTomorrow() {

        val now = calendar(2026, 7, 7, 10, 0).timeInMillis
        val a = automation(AutomationType.DAILY, hourOfDay = 9, minuteOfHour = 30)
        assertEquals(calendar(2026, 7, 8, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyFiresNextMatchingDay() {

        val target = referenceDay(0, 8, 0)
        val now = referenceDay(-1, 12, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(target, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklySameDayBeforeTimeFiresToday() {

        val now = referenceDay(0, 7, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        assertEquals(referenceDay(0, 8, 0), AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)
    }

    @Test
    fun weeklyAfterTimeFiresNextWeek() {

        val now = referenceDay(0, 9, 0)
        val a = automation(AutomationType.WEEKLY, hourOfDay = 8, minuteOfHour = 0, dayOfWeek = 2)
        val next = AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!
        assertTrue(next > now)
        assertEquals(referenceDay(7, 8, 0), next)
    }

    @Test
    fun weeklyNullOrInvalidDayOfWeekHandled() {

        val now = calendar(2026, 7, 7, 8, 0).timeInMillis

        val a = automation(AutomationType.WEEKLY, hourOfDay = 9, minuteOfHour = 30, dayOfWeek = null)
        assertEquals(calendar(2026, 7, 7, 9, 30).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(a, now)!!)

        val b = automation(AutomationType.WEEKLY, hourOfDay = 7, minuteOfHour = 0, dayOfWeek = null)
        assertEquals(calendar(2026, 7, 8, 7, 0).timeInMillis, AutomationSchedulePolicy.nextTriggerAtMillis(b, now)!!)

        val c = automation(AutomationType.WEEKLY, hourOfDay = 10, minuteOfHour = 0, dayOfWeek = 9)
        assertEquals(
            calendar(2026, 7, 8, 10, 0).timeInMillis,
            AutomationSchedulePolicy.nextTriggerAtMillis(c, calendar(2026, 7, 7, 7, 0).timeInMillis)!!
        )
    }

    @Test
    fun normalizedAutomationFixesDailyTriggerAt() {
        val now = referenceDay(0, 21, 0)

        val a = automation(AutomationType.DAILY, hourOfDay = 8, minuteOfHour = 0)
        val normalized = AutomationSchedulePolicy.normalizedAutomation(a, now)

        assertEquals(referenceDay(1, 8, 0), normalized.triggerAtMillis)

        val once = automation(AutomationType.ONCE, triggerAtMillis = 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, AutomationSchedulePolicy.normalizedAutomation(once, now).triggerAtMillis)
    }

    @Test
    fun shouldFireRejectsUninitializedTriggerAt() {
        val now = referenceDay(0, 21, 0)

        val bad = automation(AutomationType.DAILY, hourOfDay = 8, minuteOfHour = 0)
        assertFalse(AutomationSchedulePolicy.shouldFire(bad, now))

        val normalized = AutomationSchedulePolicy.normalizedAutomation(bad, now)
        assertFalse(AutomationSchedulePolicy.shouldFire(normalized, referenceDay(0, 21, 0)))

        assertTrue(AutomationSchedulePolicy.shouldFire(normalized, referenceDay(1, 8, 0)))
    }

    @Test
    fun shouldFireToTimeAndIdempotent() {
        val now = 1_700_000_000_000L

        val due = automation(
            AutomationType.DAILY, hourOfDay = 8, minuteOfHour = 0
        ).copy(triggerAtMillis = now - 1000L, stats = com.yunian.ai.feature.automation.data.AutomationStats())
        assertTrue(AutomationSchedulePolicy.shouldFire(due, now))

        val notYet = due.copy(triggerAtMillis = now + 60_000L)
        assertFalse(AutomationSchedulePolicy.shouldFire(notYet, now))

        val alreadyFired = due.copy(
            stats = com.yunian.ai.feature.automation.data.AutomationStats(
                fireCount = 1, lastFiredAt = now - 500L,
                lastScheduledFiredAt = now - 500L, lastResult = "success"
            )
        )
        assertFalse(AutomationSchedulePolicy.shouldFire(alreadyFired, now))

        val manualFiredOnly = due.copy(
            stats = com.yunian.ai.feature.automation.data.AutomationStats(
                fireCount = 1, lastFiredAt = now - 500L, lastResult = "success"
            )
        )
        assertTrue(AutomationSchedulePolicy.shouldFire(manualFiredOnly, now))

        val disabled = due.copy(enabled = false)
        assertFalse(AutomationSchedulePolicy.shouldFire(disabled, now))
    }

    private fun calendar(year: Int, month0: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month0, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
