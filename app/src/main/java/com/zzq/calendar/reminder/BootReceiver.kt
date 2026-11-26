package com.zzq.calendar.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zzq.calendar.AppGraph

/** Alarms do not survive a reboot; rebuild the plan as soon as the system is back. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching { AppGraph.reminders(context).reconcileAsync() }
    }
}
