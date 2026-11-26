package com.zzq.calendar.reminder

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RRuleExpander
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.Valarm
import com.zzq.calendar.model.WallTime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ReminderSlot(
    val key: String,
    val eventId: String,
    val occurrenceKey: String,
    val offsetSeconds: Int,
    val title: String,
    val startWall: String,
    val fireAt: Long,
)

/**
 * PRD-05 §3.1: `duration` offsets run on the absolute timeline, `calendar` offsets on the
 * event TZID's wall clock (so "提前 1 天" stays the same wall time across DST).
 * Wall resolution uses [LocalDateTime.atZone], which already shifts a spring-forward gap
 * forward and picks the first (DST side) instant of a repeated hour.
 */
object ReminderPlanner {

    const val HORIZON_DAYS = 30L

    fun plan(
        events: List<StoredEvent>,
        nowMillis: Long,
        deviceZone: ZoneId = ZoneId.systemDefault(),
    ): List<ReminderSlot> {
        val now = Instant.ofEpochMilli(nowMillis)
        val windowStart = now.minus(1, ChronoUnit.DAYS)
        val windowEnd = now.plus(HORIZON_DAYS, ChronoUnit.DAYS)
        val slots = mutableListOf<ReminderSlot>()

        for (stored in events) {
            val master = stored.master
            if (master.tombstone || master.owner == EventMaster.OWNER_SYSTEM) continue
            if (master.valarms.isEmpty()) continue
            val zone = TimeZones.resolve(master.tzid) ?: deviceZone
            val recurring = RRule.parse(master.rruleRaw).isRecurring
            val occurrences = if (recurring) {
                RRuleExpander.instancesOverlapping(master, windowStart, windowEnd, deviceZone)
            } else {
                listOf(WallTime.parse(master.dtstartWall) to WallTime.parse(master.dtendWall))
            }

            for (occurrence in occurrences) {
                val recurrenceId = WallTime.format(occurrence.first)
                val exception = stored.exceptions.firstOrNull {
                    it.recurrenceIdWall == recurrenceId && !it.tombstone
                }
                if (exception?.status == ExceptionStatus.CANCELLED) continue
                val startWall = exception?.dtstartWall
                    ?.let { runCatching { WallTime.parse(it) }.getOrNull() }
                    ?: occurrence.first
                val startWallText = WallTime.format(startWall)

                for (alarm in master.valarms) {
                    val fireAt = fireAtMillis(startWall, alarm, zone)
                    if (fireAt < nowMillis) continue
                    slots += ReminderSlot(
                        key = scheduleKey(master.id, startWallText, alarm.offsetSeconds, master.tzdataVersion),
                        eventId = master.id,
                        occurrenceKey = startWallText,
                        offsetSeconds = alarm.offsetSeconds,
                        title = exception?.title ?: master.title,
                        startWall = startWallText,
                        fireAt = fireAt,
                    )
                }
            }
        }
        return slots.distinctBy { it.key }.sortedBy { it.fireAt }
    }

    fun fireAtMillis(startWall: LocalDateTime, alarm: Valarm, zone: ZoneId): Long {
        val start = startWall.atZone(zone).toInstant()
        return if (alarm.triggerType == TRIGGER_CALENDAR) {
            startWall.plusSeconds(alarm.offsetSeconds.toLong()).atZone(zone).toInstant().toEpochMilli()
        } else {
            start.plusSeconds(alarm.offsetSeconds.toLong()).toEpochMilli()
        }
    }

    fun scheduleKey(eventId: String, occurrenceWall: String, offset: Int, tzdataVersion: String): String =
        "$eventId|$occurrenceWall|$offset|$tzdataVersion"

    const val TRIGGER_CALENDAR = "calendar"
}
