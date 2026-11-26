package com.wangzan101.mika.core.trigger

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 允许模型任务执行的当地时间段，分钟从 0 到 1440；[endMinute] 小于 [startMinute] 时跨过午夜。
 * [days] 是窗口开始日，1 到 7 对应周一到周日，空表示每天。00:00–24:00 可表示全天。
 */
@Serializable
data class ExecutionWindow(
    val startMinute: Int,
    val endMinute: Int,
    val days: List<Int> = emptyList(),
)

fun isValidExecutionWindow(window: ExecutionWindow): Boolean =
    window.startMinute in 0..1439 && window.endMinute in 0..1440 &&
        window.startMinute != window.endMinute && window.days.all { it in 1..7 } &&
        window.days == window.days.distinct().sorted()

private val WEEKDAYS = (1..5).toList()
private val WEEKEND = listOf(6, 7)

/**
 * DeepSeek 公布的工作日峰段为 01:00–04:00、06:00–10:00 UTC，其余时间与周末半价。
 * 法定节假日也全天半价，但不内置会更新的节假日表；这里保守地仍按普通工作日避开峰段。
 */
val DEEPSEEK_OFF_PEAK_WINDOWS = listOf(
    ExecutionWindow(0, 60, WEEKDAYS),
    ExecutionWindow(4 * 60, 6 * 60, WEEKDAYS),
    ExecutionWindow(10 * 60, 24 * 60, WEEKDAYS),
    ExecutionWindow(0, 24 * 60, WEEKEND),
)
const val DEEPSEEK_OFF_PEAK_ZONE = "UTC"

private fun localMinuteAt(date: LocalDate, minute: Int, zone: ZoneId): Long = when (minute) {
    0 -> date.atStartOfDay(zone).toInstant().toEpochMilli()
    24 * 60 -> date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    else -> date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
}

/** [atMs] 已在窗口内就原样返回，否则返回下一次开窗；开始日属于前一天时也计入跨午夜窗口。 */
fun nextExecutionWindowAt(windows: List<ExecutionWindow>, zone: String, atMs: Long): Long {
    require(windows.isNotEmpty() && windows.all(::isValidExecutionWindow))
    val zoneId = ZoneId.of(zone)
    val firstDate = Instant.ofEpochMilli(atMs).atZone(zoneId).toLocalDate().minusDays(1)
    var best: Long? = null
    repeat(11) { offset ->
        val openingDate = firstDate.plusDays(offset.toLong())
        for (window in windows) {
            if (window.days.isNotEmpty() && openingDate.dayOfWeek.value !in window.days) continue
            val opens = localMinuteAt(openingDate, window.startMinute, zoneId)
            val endDate = if (window.endMinute > window.startMinute) openingDate else openingDate.plusDays(1)
            val closes = localMinuteAt(endDate, window.endMinute, zoneId)
            if (opens >= closes) continue
            val candidate = when {
                atMs < opens -> opens
                atMs < closes -> atMs
                else -> null
            }
            if (candidate != null && (best == null || candidate < best!!)) best = candidate
        }
    }
    return checkNotNull(best) { "execution window has no future opening" }
}

fun isWithinExecutionWindow(windows: List<ExecutionWindow>, zone: String, atMs: Long): Boolean =
    windows.isNotEmpty() && nextExecutionWindowAt(windows, zone, atMs) == atMs

/** UI 与 schedule_task 共用的自定义 HH:mm 窗口；结束可写 24:00，起止相同会被拒绝。 */
fun customExecutionWindow(start: String, end: String): ExecutionWindow {
    fun minute(raw: String, allowEndOfDay: Boolean): Int {
        val text = raw.trim()
        if (allowEndOfDay && text == "24:00") return 24 * 60
        val clock = normalizedCalendarTimes(listOf(text)).single()
        return LocalTime.parse(clock).let { it.hour * 60 + it.minute }
    }
    val startMinute = minute(start, allowEndOfDay = false)
    val endMinute = minute(end, allowEndOfDay = true)
    require(startMinute != endMinute) { "execution window must have a positive duration" }
    return ExecutionWindow(startMinute, endMinute)
}

fun formatExecutionWindowMinute(minute: Int): String =
    if (minute == 24 * 60) "24:00" else "%02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
