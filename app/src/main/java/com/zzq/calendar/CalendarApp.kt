package com.zzq.calendar

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zzq.calendar.sync.SyncWorker
import java.util.concurrent.TimeUnit

class CalendarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // best-effort only; Doze / ROM power saving can delay this well past 15 minutes.
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SYNC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
            )
        }.onFailure { Log.w("CalendarApp", "system calendar sync not scheduled", it) }
    }

    companion object {
        private const val SYNC_WORK = "system-calendar-sync"
    }
}
