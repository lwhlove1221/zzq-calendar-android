package com.zzq.calendar.importer

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object IcsFixtures {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DAY = LocalDate.of(2026, 11, 1)

    fun calendar(body: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//ZZQ Test//CN\r\n")
        append(body)
        append("END:VCALENDAR\r\n")
    }

    fun dayOf(index: Int): LocalDate = DAY.plusDays((index - 1).toLong())

    fun event(index: Int): String {
        val day = dayOf(index)
        val token = DATE.format(day)
        val next = DATE.format(day.plusDays(1))
        val uid = "ev-$index@test"
        return when {
            index == 20 -> buildString {
                append(eventHeader(uid, "事件 20", tzid = "America/New_York", token = token))
                append("RRULE:FREQ=WEEKLY;BYDAY=FR;COUNT=10\r\n")
                append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n")
                append("END:VEVENT\r\n")
                append(
                    "BEGIN:VEVENT\r\n" +
                        "UID:$uid\r\n" +
                        "RECURRENCE-ID;TZID=America/New_York:20261127T090000\r\n" +
                        "DTSTART;TZID=America/New_York:20261127T100000\r\n" +
                        "DTEND;TZID=America/New_York:20261127T110000\r\n" +
                        "SUMMARY:事件 20（改）\r\n" +
                        "END:VEVENT\r\n",
                )
                append(
                    "BEGIN:VEVENT\r\n" +
                        "UID:$uid\r\n" +
                        "RECURRENCE-ID;TZID=America/New_York:20261204T090000\r\n" +
                        "STATUS:CANCELLED\r\n" +
                        "SUMMARY:事件 20\r\n" +
                        "END:VEVENT\r\n",
                )
            }
            index % 4 == 0 -> buildString {
                append("BEGIN:VEVENT\r\nUID:$uid\r\n")
                append("DTSTART;VALUE=DATE:$token\r\n")
                append("DTEND;VALUE=DATE:$next\r\n")
                append("SUMMARY:事件 $index\r\n")
                append("END:VEVENT\r\n")
            }
            index % 4 == 1 -> buildString {
                append(eventHeader(uid, "事件 $index", tzid = "Asia/Shanghai", token = token))
                append("END:VEVENT\r\n")
            }
            index % 4 == 2 -> buildString {
                append(eventHeader(uid, "事件 $index", tzid = "America/New_York", token = token))
                append("RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=10\r\n")
                append("EXDATE;TZID=America/New_York:20261116T090000,20261123T090000\r\n")
                append("RDATE;TZID=America/New_York:20261221T090000\r\n")
                append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n")
                append("END:VEVENT\r\n")
            }
            else -> buildString {
                append(eventHeader(uid, "事件 $index", tzid = "Europe/Berlin", token = token))
                append("DESCRIPTION:备注 $index\\, 含逗号\\; 含分号\\n第二行\r\n")
                append("LOCATION:会议室 $index\r\n")
                append("URL:https://example.com/$index\r\n")
                append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;VALUE=DATE-TIME:20261115T083000Z\r\nEND:VALARM\r\n")
                append("END:VEVENT\r\n")
            }
        }
    }

    fun baseline(): String = calendar(buildString {
        (1..20).forEach { append(event(it)) }
    })

    /** [count] simple timed events; [broken] indexes render an unparsable DTSTART. */
    fun events(count: Int, broken: Set<Int> = emptySet()): String = calendar(buildString {
        (1..count).forEach { index ->
            append("BEGIN:VEVENT\r\n")
            append("UID:batch-$index@test\r\n")
            if (index in broken) {
                append("DTSTART:NOT-A-DATE\r\n")
            } else {
                append("DTSTART;TZID=Asia/Shanghai:20261125T090000\r\n")
            }
            append("DTEND;TZID=Asia/Shanghai:20261125T100000\r\n")
            append("SUMMARY:批量事件 $index\r\n")
            append("END:VEVENT\r\n")
        }
    })

    private fun eventHeader(uid: String, title: String, tzid: String, token: String): String =
        buildString {
            append("BEGIN:VEVENT\r\n")
            append("UID:$uid\r\n")
            append("DTSTART;TZID=$tzid:${token}T090000\r\n")
            append("DTEND;TZID=$tzid:${token}T100000\r\n")
            append("SUMMARY:$title\r\n")
        }
}
