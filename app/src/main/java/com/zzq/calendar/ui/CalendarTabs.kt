package com.zzq.calendar.ui

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zzq.calendar.R
import java.time.LocalDate
import java.time.YearMonth

/** Bottom switcher shared by all four screens: 今天 | 周 | 月 | 年. */
object CalendarTabs {

    enum class Tab(val labelRes: Int) {
        DAY(R.string.today),
        WEEK(R.string.tab_week),
        MONTH(R.string.tab_month),
        YEAR(R.string.tab_year),
    }

    fun attach(
        activity: AppCompatActivity,
        bar: LinearLayout,
        current: Tab,
        anchor: () -> LocalDate,
        onToday: () -> Unit = {},
    ) {
        bar.removeAllViews()
        Tab.entries.forEach { tab ->
            bar.addView(
                item(activity, activity.getString(tab.labelRes), selected = tab == current) {
                    if (tab == current) {
                        if (tab == Tab.DAY) onToday()
                        return@item
                    }
                    when (tab) {
                        Tab.DAY -> activity.startActivity(MainActivity.dayIntent(activity, LocalDate.now()))
                        Tab.WEEK -> activity.startActivity(WeekActivity.weekIntent(activity, anchor()))
                        Tab.MONTH -> activity.startActivity(
                            MonthActivity.monthIntent(activity, YearMonth.from(anchor())),
                        )
                        Tab.YEAR -> activity.startActivity(YearActivity.yearIntent(activity, anchor().year))
                    }
                    activity.finish()
                },
            )
        }
    }

    fun monthAnchor(month: YearMonth, today: LocalDate): LocalDate =
        if (YearMonth.from(today) == month) today else month.atDay(1)

    fun yearAnchor(year: Int, today: LocalDate): LocalDate =
        if (year == today.year) today else LocalDate.of(year, 1, 1)

    private fun item(
        activity: AppCompatActivity,
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(
            ContextCompat.getColor(
                activity,
                if (selected) R.color.primary else R.color.on_surface_variant,
            ),
        )
        if (selected) setTypeface(typeface, Typeface.BOLD)
        isSelected = selected
        background = selectableBackground(activity)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            0,
            activity.resources.getDimensionPixelSize(R.dimen.touch_target_min),
            1f,
        )
    }

    private fun selectableBackground(activity: AppCompatActivity): Drawable? {
        val value = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return ContextCompat.getDrawable(activity, value.resourceId)
    }
}
