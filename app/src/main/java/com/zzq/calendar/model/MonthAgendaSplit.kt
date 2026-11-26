package com.zzq.calendar.model

import java.time.Instant
import java.time.ZoneId

/** Current-month instances split into upcoming and finished, each sorted by start. */
data class MonthAgendaGroups(
    val remaining: List<EventInstance>,
    val completed: List<EventInstance>,
)

object MonthAgendaSplit {

    fun split(instances: List<EventInstance>, now: Instant, zone: ZoneId): MonthAgendaGroups {
        val sorted = instances.sortedWith(compareBy({ it.start }, { it.title }))
        val (completed, remaining) = sorted.partition { effectiveEnd(it, zone).isBefore(now) }
        return MonthAgendaGroups(remaining = remaining, completed = completed)
    }

    /** All-day events end at 23:59 on their last day, not at the next midnight. */
    fun effectiveEnd(instance: EventInstance, zone: ZoneId): Instant {
        val endExclusive = instance.endDateExclusive ?: return instance.end
        return endExclusive.minusDays(1).atTime(23, 59).atZone(zone).toInstant()
    }
}
