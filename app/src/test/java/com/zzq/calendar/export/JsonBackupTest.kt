package com.zzq.calendar.export

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.Valarm
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JsonBackupTest {

    private val now: Instant = Instant.parse("2026-11-23T07:00:00Z")

    @Test
    fun `one thousand events keep count and single part`() {
        val events = (1..1000).map { stored(event(it)) }

        val json = JSONObject(JsonBackup.build(events, now))
        assertEquals(1, json.getInt("schema_version"))
        assertEquals("2026-11-23T07:00:00Z", json.getString("exported_at"))
        assertEquals(1000, json.getInt("event_count"))
        val parts = json.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals(1000, parts.getJSONObject(0).getInt("event_count"))
        assertEquals(1000, parts.getJSONObject(0).getJSONArray("events").length())

        val first = parts.getJSONObject(0).getJSONArray("events").getJSONObject(0)
        assertEquals("e1@zzq.calendar", first.getString("uid"))
        assertEquals("事件 1", first.getString("title"))
        assertEquals("2026-11-02T09:00:00", first.getString("dtstart_wall"))
        assertEquals("Asia/Shanghai", first.getString("tzid"))
        assertEquals(false, first.getBoolean("all_day"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", first.getString("rrule_raw"))
        assertEquals(1, first.getJSONArray("exdate").length())
        assertEquals("cal-local", first.getString("calendar_id"))
        assertEquals("local", first.getString("source_type"))
        assertEquals(1, first.getInt("rev"))
        assertEquals(events[0].fingerprint, first.getString("fingerprint"))
    }

    @Test
    fun `events are sharded at 5000 per part`() {
        val events = (1..5001).map { stored(event(it, includeRecurrence = false)) }

        val parts = JSONObject(JsonBackup.build(events, now)).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals(5000, parts.getJSONObject(0).getInt("event_count"))
        assertEquals(1, parts.getJSONObject(1).getInt("event_count"))
    }

    @Test
    fun `parts stay under the byte cap`() {
        val longNote = "备注".repeat(100) // 600 UTF-8 bytes
        val events = (1..12).map { stored(event(it, description = longNote)) }
        val cap = 2000

        val json = JSONObject(JsonBackup.build(events, now, maxPartBytes = cap))
        val parts = json.getJSONArray("parts")
        assertTrue(parts.length() > 1)
        var total = 0
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            val size = part.getInt("bytes")
            assertTrue("part $index is $size bytes", size <= cap)
            total += part.getInt("event_count")
        }
        assertEquals(12, total)
        assertEquals(12, json.getInt("event_count"))
    }

    @Test
    fun `exceptions and alarms are exported with dto fields`() {
        val master = event(1).copy(valarms = listOf(Valarm("duration", -600, "Asia/Shanghai")))
        val exception = EventException(
            id = "x1",
            eventId = master.id,
            recurrenceIdWall = "2026-11-08T09:00:00",
            tzid = "Asia/Shanghai",
            status = ExceptionStatus.MODIFIED,
            title = "改期",
            dtstartWall = "2026-11-08T10:00:00",
            dtendWall = "2026-11-08T11:00:00",
        )
        val json = JSONObject(JsonBackup.build(listOf(stored(master, listOf(exception))), now))
        val event = json.getJSONArray("parts").getJSONObject(0).getJSONArray("events").getJSONObject(0)

        val exportedException = event.getJSONArray("exceptions").getJSONObject(0)
        assertEquals("2026-11-08T09:00:00", exportedException.getString("recurrence_id_wall"))
        assertEquals("modified", exportedException.getString("status"))
        assertEquals("改期", exportedException.getString("title"))

        val alarm = event.getJSONArray("valarm").getJSONObject(0)
        assertEquals("duration", alarm.getString("trigger_type"))
        assertEquals(-600, alarm.getInt("offset"))
        assertEquals("Asia/Shanghai", alarm.getString("tzid"))
    }

    private fun event(index: Int, includeRecurrence: Boolean = true, description: String = "备注 $index"): EventMaster {
        val day = (index % 28) + 1
        return EventMaster(
            id = "e$index",
            uid = "e$index@zzq.calendar",
            calendarId = "cal-local",
            title = "事件 $index",
            description = description,
            allDay = false,
            dtstartWall = "2026-11-${day.toString().padStart(2, '0')}T09:00:00",
            dtendWall = "2026-11-${day.toString().padStart(2, '0')}T10:00:00",
            tzid = "Asia/Shanghai",
            rruleRaw = if (includeRecurrence) "FREQ=WEEKLY;BYDAY=MO" else "",
            exdates = if (includeRecurrence) listOf("2026-11-16T09:00:00") else emptyList(),
            rev = 1,
        )
    }

    private fun stored(master: EventMaster, exceptions: List<EventException> = emptyList()) = StoredEvent(
        master = master,
        exceptions = exceptions,
        fingerprint = FingerprintV1.sha256(master, exceptions),
    )
}
