package com.zzq.calendar.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.databinding.ActivityWeekBinding
import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.EventInstance
import com.zzq.calendar.model.MonthGrid
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.launch

class WeekActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWeekBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    // Rows split the screen evenly (7 days, one screen); large fonts get fewer lines, never clipped ones.
    private val maxEventLines: Int
        get() = if (resources.configuration.fontScale > 1.5f) 1 else MAX_EVENT_LINES

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
        CalendarTabs.attach(this, binding.tabBar, CalendarTabs.Tab.WEEK, anchor = { weekStart })
        SwipeNavigator.attach(this, canNavigate = { CalendarRange.canShiftWeek(weekStart, it) }) {
            shiftWeek(CalendarRange.deltaDays(it))
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
                binding.weekRows.removeAllViews()
                return@launch
            }
            render(days)
        }
    }

    private fun render(days: List<DayAgenda>) {
        binding.weekRows.removeAllViews()
        val today = LocalDate.now(zone)
        days.forEach { agenda ->
            binding.weekRows.addView(buildDayRow(agenda, agenda.day == today))
        }
    }

    /** One row per day so all seven days stay on one screen; overflowing events collapse to a count. */
    private fun buildDayRow(agenda: DayAgenda, isToday: Boolean): View {
        val selectable = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { bottomMargin = 6.dp() }
            minimumHeight = 56.dp()
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            setBackgroundResource(R.drawable.bg_agenda_card)
            foreground = ContextCompat.getDrawable(this@WeekActivity, selectable.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                R.string.week_day_description,
                agenda.day.monthValue,
                agenda.day.dayOfMonth,
                agenda.instanceCount,
            )
            setOnClickListener { startActivity(MainActivity.dayIntent(this@WeekActivity, agenda.day)) }
        }

        val dateBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(48.dp(), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        dateBlock.addView(
            TextView(this).apply {
                text = MonthGrid.weekdayLabels[agenda.day.dayOfWeek.value - 1]
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(getColor(if (isToday) R.color.primary else R.color.on_surface_variant))
            },
        )
        dateBlock.addView(
            TextView(this).apply {
                text = agenda.day.dayOfMonth.toString()
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(getColor(if (isToday) R.color.current_time else R.color.on_surface))
                setTypeface(typeface, Typeface.BOLD)
                if (isToday) {
                    background = ContextCompat.getDrawable(this@WeekActivity, R.drawable.bg_today_circle)
                    minimumWidth = resources.getDimensionPixelSize(R.dimen.today_circle_size)
                    minimumHeight = resources.getDimensionPixelSize(R.dimen.today_circle_size)
                }
            },
        )
        row.addView(dateBlock)

        val events = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(12.dp(), 0, 0, 0)
        }
        val instances = agenda.allDay + agenda.timed
        instances.take(maxEventLines).forEach { events.addView(buildEventLine(it)) }
        if (instances.size > maxEventLines) {
            events.addView(
                TextView(this).apply {
                    text = getString(R.string.week_more_events, instances.size - maxEventLines)
                    textSize = 11f
                    setTextColor(getColor(R.color.primary))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        if (instances.isEmpty()) {
            events.addView(
                TextView(this).apply {
                    text = getString(R.string.week_no_events)
                    textSize = 11f
                    setTextColor(getColor(R.color.hour_text))
                },
            )
        }
        row.addView(events)
        return row
    }

    private fun buildEventLine(instance: EventInstance): TextView {
        val label = if (instance.allDay) {
            getString(R.string.all_day_label)
        } else {
            LocalDateTime.ofInstant(instance.start, zone).format(timeFormatter)
        }
        val line = SpannableStringBuilder(label).append("  ").append(instance.title)
        line.setSpan(
            ForegroundColorSpan(getColor(R.color.on_surface_variant)),
            0,
            label.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return TextView(this).apply {
            text = line
            textSize = 12f
            setTextColor(getColor(R.color.on_surface))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_EVENT_LINES = 3
        private const val KEY_WEEK = "week_start"
        private const val EXTRA_DATE = "extra_date"

        fun mondayOf(date: LocalDate): LocalDate =
            date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun weekIntent(context: android.content.Context, date: LocalDate) =
            android.content.Intent(context, WeekActivity::class.java)
                .putExtra(EXTRA_DATE, date.toString())
    }
}
