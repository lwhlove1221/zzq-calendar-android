package com.zzq.calendar.importer

import com.zzq.calendar.model.ExceptionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsParserTest {

    @Test
    fun `twenty baseline events keep their fields`() {
        val result = IcsParser.parse(IcsFixtures.baseline().toByteArray())

        assertNull(result.fatal)
        assertTrue(result.failures.toString(), result.failures.isEmpty())
        assertEquals(20, result.events.size)

        (1..19).forEach { index ->
            val event = result.events.first { it.master.uid == "ev-$index@test" }
            val master = event.master
            assertEquals("事件 $index", master.title)
            assertEquals(true, event.hasUid)
            when (index % 4) {
                0 -> {
                    assertTrue(master.allDay)
                    assertEquals("${IcsFixtures.dayOf(index)}T00:00:00", master.dtstartWall)
                    assertEquals("${IcsFixtures.dayOf(index).plusDays(1)}T00:00:00", master.dtendWall)
                }
                1 -> {
                    assertFalse(master.allDay)
                    assertEquals("Asia/Shanghai", master.tzid)
                    assertEquals("${IcsFixtures.dayOf(index)}T09:00:00", master.dtstartWall)
                    assertEquals("${IcsFixtures.dayOf(index)}T10:00:00", master.dtendWall)
                }
                2 -> {
                    assertEquals("America/New_York", master.tzid)
                    assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=10", master.rruleRaw)
                    assertEquals(listOf("2026-11-16T09:00:00", "2026-11-23T09:00:00"), master.exdates)
                    assertEquals(listOf("2026-12-21T09:00:00"), master.rdates)
                    assertEquals(1, master.valarms.size)
                    assertEquals(-600, master.valarms.first().offsetSeconds)
                    assertEquals("duration", master.valarms.first().triggerType)
                    assertEquals("America/New_York", master.valarms.first().tzid)
                }
                else -> {
                    assertEquals("Europe/Berlin", master.tzid)
                    assertEquals("备注 $index, 含逗号; 含分号\n第二行", master.description)
                    assertEquals("会议室 $index", master.location)
                    assertEquals("https://example.com/$index", master.url)
                    assertEquals(1, master.valarms.size)
                    assertEquals("absolute", master.valarms.first().triggerType)
                }
            }
        }
    }

    @Test
    fun `series exceptions attach to their master`() {
        val series = IcsParser.parse(IcsFixtures.baseline().toByteArray())
            .events
            .first { it.master.uid == "ev-20@test" }

        assertEquals("事件 20", series.master.title)
        assertEquals(2, series.exceptions.size)
        val modified = series.exceptions.first { it.status == ExceptionStatus.MODIFIED }
        assertEquals("2026-11-27T09:00:00", modified.recurrenceIdWall)
        assertEquals("事件 20（改）", modified.title)
        assertEquals("2026-11-27T10:00:00", modified.dtstartWall)
        assertEquals("America/New_York", modified.tzid)
        val cancelled = series.exceptions.first { it.status == ExceptionStatus.CANCELLED }
        assertEquals("2026-12-04T09:00:00", cancelled.recurrenceIdWall)
    }

    @Test
    fun `gbk encoded file is decoded`() {
        val text = IcsFixtures.calendar(
            "BEGIN:VEVENT\r\nUID:gbk@test\r\nDTSTART;TZID=Asia/Shanghai:20261125T090000\r\n" +
                "DTEND;TZID=Asia/Shanghai:20261125T100000\r\nSUMMARY:中文标题\r\nEND:VEVENT\r\n",
        )
        val bytes = text.toByteArray(charset("GB18030"))

        val result = IcsParser.parse(bytes)

        assertNull(result.fatal)
        assertEquals("中文标题", result.events.single().master.title)
        assertTrue(result.warnings.any { it.contains("GBK") })
    }

    @Test
    fun `unreadable bytes are a fatal encoding error`() {
        val result = IcsParser.parse(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x81.toByte()))

        assertNotNull(result.fatal)
        assertEquals("E_ENCODING", result.fatal?.code)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `missing vtimezone falls back to the utc offset`() {
        val text = IcsFixtures.calendar(
            "BEGIN:VTIMEZONE\r\nTZID:Custom/Zone\r\nBEGIN:STANDARD\r\nTZOFFSETTO:+0530\r\n" +
                "TZOFFSETFROM:+0530\r\nDTSTART:19700101T000000\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n" +
                "BEGIN:VEVENT\r\nUID:tz@test\r\nDTSTART;TZID=Custom/Zone:20261125T090000\r\n" +
                "DTEND;TZID=Custom/Zone:20261125T100000\r\nSUMMARY:自定义时区\r\nEND:VEVENT\r\n",
        )

        val result = IcsParser.parse(text.toByteArray())

        val master = result.events.single().master
        assertEquals("+05:30", master.tzid)
        assertTrue(result.warnings.any { it.contains("Custom/Zone") })
    }

    @Test
    fun `broken event reports its line and does not block the rest`() {
        val text = IcsFixtures.calendar(
            "BEGIN:VEVENT\r\nUID:ok@test\r\nDTSTART;TZID=Asia/Shanghai:20261125T090000\r\n" +
                "DTEND;TZID=Asia/Shanghai:20261125T100000\r\nSUMMARY:正常\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:bad@test\r\nDTSTART:NOT-A-DATE\r\nSUMMARY:坏事件\r\nEND:VEVENT\r\n",
        )

        val result = IcsParser.parse(text.toByteArray())

        assertEquals(1, result.events.size)
        val failure = result.failures.single()
        assertEquals("E_PARSE", failure.code)
        assertTrue(failure.message.contains("DTSTART"))
        assertEquals(10, failure.line)
    }

    @Test
    fun `event without uid gets a deterministic synthetic uid`() {
        val text = IcsFixtures.calendar(
            "BEGIN:VEVENT\r\nDTSTART;TZID=Asia/Shanghai:20261125T090000\r\n" +
                "DTEND;TZID=Asia/Shanghai:20261125T100000\r\nSUMMARY:无 UID\r\nEND:VEVENT\r\n",
        )

        val first = IcsParser.parse(text.toByteArray()).events.single()
        val second = IcsParser.parse(text.toByteArray()).events.single()

        assertFalse(first.hasUid)
        assertTrue(first.master.uid.startsWith("no-uid-"))
        assertEquals(first.master.uid, second.master.uid)
        assertEquals(first.fingerprint, second.fingerprint)
    }
}
