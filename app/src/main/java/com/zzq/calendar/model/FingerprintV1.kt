package com.zzq.calendar.model

import java.security.MessageDigest
import java.util.TreeMap

object FingerprintV1 {

    const val VERSION = "v1"

    fun canonicalJson(master: EventMaster, exceptions: List<EventException>): String {
        val fields = TreeMap<String, String>()
        fields["all_day"] = master.allDay.toString()
        fields["calendar_id"] = quote(master.calendarId)
        fields["dtend_wall"] = quote(master.dtendWall)
        fields["dtstart_wall"] = quote(master.dtstartWall)
        fields["exceptions"] = exceptions
            .sortedWith(compareBy({ it.recurrenceIdWall }, { it.tzid }))
            .joinToString(prefix = "[", postfix = "]", separator = ",") {
                exceptionJson(it, normalizeAllDayTzid = master.allDay)
            }
        fields["exdate"] = master.exdates.sorted().joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }
        fields["rdate"] = master.rdates.sorted().joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }
        fields["rrule_raw"] = quote(master.rruleRaw)
        fields["title"] = quote(master.title)
        fields["tombstone"] = master.tombstone.toString()
        fields["tzid"] = quote(if (master.allDay) "" else master.tzid)
        fields["valarm"] = master.valarms
            .sortedWith(compareBy({ it.offsetSeconds }, { it.triggerType }, { it.tzid }))
            .joinToString(prefix = "[", postfix = "]", separator = ",") { alarmJson(it) }
        return fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${quote(key)}:$value"
        }
    }

    fun sha256(master: EventMaster, exceptions: List<EventException>): String {
        val bytes = canonicalJson(master, exceptions).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun exceptionJson(exception: EventException, normalizeAllDayTzid: Boolean): String {
        val fields = TreeMap<String, String>()
        fields["recurrence_id_wall"] = quote(exception.recurrenceIdWall)
        fields["status"] = quote(exception.status.wireValue)
        fields["tzid"] = quote(if (normalizeAllDayTzid) "" else exception.tzid)
        exception.title?.let { fields["title"] = quote(it) }
        exception.description?.let { fields["description"] = quote(it) }
        exception.location?.let { fields["location"] = quote(it) }
        val timeChanged = exception.dtstartWall != null || exception.dtendWall != null
        if (timeChanged) {
            fields["all_day"] = (exception.allDay ?: false).toString()
            fields["dtend_wall"] = quote(exception.dtendWall ?: "")
            fields["dtstart_wall"] = quote(exception.dtstartWall ?: "")
        } else {
            exception.allDay?.let { fields["all_day"] = it.toString() }
        }
        return objectJson(fields)
    }

    private fun alarmJson(alarm: Valarm): String {
        val fields = TreeMap<String, String>()
        fields["offset"] = alarm.offsetSeconds.toString()
        fields["trigger_type"] = quote(alarm.triggerType)
        fields["tzid"] = quote(alarm.tzid)
        return objectJson(fields)
    }

    private fun objectJson(fields: Map<String, String>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${quote(key)}:$value"
        }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                else -> {
                    if (char.code < 0x20) {
                        builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
