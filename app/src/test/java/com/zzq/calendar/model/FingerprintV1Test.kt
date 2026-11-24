package com.zzq.calendar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintV1Test {

    private fun master(
        dtstart: String,
        dtend: String,
        rrule: String = "",
        exdates: List<String> = emptyList(),
        rdates: List<String> = emptyList(),
        valarms: List<Valarm> = emptyList(),
        title: String = "周会",
    ) = EventMaster(
        id = "event-1",
        uid = "uid-1",
        calendarId = "cal-work",
        title = title,
        allDay = false,
        dtstartWall = dtstart,
        dtendWall = dtend,
        tzid = "Asia/Shanghai",
        rruleRaw = rrule,
        exdates = exdates,
        rdates = rdates,
        valarms = valarms,
    )

    @Test
    fun vector1_pureFields_matchesFrozenCanonicalBytesAndHash() {
        val event = master(
            dtstart = "2026-11-25T09:00:00",
            dtend = "2026-11-25T10:00:00",
            valarms = listOf(Valarm("duration", -600, "Asia/Shanghai")),
        )
        val expected = "{\"all_day\":false,\"calendar_id\":\"cal-work\"," +
            "\"dtend_wall\":\"2026-11-25T10:00:00\",\"dtstart_wall\":\"2026-11-25T09:00:00\"," +
            "\"exceptions\":[],\"exdate\":[],\"rdate\":[],\"rrule_raw\":\"\",\"title\":\"周会\"," +
            "\"tombstone\":false,\"tzid\":\"Asia/Shanghai\"," +
            "\"valarm\":[{\"offset\":-600,\"trigger_type\":\"duration\",\"tzid\":\"Asia/Shanghai\"}]}"
        assertEquals(expected, FingerprintV1.canonicalJson(event, emptyList()))
        assertEquals(
            "0e3f42c3ba9245e81e01ef3e0374d59679ac67cbf0c69e16bd69490d97456518",
            FingerprintV1.sha256(event, emptyList()),
        )
    }

    @Test
    fun vector2_rruleAndExdate_matchesFrozenCanonicalBytesAndHash() {
        val event = master(
            dtstart = "2026-11-02T09:00:00",
            dtend = "2026-11-30T10:00:00",
            rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            exdates = listOf("2026-11-16T09:00:00"),
            valarms = listOf(Valarm("duration", -300, "Asia/Shanghai")),
        )
        val expected = "{\"all_day\":false,\"calendar_id\":\"cal-work\"," +
            "\"dtend_wall\":\"2026-11-30T10:00:00\",\"dtstart_wall\":\"2026-11-02T09:00:00\"," +
            "\"exceptions\":[],\"exdate\":[\"2026-11-16T09:00:00\"],\"rdate\":[]," +
            "\"rrule_raw\":\"FREQ=WEEKLY;BYDAY=MO;COUNT=10\",\"title\":\"周会\",\"tombstone\":false," +
            "\"tzid\":\"Asia/Shanghai\",\"valarm\":[{\"offset\":-300,\"trigger_type\":\"duration\"," +
            "\"tzid\":\"Asia/Shanghai\"}]}"
        assertEquals(expected, FingerprintV1.canonicalJson(event, emptyList()))
        assertEquals(
            "fd5f379afdaa17a7749eeb1db19fd6b0c529066c804abf65c2ffdaec7caf57bf",
            FingerprintV1.sha256(event, emptyList()),
        )
    }

    @Test
    fun vector3_instanceExceptionAndMultipleValarms_matchesFrozenCanonicalBytesAndHash() {
        val event = master(
            dtstart = "2026-12-07T09:00:00",
            dtend = "2026-12-07T10:00:00",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            valarms = listOf(
                Valarm("duration", -600, "Asia/Shanghai"),
                Valarm("absolute", 0, "Asia/Shanghai"),
            ),
        )
        val exception = EventException(
            id = "exception-1",
            eventId = "event-1",
            recurrenceIdWall = "2026-12-07T09:00:00",
            tzid = "Asia/Shanghai",
            status = ExceptionStatus.MODIFIED,
            title = "周会（改）",
        )
        val expected = "{\"all_day\":false,\"calendar_id\":\"cal-work\"," +
            "\"dtend_wall\":\"2026-12-07T10:00:00\",\"dtstart_wall\":\"2026-12-07T09:00:00\"," +
            "\"exceptions\":[{\"recurrence_id_wall\":\"2026-12-07T09:00:00\"," +
            "\"status\":\"modified\",\"title\":\"周会（改）\",\"tzid\":\"Asia/Shanghai\"}]," +
            "\"exdate\":[],\"rdate\":[],\"rrule_raw\":\"FREQ=WEEKLY;BYDAY=MO\",\"title\":\"周会\"," +
            "\"tombstone\":false,\"tzid\":\"Asia/Shanghai\",\"valarm\":[" +
            "{\"offset\":-600,\"trigger_type\":\"duration\",\"tzid\":\"Asia/Shanghai\"}," +
            "{\"offset\":0,\"trigger_type\":\"absolute\",\"tzid\":\"Asia/Shanghai\"}]}"
        assertEquals(expected, FingerprintV1.canonicalJson(event, listOf(exception)))
        assertEquals(
            "bcd5e845df7b4a924afe227819a7b7ceea1935e5c969549634b29b4967203b39",
            FingerprintV1.sha256(event, listOf(exception)),
        )
    }

    @Test
    fun vector4_instanceExceptionTimeChange_matchesFrozenCanonicalBytesAndHash() {
        val event = master(
            dtstart = "2026-12-07T09:00:00",
            dtend = "2026-12-07T10:00:00",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            valarms = listOf(Valarm("duration", -600, "Asia/Shanghai")),
        )
        val exception = EventException(
            id = "exception-1",
            eventId = "event-1",
            recurrenceIdWall = "2026-12-07T09:00:00",
            tzid = "Asia/Shanghai",
            status = ExceptionStatus.MODIFIED,
            title = "周会",
            allDay = false,
            dtstartWall = "2026-12-07T10:00:00",
            dtendWall = "2026-12-07T11:00:00",
        )
        val expected = "{\"all_day\":false,\"calendar_id\":\"cal-work\"," +
            "\"dtend_wall\":\"2026-12-07T10:00:00\",\"dtstart_wall\":\"2026-12-07T09:00:00\"," +
            "\"exceptions\":[{\"all_day\":false,\"dtend_wall\":\"2026-12-07T11:00:00\"," +
            "\"dtstart_wall\":\"2026-12-07T10:00:00\",\"recurrence_id_wall\":\"2026-12-07T09:00:00\"," +
            "\"status\":\"modified\",\"title\":\"周会\",\"tzid\":\"Asia/Shanghai\"}]," +
            "\"exdate\":[],\"rdate\":[],\"rrule_raw\":\"FREQ=WEEKLY;BYDAY=MO\",\"title\":\"周会\"," +
            "\"tombstone\":false,\"tzid\":\"Asia/Shanghai\",\"valarm\":[" +
            "{\"offset\":-600,\"trigger_type\":\"duration\",\"tzid\":\"Asia/Shanghai\"}]}"
        assertEquals(expected, FingerprintV1.canonicalJson(event, listOf(exception)))
        assertEquals(
            "af42e311adc236c8629dfd3c0ebd6092d07936bf00fd84259d58475763977a42",
            FingerprintV1.sha256(event, listOf(exception)),
        )
    }

    @Test
    fun allDayEventIgnoresDisplayTimezoneInFingerprint() {
        fun allDayEvent(tzid: String) = EventMaster(
            id = "event-1",
            uid = "uid-1",
            calendarId = "cal-work",
            title = "年会",
            allDay = true,
            dtstartWall = "2026-12-07T00:00:00",
            dtendWall = "2026-12-08T00:00:00",
            tzid = tzid,
        )
        fun allDayException(tzid: String) = EventException(
            id = "exception-1",
            eventId = "event-1",
            recurrenceIdWall = "2026-12-07T00:00:00",
            tzid = tzid,
            status = ExceptionStatus.MODIFIED,
            title = "年会（改）",
            allDay = true,
        )
        val shanghai = allDayEvent("Asia/Shanghai")
        val newYork = allDayEvent("America/New_York")
        val shanghaiJson = FingerprintV1.canonicalJson(shanghai, listOf(allDayException("Asia/Shanghai")))
        val newYorkJson = FingerprintV1.canonicalJson(newYork, listOf(allDayException("America/New_York")))
        assertTrue(shanghaiJson.contains("\"tzid\":\"\""))
        assertFalse(shanghaiJson.contains("Asia/Shanghai"))
        assertFalse(newYorkJson.contains("America/New_York"))
        assertEquals(shanghaiJson, newYorkJson)
        assertEquals(
            FingerprintV1.sha256(shanghai, listOf(allDayException("Asia/Shanghai"))),
            FingerprintV1.sha256(newYork, listOf(allDayException("America/New_York"))),
        )
    }

    @Test
    fun instanceTimeChangeAltersFingerprint() {
        val event = master(
            dtstart = "2026-12-07T09:00:00",
            dtend = "2026-12-07T10:00:00",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )
        val titleOnly = EventException(
            id = "exception-1",
            eventId = "event-1",
            recurrenceIdWall = "2026-12-07T09:00:00",
            tzid = "Asia/Shanghai",
            status = ExceptionStatus.MODIFIED,
            title = "周会",
        )
        val rescheduled = titleOnly.copy(
            allDay = false,
            dtstartWall = "2026-12-07T10:00:00",
            dtendWall = "2026-12-07T11:00:00",
        )
        val before = FingerprintV1.sha256(event, listOf(titleOnly))
        val after = FingerprintV1.sha256(event, listOf(rescheduled))
        assertNotEquals(before, after)
        assertTrue(FingerprintV1.canonicalJson(event, listOf(rescheduled)).contains("\"dtstart_wall\":\"2026-12-07T10:00:00\""))
    }

    @Test
    fun exceptionSerializesOnlyModifiedFields() {
        val event = master(
            dtstart = "2026-12-07T09:00:00",
            dtend = "2026-12-07T10:00:00",
            rrule = "FREQ=WEEKLY;BYDAY=MO",
        )
        val untouched = EventException(
            id = "exception-1",
            eventId = "event-1",
            recurrenceIdWall = "2026-12-07T09:00:00",
            tzid = "Asia/Shanghai",
            status = ExceptionStatus.MODIFIED,
        )
        val json = FingerprintV1.canonicalJson(event, listOf(untouched))
        val exceptionPart = json.substringAfter("\"exceptions\":[").substringBefore("],\"exdate\"")
        assertEquals(
            "{\"recurrence_id_wall\":\"2026-12-07T09:00:00\",\"status\":\"modified\",\"tzid\":\"Asia/Shanghai\"}",
            exceptionPart,
        )
        assertFalse(exceptionPart.contains("\"title\""))
        assertFalse(exceptionPart.contains("\"dtstart_wall\""))
        assertFalse(exceptionPart.contains("\"description\""))
        assertFalse(exceptionPart.contains("\"location\""))
    }

    @Test
    fun collectionsAreSortedBeforeHashing() {
        val a = master(
            dtstart = "2026-11-02T09:00:00",
            dtend = "2026-11-02T10:00:00",
            exdates = listOf("2026-11-16T09:00:00", "2026-11-09T09:00:00"),
            valarms = listOf(
                Valarm("duration", -300, "Asia/Shanghai"),
                Valarm("duration", -600, "Asia/Shanghai"),
            ),
        )
        val b = a.copy(
            exdates = a.exdates.reversed(),
            valarms = a.valarms.reversed(),
        )
        assertEquals(
            FingerprintV1.canonicalJson(a, emptyList()),
            FingerprintV1.canonicalJson(b, emptyList()),
        )
        assertEquals(FingerprintV1.sha256(a, emptyList()), FingerprintV1.sha256(b, emptyList()))
    }

    @Test
    fun valarmSortsByOffsetThenTriggerTypeThenTzid() {
        val event = master(
            dtstart = "2026-11-02T09:00:00",
            dtend = "2026-11-02T10:00:00",
            valarms = listOf(
                Valarm("duration", -300, "Asia/Shanghai"),
                Valarm("absolute", -300, "Asia/Shanghai"),
                Valarm("absolute", -600, "Asia/Shanghai"),
            ),
        )
        val json = FingerprintV1.canonicalJson(event, emptyList())
        val valarm = json.substringAfter("\"valarm\":[").substringBefore("]}")
        assertEquals(
            "{\"offset\":-600,\"trigger_type\":\"absolute\",\"tzid\":\"Asia/Shanghai\"}," +
                "{\"offset\":-300,\"trigger_type\":\"absolute\",\"tzid\":\"Asia/Shanghai\"}," +
                "{\"offset\":-300,\"trigger_type\":\"duration\",\"tzid\":\"Asia/Shanghai\"}",
            valarm,
        )
    }

    @Test
    fun escapingIsStableForControlCharactersAndQuotes() {
        val event = master(
            dtstart = "2026-11-02T09:00:00",
            dtend = "2026-11-02T10:00:00",
            title = "a\"b\\c\n\t",
        )
        val json = FingerprintV1.canonicalJson(event, emptyList())
        assert(json.contains("\"title\":\"a\\\"b\\\\c\\n\\t\""))
        assertEquals(64, FingerprintV1.sha256(event, emptyList()).length)
    }
}
