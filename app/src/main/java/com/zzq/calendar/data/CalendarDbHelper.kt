package com.zzq.calendar.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class CalendarDbHelper(
    context: Context,
    databaseName: String = DEFAULT_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                id TEXT PRIMARY KEY NOT NULL,
                uid TEXT NOT NULL,
                calendar_id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                all_day INTEGER NOT NULL,
                dtstart_wall TEXT NOT NULL,
                dtend_wall TEXT NOT NULL,
                tzid TEXT NOT NULL,
                rrule_raw TEXT NOT NULL DEFAULT '',
                exdate_list TEXT NOT NULL DEFAULT '',
                rdate_list TEXT NOT NULL DEFAULT '',
                valarm_list TEXT NOT NULL DEFAULT '',
                tombstone INTEGER NOT NULL DEFAULT 0,
                rev INTEGER NOT NULL DEFAULT 1,
                fingerprint TEXT NOT NULL,
                tzdata_version TEXT NOT NULL,
                source_type TEXT NOT NULL DEFAULT 'local',
                source_id TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_events_uid ON events(uid)")
        db.execSQL("CREATE INDEX idx_events_start ON events(dtstart_wall)")
        db.execSQL(
            """
            CREATE TABLE event_exceptions (
                id TEXT PRIMARY KEY NOT NULL,
                event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                recurrence_id_wall TEXT NOT NULL,
                tzid TEXT NOT NULL,
                status TEXT NOT NULL,
                title TEXT,
                all_day INTEGER,
                dtstart_wall TEXT,
                dtend_wall TEXT,
                description TEXT,
                location TEXT,
                tombstone INTEGER NOT NULL DEFAULT 0,
                UNIQUE(event_id, recurrence_id_wall, tzid)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_exceptions_event ON event_exceptions(event_id)")
        createStaging(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createStaging(db)
    }

    /** Parse results live here until the single commit transaction moves them into `events`. */
    private fun createStaging(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_staging (
                batch_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                line INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY(batch_id, seq)
            )
            """.trimIndent(),
        )
    }

    companion object {
        const val DEFAULT_NAME = "calendar.db"
        const val VERSION = 2
    }
}
