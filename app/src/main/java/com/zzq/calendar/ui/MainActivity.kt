package com.zzq.calendar.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityMainBinding
import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.EventInstance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var selectedDay: LocalDate = LocalDate.now()
    private var loadGeneration = 0
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { syncFromSystem() }
    private val systemCalendarObserver = SystemCalendarObserver { scheduleSync() }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val syncPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SystemSyncUi.startWatching(this, systemCalendarObserver)
                syncFromSystem()
            } else {
                Toast.makeText(this, R.string.sync_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyAccent(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedDay = savedInstanceState?.getString(KEY_DAY)?.let(LocalDate::parse)
            ?: intent.getStringExtra(EXTRA_DAY)?.let(LocalDate::parse)
            ?: LocalDate.now()

        binding.monthButton.setOnClickListener {
            startActivity(MonthActivity.monthIntent(this, java.time.YearMonth.from(selectedDay)))
            finish()
        }
        binding.previousDayButton.setOnClickListener { shiftDay(-1) }
        binding.nextDayButton.setOnClickListener { shiftDay(1) }
        binding.todayButton.setOnClickListener {
            selectedDay = LocalDate.now()
            loadDay()
        }
        binding.newEventButton.setOnClickListener { openCreate(defaultStart()) }
        binding.timeline.onEventClick = { instance -> openEdit(instance.master.id) }
        binding.timeline.onEmptyClick = { local -> openCreate(local) }

        CalendarTabs.attach(
            this,
            binding.tabBar,
            CalendarTabs.Tab.DAY,
            anchor = { selectedDay },
        ) {
            selectedDay = LocalDate.now(zone)
            loadDay()
        }
        SwipeNavigator.attach(this, canNavigate = { CalendarRange.canShiftDay(selectedDay, it) }) {
            shiftDay(CalendarRange.deltaDays(it))
        }

        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val interrupted = runCatching { repository.discardStaging() }.getOrDefault(false)
            if (interrupted) {
                Toast.makeText(this@MainActivity, R.string.import_interrupted, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SystemSyncUi.hasRead(this)) syncFromSystem()
        loadDay()
    }

    override fun onStart() {
        super.onStart()
        SystemSyncUi.startWatching(this, systemCalendarObserver)
        SystemSyncUi.askReadOnce(this, syncPermission)
    }

    override fun onStop() {
        SystemSyncUi.stopWatching(this, systemCalendarObserver)
        syncHandler.removeCallbacks(syncRunnable)
        super.onStop()
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

    private fun syncFromSystem() {
        SystemSyncUi.refresh(this) { loadDay() }
    }

    private fun scheduleSync() {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, 800)
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
        const val EXTRA_DAY = "extra_day"
        private const val KEY_DAY = "selected_day"
        fun dayIntent(context: android.content.Context, day: LocalDate): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_DAY, day.toString())
    }
}
