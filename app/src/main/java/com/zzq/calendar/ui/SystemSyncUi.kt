package com.zzq.calendar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.SyncResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Foreground refresh + the source-reset / conflict dialogs. */
object SystemSyncUi {

    fun hasRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun refresh(activity: AppCompatActivity, onDone: (() -> Unit)? = null) {
        if (!hasRead(activity)) return
        activity.lifecycleScope.launch(AppGraph.mainDispatcher) {
            val result = runCatching { AppGraph.repository(activity).syncSystem() }.getOrNull()
            if (result == null) {
                Toast.makeText(activity, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (result) {
                is SyncResult.Suspended -> confirmReset(activity, result, onDone)
                is SyncResult.Applied -> {
                    if (result.conflicts > 0) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.sync_conflicts_found, result.conflicts),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    onDone?.invoke()
                }
            }
        }
    }

    fun showConflicts(activity: AppCompatActivity) {
        activity.lifecycleScope.launch(AppGraph.mainDispatcher) {
            val conflicts = runCatching { AppGraph.repository(activity).conflicts() }.getOrDefault(emptyList())
            if (conflicts.isEmpty()) {
                Toast.makeText(activity, R.string.sync_conflict_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val lines = conflicts.map { conflict ->
                "${conflict.title.ifBlank { activity.getString(R.string.untitled) }}\n" +
                    "${conflict.detail} · ${format.format(Date(conflict.createdAt))}"
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.sync_conflict_title)
                .setItems(lines.toTypedArray(), null)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun confirmReset(
        activity: AppCompatActivity,
        result: SyncResult.Suspended,
        onDone: (() -> Unit)?,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.sync_reset_title)
            .setMessage(activity.getString(R.string.sync_reset_message, result.missing, result.total))
            .setPositiveButton(R.string.sync_reset_confirm) { _, _ ->
                activity.lifecycleScope.launch(AppGraph.mainDispatcher) {
                    runCatching { AppGraph.repository(activity).syncSystem(force = true) }
                    onDone?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** One ContentObserver per activity; debounce lives with the caller. */
class SystemCalendarObserver(private val onChanged: () -> Unit) :
    ContentObserver(Handler(Looper.getMainLooper())) {

    override fun onChange(selfChange: Boolean, uri: Uri?) = onChanged()
}
