package com.zzq.calendar.data

import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.Valarm
import com.zzq.calendar.model.WallTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reads the ROM calendar (CalendarContract) so the user can pull their system
 * calendar into the app. Requires `READ_CALENDAR`.
 *
 * Every row becomes a read-only mirror (`owner=system`) keyed by its CalendarContract id.
 */
class SystemCalendarSource(private val context: Context) {

    fun readEvents(): List<StoredEvent> {
        val resolver = context.contentResolver
        val visibleCalendars = runCatching { readVisibleCalendarIds(resolver) }.getOrNull()
        val alarms = readAlarmMinutes(resolver)

        val events = mutableListOf<StoredEvent>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            PROJECTION,
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = Row(cursor)
                if (row.deleted || visibleCalendars?.contains(row.calendarId) == false) continue
                events += row.toStored(alarms[row.id].orEmpty())
            }
        }
        return events
    }

    private fun readVisibleCalendarIds(resolver: ContentResolver): Set<Long> =
        mutableSetOf<Long>().also { ids ->
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.VISIBLE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(1) >= CalendarContract.Calendars.CAL_ACCESS_READ &&
                        cursor.getInt(2) != 0
                    ) {
                        ids += cursor.getLong(0)
                    }
                }
            }
        }

    private fun readAlarmMinutes(resolver: ContentResolver): Map<Long, List<Int>> =
        mutableMapOf<Long, MutableList<Int>>().also { map ->
            resolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    map.getOrPut(cursor.getLong(0)) { mutableListOf() } += cursor.getInt(1)
                }
            }
        }

    private class Row(cursor: android.database.Cursor) {
        val id = cursor.getLong(0)
        val calendarId = cursor.getLong(1)
        val title = cursor.getString(2).orEmpty()
        val description = cursor.getString(3).orEmpty()
        val location = cursor.getString(4).orEmpty()
        val dtstart = cursor.getLong(5)
        val dtend = if (cursor.isNull(6)) null else cursor.getLong(6)
        val duration = cursor.getString(7)
        val allDay = cursor.getInt(8) != 0
        val tzid = cursor.getString(9)?.takeIf { it.isNotBlank() }
        val rrule = cursor.getString(10).orEmpty()
        val exdate = cursor.getString(11)
        val deleted = cursor.getInt(12) != 0
        val lastModified = cursor.getLong(13)

        fun toStored(alarms: List<Int>): StoredEvent {
            val zone = TimeZones.resolve(tzid.orEmpty()) ?: ZoneId.systemDefault()
            val displayTzid = TimeZones.resolve(tzid.orEmpty())?.id ?: ZoneId.systemDefault().id
            val endMillis = dtend ?: durationSeconds(duration)?.let { dtstart + it * 1000 }
            val timedStart = Instant.ofEpochMilli(dtstart).atZone(zone).toLocalDateTime()
            val timedEnd = endMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
                ?: timedStart
            // Xiaomi stores its day-level entries (纪念日/生日) as allDay=0 with a UTC-midnight
            // start and a duration that ends at local midnight, i.e. wall time reads 08:00-24:00.
            // shortcut: assumes such a span is meant as all-day; a genuine 08:00-24:00 event in a
            // UTC+8 ROM would also be converted, so revisit if users report it.
            val romDaySpan = Math.floorMod(dtstart, DAY_MILLIS) == 0L &&
                timedEnd == timedStart.toLocalDate().plusDays(1).atStartOfDay()
            val isAllDay = allDay || romDaySpan
            val startWall: LocalDateTime
            val endWall: LocalDateTime
            if (isAllDay) {
                val startDate = Instant.ofEpochMilli(dtstart).atZone(ZoneOffset.UTC).toLocalDate()
                val days = allDayLength(startDate, endMillis, duration)
                startWall = startDate.atStartOfDay()
                endWall = startDate.plusDays(days).atStartOfDay()
            } else {
                startWall = timedStart
                endWall = if (timedEnd.isAfter(timedStart)) timedEnd else timedStart
            }
            val uid = "system-$id@system.calendar"
            val master = EventMaster(
                id = "sys-$id",
                uid = uid,
                calendarId = "system-$calendarId",
                title = title,
                description = description,
                location = location,
                allDay = isAllDay,
                dtstartWall = WallTime.format(startWall),
                dtendWall = WallTime.format(endWall),
                tzid = displayTzid,
                rruleRaw = rrule,
                exdates = exdates(exdate, zone),
                valarms = alarms.map { Valarm("duration", -it * 60, displayTzid) },
                owner = EventMaster.OWNER_SYSTEM,
                writeBack = false,
                systemEventId = id,
                systemCalendarId = calendarId,
                lastSyncedModified = lastModified,
            )
            return StoredEvent(master, emptyList(), FingerprintV1.sha256(master, emptyList()))
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.LAST_SYNCED,
        )

        private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val WALL_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
        private const val DAY_MILLIS = 86_400_000L
        // Xiaomi's provider writes "P57600S" (no T), so the T separator is optional here.
        private val DURATION =
            Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")

        /** `P57600S`, `P1D`, `PT1H30M` — the provider stores recurring event lengths this way. */
        internal fun durationSeconds(raw: String?): Long? {
            val match = DURATION.matchEntire(raw?.trim()?.uppercase() ?: return null) ?: return null
            val (weeks, days, hours, minutes, seconds) = match.destructured
            return (weeks.toLongOrNull() ?: 0L) * 604_800 +
                (days.toLongOrNull() ?: 0L) * 86_400 +
                (hours.toLongOrNull() ?: 0L) * 3_600 +
                (minutes.toLongOrNull() ?: 0L) * 60 +
                (seconds.toLongOrNull() ?: 0L)
        }

        internal fun exdates(raw: String?, zone: ZoneId): List<String> =
            raw.orEmpty().split(',').mapNotNull { token ->
                val value = token.trim()
                if (value.isEmpty()) return@mapNotNull null
                val instant = runCatching {
                    if (value.endsWith("Z")) {
                        java.time.LocalDateTime.parse(value, UTC_STAMP).toInstant(ZoneOffset.UTC)
                    } else {
                        java.time.LocalDateTime.parse(value, WALL_STAMP).atZone(zone).toInstant()
                    }
                }.getOrNull()
                instant?.atZone(zone)?.let { WallTime.format(it.toLocalDateTime()) }
            }

        private fun allDayLength(start: LocalDate, dtend: Long?, duration: String?): Long {
            if (dtend != null) {
                val end = Instant.ofEpochMilli(dtend).atZone(ZoneOffset.UTC).toLocalDate()
                return maxOf(1L, java.time.temporal.ChronoUnit.DAYS.between(start, end))
            }
            val seconds = durationSeconds(duration) ?: 86_400L
            return maxOf(1L, seconds / 86_400)
        }
    }
}
