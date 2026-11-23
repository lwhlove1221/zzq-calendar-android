package com.zzq.calendar.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class Valarm(
    val triggerType: String,
    val offsetSeconds: Int,
    val tzid: String,
)

enum class ExceptionStatus(val wireValue: String) {
    MODIFIED("modified"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): ExceptionStatus =
            entries.firstOrNull { it.wireValue == value } ?: MODIFIED
    }
}

data class EventException(
    val id: String,
    val eventId: String,
    val recurrenceIdWall: String,
    val tzid: String,
    val status: ExceptionStatus,
    val title: String? = null,
    val allDay: Boolean? = null,
    val dtstartWall: String? = null,
    val dtendWall: String? = null,
    val description: String? = null,
    val location: String? = null,
    val tombstone: Boolean = false,
)

data class EventMaster(
    val id: String,
    val uid: String,
    val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val url: String = "",
    val allDay: Boolean,
    val dtstartWall: String,
    val dtendWall: String,
    val tzid: String,
    val rruleRaw: String = "",
    val exdates: List<String> = emptyList(),
    val rdates: List<String> = emptyList(),
    val valarms: List<Valarm> = emptyList(),
    val tombstone: Boolean = false,
    val rev: Int = 1,
    val tzdataVersion: String = TimeZones.tzdataVersion(),
    val sourceType: String = SOURCE_LOCAL,
    val sourceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        const val SOURCE_LOCAL = "local"
    }
}

data class InstanceKey(
    val uid: String,
    val recurrenceIdWall: String,
    val tzid: String,
)

enum class InstanceType(val wireValue: String) {
    NORMAL("normal"),
    MODIFIED("modified"),
    CANCELLED("cancelled"),
}

data class EventInstance(
    val master: EventMaster,
    val key: InstanceKey,
    val type: InstanceType,
    val title: String,
    val allDay: Boolean,
    val startWall: String,
    val endWall: String,
    val start: Instant,
    val end: Instant,
    val endDateExclusive: LocalDate? = null,
    val displayTzid: String,
)

data class DayAgenda(
    val day: LocalDate,
    val allDay: List<EventInstance>,
    val timed: List<EventInstance>,
) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty()
}

data class RepeatSpec(
    val frequency: RRule.Freq? = null,
    val interval: Int = 1,
    val count: Int? = null,
    val untilWall: LocalDateTime? = null,
    val byDay: List<DayOfWeek> = emptyList(),
)
