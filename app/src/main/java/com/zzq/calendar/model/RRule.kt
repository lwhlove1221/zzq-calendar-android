package com.zzq.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ByDayRule(
    val ordinal: Int?,
    val day: DayOfWeek,
)

sealed interface UntilRule {
    data class Absolute(val instant: java.time.Instant) : UntilRule
    data class Local(val dateTime: LocalDateTime) : UntilRule
    data class DateOnly(val date: LocalDate) : UntilRule
}

data class RRule(
    val raw: String,
    val freq: Freq?,
    val interval: Int,
    val byDay: List<ByDayRule>,
    val byMonthDay: List<Int>,
    val count: Int?,
    val until: UntilRule?,
    val wkst: DayOfWeek,
    val unsupportedParts: Set<String>,
    val warnings: List<String>,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    val isRecurring: Boolean get() = freq != null
    val isReadOnly: Boolean get() = unsupportedParts.isNotEmpty()
    val isSupported: Boolean get() = unsupportedParts.isEmpty()

    companion object {
        private val UNSUPPORTED = setOf(
            "BYSETPOS",
            "BYMONTH",
            "BYYEARDAY",
            "BYWEEKNO",
            "BYHOUR",
            "BYMINUTE",
            "BYSECOND",
        )

        private val DAY_CODES = mapOf(
            "MO" to DayOfWeek.MONDAY,
            "TU" to DayOfWeek.TUESDAY,
            "WE" to DayOfWeek.WEDNESDAY,
            "TH" to DayOfWeek.THURSDAY,
            "FR" to DayOfWeek.FRIDAY,
            "SA" to DayOfWeek.SATURDAY,
            "SU" to DayOfWeek.SUNDAY,
        )

        fun parse(raw: String): RRule {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return RRule("", null, 1, emptyList(), emptyList(), null, null, DayOfWeek.MONDAY, emptySet(), emptyList())
            }

            val parts = linkedMapOf<String, String>()
            val unsupported = linkedSetOf<String>()
            val warnings = mutableListOf<String>()

            trimmed.removePrefix("RRULE:").split(';').forEach { segment ->
                if (segment.isBlank()) return@forEach
                val eq = segment.indexOf('=')
                if (eq <= 0) {
                    warnings += "无法解析规则片段: $segment"
                    return@forEach
                }
                val key = segment.substring(0, eq).trim().uppercase()
                val value = segment.substring(eq + 1).trim()
                if (key in UNSUPPORTED) {
                    unsupported += key
                }
                parts[key] = value
            }

            val freq = parts["FREQ"]?.let { value ->
                Freq.entries.firstOrNull { it.name == value.uppercase() } ?: run {
                    unsupported += "FREQ=$value"
                    null
                }
            }

            val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
            val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
            var until = parts["UNTIL"]?.let { parseUntil(it) }
            if (count != null && until != null) {
                warnings += "COUNT 与 UNTIL 互斥，本实现以 COUNT 为准，UNTIL 不参与展开"
                until = null
            }

            val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { token ->
                parseByDay(token.trim().uppercase())
            }.orEmpty()

            val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.mapNotNull { token ->
                token.trim().toIntOrNull()?.takeIf { it != 0 && it in -31..31 }
            }.orEmpty()

            val wkst = parts["WKST"]?.let { DAY_CODES[it.uppercase()] } ?: DayOfWeek.MONDAY

            return RRule(
                raw = trimmed,
                freq = freq,
                interval = interval,
                byDay = byDay,
                byMonthDay = byMonthDay,
                count = count,
                until = until,
                wkst = wkst,
                unsupportedParts = unsupported,
                warnings = warnings,
            )
        }

        private fun parseByDay(token: String): ByDayRule? {
            if (token.isEmpty()) return null
            val dayCode = token.takeLast(2)
            val day = DAY_CODES[dayCode] ?: return null
            val ordinalPart = token.dropLast(2)
            val ordinal = ordinalPart.toIntOrNull()
            return ByDayRule(ordinal, day)
        }

        private fun parseUntil(value: String): UntilRule? {
            val token = value.trim()
            return try {
                when {
                    token.endsWith("Z") && token.length >= 16 -> {
                        val parsed = LocalDateTime.parse(token.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        UntilRule.Absolute(parsed.toInstant(ZoneOffset.UTC))
                    }
                    token.length == 8 -> UntilRule.DateOnly(LocalDate.parse(token, DateTimeFormatter.BASIC_ISO_DATE))
                    else -> UntilRule.Local(LocalDateTime.parse(token, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")))
                }
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
