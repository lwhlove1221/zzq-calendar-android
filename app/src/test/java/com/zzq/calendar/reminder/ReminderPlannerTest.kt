package com.zzq.calendar.reminder

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.Valarm
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {

    private val ny = ZoneId.of("America/New_York")

    @Test
    fun `duration offset fires before the occurrence`() {
        val event = timed("2026-03-02T10:00:00", valarms = listOf(Valarm("duration", -300, NY)))
        val slots = plan(event, "2026-03-02T00:00:00Z")

        assertEquals(1, slots.size)
        assertEquals(Instant.parse("2026-03-02T14:55:00Z"), Instant.ofEpochMilli(slots[0].fireAt))
    }

    @Test
    fun `multiple alarms produce one slot each`() {
        val event = timed(
            "2026-03-02T10:00:00",
            valarms = listOf(Valarm("duration", -300, NY), Valarm("duration", -3600, NY)),
        )
        val slots = plan(event, "2026-03-02T00:00:00Z")

        assertEquals(2, slots.size)
        assertEquals(setOf(-300, -3600), slots.map { it.offsetSeconds }.toSet())
        assertEquals(2, slots.map { it.key }.toSet().size)
    }

    @Test
    fun `all-day calendar offset lands on the event wall clock`() {
        val event = allDay("2026-03-02", listOf(Valarm("calendar", 32_400, NY)))
        val slots = plan(event, "2026-03-02T00:00:00Z")

        assertEquals(1, slots.size)
        // 09:00 New York on 2026-03-02 is EST (-05:00).
        assertEquals(Instant.parse("2026-03-02T14:00:00Z"), Instant.ofEpochMilli(slots[0].fireAt))
    }

    @Test
    fun `spring forward gap shifts to the first valid minute`() {
        val event = allDay("2026-03-08", listOf(Valarm("calendar", 9_000, NY)))
        val slots = plan(event, "2026-03-08T00:00:00Z")

        // 02:30 does not exist; 03:30 EDT (UTC-4).
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), Instant.ofEpochMilli(slots[0].fireAt))
    }

    @Test
    fun `fall back repeated hour picks the first occurrence`() {
        val event = allDay("2026-11-01", listOf(Valarm("calendar", 5_400, NY)))
        val slots = plan(event, "2026-11-01T00:00:00Z")

        // 01:30 happens twice; take the DST side (-04:00).
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), Instant.ofEpochMilli(slots[0].fireAt))
    }

    @Test
    fun `one day before keeps the wall clock across DST`() {
        // 2026-03-08 is the spring-forward day, so it is only 23 hours long.
        val event = timed("2026-03-08T09:00:00", valarms = listOf(Valarm("calendar", -86_400, NY)))
        val slots = plan(event, "2026-03-07T00:00:00Z")

        val start = Instant.parse("2026-03-08T13:00:00Z")
        assertEquals(Duration.ofHours(23).toMillis(), start.toEpochMilli() - slots[0].fireAt)
    }

    @Test
    fun `repeating event expands into one slot per occurrence`() {
        val event = timed(
            "2026-03-02T10:00:00",
            valarms = listOf(Valarm("duration", -600, NY)),
            rrule = "FREQ=DAILY;COUNT=3",
        )
        val slots = plan(event, "2026-03-02T00:00:00Z")

        assertEquals(3, slots.size)
        assertEquals(3, slots.map { it.occurrenceKey }.toSet().size)
        assertTrue(slots.zipWithNext().all { (a, b) -> a.fireAt < b.fireAt })
    }

    @Test
    fun `cancelled occurrence is not scheduled`() {
        val event = timed(
            "2026-03-02T10:00:00",
            valarms = listOf(Valarm("duration", 0, NY)),
            rrule = "FREQ=DAILY;COUNT=2",
            exceptions = listOf(
                EventException(
                    id = "x1",
                    eventId = "e1",
                    recurrenceIdWall = "2026-03-03T10:00:00",
                    tzid = NY,
                    status = ExceptionStatus.CANCELLED,
                ),
            ),
        )
        val slots = plan(event, "2026-03-02T00:00:00Z")

        assertEquals(listOf("2026-03-02T10:00:00"), slots.map { it.occurrenceKey })
    }

    @Test
    fun `mirror events never schedule local reminders`() {
        val source = timed("2026-03-02T10:00:00", valarms = listOf(Valarm("duration", 0, NY)))
        val event = source.copy(master = source.master.copy(owner = EventMaster.OWNER_SYSTEM))

        assertTrue(plan(event, "2026-03-02T00:00:00Z").isEmpty())
    }

    @Test
    fun `alarms already in the past are skipped`() {
        val event = timed("2026-03-02T10:00:00", valarms = listOf(Valarm("duration", 0, NY)))

        assertTrue(plan(event, "2026-03-03T00:00:00Z").isEmpty())
    }

    @Test
    fun `schedule key follows the PRD tuple`() {
        assertEquals(
            "e1|2026-03-02T10:00:00|-300|2024a",
            ReminderPlanner.scheduleKey("e1", "2026-03-02T10:00:00", -300, "2024a"),
        )
    }

    private fun plan(event: StoredEvent, now: String) =
        ReminderPlanner.plan(listOf(event), Instant.parse(now).toEpochMilli(), ny)

    private fun timed(
        startWall: String,
        valarms: List<Valarm>,
        rrule: String = "",
        exceptions: List<EventException> = emptyList(),
    ): StoredEvent {
        val start = java.time.LocalDateTime.parse(startWall)
        return StoredEvent(
            master = EventMaster(
                id = "e1",
                uid = "e1@test",
                calendarId = "cal-local",
                title = "standup",
                allDay = false,
                dtstartWall = startWall,
                dtendWall = start.plusHours(1).format(com.zzq.calendar.model.WallTime.WALL_FORMATTER),
                tzid = NY,
                rruleRaw = rrule,
                valarms = valarms,
            ),
            exceptions = exceptions,
            fingerprint = "",
        )
    }

    private fun allDay(date: String, valarms: List<Valarm>): StoredEvent = StoredEvent(
        master = EventMaster(
            id = "e1",
            uid = "e1@test",
            calendarId = "cal-local",
            title = "holiday",
            allDay = true,
            dtstartWall = "${date}T00:00:00",
            dtendWall = "${date}T00:00:00",
            tzid = NY,
            valarms = valarms,
        ),
        exceptions = emptyList(),
        fingerprint = "",
    )

    private companion object {
        const val NY = "America/New_York"
    }
}
