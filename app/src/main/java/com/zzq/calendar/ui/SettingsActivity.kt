package com.zzq.calendar.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivitySettingsBinding
import com.zzq.calendar.export.IcsExporter
import com.zzq.calendar.export.JsonBackup
import com.zzq.calendar.importer.ImportPreview
import com.zzq.calendar.importer.ImportStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private var bindingTheme = false

    private val syncPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SystemSyncUi.refresh(this)
            } else {
                Toast.makeText(this, R.string.sync_permission, Toast.LENGTH_LONG).show()
            }
        }

    private val openIcs = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readImport(it) }
    }

    private val createIcs =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
            uri?.let { writeExport(it, ExportKind.ICS) }
        }

    private val createJson =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeExport(it, ExportKind.JSON) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CalendarTabs.attach(
            this,
            binding.tabBar,
            CalendarTabs.Tab.SETTINGS,
            anchor = { LocalDate.now(zone) },
        )
        renderThemeChoices()
        renderActions()
    }

    private fun renderThemeChoices() {
        bindingTheme = true
        when (ThemePrefs.mode(this)) {
            ThemePrefs.MODE_LIGHT -> binding.themeLightButton.isChecked = true
            ThemePrefs.MODE_DARK -> binding.themeDarkButton.isChecked = true
            else -> binding.themeSystemButton.isChecked = true
        }
        when (ThemePrefs.accent(this)) {
            ThemePrefs.ACCENT_GREEN -> binding.accentGreenButton.isChecked = true
            ThemePrefs.ACCENT_PURPLE -> binding.accentPurpleButton.isChecked = true
            else -> binding.accentBlueButton.isChecked = true
        }
        bindingTheme = false

        binding.themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingTheme) return@setOnCheckedChangeListener
            pickMode(
                when (checkedId) {
                    R.id.themeLightButton -> ThemePrefs.MODE_LIGHT
                    R.id.themeDarkButton -> ThemePrefs.MODE_DARK
                    else -> ThemePrefs.MODE_SYSTEM
                },
            )
        }
        binding.accentGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingTheme) return@setOnCheckedChangeListener
            pickAccent(
                when (checkedId) {
                    R.id.accentGreenButton -> ThemePrefs.ACCENT_GREEN
                    R.id.accentPurpleButton -> ThemePrefs.ACCENT_PURPLE
                    else -> ThemePrefs.ACCENT_BLUE
                },
            )
        }
    }

    private fun pickMode(mode: Int) {
        if (mode == ThemePrefs.mode(this)) return
        // AppCompat recreates the visible activity, so the change lands without an app restart.
        ThemePrefs.setMode(this, mode)
    }

    private fun pickAccent(accent: Int) {
        if (accent == ThemePrefs.accent(this)) return
        ThemePrefs.setAccent(this, accent)
        recreate()
    }

    private fun renderActions() {
        val stamp = LocalDate.now(zone).toString()
        action(R.string.import_system_calendar) {
            if (SystemSyncUi.hasRead(this)) {
                SystemSyncUi.refresh(this)
            } else {
                syncPermission.launch(Manifest.permission.READ_CALENDAR)
            }
        }
        action(R.string.sync_conflicts) { SystemSyncUi.showConflicts(this) }
        action(R.string.import_ics) { openIcs.launch(arrayOf("*/*")) }
        action(R.string.export_ics_all) { createIcs.launch(getString(R.string.export_ics_name, stamp)) }
        action(R.string.export_backup_all) { createJson.launch(getString(R.string.export_json_name, stamp)) }
    }

    private fun action(labelRes: Int, onClick: () -> Unit) {
        val row = TextView(this).apply {
            text = getString(labelRes)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(0, 16.dp(), 0, 16.dp())
            minHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
            isClickable = true
            isFocusable = true
            val selectable = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            setBackgroundResource(selectable.resourceId)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        binding.actionList.addView(row)
    }

    private fun readImport(uri: Uri) {
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val loaded = runCatching {
                withContext(AppGraph.ioDispatcher) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("cannot open import source")
                    repository.previewImport(bytes) to bytes
                }
            }.getOrNull()
            if (loaded == null) {
                Toast.makeText(this@SettingsActivity, R.string.import_read_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showImportPreview(loaded.first, loaded.second)
        }
    }

    private fun showImportPreview(preview: ImportPreview, bytes: ByteArray) {
        if (preview.fatal != null) {
            val message = buildString {
                append(preview.fatal.message)
                preview.failures.take(8).forEach { failure ->
                    append('\n').append(getString(R.string.import_line_failure, failure.line, failure.message))
                }
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.import_preview_title)
                .setMessage(message)
                .setPositiveButton(R.string.cancel, null)
                .show()
            return
        }
        if (preview.isEmpty) {
            Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_preview_title)
            .setMessage(importPreviewText(preview))
            .setPositiveButton(R.string.import_confirm) { _, _ -> confirmImport(bytes) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importPreviewText(preview: ImportPreview): String = buildString {
        append(
            getString(
                R.string.import_preview_summary,
                preview.total,
                preview.created.size,
                preview.skipped.size,
                preview.conflicts.size,
                preview.failures.size,
            ),
        )
        append('\n').append(getString(R.string.import_target_calendar, getString(R.string.import_default_calendar)))
        preview.failures.take(8).forEach { failure ->
            append('\n').append(getString(R.string.import_line_failure, failure.line, failure.message))
        }
        if (preview.failures.size > 8) {
            append('\n').append(getString(R.string.import_more_failures, preview.failures.size - 8))
        }
        preview.conflicts.take(5).forEach { conflict ->
            append('\n').append(getString(R.string.import_line_conflict, conflict.title, conflict.line))
        }
        if (preview.warnings.isNotEmpty()) {
            append('\n').append(getString(R.string.import_warnings, preview.warnings.first()))
        }
    }

    private fun confirmImport(bytes: ByteArray) {
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val outcome = runCatching { repository.importIcs(bytes) }.getOrNull()
            val message = when {
                outcome == null -> getString(R.string.import_failed, getString(R.string.load_failed))
                outcome.status == ImportStatus.FAILED ->
                    getString(R.string.import_failed, outcome.fatal?.message.orEmpty())
                outcome.status == ImportStatus.PARTIAL -> getString(
                    R.string.import_done,
                    outcome.created,
                    outcome.skipped,
                    outcome.conflicts,
                    outcome.failed,
                ) + getString(R.string.import_partial_suffix)
                else -> getString(
                    R.string.import_done,
                    outcome.created,
                    outcome.skipped,
                    outcome.conflicts,
                    outcome.failed,
                )
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** SAF target: no storage permission is requested. */
    private fun writeExport(uri: Uri, kind: ExportKind) {
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val written = runCatching {
                val events = repository.loadAll()
                if (events.isEmpty()) return@runCatching false
                withContext(AppGraph.ioDispatcher) {
                    val now = Instant.now()
                    val text = when (kind) {
                        ExportKind.ICS -> IcsExporter.export(events, now)
                        ExportKind.JSON -> JsonBackup.build(events, now)
                    }
                    val stream = contentResolver.openOutputStream(uri) ?: error("cannot open export target")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                true
            }
            val message = when {
                written.isFailure -> R.string.export_failed
                written.getOrNull() == false -> R.string.export_empty
                else -> R.string.export_done
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: android.content.Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    private enum class ExportKind { ICS, JSON }
}
