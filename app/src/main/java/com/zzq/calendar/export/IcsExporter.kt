package com.zzq.calendar.export

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.Valarm
import com.zzq.calendar.model.WallTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** RFC 5545 exporter: CRLF, UTF-8, 75-octet folding, no TZID on all-day values. */
object IcsExporter {

    private const val CRLF = "\r\n"
    private const val PRODID = "-//ZZQ//Calendar Android V1//CN"
    private val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val WALL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun export(events: List<StoredEvent>, now: Instant = Instant.now()): String {
        val stamp = UTC.format(now.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC))
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:$PRODID",
            "CALSCALE:GREGORIAN",
        )
        events.filter { !it.master.tombstone }.forEach { appendEvent(lines, it, stamp) }
        lines += "END:VCALENDAR"
        return lines.joinToString(CRLF) { fold(it) } + CRLF
    }

    private fun appendEvent(out: MutableList<String>, stored: StoredEvent, stamp: String) {
        val master = stored.master
        out += "BEGIN:VEVENT"
        out += "UID:${master.uid}"
        out += "DTSTAMP:$stamp"
        out += dateLine("DTSTART", master.allDay, master.dtstartWall, master.tzid)
        out += dateLine("DTEND", master.allDay, master.dtendWall, master.tzid)
        out += "SUMMARY:${escapeText(master.title)}"
        if (master.description.isNotEmpty()) out += "DESCRIPTION:${escapeText(master.description)}"
        if (master.location.isNotEmpty()) out += "LOCATION:${escapeText(master.location)}"
        if (master.url.isNotEmpty()) out += "URL:${master.url}"
        if (master.rruleRaw.isNotBlank()) out += "RRULE:${master.rruleRaw}"
        if (master.exdates.isNotEmpty()) out += dateListLine("EXDATE", master.allDay, master.exdates, master.tzid)
        if (master.rdates.isNotEmpty()) out += dateListLine("RDATE", master.allDay, master.rdates, master.tzid)
        master.valarms.forEach { appendAlarm(out, it, master) }
        out += "END:VEVENT"

        stored.exceptions.filter { !it.tombstone }.forEach { appendException(out, master, it, stamp) }
    }

    private fun appendException(
        out: MutableList<String>,
        master: EventMaster,
        exception: EventException,
        stamp: String,
    ) {
        val allDay = exception.allDay ?: master.allDay
        val tzid = exception.tzid.ifBlank { master.tzid }
        out += "BEGIN:VEVENT"
        out += "UID:${master.uid}"
        out += "DTSTAMP:$stamp"
        out += dateLine("RECURRENCE-ID", allDay, exception.recurrenceIdWall, tzid)
        out += dateLine("DTSTART", allDay, exception.dtstartWall ?: master.dtstartWall, tzid)
        out += dateLine("DTEND", allDay, exception.dtendWall ?: master.dtendWall, tzid)
        out += "SUMMARY:${escapeText(exception.title ?: master.title)}"
        if (exception.status == ExceptionStatus.CANCELLED) out += "STATUS:CANCELLED"
        (exception.description ?: master.description)
            .takeIf { it.isNotEmpty() }
            ?.let { out += "DESCRIPTION:${escapeText(it)}" }
        (exception.location ?: master.location)
            .takeIf { it.isNotEmpty() }
            ?.let { out += "LOCATION:${escapeText(it)}" }
        out += "END:VEVENT"
    }

    private fun appendAlarm(out: MutableList<String>, alarm: Valarm, master: EventMaster) {
        out += "BEGIN:VALARM"
        out += "ACTION:DISPLAY"
        out += "DESCRIPTION:${escapeText(master.title)}"
        out += if (alarm.triggerType == "absolute") {
            "TRIGGER;VALUE=DATE-TIME:${absoluteTrigger(master, alarm)}"
        } else {
            "TRIGGER:${duration(alarm.offsetSeconds)}"
        }
        out += "END:VALARM"
    }

    private fun absoluteTrigger(master: EventMaster, alarm: Valarm): String {
        val tzid = alarm.tzid.ifBlank { master.tzid }
        val start = TimeZones.toInstant(master.dtstartWall, tzid, ZoneOffset.UTC)
        return UTC.format(start.plusSeconds(alarm.offsetSeconds.toLong()).atOffset(ZoneOffset.UTC))
    }

    private fun duration(seconds: Int): String {
        val total = abs(seconds)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val rest = total % 60
        val body = buildString {
            append("PT")
            if (hours > 0) append(hours).append('H')
            if (minutes > 0) append(minutes).append('M')
            if (rest > 0 || (hours == 0 && minutes == 0)) append(rest).append('S')
        }
        return if (seconds < 0) "-$body" else body
    }

    private fun dateLine(name: String, allDay: Boolean, wall: String, tzid: String): String =
        if (allDay) {
            "$name;VALUE=DATE:${DATE.format(WallTime.parse(wall).toLocalDate())}"
        } else {
            "$name;TZID=$tzid:${WALL.format(WallTime.parse(wall))}"
        }

    private fun dateListLine(name: String, allDay: Boolean, walls: List<String>, tzid: String): String {
        val values = walls.joinToString(",") { wall ->
            if (allDay) {
                DATE.format(WallTime.parse(wall).toLocalDate())
            } else {
                WALL.format(WallTime.parse(wall))
            }
        }
        return if (allDay) "$name;VALUE=DATE:$values" else "$name;TZID=$tzid:$values"
    }

    private fun escapeText(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")

    /** Folds a content line at 75 octets without splitting a UTF-8 character. */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val sb = StringBuilder()
        var used = 0
        var limit = 75
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val char = String(Character.toChars(codePoint))
            val width = char.toByteArray(Charsets.UTF_8).size
            if (used + width > limit) {
                sb.append(CRLF).append(' ')
                used = 0
                limit = 74
            }
            sb.append(char)
            used += width
            index += Character.charCount(codePoint)
        }
        return sb.toString()
    }
}
