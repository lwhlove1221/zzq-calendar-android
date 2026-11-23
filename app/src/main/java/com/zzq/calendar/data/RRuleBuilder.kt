package com.zzq.calendar.data

import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.WallTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

object RRuleBuilder {

    fun build(spec: RepeatSpec, dtstartWall: String): String {
        val freq = spec.frequency ?: return ""
        val parts = mutableListOf("FREQ=${freq.name}")
        if (spec.interval > 1) parts += "INTERVAL=${spec.interval}"
        when (freq) {
            RRule.Freq.WEEKLY -> {
                val day = WallTime.parse(dtstartWall).dayOfWeek
                parts += "BYDAY=${dayCode(day)}"
            }
            RRule.Freq.MONTHLY -> {
                parts += "BYMONTHDAY=${WallTime.parse(dtstartWall).dayOfMonth}"
            }
            else -> Unit
        }
        spec.count?.let { parts += "COUNT=$it" }
        spec.untilWall?.let { until ->
            parts += "UNTIL=${formatUntilUtc(until)}"
        }
        return parts.joinToString(";")
    }

    private fun formatUntilUtc(value: LocalDateTime): String =
        value.toInstant(ZoneOffset.UTC).atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

    private fun dayCode(day: java.time.DayOfWeek): String = when (day) {
        java.time.DayOfWeek.MONDAY -> "MO"
        java.time.DayOfWeek.TUESDAY -> "TU"
        java.time.DayOfWeek.WEDNESDAY -> "WE"
        java.time.DayOfWeek.THURSDAY -> "TH"
        java.time.DayOfWeek.FRIDAY -> "FR"
        java.time.DayOfWeek.SATURDAY -> "SA"
        java.time.DayOfWeek.SUNDAY -> "SU"
    }
}
