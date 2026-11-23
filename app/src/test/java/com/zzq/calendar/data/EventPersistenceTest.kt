package com.zzq.calendar.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.ExceptionStatus
import com.zzq.calendar.model.EventException
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.TimeZones
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.ZoneId
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EventPersistenceTest {

    private lateinit var context: Context
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val day: LocalDate = LocalDate.of(2026, 11, 23)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        com.zzq.calendar.AppGraph.resetForTest()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun newRepository(): Pair<CalendarDbHelper, EventRepository> {
        val helper = CalendarDbHelper(context, DB_NAME)
        return helper to EventRepository(helper, Dispatchers.Unconfined)
    }

    @Test
    fun createdEventSurvivesDatabaseReopen() = runBlocking {
        val (helper, repository) = newRepository()
        val master = repository.create(
            EventDraft(
                title = "周会",
                allDay = false,
                startWall = "2026-11-23T09:00:00",
                endWall = "2026-11-23T10:00:00",
                tzid = zone.id,
            ),
        )
        assertTrue(master.id.isNotEmpty())
        helper.close()

        val (reopened, reopenedRepository) = newRepository()
        val agenda = reopenedRepository.loadDay(day, zone)
        assertEquals(1, agenda.timed.size)
        assertEquals("周会", agenda.timed.single().title)
        assertEquals("2026-11-23T09:00:00", agenda.timed.single().startWall)
        val stored = reopenedRepository.get(master.id)
        assertNotNull(stored)
        assertEquals(64, stored!!.fingerprint.length)
        reopened.close()
    }

    @Test
    fun eventOnAnotherDayIsNotReturned() = runBlocking {
        val (helper, repository) = newRepository()
        repository.create(
            EventDraft(
                title = "明天",
                allDay = false,
                startWall = "2026-11-24T09:00:00",
                endWall = "2026-11-24T10:00:00",
                tzid = zone.id,
            ),
        )
        assertTrue(repository.loadDay(day, zone).isEmpty)
        assertEquals(1, repository.loadDay(day.plusDays(1), zone).timed.size)
        helper.close()
    }

    @Test
    fun recurringEventIsExpandedForTheDay() = runBlocking {
        val (helper, repository) = newRepository()
        repository.create(
            EventDraft(
                title = "每工作周会",
                allDay = false,
                startWall = "2026-11-02T09:00:00",
                endWall = "2026-11-02T10:00:00",
                tzid = zone.id,
                repeat = RepeatSpec(frequency = RRule.Freq.WEEKLY, count = 6),
            ),
        )
        assertEquals(1, repository.loadDay(day, zone).timed.size)
        assertEquals(1, repository.loadDay(day.plusWeeks(1), zone).timed.size)
        assertTrue(repository.loadDay(day.plusDays(2), zone).isEmpty)
        helper.close()
    }

    @Test
    fun exdateAndCancelledExceptionAreNotRendered() = runBlocking {
        val helper = CalendarDbHelper(context, DB_NAME)
        val store = EventStore(helper)
        val master = EventMaster(
            id = "event-1",
            uid = "uid-1",
            calendarId = EventRepository.DEFAULT_CALENDAR_ID,
            title = "周会",
            allDay = false,
            dtstartWall = "2026-11-02T09:00:00",
            dtendWall = "2026-11-02T10:00:00",
            tzid = zone.id,
            rruleRaw = "FREQ=WEEKLY;BYDAY=MO;COUNT=10",
            exdates = listOf("2026-11-16T09:00:00"),
        )
        val cancelled = EventException(
            id = "exception-1",
            eventId = master.id,
            recurrenceIdWall = "2026-11-30T09:00:00",
            tzid = zone.id,
            status = ExceptionStatus.CANCELLED,
        )
        store.insert(master, listOf(cancelled))

        val repository = EventRepository(helper, Dispatchers.Unconfined)
        assertTrue(repository.loadDay(LocalDate.of(2026, 11, 16), zone).isEmpty)
        assertTrue(repository.loadDay(LocalDate.of(2026, 11, 30), zone).isEmpty)
        assertEquals(1, repository.loadDay(day, zone).timed.size)
        helper.close()
    }

    @Test
    fun allDayEventIsReturnedInAllDayBucket() = runBlocking {
        val (helper, repository) = newRepository()
        repository.create(
            EventDraft(
                title = "出差",
                allDay = true,
                startWall = "2026-11-22T00:00:00",
                endWall = "2026-11-24T00:00:00",
                tzid = zone.id,
            ),
        )
        val agenda = repository.loadDay(day, zone)
        assertEquals(1, agenda.allDay.size)
        assertEquals(0, agenda.timed.size)
        assertEquals("出差", agenda.allDay.single().title)
        helper.close()
    }

    @Test
    fun updateBumpsRevisionAndRewritesFingerprint() = runBlocking {
        val (helper, repository) = newRepository()
        val created = repository.create(
            EventDraft(
                title = "原标题",
                allDay = false,
                startWall = "2026-11-23T09:00:00",
                endWall = "2026-11-23T10:00:00",
                tzid = zone.id,
            ),
        )
        val before = repository.fingerprintOf(created.id)
        val updated = repository.update(created.copy(title = "新标题"))
        val after = repository.fingerprintOf(created.id)
        assertEquals(2, updated.rev)
        assertFalse(before == after)
        assertEquals("新标题", repository.loadDay(day, zone).timed.single().title)
        helper.close()
    }

    @Test
    fun deleteRemovesEventFromDay() = runBlocking {
        val (helper, repository) = newRepository()
        val created = repository.create(
            EventDraft(
                title = "临时",
                allDay = false,
                startWall = "2026-11-23T15:00:00",
                endWall = "2026-11-23T16:00:00",
                tzid = zone.id,
            ),
        )
        assertTrue(repository.delete(created.id))
        assertTrue(repository.loadDay(day, zone).isEmpty)
        helper.close()
    }

    @Test
    fun storedWallClockAndTimezoneArePreserved() = runBlocking {
        val (helper, repository) = newRepository()
        val created = repository.create(
            EventDraft(
                title = "纽约站会",
                allDay = false,
                startWall = "2026-11-23T09:00:00",
                endWall = "2026-11-23T10:00:00",
                tzid = "America/New_York",
            ),
        )
        val stored = repository.get(created.id)!!.master
        assertEquals("2026-11-23T09:00:00", stored.dtstartWall)
        assertEquals("America/New_York", stored.tzid)
        assertEquals(
            java.time.Instant.parse("2026-11-23T14:00:00Z"),
            TimeZones.toInstant(stored.dtstartWall, stored.tzid, zone),
        )
        helper.close()
    }

    companion object {
        private const val DB_NAME = "calendar-test.db"
    }
}

