package com.zzq.calendar.model

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RRuleExpanderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun master(
        rrule: String,
        dtstart: String,
        dtend: String = defaultEnd(dtstart),
        exdates: List<String> = emptyList(),
        rdates: List<String> = emptyList(),
        tzid: String = "Asia/Shanghai",
    ) = EventMaster(
        id = "e1",
        uid = "u1",
        calendarId = "cal-work",
        title = "事件",
        allDay = false,
        dtstartWall = dtstart,
        dtendWall = dtend,
        tzid = tzid,
        rruleRaw = rrule,
        exdates = exdates,
        rdates = rdates,
    )

    private fun defaultEnd(dtstart: String): String =
        WallTime.format(WallTime.parse(dtstart).plusHours(1))

    private fun expand(
        rrule: String,
        dtstart: String,
        windowEnd: String = "2040-01-01T00:00:00",
        tzid: String = "Asia/Shanghai",
        exdates: List<String> = emptyList(),
        rdates: List<String> = emptyList(),
    ): RRuleExpander.Result {
        val event = master(rrule, dtstart, exdates = exdates, rdates = rdates, tzid = tzid)
        val start = startOfDay(dtstart)
        return RRuleExpander.expand(event, start, WallTime.parse(windowEnd), zone)
    }

    private fun startOfDay(wall: String): LocalDateTime = WallTime.parse(wall)

    private fun assertStarts(
        rrule: String,
        dtstart: String,
        expected: List<String>,
        windowEnd: String = "2040-01-01T00:00:00",
        tzid: String = "Asia/Shanghai",
        exdates: List<String> = emptyList(),
        rdates: List<String> = emptyList(),
    ) {
        val result = expand(rrule, dtstart, windowEnd, tzid, exdates, rdates)
        assertEquals(rrule, expected, result.starts.map { WallTime.format(it) })
    }

    @Test
    fun case01_dailyCount() = assertStarts(
        "FREQ=DAILY;COUNT=5",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-03T09:00:00",
            "2026-11-04T09:00:00",
            "2026-11-05T09:00:00",
            "2026-11-06T09:00:00",
        ),
    )

    @Test
    fun case02_dailyInterval() = assertStarts(
        "FREQ=DAILY;INTERVAL=2;COUNT=4",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-04T09:00:00",
            "2026-11-06T09:00:00",
            "2026-11-08T09:00:00",
        ),
    )

    @Test
    fun case03_dailyUntilAbsoluteUtc() = assertStarts(
        "FREQ=DAILY;UNTIL=20261110T090000Z",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-03T09:00:00",
            "2026-11-04T09:00:00",
            "2026-11-05T09:00:00",
            "2026-11-06T09:00:00",
            "2026-11-07T09:00:00",
            "2026-11-08T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-10T09:00:00",
        ),
    )

    @Test
    fun case04_dailyByDayFilter() = assertStarts(
        "FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR;COUNT=8",
        "2026-11-06T09:00:00",
        listOf(
            "2026-11-06T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-10T09:00:00",
            "2026-11-11T09:00:00",
            "2026-11-12T09:00:00",
            "2026-11-13T09:00:00",
            "2026-11-16T09:00:00",
            "2026-11-17T09:00:00",
        ),
    )

    @Test
    fun case05_weeklyByDay() = assertStarts(
        "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-04T09:00:00",
            "2026-11-06T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-11T09:00:00",
            "2026-11-13T09:00:00",
        ),
    )

    @Test
    fun case06_weeklyInterval() = assertStarts(
        "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=6",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-06T09:00:00",
            "2026-11-16T09:00:00",
            "2026-11-20T09:00:00",
            "2026-11-30T09:00:00",
            "2026-12-04T09:00:00",
        ),
    )

    @Test
    fun case07_weeklyWkstSunday() = assertStarts(
        "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=SU;COUNT=5",
        "2026-11-07T09:00:00",
        listOf(
            "2026-11-15T09:00:00",
            "2026-11-16T09:00:00",
            "2026-11-29T09:00:00",
            "2026-11-30T09:00:00",
            "2026-12-13T09:00:00",
        ),
    )

    @Test
    fun case08_weeklyWkstMonday() = assertStarts(
        "FREQ=WEEKLY;INTERVAL=2;BYDAY=SU,MO;WKST=MO;COUNT=5",
        "2026-11-07T09:00:00",
        listOf(
            "2026-11-08T09:00:00",
            "2026-11-16T09:00:00",
            "2026-11-22T09:00:00",
            "2026-11-30T09:00:00",
            "2026-12-06T09:00:00",
        ),
    )

    @Test
    fun case09_weeklyUntil() = assertStarts(
        "FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261201T000000Z",
        "2026-11-03T09:00:00",
        listOf(
            "2026-11-03T09:00:00",
            "2026-11-05T09:00:00",
            "2026-11-10T09:00:00",
            "2026-11-12T09:00:00",
            "2026-11-17T09:00:00",
            "2026-11-19T09:00:00",
            "2026-11-24T09:00:00",
            "2026-11-26T09:00:00",
        ),
    )

    @Test
    fun case10_monthlyByMonthDay() = assertStarts(
        "FREQ=MONTHLY;BYMONTHDAY=15;COUNT=4",
        "2027-01-15T09:00:00",
        listOf(
            "2027-01-15T09:00:00",
            "2027-02-15T09:00:00",
            "2027-03-15T09:00:00",
            "2027-04-15T09:00:00",
        ),
    )

    @Test
    fun case11_monthlyLastDay() = assertStarts(
        "FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=4",
        "2027-01-31T09:00:00",
        listOf(
            "2027-01-31T09:00:00",
            "2027-02-28T09:00:00",
            "2027-03-31T09:00:00",
            "2027-04-30T09:00:00",
        ),
    )

    @Test
    fun case12_monthlyMissingDayIsSkipped() = assertStarts(
        "FREQ=MONTHLY;BYMONTHDAY=31;COUNT=4",
        "2027-01-31T09:00:00",
        listOf(
            "2027-01-31T09:00:00",
            "2027-03-31T09:00:00",
            "2027-05-31T09:00:00",
            "2027-07-31T09:00:00",
        ),
    )

    @Test
    fun case13_monthlySecondTuesday() = assertStarts(
        "FREQ=MONTHLY;BYDAY=2TU;COUNT=4",
        "2027-01-12T09:00:00",
        listOf(
            "2027-01-12T09:00:00",
            "2027-02-09T09:00:00",
            "2027-03-09T09:00:00",
            "2027-04-13T09:00:00",
        ),
    )

    @Test
    fun case14_monthlyLastFriday() = assertStarts(
        "FREQ=MONTHLY;BYDAY=-1FR;COUNT=4",
        "2027-01-29T09:00:00",
        listOf(
            "2027-01-29T09:00:00",
            "2027-02-26T09:00:00",
            "2027-03-26T09:00:00",
            "2027-04-30T09:00:00",
        ),
    )

    @Test
    fun case15_monthlyByDayAndByMonthDayIntersection() = assertStarts(
        "FREQ=MONTHLY;BYDAY=MO,WE;BYMONTHDAY=1,15;COUNT=6",
        "2027-02-01T09:00:00",
        listOf(
            "2027-02-01T09:00:00",
            "2027-02-15T09:00:00",
            "2027-03-01T09:00:00",
            "2027-03-15T09:00:00",
            "2027-09-01T09:00:00",
            "2027-09-15T09:00:00",
        ),
    )

    @Test
    fun case16_yearlyLeapDaySkipsCommonYears() = assertStarts(
        "FREQ=YEARLY;COUNT=3",
        "2024-02-29T09:00:00",
        listOf(
            "2024-02-29T09:00:00",
            "2028-02-29T09:00:00",
            "2032-02-29T09:00:00",
        ),
    )

    @Test
    fun case17_yearlyByMonthDay() = assertStarts(
        "FREQ=YEARLY;COUNT=3",
        "2027-06-01T09:00:00",
        listOf(
            "2027-06-01T09:00:00",
            "2028-06-01T09:00:00",
            "2029-06-01T09:00:00",
        ),
    )

    @Test
    fun case18_countOne() = assertStarts(
        "FREQ=DAILY;COUNT=1",
        "2026-11-02T09:00:00",
        listOf("2026-11-02T09:00:00"),
    )

    @Test
    fun case19_untilEqualsDtstart() = assertStarts(
        "FREQ=DAILY;UNTIL=20261102T090000Z",
        "2026-11-02T09:00:00",
        listOf("2026-11-02T09:00:00"),
    )

    @Test
    fun case20_weeklyByDay() = assertStarts(
        "FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=6",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-04T09:00:00",
            "2026-11-06T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-11T09:00:00",
            "2026-11-13T09:00:00",
        ),
    )

    @Test
    fun exdateRemovesInstanceButKeepsCount() = assertStarts(
        "FREQ=WEEKLY;BYDAY=MO;COUNT=6",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-23T09:00:00",
            "2026-11-30T09:00:00",
            "2026-12-07T09:00:00",
        ),
        exdates = listOf("2026-11-16T09:00:00"),
    )

    @Test
    fun rdateAddsExtraInstance() = assertStarts(
        "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
        "2026-11-02T09:00:00",
        listOf(
            "2026-11-02T09:00:00",
            "2026-11-09T09:00:00",
            "2026-11-16T09:00:00",
            "2026-11-19T15:00:00",
        ),
        rdates = listOf("2026-11-19T15:00:00"),
    )

    @Test
    fun unsupportedParameterIsPreservedAndFlaggedReadOnly() {
        val rule = RRule.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1")
        assertEquals("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1", rule.raw)
        assertTrue(rule.isReadOnly)
        assertTrue(rule.unsupportedParts.contains("BYSETPOS"))
    }

    @Test
    fun infiniteRuleIsBoundedByWindow() {
        val event = master("FREQ=DAILY", "2020-01-01T09:00:00")
        val result = RRuleExpander.expand(
            event,
            WallTime.parse("2026-11-01T00:00:00"),
            WallTime.parse("2026-11-30T00:00:00"),
            zone,
        )
        assertEquals("2026-11-01T09:00:00", WallTime.format(result.starts.first()))
        assertEquals("2026-11-29T09:00:00", WallTime.format(result.starts.last()))
        assertFalse(result.truncated)
    }

    @Test
    fun yearlyByMonthDayUsesDtstartMonth() = assertStarts(
        "FREQ=YEARLY;BYMONTHDAY=1;COUNT=3",
        "2027-06-01T09:00:00",
        listOf(
            "2027-06-01T09:00:00",
            "2028-06-01T09:00:00",
            "2029-06-01T09:00:00",
        ),
    )

    @Test
    fun emptyRuleExpandsToSingleStart() {
        val result = expand("", "2026-11-02T09:00:00", windowEnd = "2026-12-01T00:00:00")
        assertEquals(listOf("2026-11-02T09:00:00"), result.starts.map { WallTime.format(it) })
    }

    @Test
    fun windowExcludesInstancesOutsideRange() {
        val event = master("FREQ=DAILY", "2026-11-02T09:00:00", dtend = "2026-11-02T10:00:00")
        val result = RRuleExpander.expand(
            event,
            WallTime.parse("2026-11-10T00:00:00"),
            WallTime.parse("2026-11-11T00:00:00"),
            zone,
        )
        assertEquals(listOf("2026-11-10T09:00:00"), result.starts.map { WallTime.format(it) })
    }

    @Test
    fun countWinsOverUntilWhenBothPresent() {
        val raw = "FREQ=DAILY;COUNT=3;UNTIL=20261102T090000Z"
        val result = expand(raw, "2026-11-02T09:00:00", windowEnd = "2026-12-01T00:00:00")
        assertEquals(
            listOf(
                "2026-11-02T09:00:00",
                "2026-11-03T09:00:00",
                "2026-11-04T09:00:00",
            ),
            result.starts.map { WallTime.format(it) },
        )
        assertTrue(result.warnings.any { it.contains("COUNT") && it.contains("UNTIL") })

        val rule = RRule.parse(raw)
        assertEquals(raw, rule.raw)
        assertEquals(3, rule.count)
        assertNull(rule.until)
    }
}
