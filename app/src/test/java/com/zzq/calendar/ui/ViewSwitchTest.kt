package com.zzq.calendar.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
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
import org.junit.Assert.assertTrue
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

    @Test
    fun tabBarOrderIsTodayWeekMonthYearSettings() {
        val activity = Robolectric.buildActivity(WeekActivity::class.java).setup().get()
        val bar = activity.findViewById<LinearLayout>(R.id.tabBar)
        val expected = listOf(
            R.string.today,
            R.string.tab_week,
            R.string.tab_month,
            R.string.tab_year,
            R.string.settings,
        ).map(activity::getString)

        val actual = (0 until bar.childCount)
            .map(bar::getChildAt)
            .filterIsInstance<TextView>()
            .map { it.text.toString() }

        assertEquals(expected, actual)
    }

    @Test
    fun todayTabOpensDayViewForToday() {
        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()

        clickTab(activity, R.string.today)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertEquals(LocalDate.now().toString(), next.getStringExtra(MainActivity.EXTRA_DAY))
    }

    @Test
    fun dayViewShowsTabsWithTodaySelected() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bar = activity.findViewById<LinearLayout>(R.id.tabBar)

        assertEquals(5, bar.childCount)
        val first = bar.getChildAt(0) as TextView
        assertEquals(activity.getString(R.string.today), first.text.toString())
        assertTrue(first.isSelected)
    }

    @Test
    fun settingsTabOpensSettingsScreen() {
        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()

        clickTab(activity, R.string.settings)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, next.component?.className)
    }

    @Test
    fun dayViewKeepsDisplayedDateAsAnchorWhenSwitchingViews() {
        val day = LocalDate.of(2026, 10, 15)
        val intent = MainActivity.dayIntent(context, day)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()

        clickTab(activity, R.string.tab_week)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals(day.toString(), next.getStringExtra("extra_date"))
    }

    @Test
    fun monthTabAfterWeekSwipeUsesTheNewWeekStartAsAnchor() {
        val start = LocalDate.of(2026, 11, 30)
        val intent = WeekActivity.weekIntent(context, start)
        val activity = Robolectric.buildActivity(WeekActivity::class.java, intent).setup().get()

        swipe(activity, -200f)
        clickTab(activity, R.string.tab_month)

        val next = shadowOf(activity).nextStartedActivity
        assertEquals("2026-12", next.getStringExtra("extra_month"))
    }

    private fun swipe(activity: AppCompatActivity, dxDp: Float) {
        val pager = activity.findViewById<View>(R.id.swipeSurface)
        val density = activity.resources.displayMetrics.density
        val startX = 600f
        val startY = 300f
        val endX = startX + dxDp * density
        fun event(action: Int, x: Float) = MotionEvent.obtain(0L, 0L, action, x, startY, 0)
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, startX))
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, (startX + endX) / 2f))
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_UP, endX))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
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
