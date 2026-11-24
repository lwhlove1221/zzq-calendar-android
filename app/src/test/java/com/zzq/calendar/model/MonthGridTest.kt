package com.zzq.calendar.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthGridTest {

    @Test
    fun `grid is forty two cells starting on monday`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 11))

        assertEquals(MonthGrid.SIZE, cells.size)
        assertEquals(DayOfWeek.MONDAY, cells.first().date.dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 26), cells.first().date)
        assertEquals(30, cells.count { it.inMonth })
        assertEquals(MonthGrid.ROWS, cells.count { it.date.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test
    fun `first of month on monday needs no leading days`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 6))

        assertEquals(LocalDate.of(2026, 6, 1), cells.first().date)
        assertTrue(cells.first().inMonth)
        assertEquals(30, cells.count { it.inMonth })
    }

    @Test
    fun `leap february keeps the 29th`() {
        val cells = MonthGrid.cells(YearMonth.of(2028, 2))

        assertEquals(29, cells.count { it.inMonth })
        assertTrue(cells.any { it.inMonth && it.date.dayOfMonth == 29 })
        assertFalse(cells.any { it.inMonth && it.date.dayOfMonth == 30 })
    }

    @Test
    fun `adjacent month days are marked out of month`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 11))

        assertFalse(cells.first().inMonth)
        assertTrue(cells.any { !it.inMonth && it.date.monthValue == 12 })
    }
}
