package com.zzq.calendar.ui

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureTest {

    private val density = 2f
    private val width = 720f

    @Test
    fun leftDragIsNext() {
        assertEquals(SwipeDirection.NEXT, release(-260f, 0f, 0f))
    }

    @Test
    fun rightDragIsPrevious() {
        assertEquals(SwipeDirection.PREVIOUS, release(260f, 0f, 0f))
    }

    @Test
    fun shortSlowDragSnapsBack() {
        assertNull(release(-120f, 0f, 0f))
    }

    @Test
    fun fastFlickFlipsEvenWhenShort() {
        assertEquals(SwipeDirection.NEXT, release(-40f, 0f, -2000f))
        assertEquals(SwipeDirection.PREVIOUS, release(40f, 0f, 2000f))
    }

    @Test
    fun verticalDragIsIgnored() {
        assertNull(release(-500f, 600f, 0f))
    }

    @Test
    fun flipNeedsAShareOfTheWidth() {
        // 0.18 * 720 = 129.6dp: a 140dp drag flips, a 120dp drag does not.
        assertEquals(SwipeDirection.NEXT, release(-140f, 0f, 0f))
        assertNull(release(-120f, 0f, 0f))
    }

    @Test
    fun releaseAnimationFinishesWithinThreeHundredMilliseconds() {
        assertTrue(SwipeNavigator.ANIMATION_MS in 250L..300L)
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

    private fun release(dx: Float, dy: Float, velocityX: Float): SwipeDirection? =
        SwipeGesture.releaseDirection(dx * density, dy * density, velocityX * density, width * density, density)
}
