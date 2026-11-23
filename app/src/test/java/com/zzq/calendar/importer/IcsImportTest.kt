package com.zzq.calendar.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.data.CalendarDbHelper
import com.zzq.calendar.data.EventRepository
import com.zzq.calendar.data.EventStore
import com.zzq.calendar.data.StagingRow
import com.zzq.calendar.model.ExceptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class IcsImportTest {

    private lateinit var context: Context
    private lateinit var helper: CalendarDbHelper
    private lateinit var repository: EventRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        helper = CalendarDbHelper(context, DB_NAME)
        repository = EventRepository(helper, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `baseline import keeps fields and a re-import only skips`() = runBlocking {
        val bytes = IcsFixtures.baseline().toByteArray()

        val first = repository.importIcs(bytes)

        assertEquals(ImportStatus.SUCCEEDED, first.status)
        assertEquals(20, first.created)
        val stored = repository.loadAll()
        assertEquals(20, stored.size)
        val series = stored.first { it.master.uid == "ev-20@test" }
        assertEquals(2, series.exceptions.size)
        assertEquals(
            ExceptionStatus.MODIFIED,
            series.exceptions.first { it.recurrenceIdWall == "2026-11-27T09:00:00" }.status,
        )
        val recurring = stored.first { it.master.uid == "ev-2@test" }
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=10", recurring.master.rruleRaw)
        assertEquals(2, recurring.master.exdates.size)
        assertEquals(-600, recurring.master.valarms.single().offsetSeconds)

        val second = repository.importIcs(bytes)

        assertEquals(0, second.created)
        assertEquals(20, second.skipped)
        assertEquals(20, repository.loadAll().size)
    }

    @Test
    fun `four percent failures import partially and keep line numbers`() = runBlocking {
        val outcome = repository.importIcs(IcsFixtures.events(25, setOf(7)).toByteArray())

        assertEquals(ImportStatus.PARTIAL, outcome.status)
        assertEquals(24, outcome.created)
        assertEquals(1, outcome.failed)
        assertTrue(outcome.failures.single().line > 0)
        assertEquals(24, repository.loadAll().size)
    }

    @Test
    fun `five percent failures still import partially`() = runBlocking {
        val outcome = repository.importIcs(IcsFixtures.events(20, setOf(7)).toByteArray())

        assertEquals(ImportStatus.PARTIAL, outcome.status)
        assertEquals(19, outcome.created)
        assertEquals(19, repository.loadAll().size)
    }

    @Test
    fun `six percent failures roll the whole batch back`() = runBlocking {
        val bytes = IcsFixtures.events(100, setOf(3, 7, 11, 15, 19, 23)).toByteArray()
        assertNotNull(repository.previewImport(bytes).fatal)

        val outcome = repository.importIcs(bytes)

        assertEquals(ImportStatus.FAILED, outcome.status)
        assertEquals(0, outcome.created)
        assertEquals(6, outcome.failed)
        assertEquals(0, repository.loadAll().size)
    }

    @Test
    fun `same uid with different content is a conflict and is not overwritten`() = runBlocking {
        val original = IcsFixtures.calendar(
            "BEGIN:VEVENT\r\nUID:conflict@test\r\nDTSTART;TZID=Asia/Shanghai:20261125T090000\r\n" +
                "DTEND;TZID=Asia/Shanghai:20261125T100000\r\nSUMMARY:原内容\r\nEND:VEVENT\r\n",
        )
        val changed = IcsFixtures.calendar(
            "BEGIN:VEVENT\r\nUID:conflict@test\r\nDTSTART;TZID=Asia/Shanghai:20261125T090000\r\n" +
                "DTEND;TZID=Asia/Shanghai:20261125T100000\r\nSUMMARY:新内容\r\nEND:VEVENT\r\n",
        )
        repository.importIcs(original.toByteArray())

        val preview = repository.previewImport(changed.toByteArray())
        val outcome = repository.importIcs(changed.toByteArray())

        assertEquals(1, preview.conflicts.size)
        assertEquals(0, outcome.created)
        assertEquals(1, outcome.conflicts)
        assertEquals("原内容", repository.loadAll().single().master.title)
    }

    @Test
    fun `encoding error and oversized files write nothing`() = runBlocking {
        val badEncoding = repository.importIcs(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00))
        assertEquals(ImportStatus.FAILED, badEncoding.status)
        assertEquals("E_ENCODING", badEncoding.fatal?.code)

        val oversized = repository.importIcs(ByteArray(IcsParser.MAX_BYTES + 1))
        assertEquals(ImportStatus.FAILED, oversized.status)

        assertEquals(0, repository.loadAll().size)
    }

    @Test
    fun `staged batch survives nothing after restart cleanup`() = runBlocking {
        val parsed = IcsParser.parse(IcsFixtures.events(2).toByteArray()).events
        val store = EventStore(helper)
        store.stageBatch("batch-interrupted", parsed.map { StagingRow(it.line, it.sourceText) })

        assertTrue(store.hasStaging())
        assertEquals(0, repository.loadAll().size)

        assertTrue(repository.discardStaging())
        assertFalse(store.hasStaging())
        assertEquals(0, repository.loadAll().size)
    }

    @Test
    fun `one thousand events import within twenty seconds`() = runBlocking {
        val bytes = IcsFixtures.events(1_000).toByteArray()

        val startedAt = System.nanoTime()
        val outcome = repository.importIcs(bytes)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        println("1000 条 .ics 导入实测：${elapsedMs}ms")

        assertEquals(ImportStatus.SUCCEEDED, outcome.status)
        assertEquals(1_000, outcome.created)
        assertEquals(1_000, repository.loadAll().size)
        assertTrue("1000 条导入耗时 ${elapsedMs}ms", elapsedMs < 20_000)
    }

    private companion object {
        const val DB_NAME = "import-test.db"
    }
}
