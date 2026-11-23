package com.zzq.calendar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityMainBinding
import com.zzq.calendar.export.IcsExporter
import com.zzq.calendar.export.JsonBackup
import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var selectedDay: LocalDate = LocalDate.now()
    private var loadGeneration = 0

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val createIcs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        uri?.let { writeExport(it, ExportKind.ICS) }
    }

    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeExport(it, ExportKind.JSON) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedDay = savedInstanceState?.getString(KEY_DAY)?.let(LocalDate::parse) ?: LocalDate.now()

        binding.previousDayButton.setOnClickListener { shiftDay(-1) }
        binding.nextDayButton.setOnClickListener { shiftDay(1) }
        binding.todayButton.setOnClickListener {
            selectedDay = LocalDate.now()
            loadDay()
        }
        binding.newEventButton.setOnClickListener { openCreate(defaultStart()) }
        binding.timeline.onEventClick = { instance -> openEdit(instance.master.id) }
        binding.timeline.onEmptyClick = { local -> openCreate(local) }
        binding.moreButton.setOnClickListener { anchor -> showExportMenu(anchor) }
    }

    override fun onResume() {
        super.onResume()
        loadDay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DAY, selectedDay.toString())
    }

    private fun shiftDay(delta: Long) {
        selectedDay = selectedDay.plusDays(delta)
        loadDay()
    }

    private fun defaultStart(): LocalDateTime {
        val now = LocalDateTime.now(zone)
        return if (selectedDay == now.toLocalDate()) {
            now.plusMinutes((30 - now.minute % 30).toLong()).withSecond(0).withNano(0)
        } else {
            selectedDay.atTime(9, 0)
        }
    }

    private fun openCreate(start: LocalDateTime) {
        editLauncher.launch(EventEditActivity.createIntent(this, start))
    }

    private fun openEdit(eventId: String) {
        editLauncher.launch(EventEditActivity.editIntent(this, eventId))
    }

    private fun showExportMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EXPORT_ICS, 0, R.string.export_ics_all)
            menu.add(0, MENU_EXPORT_JSON, 1, R.string.export_backup_all)
            setOnMenuItemClickListener { item ->
                val stamp = LocalDate.now(zone).toString()
                when (item.itemId) {
                    MENU_EXPORT_ICS -> createIcs.launch(getString(R.string.export_ics_name, stamp))
                    MENU_EXPORT_JSON -> createJson.launch(getString(R.string.export_json_name, stamp))
                }
                true
            }
        }.show()
    }

    /** SAF [ActivityResultContracts.CreateDocument] target: no storage permission is requested. */
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
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDay() {
        binding.dateTitle.text = UiFormat.dateTitle(selectedDay)
        binding.timezoneHint.text = getString(R.string.timezone_hint, zone.id)
        val generation = ++loadGeneration
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val agenda = runCatching { repository.loadDay(selectedDay, zone) }.getOrNull()
            if (generation != loadGeneration) return@launch
            if (agenda == null) {
                renderEmpty(showMessage = true)
            } else {
                render(agenda)
            }
        }
    }

    private fun render(agenda: DayAgenda) {
        renderAllDay(agenda.allDay)
        binding.timeline.submit(agenda.timed, agenda.day, zone)
        binding.timeline.contentDescription =
            getString(R.string.timeline_description, UiFormat.dateTitle(agenda.day), agenda.timed.size)

        binding.eventListContainer.removeAllViews()
        agenda.timed.forEach { instance ->
            binding.eventListContainer.addView(buildEventRow(instance))
        }

        val isEmpty = agenda.isEmpty
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.timeline.visibility = if (agenda.timed.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderEmpty(showMessage: Boolean) {
        binding.allDayContainer.removeAllViews()
        binding.allDayHeader.visibility = View.GONE
        binding.eventListContainer.removeAllViews()
        binding.timeline.submit(emptyList(), selectedDay, zone)
        binding.timeline.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
        if (showMessage) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderAllDay(instances: List<EventInstance>) {
        binding.allDayContainer.removeAllViews()
        binding.allDayHeader.visibility = if (instances.isEmpty()) View.GONE else View.VISIBLE
        instances.forEach { instance ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_all_day_row, binding.allDayContainer, false)
            row.findViewById<TextView>(R.id.allDayTitle).text = instance.title
            val range = UiFormat.allDayRange(instance)
            val rangeView = row.findViewById<TextView>(R.id.allDayRange)
            rangeView.text = range
            rangeView.visibility = if (UiFormat.isSingleDayAllDay(instance)) View.GONE else View.VISIBLE
            row.contentDescription = "${instance.title}，$range"
            row.setOnClickListener { openEdit(instance.master.id) }
            binding.allDayContainer.addView(row)
        }
    }

    private fun buildEventRow(instance: EventInstance): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_event_row, binding.eventListContainer, false)
        row.findViewById<TextView>(R.id.eventTitle).text = instance.title.ifBlank { getString(R.string.untitled) }
        row.findViewById<TextView>(R.id.eventTime).text = UiFormat.timeRange(instance, zone)
        row.contentDescription = "${instance.title}，${UiFormat.timeRange(instance, zone)}"
        row.setOnClickListener { openEdit(instance.master.id) }
        return row
    }

    companion object {
        private const val KEY_DAY = "selected_day"
        private const val MENU_EXPORT_ICS = 1
        private const val MENU_EXPORT_JSON = 2
    }

    private enum class ExportKind { ICS, JSON }
}
