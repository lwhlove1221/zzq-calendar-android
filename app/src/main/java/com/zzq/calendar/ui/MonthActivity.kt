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
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityMonthBinding
import com.zzq.calendar.model.MonthCell
import com.zzq.calendar.model.MonthGrid
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
        binding.previousMonthButton.setOnClickListener { shiftMonth(-1) }
        binding.nextMonthButton.setOnClickListener { shiftMonth(1) }
        CalendarTabs.attach(
            this,
            binding.tabBar,
            CalendarTabs.Tab.MONTH,
            anchor = { CalendarTabs.monthAnchor(month, LocalDate.now(zone)) },
        ) {
            month = YearMonth.now(zone)
            load()
        }
        buildWeekHeader()
        // Draw the date grid in the first frame; dots fill in when the load lands.
        render(emptyMap())
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
            val counts = runCatching { repository.loadMonth(month, zone) }.getOrDefault(emptyMap())
            if (generation != loadGeneration) return@launch
            render(counts)
        }
    }

    private fun render(counts: Map<LocalDate, Int>) {
        binding.monthGrid.removeAllViews()
        val cells = MonthGrid.cells(month)
        val today = LocalDate.now(zone)
        repeat(MonthGrid.ROWS) { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
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
        val background = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundResource(background.resourceId)
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
            setPadding(0, resources.getDimensionPixelSize(R.dimen.spacing_small) / 2, 0, 0)
            setTextColor(getColor(if (cell.inMonth) R.color.on_surface else R.color.hour_text))
            if (cell.date == today) {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.primary))
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
        container.addView(dots)
        return container
    }

    companion object {
        private const val KEY_MONTH = "month"
        private const val EXTRA_MONTH = "extra_month"

        fun monthIntent(context: android.content.Context, month: YearMonth): android.content.Intent =
            android.content.Intent(context, MonthActivity::class.java)
                .putExtra(EXTRA_MONTH, month.toString())
    }
}
