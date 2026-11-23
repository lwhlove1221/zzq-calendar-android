package com.zzq.calendar.ui

import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.InstanceKey
import com.zzq.calendar.model.InstanceType
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun allDayInstance(startDate: String, endExclusive: String): EventInstance {
        val master = EventMaster(
            id = "e",
            uid = "u",
            calendarId = "cal-local",
            title = "出差",
            allDay = true,
            dtstartWall = "${startDate}T00:00:00",
            dtendWall = "${endExclusive}T00:00:00",
            tzid = zone.id,
        )
        return EventInstance(
            master = master,
            key = InstanceKey(master.uid, master.dtstartWall, master.tzid),
            type = InstanceType.NORMAL,
            title = master.title,
            allDay = true,
            startWall = master.dtstartWall,
            endWall = master.dtendWall,
            start = WallTime.parse(master.dtstartWall).atZone(zone).toInstant(),
            end = WallTime.parse(master.dtendWall).atZone(zone).toInstant(),
            endDateExclusive = LocalDate.parse(endExclusive),
            displayTzid = master.tzid,
        )
    }

    @Test
    fun singleDayAllDayEventUsesAllDayLabel() {
        assertEquals("全天", UiFormat.allDayRange(allDayInstance("2026-11-23", "2026-11-24")))
        assertTrue(UiFormat.isSingleDayAllDay(allDayInstance("2026-11-23", "2026-11-24")))
    }

    @Test
    fun multiDayAllDayEventShowsInclusiveLastDay() {
        assertEquals("11月22日 – 11月23日", UiFormat.allDayRange(allDayInstance("2026-11-22", "2026-11-24")))
        assertFalse(UiFormat.isSingleDayAllDay(allDayInstance("2026-11-22", "2026-11-24")))
    }

    @Test
    fun allDayRangeFallsBackToEndWallWhenExclusiveDateMissing() {
        val instance = allDayInstance("2026-11-22", "2026-11-24").copy(endDateExclusive = null)
        assertEquals("11月22日 – 11月23日", UiFormat.allDayRange(instance))
    }

    @Test
    fun timedRangeUsesDeviceZone() {
        val master = EventMaster(
            id = "e2",
            uid = "u2",
            calendarId = "cal-local",
            title = "站会",
            allDay = false,
            dtstartWall = "2026-11-23T09:00:00",
            dtendWall = "2026-11-23T10:00:00",
            tzid = zone.id,
        )
        val instance = EventInstance(
            master = master,
            key = InstanceKey(master.uid, master.dtstartWall, master.tzid),
            type = InstanceType.NORMAL,
            title = master.title,
            allDay = false,
            startWall = master.dtstartWall,
            endWall = master.dtendWall,
            start = WallTime.parse(master.dtstartWall).atZone(zone).toInstant(),
            end = WallTime.parse(master.dtendWall).atZone(zone).toInstant(),
            displayTzid = master.tzid,
        )
        assertEquals("09:00 – 10:00", UiFormat.timeRange(instance, zone))
    }
}
