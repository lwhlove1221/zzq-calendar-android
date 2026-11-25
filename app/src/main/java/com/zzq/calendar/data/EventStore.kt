package com.zzq.calendar.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.FingerprintV1
import com.zzq.calendar.model.Valarm

data class StoredEvent(
    val master: EventMaster,
    val exceptions: List<EventException>,
    val fingerprint: String,
)

data class FingerprintIndex(
    val byUid: Map<String, Set<String>>,
    val all: Set<String>,
)

data class StagingRow(
    val line: Int,
    val payload: String,
)

data class SyncConflict(
    val id: String,
    val systemEventId: Long?,
    val title: String,
    val detail: String,
    val createdAt: Long,
)

class EventStore(private val helper: CalendarDbHelper) {

    fun insert(master: EventMaster, exceptions: List<EventException> = emptyList()): StoredEvent =
        helper.writableDatabase.inTransaction { db -> insertWithin(db, master, exceptions) }

    /** Inserts one event inside the caller's transaction; import commits a whole batch with it. */
    fun insertWithin(
        db: SQLiteDatabase,
        master: EventMaster,
        exceptions: List<EventException> = emptyList(),
    ): StoredEvent {
        val fingerprint = FingerprintV1.sha256(master, exceptions)
        db.insertOrThrow(TABLE_EVENTS, null, eventValues(master, fingerprint))
        exceptions.forEach { db.insertOrThrow(TABLE_EXCEPTIONS, null, exceptionValues(it)) }
        return StoredEvent(master, exceptions, fingerprint)
    }

    fun update(master: EventMaster, exceptions: List<EventException>): StoredEvent {
        val fingerprint = FingerprintV1.sha256(master, exceptions)
        helper.writableDatabase.inTransaction { db ->
            db.update(
                TABLE_EVENTS,
                eventValues(master, fingerprint).apply { remove("id") },
                "id = ?",
                arrayOf(master.id),
            )
            db.delete(TABLE_EXCEPTIONS, "event_id = ?", arrayOf(master.id))
            exceptions.forEach { db.insertOrThrow(TABLE_EXCEPTIONS, null, exceptionValues(it)) }
        }
        return StoredEvent(master, exceptions, fingerprint)
    }

    fun delete(eventId: String): Boolean =
        helper.writableDatabase.delete(TABLE_EVENTS, "id = ?", arrayOf(eventId)) > 0

    fun findById(eventId: String): StoredEvent? {
        val db = helper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_EVENTS WHERE id = ?", arrayOf(eventId))
        cursor.use {
            if (!it.moveToFirst()) return null
            val master = readMaster(it)
            return StoredEvent(master, loadExceptions(db, master.id), it.getString(it.getColumnIndexOrThrow("fingerprint")))
        }
    }

    fun loadAll(): List<StoredEvent> {
        val db = helper.readableDatabase
        val result = mutableListOf<StoredEvent>()
        db.rawQuery("SELECT * FROM $TABLE_EVENTS WHERE tombstone = 0 ORDER BY all_day DESC, dtstart_wall ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val master = readMaster(cursor)
                result += StoredEvent(
                    master = master,
                    exceptions = loadExceptions(db, master.id),
                    fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
                )
            }
        }
        return result
    }

    fun fingerprintIndex(): FingerprintIndex {
        val byUid = mutableMapOf<String, MutableSet<String>>()
        val all = mutableSetOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT uid, fingerprint FROM $TABLE_EVENTS WHERE tombstone = 0",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = cursor.getString(0)
                val fingerprint = cursor.getString(1)
                byUid.getOrPut(uid) { mutableSetOf() } += fingerprint
                all += fingerprint
            }
        }
        return FingerprintIndex(byUid, all)
    }

    fun stageBatch(batchId: String, rows: List<StagingRow>) {
        helper.writableDatabase.inTransaction { db ->
            db.delete(TABLE_STAGING, "batch_id = ?", arrayOf(batchId))
            rows.forEachIndexed { index, row ->
                db.insertOrThrow(
                    TABLE_STAGING,
                    null,
                    ContentValues().apply {
                        put("batch_id", batchId)
                        put("seq", index)
                        put("line", row.line)
                        put("payload", row.payload)
                    },
                )
            }
        }
    }

    fun readStaging(batchId: String): List<StagingRow> {
        val rows = mutableListOf<StagingRow>()
        helper.readableDatabase.rawQuery(
            "SELECT line, payload FROM $TABLE_STAGING WHERE batch_id = ? ORDER BY seq",
            arrayOf(batchId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += StagingRow(cursor.getInt(0), cursor.getString(1))
            }
        }
        return rows
    }

    fun deleteStaging(db: SQLiteDatabase, batchId: String) {
        db.delete(TABLE_STAGING, "batch_id = ?", arrayOf(batchId))
    }

    /** Called at startup: an interrupted import leaves staging behind and must never reach `events`. */
    fun clearStaging() {
        helper.writableDatabase.inTransaction { db -> db.delete(TABLE_STAGING, null, null) }
    }

    fun hasStaging(): Boolean =
        helper.readableDatabase.rawQuery("SELECT 1 FROM $TABLE_STAGING LIMIT 1", null).use { it.moveToFirst() }

    fun upsertConflict(conflict: SyncConflict) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_CONFLICTS,
            null,
            ContentValues().apply {
                put("id", conflict.id)
                put("system_event_id", conflict.systemEventId)
                put("title", conflict.title)
                put("detail", conflict.detail)
                put("created_at", conflict.createdAt)
            },
            // Keep the first detection time so the 24h retention actually expires.
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun conflicts(): List<SyncConflict> {
        val result = mutableListOf<SyncConflict>()
        helper.readableDatabase.rawQuery(
            "SELECT id, system_event_id, title, detail, created_at FROM $TABLE_CONFLICTS ORDER BY created_at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SyncConflict(
                    id = cursor.getString(0),
                    systemEventId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    title = cursor.getString(2),
                    detail = cursor.getString(3),
                    createdAt = cursor.getLong(4),
                )
            }
        }
        return result
    }

    fun purgeConflictsBefore(cutoff: Long) {
        helper.writableDatabase.delete(TABLE_CONFLICTS, "created_at < ?", arrayOf(cutoff.toString()))
    }

    private fun loadExceptions(db: SQLiteDatabase, eventId: String): List<EventException> {
        val result = mutableListOf<EventException>()
        db.rawQuery(
            "SELECT * FROM $TABLE_EXCEPTIONS WHERE event_id = ? AND tombstone = 0 ORDER BY recurrence_id_wall ASC",
            arrayOf(eventId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += readException(cursor)
            }
        }
        return result
    }

    private fun readMaster(cursor: Cursor): EventMaster = EventMaster(
        id = cursor.getStringRequired("id"),
        uid = cursor.getStringRequired("uid"),
        calendarId = cursor.getStringRequired("calendar_id"),
        title = cursor.getStringRequired("title"),
        description = cursor.getStringRequired("description"),
        location = cursor.getStringRequired("location"),
        url = cursor.getStringRequired("url"),
        allDay = cursor.getIntRequired("all_day") != 0,
        dtstartWall = cursor.getStringRequired("dtstart_wall"),
        dtendWall = cursor.getStringRequired("dtend_wall"),
        tzid = cursor.getStringRequired("tzid"),
        rruleRaw = cursor.getStringRequired("rrule_raw"),
        exdates = decodeList(cursor.getStringRequired("exdate_list")),
        rdates = decodeList(cursor.getStringRequired("rdate_list")),
        valarms = decodeValarms(cursor.getStringRequired("valarm_list")),
        tombstone = cursor.getIntRequired("tombstone") != 0,
        rev = cursor.getIntRequired("rev"),
        tzdataVersion = cursor.getStringRequired("tzdata_version"),
        sourceType = cursor.getStringRequired("source_type"),
        sourceId = cursor.getStringRequired("source_id"),
        owner = cursor.getStringRequired("owner"),
        writeBack = cursor.getIntRequired("write_back") != 0,
        systemEventId = cursor.getLongOrNull("system_event_id"),
        systemCalendarId = cursor.getLongOrNull("system_calendar_id"),
        lastSyncedModified = cursor.getLongOrNull("last_synced_modified"),
        createdAt = cursor.getLongRequired("created_at"),
        updatedAt = cursor.getLongRequired("updated_at"),
    )

    private fun readException(cursor: Cursor): EventException = EventException(
        id = cursor.getStringRequired("id"),
        eventId = cursor.getStringRequired("event_id"),
        recurrenceIdWall = cursor.getStringRequired("recurrence_id_wall"),
        tzid = cursor.getStringRequired("tzid"),
        status = ExceptionStatus.fromWire(cursor.getStringRequired("status")),
        title = cursor.getStringOrNull("title"),
        allDay = cursor.getIntOrNull("all_day")?.let { it != 0 },
        dtstartWall = cursor.getStringOrNull("dtstart_wall"),
        dtendWall = cursor.getStringOrNull("dtend_wall"),
        description = cursor.getStringOrNull("description"),
        location = cursor.getStringOrNull("location"),
        tombstone = cursor.getIntRequired("tombstone") != 0,
    )

    private fun eventValues(master: EventMaster, fingerprint: String): ContentValues = ContentValues().apply {
        put("id", master.id)
        put("uid", master.uid)
        put("calendar_id", master.calendarId)
        put("title", master.title)
        put("description", master.description)
        put("location", master.location)
        put("url", master.url)
        put("all_day", if (master.allDay) 1 else 0)
        put("dtstart_wall", master.dtstartWall)
        put("dtend_wall", master.dtendWall)
        put("tzid", master.tzid)
        put("rrule_raw", master.rruleRaw)
        put("exdate_list", encodeList(master.exdates))
        put("rdate_list", encodeList(master.rdates))
        put("valarm_list", encodeValarms(master.valarms))
        put("tombstone", if (master.tombstone) 1 else 0)
        put("rev", master.rev)
        put("fingerprint", fingerprint)
        put("tzdata_version", master.tzdataVersion)
        put("source_type", master.sourceType)
        put("source_id", master.sourceId)
        put("owner", master.owner)
        put("write_back", if (master.writeBack) 1 else 0)
        put("system_event_id", master.systemEventId)
        put("system_calendar_id", master.systemCalendarId)
        put("last_synced_modified", master.lastSyncedModified)
        put("created_at", master.createdAt)
        put("updated_at", master.updatedAt)
    }

    private fun exceptionValues(exception: EventException): ContentValues = ContentValues().apply {
        put("id", exception.id)
        put("event_id", exception.eventId)
        put("recurrence_id_wall", exception.recurrenceIdWall)
        put("tzid", exception.tzid)
        put("status", exception.status.wireValue)
        put("title", exception.title)
        put("all_day", exception.allDay?.let { if (it) 1 else 0 })
        put("dtstart_wall", exception.dtstartWall)
        put("dtend_wall", exception.dtendWall)
        put("description", exception.description)
        put("location", exception.location)
        put("tombstone", if (exception.tombstone) 1 else 0)
    }

    private fun encodeList(values: List<String>): String =
        values.map { it.replace("\n", " ") }.joinToString("\n")

    private fun decodeList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("\n").filter { it.isNotEmpty() }

    private fun encodeValarms(alarms: List<Valarm>): String =
        alarms.joinToString("\n") { "${it.triggerType},${it.offsetSeconds},${it.tzid}" }

    private fun decodeValarms(value: String): List<Valarm> =
        if (value.isEmpty()) {
            emptyList()
        } else {
            value.split("\n").mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size != 3) return@mapNotNull null
                val offset = parts[1].toIntOrNull() ?: return@mapNotNull null
                Valarm(parts[0], offset, parts[2])
            }
        }

    private inline fun <T> SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> T): T {
        beginTransaction()
        try {
            val result = block(this)
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.getStringRequired(column: String): String =
        getString(getColumnIndexOrThrow(column)) ?: ""

    private fun Cursor.getStringOrNull(column: String): String? =
        getColumnIndex(column).let { index -> if (index < 0 || isNull(index)) null else getString(index) }

    private fun Cursor.getIntRequired(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun Cursor.getIntOrNull(column: String): Int? =
        getColumnIndex(column).let { index -> if (index < 0 || isNull(index)) null else getInt(index) }

    private fun Cursor.getLongRequired(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.getLongOrNull(column: String): Long? =
        getColumnIndex(column).let { index -> if (index < 0 || isNull(index)) null else getLong(index) }

    companion object {
        private const val TABLE_EVENTS = "events"
        private const val TABLE_EXCEPTIONS = "event_exceptions"
        private const val TABLE_STAGING = "import_staging"
        private const val TABLE_CONFLICTS = "sync_conflicts"
    }
}
