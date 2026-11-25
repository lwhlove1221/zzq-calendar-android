package com.zzq.calendar.data

import com.zzq.calendar.model.EventMaster

data class SystemCalendarRef(
    val id: Long,
    val name: String,
)

/** Android CalendarContract behind an interface so mirror/write-back logic stays testable. */
interface SystemCalendarPort {

    fun readEvents(): List<StoredEvent>

    fun writableCalendars(): List<SystemCalendarRef>

    fun create(master: EventMaster, calendarId: Long): Long?

    fun update(systemEventId: Long, master: EventMaster): Long?

    fun delete(systemEventId: Long)

    fun lastModified(systemEventId: Long): Long?

    fun targetCalendarId(): Long?

    fun setTargetCalendarId(calendarId: Long)
}
