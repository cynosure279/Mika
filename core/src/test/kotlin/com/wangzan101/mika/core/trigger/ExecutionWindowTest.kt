package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExecutionWindowTest {
    @Test fun deepSeekPresetAvoidsWeekdayPeakAndUsesWeekends() {
        val windows = DEEPSEEK_OFF_PEAK_WINDOWS
        fun at(text: String) = Instant.parse(text).toEpochMilli()

        assertTrue(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T00:59:00Z")))
        assertFalse(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T01:00:00Z")))
        assertFalse(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T03:59:00Z")))
        assertTrue(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T04:00:00Z")))
        assertFalse(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T06:00:00Z")))
        assertFalse(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T09:59:00Z")))
        assertTrue(isWithinExecutionWindow(windows, "UTC", at("2026-11-02T10:00:00Z")))
        assertTrue(isWithinExecutionWindow(windows, "UTC", at("2026-11-07T01:00:00Z")))
        assertTrue(isWithinExecutionWindow(windows, "UTC", at("2026-11-08T06:00:00Z")))
        assertEquals(at("2026-11-02T04:00:00Z"), nextExecutionWindowAt(windows, "UTC", at("2026-11-02T01:00:00Z")))
    }

    @Test fun customWindowCrossesMidnightAndResumesAtNextOpening() {
        val windows = listOf(ExecutionWindow(22 * 60, 2 * 60))
        fun at(text: String) = Instant.parse(text).toEpochMilli()

        assertEquals(at("2026-11-06T22:00:00Z"), nextExecutionWindowAt(windows, "UTC", at("2026-11-06T21:00:00Z")))
        assertEquals(at("2026-11-06T23:00:00Z"), nextExecutionWindowAt(windows, "UTC", at("2026-11-06T23:00:00Z")))
        assertEquals(at("2026-11-07T01:00:00Z"), nextExecutionWindowAt(windows, "UTC", at("2026-11-07T01:00:00Z")))
        assertEquals(at("2026-11-07T22:00:00Z"), nextExecutionWindowAt(windows, "UTC", at("2026-11-07T02:00:00Z")))
    }

    @Test fun dstGapSkipsNonExecutableOpening() {
        val windows = listOf(ExecutionWindow(2 * 60 + 30, 3 * 60))
        val at = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()

        val next = nextExecutionWindowAt(windows, "America/New_York", at)

        assertEquals(Instant.parse("2026-03-09T06:30:00Z").toEpochMilli(), next)
        assertTrue(isWithinExecutionWindow(windows, "America/New_York", next))
    }

    @Test fun previewShowsDeferredIntervalRunsRatherThanRawTriggerTimes() {
        val start = Instant.parse("2026-11-06T08:00:00Z").toEpochMilli()
        val plan = TriggerDefinition(
            "interval", "thread", "check", start, intervalMs = 3_600_000,
            executionWindowZone = "UTC", executionWindows = listOf(ExecutionWindow(9 * 60, 10 * 60)),
        )

        assertEquals(
            listOf("2026-11-06T09:00:00Z", "2026-11-07T09:00:00Z"),
            upcoming(plan, start, 2).map { Instant.ofEpochMilli(it).toString() },
        )
    }

    @Test fun upcomingKeepsPendingCalendarAndIntervalRunsUntilTheirWindowOpens() {
        val due = Instant.parse("2026-11-06T08:00:00Z").toEpochMilli()
        val window = listOf(ExecutionWindow(9 * 60, 10 * 60))
        val interval = TriggerDefinition(
            "interval", "thread", "check", due, intervalMs = 6 * 3_600_000,
            executionWindowZone = "UTC", executionWindows = window,
        )
        val calendar = TriggerDefinition(
            "calendar", "thread", "check", due, calendarTime = "08:00", calendarZone = "UTC",
            executionWindowZone = "UTC", executionWindows = window,
        )

        assertEquals(
            listOf("2026-11-06T09:00:00Z", "2026-11-07T09:00:00Z"),
            upcoming(interval, Instant.parse("2026-11-06T09:00:00Z").toEpochMilli(), 2).map { Instant.ofEpochMilli(it).toString() },
        )
        assertEquals(
            listOf("2026-11-06T09:00:00Z", "2026-11-07T09:00:00Z"),
            upcoming(calendar, Instant.parse("2026-11-06T08:30:00Z").toEpochMilli(), 2).map { Instant.ofEpochMilli(it).toString() },
        )
    }

    @Test fun upcomingOneShotNeverReturnsAPastDeferredRun() {
        val plan = TriggerDefinition(
            "once", "thread", "check", Instant.parse("2026-11-06T08:00:00Z").toEpochMilli(),
            executionWindowZone = "UTC", executionWindows = listOf(ExecutionWindow(9 * 60, 10 * 60)),
        )

        assertEquals(
            listOf("2026-11-06T09:30:00Z"),
            upcoming(plan, Instant.parse("2026-11-06T09:30:00Z").toEpochMilli(), 1).map { Instant.ofEpochMilli(it).toString() },
        )
        assertEquals(
            listOf("2026-11-07T09:00:00Z"),
            upcoming(plan, Instant.parse("2026-11-06T10:30:00Z").toEpochMilli(), 1).map { Instant.ofEpochMilli(it).toString() },
        )
    }
}
