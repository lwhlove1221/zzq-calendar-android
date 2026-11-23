package com.zzq.calendar.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DateTimeException

object RRuleExpander {

    private const val MAX_PERIOD_STEPS = 200_000

    data class Result(
        val starts: List<LocalDateTime>,
        val truncated: Boolean,
        val unsupported: Boolean,
        val warnings: List<String> = emptyList(),
    )

    fun expand(
        master: EventMaster,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime,
        fallbackZone: ZoneId = ZoneId.systemDefault(),
        maxInstances: Int = 2_000,
    ): Result {
        val rule = RRule.parse(master.rruleRaw)
        val dtstart = WallTime.parse(master.dtstartWall)
        val dtend = WallTime.parse(master.dtendWall)
        val duration = if (dtend.isAfter(dtstart)) Duration.between(dtstart, dtend) else Duration.ZERO
        val earliestOverlap = windowStart.minus(duration)

        if (!rule.isRecurring) {
            val inWindow = !dtstart.isBefore(earliestOverlap) && dtstart.isBefore(windowEnd)
            return Result(
                starts = if (inWindow) listOf(dtstart) else emptyList(),
                truncated = false,
                unsupported = false,
                warnings = rule.warnings,
            )
        }

        val generated = mutableListOf<LocalDateTime>()
        var produced = 0
        var periodIndex = if (rule.count == null) initialPeriodIndex(rule, dtstart, earliestOverlap) else 0L
        var steps = 0
        var truncated = false

        while (true) {
            if (++steps > MAX_PERIOD_STEPS) {
                truncated = true
                break
            }

            val candidatesOrNull = candidatesForPeriod(rule, dtstart, periodIndex)
            if (candidatesOrNull == null) {
                periodIndex++
                continue
            }
            val candidates = candidatesOrNull

            if (candidates.isEmpty()) {
                if (periodExceeded(rule, dtstart, periodIndex, windowEnd)) break
                periodIndex++
                continue
            }

            var pastWindow = false
            for (candidate in candidates) {
                if (candidate.isBefore(dtstart)) continue
                if (isAfterUntil(rule, candidate, dtstart, master.tzid, fallbackZone)) {
                    pastWindow = true
                    break
                }

                produced++
                if (!candidate.isBefore(earliestOverlap) && candidate.isBefore(windowEnd)) {
                    generated += candidate
                    if (generated.size >= maxInstances) {
                        truncated = true
                        break
                    }
                }
                val limit = rule.count
                if (limit != null && produced >= limit) {
                    pastWindow = true
                    break
                }
            }

            if (truncated || pastWindow) break
            if (periodStartExceedsWindow(rule, dtstart, periodIndex, windowEnd)) break
            periodIndex++
        }

        val exdates = master.exdates.mapNotNull { normalizeExceptionDate(it, master.tzid, fallbackZone) }.toSet()
        val rdates = master.rdates.mapNotNull { normalizeExceptionDate(it, master.tzid, fallbackZone) }
            .filter { !it.isBefore(earliestOverlap) && it.isBefore(windowEnd) }

        val starts = (generated + rdates)
            .filterNot { exdates.contains(it) }
            .distinct()
            .sorted()

        return Result(
            starts = starts,
            truncated = truncated,
            unsupported = rule.isReadOnly,
            warnings = rule.warnings,
        )
    }

    private fun normalizeExceptionDate(raw: String, tzid: String, fallbackZone: ZoneId): LocalDateTime? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.length >= 16 && value.contains('T')) {
            return try {
                WallTime.parse(value)
            } catch (_: RuntimeException) {
                null
            }
        }
        return try {
            if (value.endsWith("Z")) {
                val parsed = LocalDateTime.parse(value.dropLast(1), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val zone = TimeZones.resolve(tzid) ?: fallbackZone
                parsed.toInstant(java.time.ZoneOffset.UTC).atZone(zone).toLocalDateTime()
            } else if (value.length == 8) {
                LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay()
            } else {
                LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun initialPeriodIndex(rule: RRule, dtstart: LocalDateTime, target: LocalDateTime): Long {
        val wanted = if (target.isAfter(dtstart)) target else dtstart
        val delta = when (rule.freq) {
            RRule.Freq.DAILY -> ChronoUnit.DAYS.between(dtstart.toLocalDate(), wanted.toLocalDate())
            RRule.Freq.WEEKLY -> ChronoUnit.WEEKS.between(
                weekStart(dtstart.toLocalDate(), rule.wkst),
                weekStart(wanted.toLocalDate(), rule.wkst),
            )
            RRule.Freq.MONTHLY -> ChronoUnit.MONTHS.between(
                dtstart.toLocalDate().withDayOfMonth(1),
                wanted.toLocalDate().withDayOfMonth(1),
            )
            RRule.Freq.YEARLY -> (wanted.year - dtstart.year).toLong()
            null -> 0L
        }
        val steps = delta / rule.interval
        return (if (steps > 1) steps - 1 else 0L)
    }

    private fun candidatesForPeriod(rule: RRule, dtstart: LocalDateTime, periodIndex: Long): List<LocalDateTime>? {
        val time: LocalTime = dtstart.toLocalTime()
        return when (rule.freq) {
            RRule.Freq.DAILY -> {
                val date = dtstart.toLocalDate().plusDays(rule.interval * periodIndex)
                if (matchesDayFilters(rule, date)) listOf(LocalDateTime.of(date, time)) else emptyList()
            }

            RRule.Freq.WEEKLY -> {
                val firstWeekStart = weekStart(dtstart.toLocalDate(), rule.wkst)
                val weekStart = firstWeekStart.plusWeeks(rule.interval * periodIndex)
                val days = if (rule.byDay.isEmpty()) {
                    listOf(dtstart.dayOfWeek)
                } else {
                    rule.byDay.map { it.day }.distinct().sortedBy { offsetFromWkst(it, rule.wkst) }
                }
                days.map { day ->
                    val shifted = weekStart.plusDays(offsetFromWkst(day, rule.wkst).toLong())
                    LocalDateTime.of(shifted, time)
                }.filter { !it.isBefore(dtstart) }.sorted()
            }

            RRule.Freq.MONTHLY -> {
                val month = YearMonth.from(dtstart.toLocalDate().withDayOfMonth(1))
                    .plusMonths(rule.interval * periodIndex)
                monthlyCandidates(rule, month, dtstart, time)
            }

            RRule.Freq.YEARLY -> {
                val year = dtstart.year + rule.interval * periodIndex
                val month = YearMonth.of(year.toInt(), dtstart.month)
                monthlyCandidates(rule, month, dtstart, time)
            }

            null -> null
        }
    }

    private fun monthlyCandidates(
        rule: RRule,
        month: YearMonth,
        dtstart: LocalDateTime,
        time: LocalTime,
    ): List<LocalDateTime> {
        val dates = mutableListOf<LocalDate>()
        when {
            rule.byMonthDay.isNotEmpty() -> {
                rule.byMonthDay.forEach { day ->
                    val resolved = resolveMonthDay(month, day)
                    if (resolved != null && (rule.byDay.isEmpty() || rule.byDay.any { it.day == resolved.dayOfWeek })) {
                        dates += resolved
                    }
                }
            }

            rule.byDay.isNotEmpty() -> {
                rule.byDay.forEach { byDay ->
                    if (byDay.ordinal == null) {
                        dates += allWeekdaysInMonth(month, byDay.day)
                    } else {
                        nthWeekdayInMonth(month, byDay.day, byDay.ordinal)?.let { dates += it }
                    }
                }
            }

            else -> {
                resolveMonthDay(month, dtstart.dayOfMonth)?.let { dates += it }
            }
        }
        return dates.distinct().sorted().map { LocalDateTime.of(it, time) }
    }

    private fun resolveMonthDay(month: YearMonth, day: Int): LocalDate? = try {
        if (day > 0) {
            if (day <= month.lengthOfMonth()) month.atDay(day) else null
        } else {
            val resolved = month.lengthOfMonth() + day + 1
            if (resolved >= 1) month.atDay(resolved) else null
        }
    } catch (_: DateTimeException) {
        null
    }

    private fun allWeekdaysInMonth(month: YearMonth, day: DayOfWeek): List<LocalDate> {
        val first = month.atDay(1).with(TemporalAdjusters.nextOrSame(day))
        val result = mutableListOf<LocalDate>()
        var cursor = first
        while (cursor.month == month.month && cursor.year == month.year) {
            result += cursor
            cursor = cursor.plusWeeks(1)
        }
        return result
    }

    private fun nthWeekdayInMonth(month: YearMonth, day: DayOfWeek, ordinal: Int): LocalDate? {
        if (ordinal > 0) {
            val first = month.atDay(1).with(TemporalAdjusters.nextOrSame(day))
            val result = first.plusWeeks((ordinal - 1).toLong())
            return if (result.month == month.month) result else null
        }
        val last = month.atEndOfMonth().with(TemporalAdjusters.previousOrSame(day))
        val result = last.plusWeeks((ordinal + 1).toLong())
        return if (result.month == month.month) result else null
    }

    private fun matchesDayFilters(rule: RRule, date: LocalDate): Boolean {
        if (rule.byDay.isNotEmpty() && rule.byDay.none { it.day == date.dayOfWeek }) return false
        if (rule.byMonthDay.isNotEmpty() && rule.byMonthDay.none { resolveMonthDay(YearMonth.from(date), it) == date }) return false
        return true
    }

    private fun weekStart(date: LocalDate, wkst: DayOfWeek): LocalDate {
        val current = date.dayOfWeek.value
        val start = wkst.value
        val diff = (current - start + 7) % 7
        return date.minusDays(diff.toLong())
    }

    private fun offsetFromWkst(day: DayOfWeek, wkst: DayOfWeek): Int =
        (day.value - wkst.value + 7) % 7

    private fun isAfterUntil(
        rule: RRule,
        candidate: LocalDateTime,
        dtstart: LocalDateTime,
        tzid: String,
        fallbackZone: ZoneId,
    ): Boolean = when (val until = rule.until) {
        null -> false
        is UntilRule.Absolute -> {
            val zone = TimeZones.resolve(tzid) ?: fallbackZone
            candidate.atZone(zone).toInstant().isAfter(until.instant)
        }
        is UntilRule.DateOnly -> candidate.toLocalDate().isAfter(until.date)
        is UntilRule.Local -> candidate.isAfter(until.dateTime)
    }

    private fun periodStartExceedsWindow(
        rule: RRule,
        dtstart: LocalDateTime,
        periodIndex: Long,
        windowEnd: LocalDateTime,
    ): Boolean {
        val periodStart: LocalDateTime = when (rule.freq) {
            RRule.Freq.DAILY -> LocalDateTime.of(
                dtstart.toLocalDate().plusDays(rule.interval * periodIndex + 1),
                LocalTime.MIN,
            )
            RRule.Freq.WEEKLY -> LocalDateTime.of(
                weekStart(dtstart.toLocalDate(), rule.wkst)
                    .plusWeeks(rule.interval * periodIndex + 1),
                LocalTime.MIN,
            )
            RRule.Freq.MONTHLY -> YearMonth.from(dtstart.toLocalDate())
                .plusMonths(rule.interval * periodIndex + 1)
                .atDay(1).atStartOfDay()
            RRule.Freq.YEARLY -> LocalDate.of(dtstart.year + (rule.interval * (periodIndex + 1)).toInt(), 1, 1).atStartOfDay()
            null -> return true
        }
        return periodStart.isAfter(windowEnd)
    }

    private fun periodExceeded(
        rule: RRule,
        dtstart: LocalDateTime,
        periodIndex: Long,
        windowEnd: LocalDateTime,
    ): Boolean = periodStartExceedsWindow(rule, dtstart, periodIndex, windowEnd)

    fun instancesOverlapping(
        master: EventMaster,
        dayStart: Instant,
        dayEnd: Instant,
        deviceZone: ZoneId,
        maxInstances: Int = 2_000,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val zone = TimeZones.resolve(master.tzid) ?: deviceZone
        val dtstart = WallTime.parse(master.dtstartWall)
        val dtend = WallTime.parse(master.dtendWall)
        val duration = if (dtend.isAfter(dtstart)) Duration.between(dtstart, dtend) else Duration.ZERO
        val windowStart = dayStart.minus(duration).atZone(zone).toLocalDateTime()
        val windowEnd = dayEnd.plusSeconds(1).atZone(zone).toLocalDateTime()
        val result = expand(master, windowStart, windowEnd, deviceZone, maxInstances)
        return result.starts.map { start -> start to start.plus(duration) }
    }
}
