package com.zzq.calendar.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.ColorUtils
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccessibilityTest {

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
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun assertContrast(foregroundRes: Int, backgroundRes: Int) {
        val foreground = context.getColor(foregroundRes)
        val background = context.getColor(backgroundRes)
        val ratio = ColorUtils.calculateContrast(foreground, background)
        assertTrue("$foregroundRes on $backgroundRes = $ratio", ratio >= 4.5)
    }

    private fun assertDayContrast() {
        assertContrast(R.color.on_surface, R.color.surface)
        assertContrast(R.color.on_surface_variant, R.color.surface)
        assertContrast(R.color.on_primary, R.color.primary)
        assertContrast(R.color.on_event_blue, R.color.event_blue)
        assertContrast(R.color.on_all_day_bg, R.color.all_day_bg)
        assertContrast(R.color.hour_text, R.color.surface)
        assertContrast(R.color.error, R.color.surface)
    }

    @Test
    fun dayPaletteMeetsFourPointFiveToOne() = assertDayContrast()

    @Test
    @Config(qualifiers = "w360dp-h640dp-night-xhdpi")
    fun nightPaletteMeetsFourPointFiveToOne() = assertDayContrast()

    @Test
    fun fontScaleTwoKeepsAllInteractiveTargetsAtLeastFortyFourDp() {
        RuntimeEnvironment.setFontScale(2f)
        seedEvent()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        listOf(
            R.id.previousDayButton,
            R.id.nextDayButton,
            R.id.todayButton,
            R.id.newEventButton,
        ).forEach { id ->
            val view = activity.findViewById<View>(id)
            assertTrue("$id height=${view.height}dp", view.height >= 44 * density - 0.5f)
            assertTrue("$id width=${view.width}dp", view.width >= 44 * density - 0.5f)
        }

        val list = activity.findViewById<LinearLayout>(R.id.eventListContainer)
        val row = list.getChildAt(0)
        assertTrue("row height=${row.height}", row.height >= 44 * density - 0.5f)
        val title = row.findViewById<android.widget.TextView>(R.id.eventTitle)
        assertTrue("title height=${title.height}", title.height > 0)
        assertTrue("title width=${title.width}", title.width > 0)
        assertTrue(title.lineCount >= 1)
    }

    @Test
    fun editorFontScaleTwoKeepsSaveAndCancelTargets() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(EventEditActivity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        listOf(R.id.saveButton, R.id.cancelButton, R.id.startDateButton, R.id.startTimeButton).forEach { id ->
            val view = activity.findViewById<View>(id)
            assertTrue("$id height=${view.height}", view.height >= 44 * density - 0.5f)
        }
        val title = activity.findViewById<android.widget.EditText>(R.id.titleInput)
        assertTrue(title.width > 0)
    }

    @Test
    fun dayHeaderFontScaleTwoShowsMonthButtonAndTitleInFull() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        root.measure(
            View.MeasureSpec.makeMeasureSpec((320 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        listOf(R.id.monthButton, R.id.dateTitle).forEach { id ->
            val view = activity.findViewById<android.widget.TextView>(id)
            val layout = view.layout
            assertTrue("$id lineCount=${view.lineCount}", view.lineCount >= 1)
            assertTrue(
                "$id height=${view.height} textBottom=${layout.getLineBottom(view.lineCount - 1)}",
                view.height >= layout.getLineBottom(view.lineCount - 1),
            )
            assertTrue("$id width=${view.width}", layout.getLineWidth(view.lineCount - 1) <= view.width)
        }
    }

    private fun seedEvent() {
        val start = LocalDateTime.now().withHour(9).withMinute(0)
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = "放大字号测试",
                    allDay = false,
                    startWall = com.zzq.calendar.model.WallTime.format(start),
                    endWall = com.zzq.calendar.model.WallTime.format(start.plusHours(1)),
                    tzid = ZoneId.systemDefault().id,
                ),
            )
        }
    }

    companion object {
        private const val DB_NAME = "a11y-test.db"
    }
}
