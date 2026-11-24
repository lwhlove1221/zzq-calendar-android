package com.zzq.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class WeekViewTest {

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
    fun `week shows seven day headers and event bars in columns`() {
        val today = LocalDate.now(zone)
        seed("产品评审", today, 9)
        seed("客户电话", today.plusDays(1), 10)

        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        val header = activity.findViewById<LinearLayout>(R.id.dayHeaderRow)
        assertEquals(7, header.childCount)
        assertEquals(
            WeekActivity.mondayOf(today).dayOfMonth,
            ((header.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).text.toString().toInt(),
        )

        val timeline = activity.findViewById<WeekTimelineView>(R.id.weekTimeline)
        val columns = timeline.columnsForTest()
        assertEquals(7, columns.size)
        val columnWidth = columns[1] - columns[0]
        val bars = timeline.visualBarsForTest()
        assertEquals(2, bars.size)
        bars.forEach { bar ->
            val column = ((bar.centerX() - columns.first()) / columnWidth).toInt()
            assertTrue("bar outside week columns: $bar", column in 0..6)
        }
    }

    @Test
    fun `tapping a day header opens the day view`() {
        val today = LocalDate.now(zone)
        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()

        val header = activity.findViewById<LinearLayout>(R.id.dayHeaderRow)
        header.getChildAt(0).performClick()

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertEquals(WeekActivity.mondayOf(today).toString(), started.getStringExtra(MainActivity.EXTRA_DAY))
    }

    @Test
    fun `next week moves the title`() {
        val today = LocalDate.now(zone)
        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        val nextStart = WeekActivity.mondayOf(today).plusWeeks(1)
        val nextEnd = nextStart.plusDays(6)

        activity.findViewById<View>(R.id.nextWeekButton).performClick()

        val title = activity.findViewById<TextView>(R.id.weekTitle)
        assertEquals(
            activity.getString(
                R.string.week_title,
                nextStart.year,
                nextStart.monthValue,
                nextStart.dayOfMonth,
                nextEnd.monthValue,
                nextEnd.dayOfMonth,
            ),
            title.text.toString(),
        )
    }

    private fun layout(activity: WeekActivity) {
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        val width = (360 * density).toInt()
        val height = (720 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        root.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
    }

    private fun seed(title: String, day: LocalDate, hour: Int) {
        val start = day.atTime(hour, 0)
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

    private companion object {
        const val DB_NAME = "week-view-test.db"
    }
}
