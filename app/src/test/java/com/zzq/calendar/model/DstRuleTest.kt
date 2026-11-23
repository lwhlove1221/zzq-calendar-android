package com.zzq.calendar.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DstRuleTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun weeklyEvent(startWall: String, tzid: String = "America/New_York") = EventMaster(
        id = "dst",
        uid = "dst-uid",
        calendarId = "cal-work",
        title = "09:00 站会",
        allDay = false,
        dtstartWall = startWall,
        dtendWall = startWall.replace("09:00:00", "10:00:00"),
        tzid = tzid,
        rruleRaw = "FREQ=WEEKLY;BYDAY=MO",
    )

    @Test
    fun springForward_keepsLocal0900_andAbsoluteHourShifts() {
        val result = RRuleExpander.expand(
            weeklyEvent("2027-03-01T09:00:00"),
            WallTime.parse("2027-03-01T00:00:00"),
            WallTime.parse("2027-03-23T00:00:00"),
            newYork,
        )
        assertEquals(
            listOf(
                "2027-03-01T09:00:00",
                "2027-03-08T09:00:00",
                "2027-03-15T09:00:00",
                "2027-03-22T09:00:00",
            ),
            result.starts.map { WallTime.format(it) },
        )
        assertEquals(
            listOf(
                Instant.parse("2027-03-01T14:00:00Z"),
                Instant.parse("2027-03-08T14:00:00Z"),
                Instant.parse("2027-03-15T13:00:00Z"),
                Instant.parse("2027-03-22T13:00:00Z"),
            ),
            result.starts.map { TimeZones.toInstant(WallTime.format(it), "America/New_York", newYork) },
        )
    }

    @Test
    fun fallBack_keepsLocal0900_andAbsoluteHourShiftsBack() {
        val result = RRuleExpander.expand(
            weeklyEvent("2027-10-25T09:00:00"),
            WallTime.parse("2027-10-25T00:00:00"),
            WallTime.parse("2027-11-16T00:00:00"),
            newYork,
        )
        assertEquals(
            listOf(
                "2027-10-25T09:00:00",
                "2027-11-01T09:00:00",
                "2027-11-08T09:00:00",
                "2027-11-15T09:00:00",
            ),
            result.starts.map { WallTime.format(it) },
        )
        assertEquals(
            listOf(
                Instant.parse("2027-10-25T13:00:00Z"),
                Instant.parse("2027-11-01T13:00:00Z"),
                Instant.parse("2027-11-08T14:00:00Z"),
                Instant.parse("2027-11-15T14:00:00Z"),
            ),
            result.starts.map { TimeZones.toInstant(WallTime.format(it), "America/New_York", newYork) },
        )
    }

    @Test
    fun nonexistentSpringTimeMovesToFirstValidMinuteAfterGap() {
        val instant = TimeZones.toInstant("2027-03-14T02:30:00", "America/New_York", newYork)
        assertEquals(Instant.parse("2027-03-14T07:30:00Z"), instant)
        assertEquals(
            "2027-03-14T03:30",
            java.time.LocalDateTime.ofInstant(instant, newYork).toString(),
        )
    }

    @Test
    fun ambiguousFallTimeResolvesToFirstOccurrence() {
        val instant = TimeZones.toInstant("2027-11-07T01:30:00", "America/New_York", newYork)
        assertEquals(Instant.parse("2027-11-07T05:30:00Z"), instant)
    }

    @Test
    fun shrunkRuleWindowKeepsWallClockInstances() {
        val result = RRuleExpander.expand(
            weeklyEvent("2027-03-01T09:00:00"),
            WallTime.parse("2027-03-15T00:00:00"),
            WallTime.parse("2027-03-16T00:00:00"),
            newYork,
        )
        assertEquals(listOf("2027-03-15T09:00:00"), result.starts.map { WallTime.format(it) })
    }
}
