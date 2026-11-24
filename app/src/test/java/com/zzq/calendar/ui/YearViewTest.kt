package com.zzq.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.YearMonth
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
class YearViewTest {

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
    fun `year shows twelve month cells inside the view`() {
        seed(LocalDate.of(2026, 7, 15))
        val activity = yearActivity(2026)

        val title = activity.findViewById<TextView>(R.id.yearTitle)
        assertEquals(activity.getString(R.string.year_title, 2026), title.text.toString())

        val grid = activity.findViewById<YearGridView>(R.id.yearGrid)
        val rects = grid.monthRectsForTest()
        assertEquals(12, rects.size)
        assertEquals((1..12).toList(), rects.map { it.first.monthValue })
        rects.forEach { (_, rect) ->
            assertTrue("month cell outside grid: $rect", rect.left >= 0f)
            assertTrue("month cell outside grid: $rect", rect.right <= grid.width + 0.5f)
            assertTrue("month cell outside grid: $rect", rect.bottom <= grid.height + 0.5f)
        }
        val july = rects.first { it.first.monthValue == 7 }
        assertEquals(
            YearMonth.of(2026, 7),
            grid.monthAt(july.second.centerX(), july.second.centerY()),
        )
    }

    @Test
    fun `tapping a month opens the month view`() {
        val activity = yearActivity(2026)
        val grid = activity.findViewById<YearGridView>(R.id.yearGrid)
        val july = grid.monthRectsForTest().first { it.first.monthValue == 7 }

        val down = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN,
            july.second.centerX(),
            july.second.centerY(),
            0,
        )
        val up = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            july.second.centerX(),
            july.second.centerY(),
            0,
        )
        grid.dispatchTouchEvent(down)
        grid.dispatchTouchEvent(up)

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(MonthActivity::class.java.name, started.component?.className)
        assertEquals("2026-07", started.getStringExtra("extra_month"))
    }

    @Test
    fun `next year moves the title`() {
        val activity = yearActivity(2026)

        activity.findViewById<View>(R.id.nextYearButton).performClick()

        val title = activity.findViewById<TextView>(R.id.yearTitle)
        assertEquals(activity.getString(R.string.year_title, 2027), title.text.toString())
    }

    private fun yearActivity(year: Int): YearActivity {
        val activity = Robolectric.buildActivity(
            YearActivity::class.java,
            YearActivity.yearIntent(context, year),
        ).setup().get()
        ShadowLooper.idleMainLooper()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        val width = (360 * density).toInt()
        val height = (640 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        root.draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
        return activity
    }

    private fun seed(day: LocalDate) {
        val start = day.atTime(9, 0)
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = "暑假",
                    allDay = false,
                    startWall = WallTime.format(start),
                    endWall = WallTime.format(start.plusHours(1)),
                    tzid = zone.id,
                ),
            )
        }
    }

    private companion object {
        const val DB_NAME = "year-view-test.db"
    }
}
