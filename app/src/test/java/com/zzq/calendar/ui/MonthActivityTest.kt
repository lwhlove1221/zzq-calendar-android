package com.zzq.calendar.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.MonthGrid
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MonthActivityTest {

    private lateinit var context: Context
    private val zone: ZoneId get() = ZoneId.systemDefault()

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
    fun `month shows six by seven grid with event dots`() {
        seed("产品评审", 0)
        seed("客户电话", 0)
        seed("健身", 1)

        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()

        val today = LocalDate.now(zone)
        val title = activity.findViewById<TextView>(R.id.monthTitle)
        assertEquals(getString(activity, R.string.month_title, today.year, today.monthValue), title.text.toString())

        val grid = activity.findViewById<LinearLayout>(R.id.monthGrid)
        assertEquals(MonthGrid.ROWS, grid.childCount)
        assertEquals(MonthGrid.COLUMNS, (grid.getChildAt(0) as LinearLayout).childCount)

        val todayCell = findCell(activity, today)
        assertNotNull(todayCell)
        assertEquals(
            getString(activity, R.string.month_cell_description, today.monthValue, today.dayOfMonth, 2),
            todayCell?.contentDescription?.toString(),
        )
        val dots = (todayCell as ViewGroup).getChildAt(1) as TextView
        assertEquals("●●", dots.text.toString())
    }

    @Test
    fun `tapping a day opens the day view for that date`() {
        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val day = LocalDate.now(zone)

        findCell(activity, day)?.performClick()

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(day.toString(), started.getStringExtra(MainActivity.EXTRA_DAY))
    }

    @Test
    fun `month navigation changes the title`() {
        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val next = YearMonth.now(zone).plusMonths(1)

        activity.findViewById<View>(R.id.nextMonthButton).performClick()

        val title = activity.findViewById<TextView>(R.id.monthTitle)
        assertEquals(getString(activity, R.string.month_title, next.year, next.monthValue), title.text.toString())
    }

    @Test
    fun `no calendar permission still opens the month view and shows the hint`() {
        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()

        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.calendarHint).visibility)
        assertEquals(MonthGrid.ROWS, activity.findViewById<LinearLayout>(R.id.monthGrid).childCount)
    }

    @Test
    fun `calendar permission hides the hint`() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CALENDAR)

        val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.calendarHint).visibility)
    }

    @Test
    fun `tabs open the week and year views`() {
        val weekActivity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val weekBar = weekActivity.findViewById<LinearLayout>(R.id.tabBar)
        assertEquals(4, weekBar.childCount)

        weekBar.getChildAt(2).performClick()

        assertEquals(
            WeekActivity::class.java.name,
            Shadows.shadowOf(weekActivity).nextStartedActivity.component?.className,
        )

        val yearActivity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        yearActivity.findViewById<LinearLayout>(R.id.tabBar).getChildAt(3).performClick()

        assertEquals(
            YearActivity::class.java.name,
            Shadows.shadowOf(yearActivity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `two hundred percent font keeps every cell tappable`() {
        seed("产品评审", 0)
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            val activity = Robolectric.buildActivity(MonthActivity::class.java).setup().get()
            ShadowLooper.idleMainLooper()
            val root = activity.window.decorView.findViewById<View>(android.R.id.content)
            val density = activity.resources.displayMetrics.density
            val width = (360 * density).toInt()
            val height = (640 * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)

            val grid = activity.findViewById<LinearLayout>(R.id.monthGrid)
            val cell = (grid.getChildAt(0) as LinearLayout).getChildAt(0)
            assertTrue(cell.isClickable)
            assertTrue(cell.height >= activity.resources.getDimensionPixelSize(R.dimen.touch_target_min))
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun findCell(activity: MonthActivity, date: LocalDate): View? {
        val grid = activity.findViewById<LinearLayout>(R.id.monthGrid)
        repeat(grid.childCount) { rowIndex ->
            val row = grid.getChildAt(rowIndex) as LinearLayout
            repeat(row.childCount) { columnIndex ->
                val cell = row.getChildAt(columnIndex)
                val number = (cell as ViewGroup).getChildAt(0) as TextView
                if (number.text.toString() == date.dayOfMonth.toString() &&
                    cell.contentDescription.toString().startsWith("${date.monthValue}月")
                ) {
                    return cell
                }
            }
        }
        return null
    }

    private fun seed(title: String, dayOffset: Long) {
        val day = LocalDate.now(zone).plusDays(dayOffset)
        val start = day.atTime(9, 0)
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = title,
                    allDay = false,
                    startWall = WallTime.format(start),
                    endWall = WallTime.format(start.plusHours(1)),
                    tzid = zone.id,
                ),
            )
        }
    }

    private fun getString(activity: MonthActivity, id: Int, vararg args: Any): String =
        activity.getString(id, *args)

    private companion object {
        const val DB_NAME = "month-activity-test.db"
    }
}
