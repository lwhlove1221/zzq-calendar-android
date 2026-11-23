package com.zzq.calendar.importer

import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.Valarm
import com.zzq.calendar.model.WallTime
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

data class ParseFailure(val line: Int, val code: String, val message: String)

data class ParseFatal(val code: String, val message: String)

data class ParsedEvent(
    val line: Int,
    val hasUid: Boolean,
    val sourceText: String,
    val master: EventMaster,
    val exceptions: List<EventException>,
) {
    val fingerprint: String by lazy { FingerprintV1.sha256(master, exceptions) }
}

data class ParseResult(
    val events: List<ParsedEvent>,
    val failures: List<ParseFailure>,
    val warnings: List<String>,
    val fatal: ParseFatal? = null,
) {
    val total: Int get() = events.size + failures.size
}

/**
 * RFC 5545 reader for the fields PRD-02 stores: wall clock + TZID, RRULE raw,
 * EXDATE/RDATE, RECURRENCE-ID exceptions and VALARM.
 */
object IcsParser {

    /** PRD-03 §4: oversized files abort before anything is staged. */
    const val MAX_BYTES = 20 * 1024 * 1024

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DURATION: Regex =
        Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.size > MAX_BYTES) {
            return ParseResult(
                emptyList(),
                emptyList(),
                emptyList(),
                ParseFatal("E_PARSE", "文件过大（上限 20MB）"),
            )
        }
        val decoded = decode(bytes)
            ?: return ParseResult(
                emptyList(),
                emptyList(),
                emptyList(),
                ParseFatal("E_ENCODING", "无法识别文件编码（支持 UTF-8 / GBK）"),
            )
        return parseText(decoded.text, decoded.charset)
    }

    fun parseText(text: String, charset: String = "UTF-8"): ParseResult {
        val warnings = mutableListOf<String>()
        if (charset != "UTF-8") warnings += "文件编码按 $charset 读取"

        val physical = unfold(text)
        var sawCalendar = false
        val tzOffsets = mutableMapOf<String, String>()
        val blocks = mutableListOf<Pair<Int, List<ContentLine>>>()

        var index = 0
        while (index < physical.size) {
            val (lineNumber, raw) = physical[index]
            val property = parseContentLine(lineNumber, raw)
            if (property == null) {
                index++
                continue
            }
            if (property.name == "BEGIN") {
                when (property.value.trim().uppercase()) {
                    "VCALENDAR" -> {
                        sawCalendar = true
                        index++
                    }
                    "VTIMEZONE" -> {
                        val (block, next) = readBlock(physical, index + 1, "VTIMEZONE")
                        readTimezone(block)?.let { (tzid, offset) -> tzOffsets[tzid] = offset }
                        index = next
                    }
                    "VEVENT" -> {
                        val (block, next) = readBlock(physical, index + 1, "VEVENT")
                        blocks += lineNumber to block
                        index = next
                    }
                    else -> index++
                }
            } else {
                index++
            }
        }

        if (!sawCalendar) {
            return ParseResult(
                emptyList(),
                emptyList(),
                warnings,
                ParseFatal("E_PARSE", "不是有效的 iCalendar 文件（缺少 VCALENDAR）"),
            )
        }

        val failures = mutableListOf<ParseFailure>()
        val raws = mutableListOf<RawEvent>()
        blocks.forEach { (lineNumber, block) ->
            try {
                raws += parseRawEvent(lineNumber, block)
            } catch (invalid: InvalidEvent) {
                failures += ParseFailure(lineNumber, invalid.code, invalid.detail)
            }
        }

        val rawByUid = raws.filter { it.recurrenceId == null }.groupBy { it.uid }
        val masters = mutableListOf<RawEvent>()
        rawByUid.forEach { (uid, group) ->
            if (uid == null) {
                masters += group
            } else if (group.size > 1) {
                masters += group.first()
                group.drop(1).forEach {
                    failures += ParseFailure(it.line, "E_PARSE", "文件内 UID 重复：$uid")
                }
            } else {
                masters += group
            }
        }
        masters.sortBy { it.line }

        val exceptionsByUid = raws.filter { it.recurrenceId != null }.groupBy { it.uid }

        val events = masters.mapNotNull { raw ->
            val attached = if (raw.uid == null) emptyList() else exceptionsByUid[raw.uid].orEmpty()
            try {
                buildEvent(raw, attached, tzOffsets, warnings)
            } catch (invalid: InvalidEvent) {
                failures += ParseFailure(raw.line, invalid.code, invalid.detail)
                null
            }
        }

        val knownUids = masters.mapNotNull { it.uid }.toSet()
        raws.filter { it.recurrenceId != null && it.uid !in knownUids }.forEach {
            failures += ParseFailure(it.line, "E_PARSE", "RECURRENCE-ID 找不到同文件内的主事件")
        }

        failures.sortBy { it.line }
        return ParseResult(events, failures, warnings)
    }

    /** Re-parses one staged VEVENT block; staging only stores blocks that already parsed. */
    fun parseStaged(blockText: String): ParsedEvent {
        val result = parseText("BEGIN:VCALENDAR\r\n$blockText\r\nEND:VCALENDAR\r\n")
        return result.events.single()
    }

    data class Decoded(val charset: String, val text: String)

    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return strict(bytes, 3, Charsets.UTF_8)?.let { Decoded("UTF-8", it) }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return strict(bytes, 2, Charsets.UTF_16LE)?.let { Decoded("UTF-16LE", it) }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return strict(bytes, 2, Charsets.UTF_16BE)?.let { Decoded("UTF-16BE", it) }
        }
        strict(bytes, 0, Charsets.UTF_8)?.let { return Decoded("UTF-8", it) }
        // GBK is a subset of GB18030; strict decoding rejects invalid byte sequences.
        strict(bytes, 0, Charset.forName("GB18030"))?.let { return Decoded("GBK", it) }
        return null
    }

    private fun strict(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private data class ContentLine(
        val line: Int,
        val name: String,
        val params: Map<String, String>,
        val value: String,
        val raw: String,
    )

    private data class IcsDate(
        val allDay: Boolean,
        val value: LocalDateTime,
        val tzid: String?,
        val utc: Boolean,
    )

    private data class RawAlarm(val value: String, val params: Map<String, String>)

    private data class RawEvent(
        val line: Int,
        val uid: String?,
        val recurrenceId: IcsDate?,
        val rangeThisAndFuture: Boolean,
        val cancelled: Boolean,
        val summary: String?,
        val description: String?,
        val location: String?,
        val url: String?,
        val start: IcsDate?,
        val end: IcsDate?,
        val rrule: String?,
        val exdates: List<IcsDate>,
        val rdates: List<IcsDate>,
        val alarms: List<RawAlarm>,
        val sourceText: String,
    )

    private class InvalidEvent(val code: String, val detail: String) : Exception(detail)

    private fun unfold(text: String): List<Pair<Int, String>> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val result = mutableListOf<Pair<Int, String>>()
        normalized.split('\n').forEachIndexed { index, line ->
            if (line.startsWith(' ') || line.startsWith('\t')) {
                val last = result.removeLastOrNull()
                if (last != null) result += last.first to (last.second + line.substring(1))
            } else {
                result += (index + 1) to line
            }
        }
        return result
    }

    private fun parseContentLine(lineNumber: Int, raw: String): ContentLine? {
        if (raw.isBlank()) return null
        var quoted = false
        var colon = -1
        for (index in raw.indices) {
            val char = raw[index]
            when {
                char == '"' -> quoted = !quoted
                char == ':' && !quoted -> {
                    colon = index
                    break
                }
            }
        }
        if (colon <= 0) return null
        val segments = splitUnquoted(raw.substring(0, colon), ';')
        val name = segments.first().trim().uppercase()
        if (name.isEmpty()) return null
        val params = mutableMapOf<String, String>()
        segments.drop(1).forEach { segment ->
            val eq = segment.indexOf('=')
            if (eq > 0) {
                val key = segment.substring(0, eq).trim().uppercase()
                val value = segment.substring(eq + 1).trim().removeSurrounding("\"")
                params[key] = value
            }
        }
        return ContentLine(lineNumber, name, params, raw.substring(colon + 1), raw)
    }

    private fun splitUnquoted(value: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        value.forEach { char ->
            when {
                char == '"' -> {
                    quoted = !quoted
                    current.append(char)
                }
                char == separator && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
        }
        result += current.toString()
        return result
    }

    private fun readBlock(
        lines: List<Pair<Int, String>>,
        start: Int,
        endName: String,
    ): Pair<List<ContentLine>, Int> {
        val block = mutableListOf<ContentLine>()
        var index = start
        while (index < lines.size) {
            val (lineNumber, raw) = lines[index]
            val property = parseContentLine(lineNumber, raw)
            if (property != null && property.name == "END" && property.value.trim().uppercase() == endName) {
                return block to (index + 1)
            }
            property?.let { block += it }
            index++
        }
        return block to lines.size
    }

    private fun readTimezone(block: List<ContentLine>): Pair<String, String>? {
        val tzid = block.firstOrNull { it.name == "TZID" }?.value?.trim() ?: return null
        val offset = block.firstOrNull { it.name == "TZOFFSETTO" }?.value?.trim() ?: return null
        return tzid to offset
    }

    private fun parseRawEvent(line: Int, block: List<ContentLine>): RawEvent {
        var uid: String? = null
        var recurrenceId: IcsDate? = null
        var range = false
        var cancelled = false
        var summary: String? = null
        var description: String? = null
        var location: String? = null
        var url: String? = null
        var start: IcsDate? = null
        var end: IcsDate? = null
        var rrule: String? = null
        val exdates = mutableListOf<IcsDate>()
        val rdates = mutableListOf<IcsDate>()
        val alarms = mutableListOf<RawAlarm>()
        var inAlarm = false

        block.forEach { property ->
            if (property.name == "BEGIN" && property.value.trim().uppercase() == "VALARM") {
                inAlarm = true
                return@forEach
            }
            if (property.name == "END" && property.value.trim().uppercase() == "VALARM") {
                inAlarm = false
                return@forEach
            }
            if (inAlarm) {
                if (property.name == "TRIGGER") {
                    alarms += RawAlarm(property.value.trim(), property.params)
                }
                return@forEach
            }
            when (property.name) {
                "UID" -> uid = property.value.trim().ifEmpty { null }
                "SUMMARY" -> summary = unescapeText(property.value)
                "DESCRIPTION" -> description = unescapeText(property.value)
                "LOCATION" -> location = unescapeText(property.value)
                "URL" -> url = property.value.trim()
                "RRULE" -> rrule = property.value.trim().removePrefix("RRULE:")
                "DTSTART" -> start = parseDate(property, "DTSTART")
                "DTEND" -> end = parseDate(property, "DTEND")
                "RECURRENCE-ID" -> {
                    recurrenceId = parseDate(property, "RECURRENCE-ID")
                    range = property.params["RANGE"].equals("THISANDFUTURE", ignoreCase = true)
                }
                "STATUS" -> cancelled = property.value.trim().equals("CANCELLED", ignoreCase = true)
                "EXDATE" -> exdates += parseDateList(property, "EXDATE")
                "RDATE" -> rdates += parseDateList(property, "RDATE")
            }
        }

        return RawEvent(
            line = line,
            uid = uid,
            recurrenceId = recurrenceId,
            rangeThisAndFuture = range,
            cancelled = cancelled,
            summary = summary,
            description = description,
            location = location,
            url = url,
            start = start,
            end = end,
            rrule = rrule,
            exdates = exdates,
            rdates = rdates,
            alarms = alarms,
            sourceText = "BEGIN:VEVENT\r\n" + block.joinToString("\r\n") { it.raw } + "\r\nEND:VEVENT",
        )
    }

    private fun parseDate(property: ContentLine, label: String): IcsDate {
        val raw = property.value.trim()
        val valueType = property.params["VALUE"]?.uppercase()
        if (valueType == "DATE" || (raw.length == 8 && !raw.contains('T'))) {
            val date = try {
                LocalDate.parse(raw, DATE)
            } catch (_: DateTimeParseException) {
                throw InvalidEvent("E_PARSE", "$label 日期格式无法解析：$raw")
            }
            return IcsDate(
                allDay = true,
                value = date.atStartOfDay(),
                tzid = property.params["TZID"],
                utc = false,
            )
        }
        val utc = raw.endsWith("Z")
        val value = try {
            LocalDateTime.parse(if (utc) raw.dropLast(1) else raw, DATE_TIME)
        } catch (_: DateTimeParseException) {
            throw InvalidEvent("E_PARSE", "$label 时间格式无法解析：$raw")
        }
        return IcsDate(allDay = false, value = value, tzid = property.params["TZID"], utc = utc)
    }

    private fun parseDateList(property: ContentLine, label: String): List<IcsDate> {
        val valueType = property.params["VALUE"]?.uppercase()
        return property.value.split(',').mapNotNull { token ->
            val raw = token.trim()
            if (raw.isEmpty()) return@mapNotNull null
            val allDay = valueType == "DATE" || (raw.length == 8 && !raw.contains('T'))
            val utc = raw.endsWith("Z")
            try {
                if (allDay) {
                    IcsDate(true, LocalDate.parse(raw, DATE).atStartOfDay(), property.params["TZID"], false)
                } else {
                    IcsDate(
                        false,
                        LocalDateTime.parse(if (utc) raw.dropLast(1) else raw, DATE_TIME),
                        property.params["TZID"],
                        utc,
                    )
                }
            } catch (_: DateTimeParseException) {
                throw InvalidEvent("E_PARSE", "$label 时间格式无法解析：$raw")
            }
        }
    }

    private fun buildEvent(
        raw: RawEvent,
        attached: List<RawEvent>,
        tzOffsets: Map<String, String>,
        warnings: MutableList<String>,
    ): ParsedEvent {
        val start = raw.start ?: throw InvalidEvent("E_PARSE", "缺少 DTSTART")
        val displayTzid = resolveTzid(start.tzid, start.utc, start.allDay, tzOffsets, warnings)
        val startWall = toWall(start, displayTzid)
        val endRaw = raw.end
            ?: if (start.allDay) start.copy(value = start.value.plusDays(1)) else start
        val endWall = toWall(endRaw, displayTzid)
        if (!start.allDay && WallTime.parse(endWall).isBefore(WallTime.parse(startWall))) {
            throw InvalidEvent("E_PARSE", "结束时间早于开始时间")
        }
        if (start.allDay && !WallTime.parse(endWall).isAfter(WallTime.parse(startWall))) {
            throw InvalidEvent("E_PARSE", "全天事件结束日期需晚于开始日期")
        }

        val now = System.currentTimeMillis()
        val exceptions = attached
            .map { buildException(raw, it, displayTzid) }
            .distinctBy { it.recurrenceIdWall to it.tzid }
        val provisionalUid = raw.uid ?: ""
        val provisional = EventMaster(
            id = UUID.nameUUIDFromBytes("ics:$provisionalUid".toByteArray()).toString(),
            uid = provisionalUid,
            calendarId = DEFAULT_CALENDAR_ID,
            title = raw.summary.orEmpty(),
            description = raw.description.orEmpty(),
            location = raw.location.orEmpty(),
            url = raw.url.orEmpty(),
            allDay = start.allDay,
            dtstartWall = startWall,
            dtendWall = endWall,
            tzid = displayTzid,
            rruleRaw = raw.rrule.orEmpty(),
            exdates = raw.exdates.map { toWall(it, displayTzid) }.sorted(),
            rdates = raw.rdates.map { toWall(it, displayTzid) }.sorted(),
            valarms = raw.alarms.mapNotNull { alarm ->
                buildAlarm(alarm, startWall, endWall, displayTzid)
            },
            createdAt = now,
            updatedAt = now,
        )
        val uid = raw.uid ?: "no-uid-${FingerprintV1.sha256(provisional, exceptions).take(16)}@zzq.calendar"
        val master = provisional.copy(
            id = UUID.nameUUIDFromBytes("ics:$uid".toByteArray()).toString(),
            uid = uid,
        )
        if (raw.rangeThisAndFuture) {
            warnings += "第 ${raw.line} 行使用 RECURRENCE-ID;RANGE=THISANDFUTURE，按单次例外导入（未拆分系列）"
        }
        return ParsedEvent(
            line = raw.line,
            hasUid = raw.uid != null,
            sourceText = buildString {
                append(raw.sourceText)
                attached.forEach { append("\r\n").append(it.sourceText) }
            },
            master = master,
            exceptions = exceptions.map { it.copy(eventId = master.id) },
        )
    }

    private fun buildException(
        master: RawEvent,
        raw: RawEvent,
        displayTzid: String,
    ): EventException {
        val recurrenceId = raw.recurrenceId
            ?: throw InvalidEvent("E_PARSE", "例外缺少 RECURRENCE-ID")
        val masterId = UUID.nameUUIDFromBytes("ics:${master.uid}".toByteArray()).toString()
        val recurrenceWall = toWall(recurrenceId, displayTzid)
        val start = raw.start
        val tzid = when {
            start == null || start.allDay -> displayTzid
            start.utc -> "UTC"
            start.tzid.isNullOrBlank() -> displayTzid
            else -> resolveTzid(start.tzid, false, false, emptyMap(), mutableListOf())
        }
        val eventId = UUID.nameUUIDFromBytes("exc:$masterId:$recurrenceWall:$tzid".toByteArray()).toString()
        val startWall = raw.start?.let { toWall(it, displayTzid) }
        val endWall = raw.end?.let { toWall(it, displayTzid) }
        return EventException(
            id = eventId,
            eventId = masterId,
            recurrenceIdWall = recurrenceWall,
            tzid = tzid,
            status = if (raw.cancelled) ExceptionStatus.CANCELLED else ExceptionStatus.MODIFIED,
            title = raw.summary,
            allDay = raw.start?.allDay,
            dtstartWall = startWall,
            dtendWall = endWall,
            description = raw.description,
            location = raw.location,
        )
    }

    private fun buildAlarm(
        alarm: RawAlarm,
        startWall: String,
        endWall: String,
        tzid: String,
    ): Valarm? {
        val value = alarm.value
        if (value.isEmpty()) return null
        val zone = TimeZones.resolve(tzid) ?: ZoneOffset.UTC
        val start = WallTime.parse(startWall)
        if (alarm.params["VALUE"]?.uppercase() == "DATE-TIME") {
            val utc = value.endsWith("Z")
            val parsed = try {
                LocalDateTime.parse(if (utc) value.dropLast(1) else value, DATE_TIME)
            } catch (_: DateTimeParseException) {
                return null
            }
            val trigger = if (utc) parsed.toInstant(ZoneOffset.UTC) else parsed.atZone(zone).toInstant()
            val offset = Duration.between(start.atZone(zone).toInstant(), trigger).seconds
            return Valarm("absolute", offset.toInt(), tzid)
        }
        val seconds = parseDuration(value) ?: return null
        val relativeToEnd = alarm.params["RELATED"].equals("END", ignoreCase = true)
        val endDelta = if (relativeToEnd) {
            Duration.between(start, WallTime.parse(endWall)).seconds
        } else {
            0L
        }
        return Valarm("duration", (seconds + endDelta).toInt(), tzid)
    }

    private fun parseDuration(value: String): Long? {
        val match = DURATION.matchEntire(value.trim().uppercase()) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val weeks = match.groupValues[2].toLongOrNull() ?: 0
        val days = match.groupValues[3].toLongOrNull() ?: 0
        val hours = match.groupValues[4].toLongOrNull() ?: 0
        val minutes = match.groupValues[5].toLongOrNull() ?: 0
        val seconds = match.groupValues[6].toLongOrNull() ?: 0
        return sign * (weeks * 604_800 + days * 86_400 + hours * 3_600 + minutes * 60 + seconds)
    }

    private fun resolveTzid(
        tzid: String?,
        utc: Boolean,
        allDay: Boolean,
        tzOffsets: Map<String, String>,
        warnings: MutableList<String>,
    ): String {
        if (allDay) return ZoneId.systemDefault().id
        if (utc) return "UTC"
        val named = tzid?.trim()?.removeSurrounding("\"")
        if (named.isNullOrEmpty()) return ZoneId.systemDefault().id
        if (TimeZones.resolve(named) != null) return named
        val offset = tzOffsets[named]
        if (offset != null) {
            val id = offsetToZoneId(offset)
            warnings += "时区 $named 无法识别，按 UTC 偏移 $id 展示"
            return id
        }
        warnings += "时区 $named 缺少 VTIMEZONE，按 UTC 展示"
        return "UTC"
    }

    private fun offsetToZoneId(raw: String): String {
        val value = raw.trim()
        val sign = when {
            value.startsWith("+") -> 1
            value.startsWith("-") -> -1
            else -> 1
        }
        val digits = value.dropWhile { it == '+' || it == '-' }
        val hours = digits.take(2).toIntOrNull() ?: 0
        val minutes = digits.drop(2).take(2).toIntOrNull() ?: 0
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes).id
    }

    private fun toWall(date: IcsDate, targetTzid: String): String {
        if (date.allDay) return WallTime.format(date.value)
        val sourceZone = when {
            date.utc -> ZoneOffset.UTC
            date.tzid != null -> TimeZones.resolve(date.tzid)
            else -> null
        }
        val targetZone = TimeZones.resolve(targetTzid)
        if (sourceZone == null || targetZone == null || sourceZone == targetZone) {
            return WallTime.format(date.value)
        }
        return WallTime.format(date.value.atZone(sourceZone).withZoneSameInstant(targetZone).toLocalDateTime())
    }

    private fun unescapeText(value: String): String {
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> builder.append('\n')
                    '\\' -> builder.append('\\')
                    ',' -> builder.append(',')
                    ';' -> builder.append(';')
                    else -> builder.append(char).append(next)
                }
                index += 2
            } else {
                builder.append(char)
                index++
            }
        }
        return builder.toString()
    }

    private const val DEFAULT_CALENDAR_ID = "cal-local"
}
