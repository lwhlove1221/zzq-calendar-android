package com.zzq.calendar.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ViewSwitchTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTest()
        AppGraph.databaseName = DB_NAME
        AppGraph.ioDispatcher = Dispatchers.Unconfined
        AppGraph.mainDispatcher = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        AppGraph.resetForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun monthTabOpensWeekContainingToday() {
        val today = LocalDate.now()
        val intent = MonthActivity.monthIntent(context, YearMonth.from(today))
        val activity = Robolectric.buildActivity(MonthActivity::class.java, intent).setup().get()

        clickTab(activity, R.string.tab_week)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(today, LocalDate.parse(next.getStringExtra("extra_date")))
    }

    @Test
    fun weekTabOpensMonthOfDisplayedWeek() {
        val weekStart = LocalDate.of(2026, 11, 2)
        val intent = WeekActivity.weekIntent(context, weekStart)
        val activity = Robolectric.buildActivity(WeekActivity::class.java, intent).setup().get()

        clickTab(activity, R.string.tab_month)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals("2026-11", next.getStringExtra("extra_month"))
    }

    @Test
    fun yearTabOpensCurrentMonth() {
        val year = LocalDate.now().year
        val intent = YearActivity.yearIntent(context, year)
        val activity = Robolectric.buildActivity(YearActivity::class.java, intent).setup().get()

        clickTab(activity, R.string.tab_month)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(YearMonth.now().toString(), next.getStringExtra("extra_month"))
    }

    private fun clickTab(activity: AppCompatActivity, labelRes: Int) {
        val bar = activity.findViewById<LinearLayout>(R.id.tabBar)
        val label = activity.getString(labelRes)
        (0 until bar.childCount)
            .map(bar::getChildAt)
            .filterIsInstance<TextView>()
            .first { it.text == label }
            .performClick()
    }

    companion object {
        private const val DB_NAME = "view-switch-test.db"
    }
}
