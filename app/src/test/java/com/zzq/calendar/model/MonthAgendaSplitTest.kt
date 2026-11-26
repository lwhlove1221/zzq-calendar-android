package com.zzq.calendar.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthAgendaSplitTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `timed events split on end time and keep start order`() {
        val now = Instant.parse("2026-11-26T04:00:00Z")
        val early = timed("早会", "2026-11-25T01:00:00Z", "2026-11-25T02:00:00Z")
        val later = timed("午会", "2026-11-26T01:00:00Z", "2026-11-26T02:00:00Z")
        val future = timed("晚会", "2026-11-26T23:00:00Z", "2026-11-27T00:00:00Z")

        val groups = MonthAgendaSplit.split(listOf(later, future, early), now, zone)

        assertEquals(listOf(early, later), groups.completed)
        assertEquals(listOf(future), groups.remaining)
    }

    @Test
    fun `all day event stays remaining until 23 59 on its last day`() {
        val lastDay = LocalDate.of(2026, 11, 26)
        val allDay = allDay(lastDay)
        val beforeCutoff = Instant.parse("2026-11-26T15:59:00Z")
        val afterCutoff = Instant.parse("2026-11-26T15:59:30Z")

        assertEquals(1, MonthAgendaSplit.split(listOf(allDay), beforeCutoff, zone).remaining.size)
        assertEquals(1, MonthAgendaSplit.split(listOf(allDay), afterCutoff, zone).completed.size)
    }

    @Test
    fun `completed events are sorted by start ascending`() {
        val now = Instant.parse("2026-12-01T00:00:00Z")
        val second = timed("第二", "2026-11-10T09:00:00Z", "2026-11-10T10:00:00Z")
        val first = timed("第一", "2026-11-01T09:00:00Z", "2026-11-01T10:00:00Z")

        val groups = MonthAgendaSplit.split(listOf(second, first), now, zone)

        assertEquals(listOf(first, second), groups.completed)
        assertEquals(emptyList<EventInstance>(), groups.remaining)
    }

    private fun timed(title: String, start: String, end: String): EventInstance {
        val startInstant = Instant.parse(start)
        val endInstant = Instant.parse(end)
        return EventInstance(
            master = master(title, allDay = false),
            key = InstanceKey("uid-$title", start, zone.id),
            type = InstanceType.NORMAL,
            title = title,
            allDay = false,
            startWall = start,
            endWall = end,
            start = startInstant,
            end = endInstant,
            displayTzid = zone.id,
        )
    }

    private fun allDay(day: LocalDate): EventInstance = EventInstance(
        master = master("全天", allDay = true),
        key = InstanceKey("uid-all-day", "$day", zone.id),
        type = InstanceType.NORMAL,
        title = "全天",
        allDay = true,
        startWall = "$day",
        endWall = "$day",
        start = day.atStartOfDay(zone).toInstant(),
        end = day.plusDays(1).atStartOfDay(zone).toInstant(),
        endDateExclusive = day.plusDays(1),
        displayTzid = zone.id,
    )

    private fun master(title: String, allDay: Boolean): EventMaster = EventMaster(
        id = "id-$title",
        uid = "uid-$title",
        calendarId = "cal-local",
        title = title,
        allDay = allDay,
        dtstartWall = "2026-11-01T09:00:00",
        dtendWall = "2026-11-01T10:00:00",
        tzid = zone.id,
    )
}
