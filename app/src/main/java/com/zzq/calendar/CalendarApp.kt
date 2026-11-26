package com.zzq.calendar

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zzq.calendar.reminder.ReminderScheduler
import com.zzq.calendar.sync.SyncWorker
import java.util.concurrent.TimeUnit

class CalendarApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.channelReady(this)
        runCatching { AppGraph.reminders(this).reconcileAsync() }
        // PRD-05 §3.2: every foreground pass diffs the plan against what is actually scheduled.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                runCatching { AppGraph.reminders(activity).reconcileAsync() }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
