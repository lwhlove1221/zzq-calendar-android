package com.zzq.calendar.ui

import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object UiFormat {

    private val dateTitleFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun dateTitle(day: LocalDate): String = day.format(dateTitleFormatter)

    fun shortDate(day: LocalDate): String = day.format(shortDateFormatter)

    fun timeRange(instance: EventInstance, zone: ZoneId): String {
        val start = LocalDateTime.ofInstant(instance.start, zone).format(timeFormatter)
        val end = LocalDateTime.ofInstant(instance.end, zone).format(timeFormatter)
        return "$start – $end"
    }

    fun allDayRange(instance: EventInstance): String {
        val endExclusive = instance.endDateExclusive
            ?: WallTime.parse(instance.endWall).toLocalDate()
        val lastDay = endExclusive.minusDays(1)
        val startDate = WallTime.parse(instance.startWall).toLocalDate()
        return if (startDate == lastDay) "全天" else "${shortDate(startDate)} – ${shortDate(lastDay)}"
    }

    fun isSingleDayAllDay(instance: EventInstance): Boolean {
        val endExclusive = instance.endDateExclusive
            ?: WallTime.parse(instance.endWall).toLocalDate()
        return endExclusive.minusDays(1) == WallTime.parse(instance.startWall).toLocalDate()
    }
}
