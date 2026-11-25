package com.zzq.calendar.data

import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.FingerprintV1

internal fun storedOf(master: EventMaster): StoredEvent =
    StoredEvent(master, emptyList(), FingerprintV1.sha256(master, emptyList()))

internal fun systemStored(
    systemEventId: Long,
    title: String,
    lastModified: Long,
    startWall: String = "2026-11-25T09:00:00",
    endWall: String = "2026-11-25T10:00:00",
): StoredEvent = storedOf(
    EventMaster(
        id = "sys-$systemEventId",
        uid = "system-$systemEventId@system.calendar",
        calendarId = "system-1",
        title = title,
        allDay = false,
        dtstartWall = startWall,
        dtendWall = endWall,
        tzid = "Asia/Shanghai",
        owner = EventMaster.OWNER_SYSTEM,
        writeBack = false,
        systemEventId = systemEventId,
        systemCalendarId = 1,
        lastSyncedModified = lastModified,
    ),
)

internal class FakeSystemCalendarPort : SystemCalendarPort {

    val events = mutableListOf<StoredEvent>()
    var target: Long? = TARGET_CALENDAR
    var failWrites = false
    var created = 0
    var updated = 0
    var deleted = 0
    private var nextId = 100L
    private var clock = 1_000L

    fun put(event: StoredEvent) {
        events.removeAll { it.master.systemEventId == event.master.systemEventId }
        events += event
    }

    fun deleteSilently(systemEventId: Long) {
        events.removeAll { it.master.systemEventId == systemEventId }
    }

    override fun readEvents(): List<StoredEvent> = events.toList()

    override fun writableCalendars(): List<SystemCalendarRef> =
        listOf(SystemCalendarRef(TARGET_CALENDAR, "本机日历"))

    override fun create(master: EventMaster, calendarId: Long): Long? {
        if (failWrites) return null
        created++
        val id = nextId++
        clock += 10
        events += storedOf(
            master.copy(
                id = "sys-$id",
                uid = "system-$id@system.calendar",
                calendarId = "system-$calendarId",
                owner = EventMaster.OWNER_SYSTEM,
                writeBack = false,
                systemEventId = id,
                systemCalendarId = calendarId,
                lastSyncedModified = clock,
            ),
        )
        return id
    }

    override fun update(systemEventId: Long, master: EventMaster): Long? {
        if (failWrites) return null
        val index = events.indexOfFirst { it.master.systemEventId == systemEventId }
        if (index < 0) return null
        updated++
        clock += 10
        events[index] = storedOf(
            master.copy(
                id = "sys-$systemEventId",
                uid = "system-$systemEventId@system.calendar",
                calendarId = "system-${master.systemCalendarId ?: TARGET_CALENDAR}",
                owner = EventMaster.OWNER_SYSTEM,
                writeBack = false,
                systemEventId = systemEventId,
                systemCalendarId = master.systemCalendarId ?: TARGET_CALENDAR,
                lastSyncedModified = clock,
            ),
        )
        return clock
    }

    override fun delete(systemEventId: Long) {
        if (failWrites) return
        deleted++
        deleteSilently(systemEventId)
    }

    override fun lastModified(systemEventId: Long): Long? =
        events.firstOrNull { it.master.systemEventId == systemEventId }?.master?.lastSyncedModified

    override fun targetCalendarId(): Long? = target

    override fun setTargetCalendarId(calendarId: Long) {
        target = calendarId
    }

    companion object {
        const val TARGET_CALENDAR = 7L
    }
}
