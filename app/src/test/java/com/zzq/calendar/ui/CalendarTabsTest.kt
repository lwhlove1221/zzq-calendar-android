package com.zzq.calendar.ui

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarTabsTest {

    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun monthAnchorIsTodayWhenShowingCurrentMonth() {
        assertEquals(today, CalendarTabs.monthAnchor(YearMonth.of(2026, 11), today))
    }

    @Test
    fun monthAnchorIsFirstOfMonthWhenShowingAnotherMonth() {
        assertEquals(LocalDate.of(2026, 10, 1), CalendarTabs.monthAnchor(YearMonth.of(2026, 10), today))
    }

    @Test
    fun yearAnchorIsTodayWhenShowingCurrentYear() {
        assertEquals(today, CalendarTabs.yearAnchor(2026, today))
    }

    @Test
    fun yearAnchorIsJanuaryWhenShowingAnotherYear() {
        assertEquals(LocalDate.of(2027, 1, 1), CalendarTabs.yearAnchor(2027, today))
    }
}
