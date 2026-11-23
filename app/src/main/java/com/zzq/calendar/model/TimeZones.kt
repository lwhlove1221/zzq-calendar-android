package com.zzq.calendar.model

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object WallTime {
    val WALL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun format(value: LocalDateTime): String = value.format(WALL_FORMATTER)

    fun parse(value: String): LocalDateTime = LocalDateTime.parse(value, WALL_FORMATTER)

    fun parseDate(value: String): LocalDate = LocalDate.parse(value)

    fun nowWall(zone: ZoneId): LocalDateTime = LocalDateTime.now(zone)

    fun startOfDayWall(date: LocalDate): String = format(date.atStartOfDay())

    fun endOfDayExclusiveWall(date: LocalDate): String = format(date.plusDays(1).atStartOfDay())
}

object TimeZones {
    fun resolve(tzid: String): ZoneId? = try {
        ZoneId.of(tzid)
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun zoneOrDevice(tzid: String, deviceZone: ZoneId): ZoneId = resolve(tzid) ?: deviceZone

    fun tzdataVersion(): String = try {
        val provider = Class.forName("java.time.zone.ZoneRulesProvider")
        val method = provider.getMethod("getVersions", String::class.java)
        @Suppress("UNCHECKED_CAST")
        val versions = method.invoke(null, "UTC") as java.util.NavigableMap<String, *>
        versions.lastKey().toString()
    } catch (_: RuntimeException) {
        "unknown"
    } catch (_: ReflectiveOperationException) {
        "unknown"
    }

    /**
     * Converts a wall-clock value to an absolute instant using the event zone.
     * A nonexistent local time (spring-forward gap) moves to the first valid minute after the gap;
     * an ambiguous local time (fall-back overlap) resolves to the first occurrence (DST side).
     * Both behaviours come from [LocalDateTime.atZone].
     */
    fun toInstant(wall: String, tzid: String, fallbackZone: ZoneId): Instant {
        val zone = resolve(tzid) ?: fallbackZone
        return WallTime.parse(wall).atZone(zone).toInstant()
    }

    fun toZoned(wall: String, tzid: String, fallbackZone: ZoneId): ZonedDateTime {
        val zone = resolve(tzid) ?: fallbackZone
        return WallTime.parse(wall).atZone(zone)
    }

    fun toInstant(date: LocalDate, tzid: String, fallbackZone: ZoneId): Instant {
        val zone = resolve(tzid) ?: fallbackZone
        return date.atStartOfDay(zone).toInstant()
    }

    fun offsetLabel(wall: String, tzid: String, fallbackZone: ZoneId): String {
        val zone = resolve(tzid) ?: ZoneOffset.UTC
        return try {
            WallTime.parse(wall).atZone(zone).offset.id
        } catch (_: RuntimeException) {
            fallbackZone.rules.getOffset(Instant.now()).id
        }
    }
}
