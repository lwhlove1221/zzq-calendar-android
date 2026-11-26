package com.zzq.calendar.ui

import java.time.LocalDate
import java.time.YearMonth

/** Supported calendar span, matching the platform date picker range. */
object CalendarRange {

    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2100, 12, 31)
    private val MIN_MONTH: YearMonth = YearMonth.from(MIN_DATE)
    private val MAX_MONTH: YearMonth = YearMonth.from(MAX_DATE)

    fun deltaDays(direction: SwipeDirection): Long =
        if (direction == SwipeDirection.NEXT) 1L else -1L

    fun canShiftDay(day: LocalDate, direction: SwipeDirection): Boolean =
        within(day.plusDays(deltaDays(direction)))

    fun canShiftWeek(weekStart: LocalDate, direction: SwipeDirection): Boolean =
        within(weekStart.plusWeeks(deltaDays(direction)))

    fun canShiftMonth(month: YearMonth, direction: SwipeDirection): Boolean {
        val target = month.plusMonths(deltaDays(direction))
        return target >= MIN_MONTH && target <= MAX_MONTH
    }

    fun canShiftYear(year: Int, direction: SwipeDirection): Boolean {
        val target = year + deltaDays(direction).toInt()
        return target in MIN_DATE.year..MAX_DATE.year
    }

    private fun within(date: LocalDate): Boolean =
        !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE)
}
