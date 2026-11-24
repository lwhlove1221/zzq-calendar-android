package com.zzq.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

data class MonthCell(val date: LocalDate, val inMonth: Boolean)

/** 6×7 month grid, Monday first (PRD-06 default). */
object MonthGrid {

    const val COLUMNS = 7
    const val ROWS = 6
    const val SIZE = COLUMNS * ROWS

    val weekdayLabels: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

    fun cells(month: YearMonth): List<MonthCell> {
        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until SIZE).map { offset ->
            val date = first.plusDays(offset.toLong())
            MonthCell(date, YearMonth.from(date) == month)
        }
    }
}
