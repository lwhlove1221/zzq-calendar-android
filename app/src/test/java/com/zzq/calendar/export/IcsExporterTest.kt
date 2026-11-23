package com.zzq.calendar.export

import biweekly.Biweekly
import biweekly.component.VAlarm
import biweekly.util.DateTimeComponents
import biweekly.util.ICalDate
import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.Valarm
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsExporterTest {

    private val now: Instant = Instant.parse("2026-11-23T07:00:00Z")

    @Test
    fun `twenty baseline events round-trip through biweekly`() {
        val stored = (1..20).map { index ->
            when (index % 4) {
                0 -> stored(allDay(index))
                1 -> stored(timed(index, tzid = "Asia/Shanghai"))
                2 -> stored(
                    timed(index, tzid = "America/New_York").copy(
                        rruleRaw = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
                        exdates = listOf("2026-11-30T09:00:00", "2026-12-07T09:00:00"),
                        rdates = listOf("2026-12-21T09:00:00"),
                        valarms = listOf(Valarm("duration", -600, "America/New_York")),
                    ),
                )
                else -> stored(
                    timed(index, tzid = "Europe/Berlin", minute = 45).copy(
                        description = "备注 $index：含逗号, 分号; 与换行\n第二行",
                        location = "会议室 $index",
                        url = "https://example.com/$index",
                    ),
                )
            }
        }

        val text = IcsExporter.export(stored, now)
        val events = Biweekly.parse(text).first().events

        assertEquals(20, events.size)
        stored.forEach { item ->
            val master = item.master
            val parsed = events.first { it.uid.value == master.uid }
            assertEquals(master.title, parsed.summary.value)
            assertEquals(master.description, plain(parsed.description?.value ?: ""))
            assertEquals(master.location, parsed.location?.value ?: "")
            assertEquals(master.url, parsed.url?.value?.toString() ?: "")
            if (master.allDay) {
                assertNull(parsed.dateStart.getParameter("TZID"))
                assertFalse(parsed.dateStart.value.rawComponents.hasTime())
                assertEquals(LocalDate.parse(master.dtstartWall.take(10)), rawDate(parsed.dateStart.value))
                assertEquals(LocalDate.parse(master.dtendWall.take(10)), rawDate(parsed.dateEnd.value))
            } else {
                assertTrue(text.contains("DTSTART;TZID=${master.tzid}:${compact(master.dtstartWall)}\r\n"))
                assertTrue(text.contains("DTEND;TZID=${master.tzid}:${compact(master.dtendWall)}\r\n"))
                assertEquals(LocalDateTime.parse(master.dtstartWall), rawDateTime(parsed.dateStart.value))
                assertEquals(LocalDateTime.parse(master.dtendWall), rawDateTime(parsed.dateEnd.value))
            }
            if (master.rruleRaw.isBlank()) {
                assertNull(parsed.recurrenceRule)
            } else {
                assertNotNull(parsed.recurrenceRule)
                assertTrue(text.contains("RRULE:${master.rruleRaw}\r\n"))
                assertEquals("WEEKLY", parsed.recurrenceRule.value.frequency.name)
            }
            val exdates: List<ICalDate> = parsed.getExceptionDates().flatMap { property ->
                property.getValues().map { value -> value as ICalDate }
            }
            assertEquals(master.exdates.size, exdates.size)
            master.exdates.indices.forEach { index ->
                assertEquals(LocalDateTime.parse(master.exdates[index]), rawDateTime(exdates[index]))
            }
            val rdates: List<ICalDate> = parsed.getRecurrenceDates().flatMap { property ->
                property.getDates()
            }
            assertEquals(master.rdates.size, rdates.size)
            master.rdates.indices.forEach { index ->
                assertEquals(LocalDateTime.parse(master.rdates[index]), rawDateTime(rdates[index]))
            }
            assertEquals(master.valarms.size, parsed.getComponents(VAlarm::class.java).size)
        }
    }

    @Test
    fun `all-day events carry VALUE=DATE without TZID and do not shift`() {
        val stored = stored(
            EventMaster(
                id = "allday",
                uid = "allday@zzq.calendar",
                calendarId = "cal-local",
                title = "全天会议",
                allDay = true,
                dtstartWall = "2026-11-25T00:00:00",
                dtendWall = "2026-11-26T00:00:00",
                tzid = "Asia/Shanghai",
            ),
        )

        val text = IcsExporter.export(listOf(stored), now)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261125\r\n"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261126\r\n"))
        assertTrue(text.lines().none { it.startsWith("DTSTART") && it.contains("TZID=") })
        assertTrue(text.lines().none { it.startsWith("DTEND") && it.contains("TZID=") })

        val parsed = Biweekly.parse(text).first().events.single()
        assertEquals(LocalDate.of(2026, 11, 25), rawDate(parsed.dateStart.value))
        assertEquals(LocalDate.of(2026, 11, 26), rawDate(parsed.dateEnd.value))
        assertNull(parsed.dateStart.getParameter("TZID"))
    }

    @Test
    fun `recurring event with exdate and exceptions keeps RECURRENCE-ID at original start`() {
        val master = EventMaster(
            id = "series",
            uid = "series@zzq.calendar",
            calendarId = "cal-local",
            title = "周会",
            allDay = false,
            dtstartWall = "2026-11-02T09:00:00",
            dtendWall = "2026-11-02T10:00:00",
            tzid = "Asia/Shanghai",
            rruleRaw = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            exdates = listOf("2026-11-16T09:00:00"),
        )
        val exceptions = listOf(
            EventException(
                id = "e1",
                eventId = "series",
                recurrenceIdWall = "2026-11-23T09:00:00",
                tzid = "Asia/Shanghai",
                status = ExceptionStatus.MODIFIED,
                title = "周会（改）",
                dtstartWall = "2026-11-23T14:00:00",
                dtendWall = "2026-11-23T15:00:00",
            ),
            EventException(
                id = "e2",
                eventId = "series",
                recurrenceIdWall = "2026-11-30T09:00:00",
                tzid = "Asia/Shanghai",
                status = ExceptionStatus.CANCELLED,
            ),
        )

        val text = IcsExporter.export(listOf(stored(master, exceptions)), now)
        val events = Biweekly.parse(text).first().events
        assertEquals(3, events.size)
        assertTrue(events.all { it.uid.value == master.uid })

        val modified = events.first {
            it.recurrenceId != null && rawDateTime(it.recurrenceId.value) == LocalDateTime.parse("2026-11-23T09:00:00")
        }
        assertEquals("周会（改）", modified.summary.value)
        assertEquals(LocalDateTime.parse("2026-11-23T14:00:00"), rawDateTime(modified.dateStart.value))
        assertTrue(text.contains("RECURRENCE-ID;TZID=Asia/Shanghai:20261123T090000\r\n"))

        val cancelled = events.first {
            it.recurrenceId != null && rawDateTime(it.recurrenceId.value) == LocalDateTime.parse("2026-11-30T09:00:00")
        }
        assertEquals("CANCELLED", cancelled.status.value)
        assertEquals(LocalDateTime.parse("2026-11-30T09:00:00"), rawDateTime(cancelled.recurrenceId.value))
    }

    @Test
    fun `output uses CRLF, 75-octet folding and keeps UTF-8 intact`() {
        val longTitle = "超长标题".repeat(40)
        val stored = stored(timed(1, tzid = "Asia/Shanghai").copy(title = longTitle, description = "很长的备注".repeat(30)))

        val text = IcsExporter.export(listOf(stored), now)
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(text))
        text.split("\r\n").filter { it.isNotEmpty() }.forEachIndexed { index, line ->
            assertTrue("line $index exceeds 75 octets", line.toByteArray(Charsets.UTF_8).size <= 75)
        }

        val parsed = Biweekly.parse(text).first().events.single()
        assertEquals(longTitle, parsed.summary.value)
        assertEquals(stored.master.description, plain(parsed.description.value))
    }

    private fun stored(master: EventMaster, exceptions: List<EventException> = emptyList()) = StoredEvent(
        master = master,
        exceptions = exceptions,
        fingerprint = FingerprintV1.sha256(master, exceptions),
    )

    private fun timed(index: Int, tzid: String, minute: Int = 0): EventMaster {
        val day = LocalDate.of(2026, 11, 10).plusDays(index.toLong())
        val minuteText = minute.toString().padStart(2, '0')
        return EventMaster(
            id = "e$index-$tzid",
            uid = "e$index@zzq.calendar",
            calendarId = "cal-local",
            title = "事件 $index",
            description = "备注 $index",
            location = "地点 $index",
            allDay = false,
            dtstartWall = "${day}T09:$minuteText:00",
            dtendWall = "${day}T10:$minuteText:00",
            tzid = tzid,
        )
    }

    private fun allDay(index: Int): EventMaster {
        val day = LocalDate.of(2026, 11, 10).plusDays(index.toLong())
        return EventMaster(
            id = "all$index",
            uid = "all$index@zzq.calendar",
            calendarId = "cal-local",
            title = "全天 $index",
            allDay = true,
            dtstartWall = "${day}T00:00:00",
            dtendWall = "${day.plusDays(1)}T00:00:00",
            tzid = "Asia/Shanghai",
        )
    }

    private fun rawDate(value: ICalDate): LocalDate =
        value.rawComponents.let { components -> LocalDate.of(components.year, components.month, components.date) }

    private fun compact(wall: String): String = wall.replace("-", "").replace(":", "")

    private fun plain(value: String): String = value.replace("\r\n", "\n")

    private fun rawDateTime(value: ICalDate): LocalDateTime =
        value.rawComponents.let { components: DateTimeComponents ->
            LocalDateTime.of(
                components.year,
                components.month,
                components.date,
                components.hour,
                components.minute,
                components.second,
            )
        }
}
