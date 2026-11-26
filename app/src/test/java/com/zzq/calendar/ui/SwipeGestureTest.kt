package com.zzq.calendar.ui

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureTest {

    private val threshold = SwipeGesture.THRESHOLD_DP

    @Test
    fun leftDragIsNext() {
        assertEquals(SwipeDirection.NEXT, SwipeGesture.direction(-threshold - 1f, 0f, threshold))
    }

    @Test
    fun rightDragIsPrevious() {
        assertEquals(SwipeDirection.PREVIOUS, SwipeGesture.direction(threshold + 1f, 0f, threshold))
    }

    @Test
    fun dragBelowThresholdIsIgnored() {
        assertNull(SwipeGesture.direction(-threshold + 1f, 0f, threshold))
    }

    @Test
    fun verticalDragIsIgnored() {
        assertNull(SwipeGesture.direction(-threshold * 3f, threshold * 4f, threshold))
    }

    @Test
    fun pageAnimationStaysUnderTwoHundredMilliseconds() {
        assertTrue(SwipeNavigator.ANIMATION_MS <= 200L)
    }

    @Test
    fun rangeStopsOnBothEnds() {
        assertFalse(CalendarRange.canShiftMonth(YearMonth.of(2100, 12), SwipeDirection.NEXT))
        assertFalse(CalendarRange.canShiftMonth(YearMonth.of(1900, 1), SwipeDirection.PREVIOUS))
        assertTrue(CalendarRange.canShiftMonth(YearMonth.of(2100, 11), SwipeDirection.NEXT))

        assertFalse(CalendarRange.canShiftDay(CalendarRange.MAX_DATE, SwipeDirection.NEXT))
        assertFalse(CalendarRange.canShiftDay(CalendarRange.MIN_DATE, SwipeDirection.PREVIOUS))

        assertFalse(CalendarRange.canShiftWeek(LocalDate.of(1899, 12, 25), SwipeDirection.PREVIOUS))
        assertFalse(CalendarRange.canShiftYear(2100, SwipeDirection.NEXT))
        assertFalse(CalendarRange.canShiftYear(1900, SwipeDirection.PREVIOUS))
    }
}
