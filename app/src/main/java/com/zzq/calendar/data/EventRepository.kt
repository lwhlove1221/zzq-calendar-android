package com.zzq.calendar.data

import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.InstanceKey
import com.zzq.calendar.model.InstanceType
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RRuleExpander
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.WallTime
import com.zzq.calendar.importer.IcsImporter
import com.zzq.calendar.importer.ImportOutcome
import com.zzq.calendar.importer.ImportPreview
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EventDraft(
    val title: String,
    val allDay: Boolean,
    val startWall: String,
    val endWall: String,
    val tzid: String,
    val description: String = "",
    val location: String = "",
    val repeat: RepeatSpec = RepeatSpec(),
)

class EventRepository(
    private val helper: CalendarDbHelper,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val store = EventStore(helper)
    private val importer by lazy { IcsImporter(helper, store) }

    suspend fun create(draft: EventDraft): EventMaster = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val master = EventMaster(
            id = id,
            uid = "$id@zzq.calendar",
            calendarId = DEFAULT_CALENDAR_ID,
            title = draft.title.trim(),
            description = draft.description.trim(),
            location = draft.location.trim(),
            allDay = draft.allDay,
            dtstartWall = draft.startWall,
            dtendWall = draft.endWall,
            tzid = draft.tzid,
            rruleRaw = com.zzq.calendar.data.RRuleBuilder.build(draft.repeat, draft.startWall),
            createdAt = now,
            updatedAt = now,
        )
        store.insert(master, emptyList()).master
    }

    suspend fun update(master: EventMaster): EventMaster = withContext(ioDispatcher) {
        val stored = store.findById(master.id) ?: error("event not found: ${master.id}")
        val updated = master.copy(
            rev = stored.master.rev + 1,
            updatedAt = System.currentTimeMillis(),
        )
        store.update(updated, stored.exceptions).master
    }

    suspend fun delete(eventId: String): Boolean = withContext(ioDispatcher) {
        store.delete(eventId)
    }

    suspend fun get(eventId: String): StoredEvent? = withContext(ioDispatcher) {
        store.findById(eventId)
    }

    suspend fun loadAll(): List<StoredEvent> = withContext(ioDispatcher) {
        store.loadAll()
    }

    suspend fun fingerprintOf(eventId: String): String? = withContext(ioDispatcher) {
        store.findById(eventId)?.fingerprint
    }

    suspend fun previewImport(bytes: ByteArray): ImportPreview = withContext(ioDispatcher) {
        importer.preview(bytes)
    }

    suspend fun importIcs(bytes: ByteArray): ImportOutcome = withContext(ioDispatcher) {
        importer.import(bytes)
    }

    /** Returns true when an interrupted import left a staging batch behind. */
    suspend fun discardStaging(): Boolean = withContext(ioDispatcher) {
        val leftover = store.hasStaging()
        store.clearStaging()
        leftover
    }

    suspend fun loadDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayAgenda =
        withContext(ioDispatcher) { buildDay(schedules(store.loadAll(), zone), day, zone) }

    /** Event counts per day for the month grid; days without events are omitted. */
    suspend fun loadMonth(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, Int> =
        withContext(ioDispatcher) {
            val schedules = schedules(store.loadAll(), zone)
            buildMap {
                (1..month.lengthOfMonth()).forEach { dayOfMonth ->
                    val day = month.atDay(dayOfMonth)
                    val count = buildDay(schedules, day, zone).instanceCount
                    if (count > 0) put(day, count)
                }
            }
        }

    /** Seven consecutive days starting at [start] (Monday for the week view). */
    suspend fun loadWeek(start: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<DayAgenda> =
        withContext(ioDispatcher) {
            val schedules = schedules(store.loadAll(), zone)
            (0L..6L).map { buildDay(schedules, start.plusDays(it), zone) }
        }

    /** Wall times and zone resolved once per load: a month pass would otherwise re-parse every event ~30 times. */
    private class Schedule(val stored: StoredEvent, deviceZone: ZoneId) {
        val master: EventMaster = stored.master
        val dtstart: LocalDateTime = WallTime.parse(master.dtstartWall)
        val dtend: LocalDateTime = WallTime.parse(master.dtendWall).let { if (it.isAfter(dtstart)) it else dtstart }
        val zone: ZoneId = TimeZones.resolve(master.tzid) ?: deviceZone
        val recurring: Boolean = RRule.parse(master.rruleRaw).isRecurring
    }

    private fun schedules(stored: List<StoredEvent>, zone: ZoneId): List<Schedule> =
        stored.map { Schedule(it, zone) }

    private fun buildDay(schedules: List<Schedule>, day: LocalDate, zone: ZoneId): DayAgenda {
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val allDay = mutableListOf<EventInstance>()
        val timed = mutableListOf<EventInstance>()

        schedules.forEach { schedule ->
            val master = schedule.master
            if (master.tombstone) return@forEach
            if (master.allDay) {
                allDay += expandAllDay(schedule, day, zone)
            } else {
                timed += expandTimed(schedule, dayStart, dayEnd)
            }
        }

        return DayAgenda(
            day = day,
            allDay = allDay.sortedWith(compareBy({ it.startWall }, { it.title })),
            timed = timed.sortedWith(compareBy({ it.start }, { it.end }, { it.title })),
        )
    }

    private fun expandAllDay(
        schedule: Schedule,
        day: LocalDate,
        zone: ZoneId,
    ): List<EventInstance> {
        val master = schedule.master
        val exceptions = schedule.stored.exceptions
        val dtstart = schedule.dtstart.toLocalDate()
        val dtend = schedule.dtend.toLocalDate()
        val days = maxOf(1, java.time.temporal.ChronoUnit.DAYS.between(dtstart, dtend).toInt())
        val starts =
            if (schedule.recurring) {
                RRuleExpander.expand(
                    master,
                    day.minusDays(days.toLong()).atStartOfDay(),
                    day.plusDays(2).atStartOfDay(),
                    zone,
                ).starts
            } else {
                listOf(schedule.dtstart)
            }
        return starts.mapNotNull { start ->
            val startDate = start.toLocalDate()
            val endDateExclusive = startDate.plusDays(days.toLong())
            if (day < startDate || day >= endDateExclusive) return@mapNotNull null
            val exception = findException(exceptions, WallTime.format(start))
            if (exception?.status == ExceptionStatus.CANCELLED) return@mapNotNull null
            EventInstance(
                master = master,
                key = InstanceKey(master.uid, WallTime.format(start), master.tzid),
                type = if (exception?.status == ExceptionStatus.MODIFIED) InstanceType.MODIFIED else InstanceType.NORMAL,
                title = exception?.title ?: master.title,
                allDay = true,
                startWall = WallTime.format(start),
                endWall = WallTime.format(start.toLocalDate().plusDays(days.toLong()).atStartOfDay()),
                start = startDate.atStartOfDay(zone).toInstant(),
                end = endDateExclusive.atStartOfDay(zone).toInstant(),
                endDateExclusive = endDateExclusive,
                displayTzid = master.tzid,
            )
        }
    }

    private fun expandTimed(
        schedule: Schedule,
        dayStart: Instant,
        dayEnd: Instant,
    ): List<EventInstance> {
        val master = schedule.master
        val exceptions = schedule.stored.exceptions
        val duration = if (schedule.dtend.isAfter(schedule.dtstart)) {
            java.time.Duration.between(schedule.dtstart, schedule.dtend)
        } else {
            java.time.Duration.ZERO
        }
        val instances =
            if (schedule.recurring) {
                RRuleExpander.instancesOverlapping(master, dayStart, dayEnd, schedule.zone)
            } else {
                listOf(schedule.dtstart to schedule.dtend)
            }
        return instances.mapNotNull { (start, end) ->
            val startInstant = start.atZone(schedule.zone).toInstant()
            val endInstant = end.atZone(schedule.zone).toInstant()
            if (!startInstant.isBefore(dayEnd) || !endInstant.isAfter(dayStart)) return@mapNotNull null
            val recurrenceId = WallTime.format(start)
            val exception = findException(exceptions, recurrenceId)
            if (exception?.status == ExceptionStatus.CANCELLED) return@mapNotNull null
            EventInstance(
                master = master,
                key = InstanceKey(master.uid, recurrenceId, master.tzid),
                type = if (exception?.status == ExceptionStatus.MODIFIED) InstanceType.MODIFIED else InstanceType.NORMAL,
                title = exception?.title ?: master.title,
                allDay = false,
                startWall = recurrenceId,
                endWall = WallTime.format(start.plus(duration)),
                start = startInstant,
                end = endInstant,
                displayTzid = master.tzid,
            )
        }
    }

    private fun findException(exceptions: List<EventException>, recurrenceIdWall: String): EventException? =
        exceptions.firstOrNull { it.recurrenceIdWall == recurrenceIdWall && !it.tombstone }

    suspend fun validateAndCreate(draft: EventDraft): Result<EventMaster> = runCatching {
        val errors = validate(draft)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.first())
        create(draft)
    }

    fun validate(draft: EventDraft): List<String> {
        val errors = mutableListOf<String>()
        if (draft.title.isBlank()) errors += "标题不能为空"
        if (TimeZones.resolve(draft.tzid) == null) errors += "未知时区：${draft.tzid}"
        val start = runCatching { WallTime.parse(draft.startWall) }.getOrNull()
        val end = runCatching { WallTime.parse(draft.endWall) }.getOrNull()
        if (start == null || end == null) {
            errors += "时间格式不正确"
        } else {
            if (draft.allDay) {
                val startDate = start.toLocalDate()
                val endDate = end.toLocalDate()
                if (!endDate.isAfter(startDate)) errors += "全天事件结束日期需晚于开始日期"
            } else if (!end.isAfter(start)) {
                errors += "结束时间需晚于开始时间"
            }
        }
        return errors
    }

    fun previewFingerprint(master: EventMaster, exceptions: List<EventException> = emptyList()): String =
        FingerprintV1.sha256(master, exceptions)

    companion object {
        const val DEFAULT_CALENDAR_ID = "cal-local"
    }
}
