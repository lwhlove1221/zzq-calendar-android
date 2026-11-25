package com.zzq.calendar.export

import com.zzq.calendar.data.StoredEvent
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.FingerprintV1
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorExportTest {

    private val now = Instant.parse("2026-11-25T03:00:00Z")

    @Test
    fun icsAndJsonBackupSkipMirrors() {
        val app = stored("app-1", "自有", EventMaster.OWNER_APP)
        val mirror = stored("sys-1", "镜像", EventMaster.OWNER_SYSTEM)

        val ics = IcsExporter.export(listOf(app, mirror), now)
        assertTrue(ics.contains("UID:app-1@test"))
        assertFalse(ics.contains("UID:sys-1@test"))

        val json = JsonBackup.build(listOf(app, mirror), now)
        assertTrue(json.contains("app-1@test"))
        assertFalse(json.contains("sys-1@test"))
    }

    private fun stored(id: String, title: String, owner: String): StoredEvent {
        val master = EventMaster(
            id = id,
            uid = "$id@test",
            calendarId = if (owner == EventMaster.OWNER_SYSTEM) "system-1" else "cal-local",
            title = title,
            allDay = false,
            dtstartWall = "2026-11-25T09:00:00",
            dtendWall = "2026-11-25T10:00:00",
            tzid = "Asia/Shanghai",
            owner = owner,
            writeBack = owner == EventMaster.OWNER_APP,
            systemEventId = if (owner == EventMaster.OWNER_SYSTEM) 1L else null,
        )
        return StoredEvent(master, emptyList(), FingerprintV1.sha256(master, emptyList()))
    }
}
