package com.zzq.calendar.ui

import android.Manifest
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.MonthAgenda
import com.zzq.calendar.databinding.ActivityMonthBinding
import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.MonthCell
import com.zzq.calendar.model.MonthAgendaGroups
import com.zzq.calendar.model.MonthAgendaSplit
import com.zzq.calendar.model.MonthGrid
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

class MonthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMonthBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var month: YearMonth = YearMonth.now()
    private var loadGeneration = 0
    private var remainingExpanded = true
    private var completedExpanded = false
    private var groups = MonthAgendaGroups(emptyList(), emptyList())
    private val syncHandler = Handler(Looper.getMainLooper())
    private val syncRunnable = Runnable { SystemSyncUi.refresh(this) { load() } }
    private val systemCalendarObserver = SystemCalendarObserver {
        syncHandler.removeCallbacks(syncRunnable)
        syncHandler.postDelayed(syncRunnable, 800)
    }
    private val readPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SystemSyncUi.startWatching(this, systemCalendarObserver)
                SystemSyncUi.refresh(this) { load() }
            } else {
                Toast.makeText(this, R.string.sync_permission, Toast.LENGTH_LONG).show()
            }
            updatePermissionHint()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMonthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        month = savedInstanceState?.getString(KEY_MONTH)?.let(YearMonth::parse)
            ?: intent.getStringExtra(EXTRA_MONTH)?.let(YearMonth::parse)
            ?: YearMonth.now(zone)
        remainingExpanded = savedInstanceState?.getBoolean(KEY_REMAINING_EXPANDED) ?: true
        completedExpanded = savedInstanceState?.getBoolean(KEY_COMPLETED_EXPANDED) ?: false
        binding.previousMonthButton.setOnClickListener { shiftMonth(-1) }
        binding.nextMonthButton.setOnClickListener { shiftMonth(1) }
        CalendarTabs.attach(
            this,
            binding.tabBar,
            CalendarTabs.Tab.MONTH,
            anchor = { CalendarTabs.monthAnchor(month, LocalDate.now(zone)) },
        )
        SwipeNavigator.attach(this, canNavigate = { CalendarRange.canShiftMonth(month, it) }) {
            shiftMonth(CalendarRange.deltaDays(it))
        }
        binding.remainingHeader.setOnClickListener {
            remainingExpanded = !remainingExpanded
            renderGroups()
        }
        binding.completedHeader.setOnClickListener {
            completedExpanded = !completedExpanded
            renderGroups()
        }
        buildWeekHeader()
        // Draw the date grid in the first frame; dots fill in when the load lands.
        render(MonthAgenda(emptyMap(), emptyList()))
        updatePermissionHint()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionHint()
        load()
        SystemSyncUi.refresh(this) { load() }
    }

    override fun onStart() {
        super.onStart()
        SystemSyncUi.startWatching(this, systemCalendarObserver)
        SystemSyncUi.askReadOnce(this, readPermission)
    }

    override fun onStop() {
        SystemSyncUi.stopWatching(this, systemCalendarObserver)
        syncHandler.removeCallbacks(syncRunnable)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MONTH, month.toString())
        outState.putBoolean(KEY_REMAINING_EXPANDED, remainingExpanded)
        outState.putBoolean(KEY_COMPLETED_EXPANDED, completedExpanded)
    }

    private fun shiftMonth(delta: Long) {
        month = month.plusMonths(delta)
        load()
    }

    private fun updatePermissionHint() {
        binding.calendarHint.visibility = if (SystemSyncUi.hasRead(this)) View.GONE else View.VISIBLE
    }

    private fun buildWeekHeader() {
        binding.weekHeader.removeAllViews()
        MonthGrid.weekdayLabels.forEach { label ->
            binding.weekHeader.addView(
                TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface_variant))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }
    }

    private fun load() {
        binding.monthTitle.text = getString(R.string.month_title, month.year, month.monthValue)
        val generation = ++loadGeneration
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val agenda = runCatching { repository.loadMonthAgenda(month, zone) }
                .getOrDefault(MonthAgenda(emptyMap(), emptyList()))
            if (generation != loadGeneration) return@launch
            render(agenda)
        }
    }

    private fun render(agenda: MonthAgenda) {
        renderGrid(agenda.counts)
        groups = MonthAgendaSplit.split(agenda.instances, Instant.now(), zone)
        renderGroups()
    }

    private fun renderGrid(counts: Map<LocalDate, Int>) {
        binding.monthGrid.removeAllViews()
        val cells = MonthGrid.cells(month)
        val today = LocalDate.now(zone)
        repeat(MonthGrid.ROWS) { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            repeat(MonthGrid.COLUMNS) { column ->
                rowView.addView(buildCell(cells[row * MonthGrid.COLUMNS + column], counts, today))
            }
            binding.monthGrid.addView(rowView)
        }
    }

    private fun buildCell(cell: MonthCell, counts: Map<LocalDate, Int>, today: LocalDate): View {
        val count = counts[cell.date] ?: 0
        val selectable = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(selectable.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = if (count > 0) {
                getString(
                    R.string.month_cell_description,
                    cell.date.monthValue,
                    cell.date.dayOfMonth,
                    count,
                )
            } else {
                getString(R.string.month_cell_empty, cell.date.monthValue, cell.date.dayOfMonth)
            }
            setOnClickListener { startActivity(MainActivity.dayIntent(this@MonthActivity, cell.date)) }
        }

        val dayNumber = TextView(this).apply {
            text = cell.date.dayOfMonth.toString()
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(getColor(if (cell.inMonth) R.color.on_surface else R.color.hour_text))
            if (cell.inMonth && cell.date == today) {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.current_time))
                background = ContextCompat.getDrawable(this@MonthActivity, R.drawable.bg_today_circle)
                minimumWidth = resources.getDimensionPixelSize(R.dimen.today_circle_size)
                minimumHeight = resources.getDimensionPixelSize(R.dimen.today_circle_size)
                setPadding(4.dp(), 0, 4.dp(), 0)
            }
        }
        val dots = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(getColor(R.color.primary))
            text = if (count > 3) "●●●+" else "●".repeat(count)
        }
        container.addView(dayNumber)
        if (count > 0) container.addView(dots)
        return container
    }

    private fun renderGroups() {
        renderGroup(
            header = binding.remainingHeader,
            label = binding.remainingLabel,
            chevron = binding.remainingChevron,
            list = binding.remainingList,
            empty = binding.remainingEmpty,
            title = getString(R.string.month_group_remaining),
            items = groups.remaining,
            expanded = remainingExpanded,
        )
        renderGroup(
            header = binding.completedHeader,
            label = binding.completedLabel,
            chevron = binding.completedChevron,
            list = binding.completedList,
            empty = binding.completedEmpty,
            title = getString(R.string.month_group_completed),
            items = groups.completed,
            expanded = completedExpanded,
        )
    }

    private fun renderGroup(
        header: View,
        label: TextView,
        chevron: TextView,
        list: LinearLayout,
        empty: TextView,
        title: String,
        items: List<EventInstance>,
        expanded: Boolean,
    ) {
        label.text = getString(R.string.month_group_label, title, items.size)
        chevron.text = if (expanded) "▾" else "▸"
        header.contentDescription = "$title，${items.size} 个日程，" +
            getString(if (expanded) R.string.month_group_collapse else R.string.month_group_expand)
        list.visibility = if (expanded) View.VISIBLE else View.GONE
        empty.visibility = if (expanded && items.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        if (!expanded) return
        items.forEach { list.addView(buildRow(it, list)) }
    }

    private fun buildRow(instance: EventInstance, parent: LinearLayout): View {
        val row = layoutInflater.inflate(R.layout.item_month_agenda, parent, false)
        val time = if (instance.allDay) UiFormat.allDayRange(instance) else UiFormat.timeRange(instance, zone)
        val location = instance.master.location.trim()
        row.findViewById<TextView>(R.id.eventTitle).text = instance.title
        row.findViewById<TextView>(R.id.eventTime).text = time
        row.findViewById<TextView>(R.id.eventLocation).apply {
            text = location
            visibility = if (location.isEmpty()) View.GONE else View.VISIBLE
        }
        row.contentDescription = listOfNotNull(instance.title, time, location.ifEmpty { null })
            .joinToString("，")
        row.setOnClickListener {
            startActivity(MainActivity.dayIntent(this, instance.start.atZone(zone).toLocalDate()))
        }
        return row
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_MONTH = "month"
        private const val KEY_REMAINING_EXPANDED = "remaining_expanded"
        private const val KEY_COMPLETED_EXPANDED = "completed_expanded"
        private const val EXTRA_MONTH = "extra_month"

        fun monthIntent(context: android.content.Context, month: YearMonth): android.content.Intent =
            android.content.Intent(context, MonthActivity::class.java)
                .putExtra(EXTRA_MONTH, month.toString())
    }
}
