package com.zzq.calendar.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.zzq.calendar.data.CalendarDbHelper
import com.zzq.calendar.data.EventStore
import java.util.concurrent.Executors

/**
 * PRD-05 §3.2: keep a record of what we actually handed to AlarmManager, then reconcile that
 * record against the plan on every start / foreground pass — scheduling alone cannot promise
 * arrival, a diff can. Stale keys (reschedule, delete, cancelled exception) are cancelled first.
 */
class ReminderScheduler(
    private val context: Context,
    private val helper: CalendarDbHelper,
    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val store = ReminderStore(helper)

    fun reconcile() {
        val expected = ReminderPlanner.plan(EventStore(helper).loadAll(), now())
        val exact = canScheduleExact()
        val actual = store.load().associateBy { it.key }

        actual.values.forEach { row ->
            if (expected.none { it.key == row.key }) {
                cancel(row.requestCode)
                store.remove(row.key)
            }
        }

        expected.forEach { slot ->
            val row = actual[slot.key]
            if (row != null && row.fireAt == slot.fireAt && row.exact == exact) return@forEach
            if (row != null) cancel(row.requestCode)
            schedule(slot, exact)
        }
    }

    fun reconcileAsync(force: Boolean = false) {
        val stamp = now()
        if (!force && stamp - lastReconcileAt < MIN_INTERVAL_MILLIS) return
        lastReconcileAt = stamp
        EXECUTOR.execute { runCatching { reconcile() } }
    }

    /** The alarm already fired; drop its row so a later reconcile neither cancels nor re-adds it. */
    fun markFired(key: String) = store.remove(key)

    private fun schedule(slot: ReminderSlot, exact: Boolean) {
        val requestCode = slot.key.hashCode()
        val pending = ReminderReceiver.pendingIntent(context, slot, requestCode)
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.fireAt, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.fireAt, pending)
        }
        store.put(ScheduledReminder(slot.key, slot.fireAt, requestCode, exact))
    }

    private fun cancel(requestCode: Int) {
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                requestCode,
                ReminderReceiver.blankIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private class ReminderStore(private val helper: CalendarDbHelper) {

        fun load(): List<ScheduledReminder> {
            val result = mutableListOf<ScheduledReminder>()
            helper.readableDatabase.rawQuery(
                "SELECT key, fire_at, request_code, exact FROM $TABLE",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += ScheduledReminder(cursor.getString(0), cursor.getLong(1), cursor.getInt(2), cursor.getInt(3) != 0)
                }
            }
            return result
        }

        fun put(row: ScheduledReminder) {
            helper.writableDatabase.replaceOrThrow(
                TABLE,
                null,
                android.content.ContentValues().apply {
                    put("key", row.key)
                    put("fire_at", row.fireAt)
                    put("request_code", row.requestCode)
                    put("exact", if (row.exact) 1 else 0)
                },
            )
        }

        fun remove(key: String) {
            helper.writableDatabase.delete(TABLE, "key = ?", arrayOf(key))
        }
    }

    private data class ScheduledReminder(
        val key: String,
        val fireAt: Long,
        val requestCode: Int,
        val exact: Boolean,
    )

    companion object {
        private const val TABLE = "reminder_schedule"
        private const val MIN_INTERVAL_MILLIS = 2_000L

        @Volatile
        private var lastReconcileAt = 0L

        private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reminder-reconcile").apply { isDaemon = true }
        }

        fun notificationsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun exactAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

        fun channelReady(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = android.app.NotificationChannel(
                ReminderReceiver.CHANNEL_ID,
                context.getString(com.zzq.calendar.R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            manager.createNotificationChannel(channel)
        }
    }
}
