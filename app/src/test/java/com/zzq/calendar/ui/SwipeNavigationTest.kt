package com.zzq.calendar.ui

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SwipeNavigationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
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
    fun dayViewSwipesToNextDayAndBack() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(activity.mainLooper).idle()

        swipe(activity, dxDp = -200f)

        assertEquals(
            UiFormat.dateTitle(LocalDate.now().plusDays(1)),
            activity.findViewById<TextView>(R.id.dateTitle).text.toString(),
        )

        swipe(activity, dxDp = 200f)

        assertEquals(
            UiFormat.dateTitle(LocalDate.now()),
            activity.findViewById<TextView>(R.id.dateTitle).text.toString(),
        )
    }

    @Test
    fun weekViewSwipesToNextWeek() {
        val start = LocalDate.of(2026, 11, 2)
        val activity = Robolectric
            .buildActivity(WeekActivity::class.java, WeekActivity.weekIntent(context, start))
            .setup()
            .get()
        shadowOf(activity.mainLooper).idle()
        val title = activity.findViewById<TextView>(R.id.weekTitle)
        val before = title.text.toString()

        swipe(activity, dxDp = -200f)

        assertNotEquals(before, title.text.toString())
        assertEquals(
            activity.getString(R.string.week_title, 2026, 11, 9, 11, 15),
            title.text.toString(),
        )
    }

    @Test
    fun monthViewSwipesToNextMonth() {
        val activity = Robolectric
            .buildActivity(MonthActivity::class.java, MonthActivity.monthIntent(context, YearMonth.of(2026, 11)))
            .setup()
            .get()
        shadowOf(activity.mainLooper).idle()

        swipe(activity, dxDp = -200f)

        assertEquals(
            activity.getString(R.string.month_title, 2026, 12),
            activity.findViewById<TextView>(R.id.monthTitle).text.toString(),
        )
    }

    @Test
    fun yearViewSwipesToNextYear() {
        val activity = Robolectric
            .buildActivity(YearActivity::class.java, YearActivity.yearIntent(context, 2026))
            .setup()
            .get()
        shadowOf(activity.mainLooper).idle()

        swipe(activity, dxDp = -200f)

        assertEquals(
            activity.getString(R.string.year_title, 2027),
            activity.findViewById<TextView>(R.id.yearTitle).text.toString(),
        )
    }

    @Test
    fun verticalDragDoesNotChangeTheMonth() {
        val activity = Robolectric
            .buildActivity(MonthActivity::class.java, MonthActivity.monthIntent(context, YearMonth.of(2026, 11)))
            .setup()
            .get()
        shadowOf(activity.mainLooper).idle()
        val before = activity.findViewById<TextView>(R.id.monthTitle).text.toString()

        swipe(activity, dxDp = -200f, dyDp = 400f)

        assertEquals(before, activity.findViewById<TextView>(R.id.monthTitle).text.toString())
    }

    @Test
    fun monthViewDoesNotOvershootTheRangeEnd() {
        val activity = Robolectric
            .buildActivity(MonthActivity::class.java, MonthActivity.monthIntent(context, YearMonth.of(2100, 12)))
            .setup()
            .get()
        shadowOf(activity.mainLooper).idle()
        val before = activity.findViewById<TextView>(R.id.monthTitle).text.toString()

        swipe(activity, dxDp = -200f)

        assertEquals(before, activity.findViewById<TextView>(R.id.monthTitle).text.toString())
    }

    private fun swipe(activity: Activity, dxDp: Float, dyDp: Float = 0f) {
        val pager = activity.findViewById<View>(R.id.swipeSurface)
        val density = activity.resources.displayMetrics.density
        val startX = 600f
        val startY = 300f
        val endX = startX + dxDp * density
        val endY = startY + dyDp * density
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, startX, startY))
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, (startX + endX) / 2f, (startY + endY) / 2f))
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, endX, endY))
        pager.dispatchTouchEvent(event(MotionEvent.ACTION_UP, endX, endY))
        shadowOf(activity.mainLooper).idle()
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    companion object {
        private const val DB_NAME = "swipe-navigation-test.db"
    }
}
