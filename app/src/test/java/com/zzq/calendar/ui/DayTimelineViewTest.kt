package com.zzq.calendar.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.InstanceKey
import com.zzq.calendar.model.InstanceType
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class DayTimelineViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun instance(start: String, end: String): EventInstance {
        val master = EventMaster(
            id = "e",
            uid = "u",
            calendarId = "cal-local",
            title = "站会",
            allDay = false,
            dtstartWall = start,
            dtendWall = end,
            tzid = zone.id,
        )
        return EventInstance(
            master = master,
            key = InstanceKey(master.uid, start, master.tzid),
            type = InstanceType.NORMAL,
            title = master.title,
            allDay = false,
            startWall = start,
            endWall = end,
            start = WallTime.parse(start).atZone(zone).toInstant(),
            end = WallTime.parse(end).atZone(zone).toInstant(),
            displayTzid = master.tzid,
        )
    }

    private fun layout(view: View, widthDp: Int = 360): View {
        val density = context.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    @Test
    fun shortEventKeepsTwentyDpBarAndFortyFourDpTouchTarget() {
        val density = context.resources.displayMetrics.density
        val view = DayTimelineView(context)
        view.submit(
            listOf(instance("2026-11-23T09:00:00", "2026-11-23T09:15:00")),
            LocalDate.of(2026, 11, 23),
            zone,
        )
        layout(view)
        val visual = view.visualBarsForTest().single()
        val hit = view.hitTargetsForTest().single()
        assertTrue("visual height ${visual.height()}", visual.height() >= 20 * density - 0.5f)
        assertTrue("hit height ${hit.height()}", hit.height() >= 44 * density - 0.5f)
        assertTrue(hit.contains(visual.centerX(), visual.top - 10 * density))
    }

    @Test
    fun overlappingEventsAreSplitIntoSideBySideColumns() {
        val view = DayTimelineView(context)
        view.submit(
            listOf(
                instance("2026-11-23T09:00:00", "2026-11-23T10:00:00"),
                instance("2026-11-23T09:30:00", "2026-11-23T10:30:00"),
            ),
            LocalDate.of(2026, 11, 23),
            zone,
        )
        layout(view)
        val bars = view.visualBarsForTest()
        assertEquals(2, bars.size)
        assertTrue(bars[0].right <= bars[1].left + 0.5f || bars[1].right <= bars[0].left + 0.5f)
    }

    @Test
    fun tappingExpandedHitTargetOpensEvent() {
        val density = context.resources.displayMetrics.density
        val view = DayTimelineView(context)
        var opened: EventInstance? = null
        view.onEventClick = { opened = it }
        view.submit(
            listOf(instance("2026-11-23T09:00:00", "2026-11-23T09:15:00")),
            LocalDate.of(2026, 11, 23),
            zone,
        )
        layout(view)
        val hit = view.hitTargetsForTest().single()
        val x = hit.centerX()
        val y = hit.top + 2 * density
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(0, 16, android.view.MotionEvent.ACTION_UP, x, y, 0)
        view.onTouchEvent(down)
        view.onTouchEvent(up)
        down.recycle()
        up.recycle()
        assertTrue(opened != null)
    }

    @Test
    fun timelineHeightCoversTwentyFourHoursAtFortyEightDp() {
        val density = context.resources.displayMetrics.density
        val view = DayTimelineView(context)
        layout(view)
        assertEquals((24 * 48 * density).toInt(), view.measuredHeight)
    }

    @Test
    fun fontScaleTwoDoesNotChangeDpTouchTargets() {
        RuntimeEnvironment.setFontScale(2f)
        val density = context.resources.displayMetrics.density
        val view = DayTimelineView(context)
        view.submit(
            listOf(instance("2026-11-23T09:00:00", "2026-11-23T09:15:00")),
            LocalDate.of(2026, 11, 23),
            zone,
        )
        layout(view)
        val hit = view.hitTargetsForTest().single()
        assertTrue(hit.height() >= 44 * density - 0.5f)
        RuntimeEnvironment.setFontScale(1f)
    }
}
