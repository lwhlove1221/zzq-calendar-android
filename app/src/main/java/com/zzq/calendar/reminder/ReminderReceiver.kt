package com.zzq.calendar.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.model.WallTime
import com.zzq.calendar.ui.MainActivity

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startWall = intent.getStringExtra(EXTRA_START_WALL)
        runCatching { AppGraph.reminders(context).markFired(key) }
        if (!ReminderScheduler.notificationsAllowed(context)) return
        ReminderScheduler.channelReady(context)

        val open = startWall?.let {
            val date = runCatching { WallTime.parse(it).toLocalDate() }.getOrNull()
                ?: return@let null
            PendingIntent.getActivity(
                context,
                key.hashCode(),
                MainActivity.dayIntent(context, date),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(context.getString(R.string.reminder_notification_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.reminder_snooze_5), snooze(context, intent))
            .apply { open?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(key.hashCode(), notification) }
    }

    private fun snooze(context: Context, intent: Intent): PendingIntent {
        val again = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_KEY, intent.getStringExtra(EXTRA_KEY))
            .putExtra(EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE))
            .putExtra(EXTRA_START_WALL, intent.getStringExtra(EXTRA_START_WALL))
        return PendingIntent.getBroadcast(
            context,
            intent.getStringExtra(EXTRA_KEY).orEmpty().hashCode() + 1,
            again,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also { pending ->
            val alarm = context.getSystemService(android.app.AlarmManager::class.java)
            val at = System.currentTimeMillis() + SNOOZE_MILLIS
            if (ReminderScheduler.exactAllowed(context)) {
                alarm.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        private const val EXTRA_KEY = "reminder_key"
        private const val EXTRA_TITLE = "reminder_title"
        private const val EXTRA_START_WALL = "reminder_start_wall"
        private const val SNOOZE_MILLIS = 5 * 60 * 1000L

        fun blankIntent(context: Context): Intent = Intent(context, ReminderReceiver::class.java)

        fun pendingIntent(context: Context, slot: ReminderSlot, requestCode: Int): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_KEY, slot.key)
                .putExtra(EXTRA_TITLE, slot.title)
                .putExtra(EXTRA_START_WALL, slot.startWall)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
