package com.zzq.calendar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.model.EventMaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SystemSyncTest {

    private lateinit var context: Context
    private lateinit var helper: CalendarDbHelper
    private lateinit var port: FakeSystemCalendarPort
    private var repositoryNow = 5_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        helper = CalendarDbHelper(context, DB_NAME)
        port = FakeSystemCalendarPort()
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun repository(): EventRepository =
        EventRepository(helper, Dispatchers.Unconfined, port) { repositoryNow }

    private fun store(): EventStore = EventStore(helper)

    @Test
    fun mirrorFollowsSystemChangesWithoutRemindersOrDuplicates() = runBlocking {
        val repository = repository()
        repeat(10) { index -> port.put(systemStored(index + 1L, "系统 $index", 1_000L + index)) }
        val first = repository.syncSystem() as SyncResult.Applied
        assertEquals(10, first.created)

        val mirrors = repository.loadAll().filter { it.master.owner == EventMaster.OWNER_SYSTEM }
        assertEquals(10, mirrors.size)
        assertTrue(mirrors.all { it.master.valarms.isEmpty() && !it.master.writeBack })

        val again = repository.syncSystem() as SyncResult.Applied
        assertEquals(0, again.created)
        assertEquals(0, again.updated)

        port.put(systemStored(1, "系统 0 改", 9_000L))
        val changed = repository.syncSystem() as SyncResult.Applied
        assertEquals(1, changed.updated)
        assertEquals(
            "系统 0 改",
            repository.loadAll().single { it.master.systemEventId == 1L }.master.title,
        )

        port.deleteSilently(1)
        val removed = repository.syncSystem() as SyncResult.Applied
        assertEquals(1, removed.removed)
        assertNull(repository.loadAll().firstOrNull { it.master.systemEventId == 1L })
    }

    @Test
    fun sourceResetSuspendsByRatioAndByBatchSize() = runBlocking {
        val repository = repository()
        repeat(100) { index -> port.put(systemStored(index + 1L, "系统 $index", 1_000L + index)) }
        repository.syncSystem()

        port.events.removeAll { it.master.systemEventId in 1L..31L }
        val ratio = repository.syncSystem()
        assertTrue(ratio is SyncResult.Suspended)
        assertEquals(100, repository.loadAll().count { it.master.owner == EventMaster.OWNER_SYSTEM })

        val forced = repository.syncSystem(force = true) as SyncResult.Applied
        assertEquals(31, forced.removed)
        assertEquals(69, repository.loadAll().count { it.master.owner == EventMaster.OWNER_SYSTEM })

        repeat(200) { index -> port.put(systemStored(index + 1L, "系统 $index", 2_000L + index)) }
        repository.syncSystem()
        assertEquals(200, repository.loadAll().count { it.master.owner == EventMaster.OWNER_SYSTEM })
        port.events.removeAll { (it.master.systemEventId ?: 0) in 1L..51L }
        assertTrue(repository.syncSystem() is SyncResult.Suspended)
    }

    @Test
    fun systemDeletionOfAppEventGoesToConflictAndIsNotRewritten() = runBlocking {
        val repository = repository()
        val created = repository.create(draft("App 事件"))
        assertNotNull(created.systemEventId)
        port.deleteSilently(created.systemEventId!!)

        val result = repository.syncSystem() as SyncResult.Applied
        assertEquals(1, result.conflicts)
        assertNotNull(repository.get(created.id))
        val conflict = repository.conflicts().single()
        assertEquals(created.systemEventId, conflict.systemEventId)
        assertTrue(conflict.detail.contains("24"))
        assertNull(repository.get(created.id)!!.master.systemEventId)
        assertEquals(1, port.created)
        assertEquals(0, port.events.size)

        val again = repository.syncSystem() as SyncResult.Applied
        assertEquals(0, again.conflicts)
        assertEquals(1, repository.conflicts().size)
    }

    @Test
    fun bothSidesChangedNewerSystemVersionWins() = runBlocking {
        val repository = repository()
        val created = repository.create(draft("原始"))
        port.put(systemStored(created.systemEventId!!, "系统改", 9_000L))

        val result = repository.syncSystem() as SyncResult.Applied
        assertEquals(1, result.conflicts)
        assertEquals("系统改", repository.get(created.id)!!.master.title)
        assertTrue(repository.conflicts().single().detail.contains("系统版本较新"))
    }

    @Test
    fun bothSidesChangedNewerAppVersionIsKept() = runBlocking {
        val repository = repository()
        val created = repository.create(draft("原始"))
        repositoryNow = 10_000L
        repository.update(created.copy(title = "本机改"))
        port.put(systemStored(created.systemEventId!!, "系统改", 9_000L))

        val result = repository.syncSystem() as SyncResult.Applied
        assertEquals(1, result.conflicts)
        assertEquals("本机改", repository.get(created.id)!!.master.title)
        assertTrue(repository.conflicts().single().detail.contains("本机版本较新"))
        assertEquals("本机改", port.events.single().master.title)
        assertEquals(0, (repository.syncSystem() as SyncResult.Applied).conflicts)
    }

    @Test
    fun appCreateWritesBackAndSecondSyncDoesNotDuplicate() = runBlocking {
        val repository = repository()
        val created = repository.create(draft("写回"))
        assertEquals(1, port.created)
        assertEquals(created.systemEventId, port.events.single().master.systemEventId)

        val result = repository.syncSystem() as SyncResult.Applied
        assertEquals(0, result.created)
        assertEquals(1, repository.loadAll().size)
        assertEquals(0, repository.conflicts().size)
    }

    @Test
    fun importedEventStaysLocalUntilWriteBackChosen() = runBlocking {
        val repository = repository()
        val imported = EventMaster(
            id = "imported-1",
            uid = "imported-1@zzq.calendar",
            calendarId = EventRepository.DEFAULT_CALENDAR_ID,
            title = "导入",
            allDay = false,
            dtstartWall = "2026-11-25T09:00:00",
            dtendWall = "2026-11-25T10:00:00",
            tzid = "Asia/Shanghai",
            owner = EventMaster.OWNER_IMPORTED,
            writeBack = false,
        )
        store().insert(imported)

        val localOnly = repository.update(
            imported.copy(title = "仅本机", owner = EventMaster.OWNER_APP, writeBack = false),
        )
        assertEquals(0, port.created)
        assertNull(localOnly.systemEventId)

        val written = repository.update(localOnly.copy(title = "写回", writeBack = true))
        assertEquals(1, port.created)
        assertNotNull(written.systemEventId)
        assertEquals(written.systemEventId, port.events.single().master.systemEventId)
    }

    @Test
    fun mirrorEditWritesThroughInsteadOfCopying() = runBlocking {
        val repository = repository()
        port.put(systemStored(1, "系统", 1_000L))
        repository.syncSystem()
        val mirror = repository.loadAll().single { it.master.owner == EventMaster.OWNER_SYSTEM }

        repository.update(mirror.master.copy(title = "写穿"))

        assertEquals("写穿", port.events.single().master.title)
        assertEquals("写穿", repository.get(mirror.master.id)!!.master.title)
        assertEquals(1, port.updated)
        assertEquals(1, repository.loadAll().size)
    }

    private fun draft(title: String) = EventDraft(
        title = title,
        allDay = false,
        startWall = "2026-11-25T09:00:00",
        endWall = "2026-11-25T10:00:00",
        tzid = "Asia/Shanghai",
    )

    companion object {
        private const val DB_NAME = "system-sync-test.db"
    }
}
