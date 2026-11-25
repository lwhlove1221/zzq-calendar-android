package com.zzq.calendar.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.WallTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class AndroidSystemCalendarPort(private val context: Context) : SystemCalendarPort {

    private val resolver get() = context.contentResolver
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun readEvents(): List<StoredEvent> = SystemCalendarSource(context).readEvents()

    override fun writableCalendars(): List<SystemCalendarRef> {
        val result = mutableListOf<SystemCalendarRef>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE,
            ),
            null,
            null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val access = cursor.getInt(2)
                if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || cursor.getInt(3) == 0) continue
                result += SystemCalendarRef(
                    id = cursor.getLong(0),
                    name = cursor.getString(1).orEmpty().ifBlank { "日历 ${cursor.getLong(0)}" },
                )
            }
        }
        return result
    }

    override fun create(master: EventMaster, calendarId: Long): Long? {
        requireWrite()
        val values = values(master).apply { put(CalendarContract.Events.CALENDAR_ID, calendarId) }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    override fun update(systemEventId: Long, master: EventMaster): Long? {
        requireWrite()
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, systemEventId)
        val rows = resolver.update(uri, values(master), null, null)
        if (rows <= 0) return null
        return lastModified(systemEventId) ?: System.currentTimeMillis()
    }

    override fun delete(systemEventId: Long) {
        requireWrite()
        resolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, systemEventId),
            null,
            null,
        )
    }

    override fun lastModified(systemEventId: Long): Long? {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, systemEventId),
            arrayOf(CalendarContract.Events.LAST_SYNCED),
            null,
            null,
            null,
        )?.use { cursor ->
            // Local calendars often leave LAST_SYNCED at 0; the write just succeeded, so our
            // clock is the only timestamp available for the next sync's bookkeeping.
            if (cursor.moveToFirst() && cursor.getLong(0) > 0) return cursor.getLong(0)
        }
        return System.currentTimeMillis()
    }

    override fun targetCalendarId(): Long? =
        prefs.getLong(KEY_TARGET_CALENDAR, -1L).takeIf { it > 0 }

    override fun setTargetCalendarId(calendarId: Long) {
        prefs.edit().putLong(KEY_TARGET_CALENDAR, calendarId).apply()
    }

    private fun requireWrite() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("WRITE_CALENDAR not granted")
        }
    }

    private fun values(master: EventMaster): ContentValues {
        val zone = TimeZones.resolve(master.tzid) ?: ZoneId.systemDefault()
        val start = WallTime.parse(master.dtstartWall)
        val end = WallTime.parse(master.dtendWall)
        return ContentValues().apply {
            put(CalendarContract.Events.TITLE, master.title)
            put(CalendarContract.Events.DESCRIPTION, master.description)
            put(CalendarContract.Events.EVENT_LOCATION, master.location)
            put(CalendarContract.Events.ALL_DAY, if (master.allDay) 1 else 0)
            if (master.allDay) {
                put(CalendarContract.Events.DTSTART, utcMidnight(start))
                put(CalendarContract.Events.DTEND, utcMidnight(end))
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.EVENT_TIMEZONE, master.tzid)
            }
            if (master.rruleRaw.isBlank()) {
                putNull(CalendarContract.Events.RRULE)
            } else {
                put(CalendarContract.Events.RRULE, master.rruleRaw)
            }
            if (master.exdates.isEmpty()) {
                putNull(CalendarContract.Events.EXDATE)
            } else {
                put(CalendarContract.Events.EXDATE, exdates(master, zone))
            }
        }
    }

    private fun utcMidnight(wall: LocalDateTime): Long =
        wall.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** CalendarContract accepts `yyyyMMdd` for all-day and UTC stamps for timed events. */
    private fun exdates(master: EventMaster, zone: ZoneId): String =
        master.exdates.joinToString(",") { value ->
            val wall = WallTime.parse(value)
            if (master.allDay) {
                DATE.format(wall)
            } else {
                UTC.format(wall.atZone(zone).toInstant().atOffset(ZoneOffset.UTC))
            }
        }

    companion object {
        private const val PREFS = "calendar_sync"
        private const val KEY_TARGET_CALENDAR = "target_calendar_id"
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
