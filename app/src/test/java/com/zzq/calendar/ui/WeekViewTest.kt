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
import org.robolectric.RuntimeEnvironment
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
    fun `week shows seven readable day rows inside one screen`() {
        val today = LocalDate.now(zone)
        seed("产品评审", today, 9)
        seed("客户电话", today, 10)

        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        val rows = activity.findViewById<LinearLayout>(R.id.weekRows)
        assertEquals(7, rows.childCount)
        val monday = WeekActivity.mondayOf(today)
        assertTrue(
            "monday day number missing: ${texts(rows.getChildAt(0))}",
            texts(rows.getChildAt(0)).contains(monday.dayOfMonth.toString()),
        )
        val density = activity.resources.displayMetrics.density
        repeat(7) { index ->
            val row = rows.getChildAt(index)
            assertTrue("row $index height=${row.height}", row.height >= 44 * density)
        }
        assertTrue(
            "week rows ${rows.height} exceed viewport ${(rows.parent as View).height}",
            rows.height <= (rows.parent as View).height,
        )

        val todayRow = rows.getChildAt(today.dayOfWeek.value - 1)
        val todayLines = texts(todayRow)
        assertTrue("today titles=$todayLines", todayLines.any { it.contains("产品评审") && it.contains("09:00") })
        assertTrue("today titles=$todayLines", todayLines.any { it.contains("客户电话") && it.contains("10:00") })
        val emptyRow = rows.getChildAt((today.dayOfWeek.value) % 7)
        if (emptyRow != todayRow) {
            assertTrue("empty row=${texts(emptyRow)}", texts(emptyRow).contains("无日程"))
        }
    }

    @Test
    fun `a crowded day keeps three lines and counts the rest`() {
        val today = LocalDate.now(zone)
        repeat(5) { index -> seed("会议$index", today, 9 + index) }

        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        layout(activity)

        val rows = activity.findViewById<LinearLayout>(R.id.weekRows)
        val crowdedLines = texts(rows.getChildAt(today.dayOfWeek.value - 1))
        assertEquals(3, crowdedLines.count { it.contains("会议") })
        assertTrue("crowded lines=$crowdedLines", crowdedLines.contains("+2 条"))
    }

    @Test
    fun `large font keeps fewer lines plus the overflow count`() {
        val today = LocalDate.now(zone)
        repeat(5) { index -> seed("会议$index", today, 9 + index) }
        RuntimeEnvironment.setFontScale(2f)
        try {
            val activity = Robolectric.buildActivity(
                WeekActivity::class.java,
                WeekActivity.weekIntent(context, today),
            ).setup().get()
            ShadowLooper.idleMainLooper()
            layout(activity)

            val rows = activity.findViewById<LinearLayout>(R.id.weekRows)
            val crowdedLines = texts(rows.getChildAt(today.dayOfWeek.value - 1))
            assertEquals(1, crowdedLines.count { it.contains("会议") })
            assertTrue("crowded lines=$crowdedLines", crowdedLines.contains("+4 条"))
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun `tapping a day row opens the day view`() {
        val today = LocalDate.now(zone)
        val activity = Robolectric.buildActivity(
            WeekActivity::class.java,
            WeekActivity.weekIntent(context, today),
        ).setup().get()
        ShadowLooper.idleMainLooper()

        val rows = activity.findViewById<LinearLayout>(R.id.weekRows)
        rows.getChildAt(0).performClick()

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

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private companion object {
        const val DB_NAME = "week-view-test.db"
    }
}
