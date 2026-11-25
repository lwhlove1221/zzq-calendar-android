package com.zzq.calendar.ui

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityWeekBinding
import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.MonthGrid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.launch

class WeekActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWeekBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var weekStart: LocalDate = mondayOf(LocalDate.now())
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWeekBinding.inflate(layoutInflater)
        setContentView(binding.root)

        weekStart = savedInstanceState?.getString(KEY_WEEK)?.let(LocalDate::parse)
            ?: intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse)?.let(::mondayOf)
            ?: mondayOf(LocalDate.now(zone))

        binding.previousWeekButton.setOnClickListener { shiftWeek(-1) }
        binding.nextWeekButton.setOnClickListener { shiftWeek(1) }
        binding.weekTimeline.onEventClick = { instance ->
            startActivity(MainActivity.dayIntent(this, instance.start.atZone(zone).toLocalDate()))
        }
        CalendarTabs.attach(this, binding.tabBar, CalendarTabs.Tab.WEEK, anchor = { weekStart }) {
            weekStart = mondayOf(LocalDate.now(zone))
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_WEEK, weekStart.toString())
    }

    private fun shiftWeek(delta: Long) {
        weekStart = weekStart.plusWeeks(delta)
        load()
    }

    private fun load() {
        val end = weekStart.plusDays(6)
        binding.weekTitle.text = if (weekStart.year == end.year) {
            getString(
                R.string.week_title,
                weekStart.year,
                weekStart.monthValue,
                weekStart.dayOfMonth,
                end.monthValue,
                end.dayOfMonth,
            )
        } else {
            getString(
                R.string.week_title_cross_year,
                weekStart.year,
                weekStart.monthValue,
                weekStart.dayOfMonth,
                end.year,
                end.monthValue,
                end.dayOfMonth,
            )
        }
        val generation = ++loadGeneration
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val days = runCatching { repository.loadWeek(weekStart, zone) }.getOrNull()
            if (generation != loadGeneration) return@launch
            if (days == null) {
                binding.dayHeaderRow.removeAllViews()
                binding.weekTimeline.submit(emptyList(), zone)
                return@launch
            }
            render(days)
        }
    }

    private fun render(days: List<DayAgenda>) {
        binding.dayHeaderRow.removeAllViews()
        val today = LocalDate.now(zone)
        days.forEachIndexed { index, agenda ->
            val isToday = agenda.day == today
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                val background = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
                setBackgroundResource(background.resourceId)
                contentDescription = getString(
                    R.string.week_day_description,
                    agenda.day.monthValue,
                    agenda.day.dayOfMonth,
                    agenda.instanceCount,
                )
                setOnClickListener { startActivity(MainActivity.dayIntent(this@WeekActivity, agenda.day)) }
            }
            val weekday = TextView(this).apply {
                text = MonthGrid.weekdayLabels[index]
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(getColor(if (isToday) R.color.primary else R.color.on_surface_variant))
            }
            val number = TextView(this).apply {
                text = agenda.day.dayOfMonth.toString()
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(getColor(if (isToday) R.color.primary else R.color.on_surface))
                if (isToday) setTypeface(typeface, Typeface.BOLD)
            }
            val dot = TextView(this).apply {
                text = if (agenda.allDay.isEmpty()) "" else "●"
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(getColor(R.color.primary))
            }
            cell.addView(weekday)
            cell.addView(number)
            cell.addView(dot)
            binding.dayHeaderRow.addView(cell)
        }
        binding.weekTimeline.submit(days, zone)
    }

    companion object {
        private const val KEY_WEEK = "week_start"
        private const val EXTRA_DATE = "extra_date"

        fun mondayOf(date: LocalDate): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun weekIntent(context: android.content.Context, date: LocalDate) =
            android.content.Intent(context, WeekActivity::class.java)
                .putExtra(EXTRA_DATE, date.toString())
    }
}
