package com.zzq.calendar

import android.content.Context
import com.zzq.calendar.data.AndroidSystemCalendarPort
import com.zzq.calendar.data.CalendarDbHelper
import com.zzq.calendar.data.EventRepository
import com.zzq.calendar.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object AppGraph {

    @Volatile
    private var helper: CalendarDbHelper? = null

    @Volatile
    private var repository: EventRepository? = null

    @Volatile
    private var reminderScheduler: ReminderScheduler? = null

    @Volatile
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @Volatile
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    @Volatile
    var databaseName: String = CalendarDbHelper.DEFAULT_NAME

    fun repository(context: Context): EventRepository {
        val current = repository
        if (current != null) return current
        return synchronized(this) {
            val existing = repository
            if (existing != null) {
                existing
            } else {
                val dbHelper = helper ?: CalendarDbHelper(context, databaseName).also { helper = it }
                EventRepository(
                    dbHelper,
                    ioDispatcher,
                    AndroidSystemCalendarPort(context.applicationContext),
                ).also { repository = it }
            }
        }
    }

    fun resetForTest() {
        synchronized(this) {
            helper?.close()
            helper = null
            repository = null
            reminderScheduler = null
            ioDispatcher = Dispatchers.IO
            mainDispatcher = Dispatchers.Main.immediate
            databaseName = CalendarDbHelper.DEFAULT_NAME
        }
    }

    fun reminders(context: Context): ReminderScheduler {
        val current = reminderScheduler
        if (current != null) return current
        return synchronized(this) {
            reminderScheduler ?: ReminderScheduler(
                context.applicationContext,
                helper ?: CalendarDbHelper(context, databaseName).also { helper = it },
            ).also { reminderScheduler = it }
        }
    }
}
