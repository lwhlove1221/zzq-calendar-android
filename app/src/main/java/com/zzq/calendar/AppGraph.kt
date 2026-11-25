package com.zzq.calendar

import android.content.Context
import com.zzq.calendar.data.AndroidSystemCalendarPort
import com.zzq.calendar.data.CalendarDbHelper
import com.zzq.calendar.data.EventRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object AppGraph {

    @Volatile
    private var helper: CalendarDbHelper? = null

    @Volatile
    private var repository: EventRepository? = null

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
                val dbHelper = CalendarDbHelper(context, databaseName)
                helper = dbHelper
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
            ioDispatcher = Dispatchers.IO
            mainDispatcher = Dispatchers.Main.immediate
            databaseName = CalendarDbHelper.DEFAULT_NAME
        }
    }
}
