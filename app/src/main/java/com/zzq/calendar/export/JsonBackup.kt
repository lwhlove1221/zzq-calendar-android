package com.zzq.calendar.export

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.Valarm
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Local backup manifest: `{schema_version, exported_at, event_count, parts[]}`.
 * A part holds at most [MAX_EVENTS_PER_PART] events or [MAX_PART_BYTES] UTF-8 bytes of events JSON.
 * A single event larger than [MAX_PART_BYTES] still gets its own part (cannot be split).
 */
object JsonBackup {

    const val SCHEMA_VERSION = 1
    const val MAX_EVENTS_PER_PART = 5000
    const val MAX_PART_BYTES = 20 * 1024 * 1024

    fun build(
        events: List<StoredEvent>,
        now: Instant = Instant.now(),
        maxEventsPerPart: Int = MAX_EVENTS_PER_PART,
        maxPartBytes: Int = MAX_PART_BYTES,
    ): String {
        val live = events.filter { !it.master.tombstone }
        val parts = shard(live, maxEventsPerPart, maxPartBytes)
        val sb = StringBuilder(1024 + live.size * 512)
        sb.append("{\"schema_version\":").append(SCHEMA_VERSION)
        sb.append(",\"exported_at\":\"")
            .append(DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(ChronoUnit.SECONDS)))
            .append('"')
        sb.append(",\"event_count\":").append(live.size)
        sb.append(",\"parts\":[")
        parts.forEachIndexed { index, part ->
            if (index > 0) sb.append(',')
            val body = part.joinToString(",")
            sb.append("{\"index\":").append(index)
            sb.append(",\"event_count\":").append(part.size)
            sb.append(",\"bytes\":").append(body.toByteArray(Charsets.UTF_8).size + 2)
            sb.append(",\"events\":[").append(body).append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun shard(events: List<StoredEvent>, maxEventsPerPart: Int, maxPartBytes: Int): List<List<String>> {
        val parts = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var bytes = 2 // [ ]
        events.forEach { event ->
            val json = toJson(event)
            val width = json.toByteArray(Charsets.UTF_8).size + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() &&
                (current.size >= maxEventsPerPart || bytes + width > maxPartBytes)
            ) {
                parts += current
                current = mutableListOf()
                bytes = 2
            }
            bytes += width
            current += json
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    private fun toJson(stored: StoredEvent): String {
        val master = stored.master
        val fields = mutableListOf<String>()
        fun add(key: String, value: String) = fields.add("\"$key\":$value")

        add("uid", quote(master.uid))
        add("dtstart_wall", quote(master.dtstartWall))
        add("dtend_wall", quote(master.dtendWall))
        add("tzid", quote(master.tzid))
        add("all_day", master.allDay.toString())
        add("rrule_raw", quote(master.rruleRaw))
        add("exdate", stringArray(master.exdates))
        add("rdate", stringArray(master.rdates))
        add("exceptions", stored.exceptions.filter { !it.tombstone }.joinToString(",", "[", "]") { exceptionJson(it) })
        add("valarm", master.valarms.joinToString(",", "[", "]") { valarmJson(it) })
        add("calendar_id", quote(master.calendarId))
        add("source_type", quote(master.sourceType))
        add("source_id", quote(master.sourceId))
        add("rev", master.rev.toString())
        add("fingerprint", quote(stored.fingerprint))
        add("tzdata_version", quote(master.tzdataVersion))
        add("title", quote(master.title))
        add("description", quote(master.description))
        add("location", quote(master.location))
        add("url", quote(master.url))
        return fields.joinToString(",", "{", "}")
    }

    private fun exceptionJson(exception: EventException): String {
        val fields = mutableListOf<String>()
        fun add(key: String, value: String) = fields.add("\"$key\":$value")

        add("recurrence_id_wall", quote(exception.recurrenceIdWall))
        add("tzid", quote(exception.tzid))
        add("status", quote(exception.status.wireValue))
        exception.title?.let { add("title", quote(it)) }
        exception.allDay?.let { add("all_day", it.toString()) }
        exception.dtstartWall?.let { add("dtstart_wall", quote(it)) }
        exception.dtendWall?.let { add("dtend_wall", quote(it)) }
        exception.description?.let { add("description", quote(it)) }
        exception.location?.let { add("location", quote(it)) }
        return fields.joinToString(",", "{", "}")
    }

    private fun valarmJson(alarm: Valarm): String =
        "{\"trigger_type\":${quote(alarm.triggerType)}" +
            ",\"offset\":${alarm.offsetSeconds}" +
            ",\"tzid\":${quote(alarm.tzid)}}"

    private fun stringArray(values: List<String>): String =
        values.joinToString(",", "[", "]") { quote(it) }

    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (char < ' ') {
                    sb.append("\\u").append(String.format("%04x", char.code))
                } else {
                    sb.append(char)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
