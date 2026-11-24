package com.zzq.calendar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.WallTime
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScreenshotTest {

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

    private fun seed(title: String, startMinute: Int, durationMinutes: Int, dayOffset: Long = 0) {
        val day = LocalDate.now().plusDays(dayOffset)
        val start = day.atTime(startMinute / 60, startMinute % 60)
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = title,
                    allDay = false,
                    startWall = WallTime.format(start),
                    endWall = WallTime.format(start.plusMinutes(durationMinutes.toLong())),
                    tzid = zone.id,
                ),
            )
        }
    }

    private fun seedAllDay(title: String) {
        val day = LocalDate.now()
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = title,
                    allDay = true,
                    startWall = WallTime.format(day.atStartOfDay()),
                    endWall = WallTime.format(day.plusDays(1).atStartOfDay()),
                    tzid = zone.id,
                ),
            )
        }
    }

    @Test
    fun renderDayView() {
        seedAllDay("公司团建")
        seed("产品评审", 9 * 60, 60)
        seed("客户电话", 10 * 60 + 30, 45)
        seed("一对一", 10 * 60 + 45, 30)
        seed("健身", 19 * 60, 60, dayOffset = 1)

        val repository = AppGraph.repository(context)
        println(
            "screenshot seed: all=${runBlocking { repository.loadAll() }.size} " +
                "today=${runBlocking { repository.loadDay(LocalDate.now(), zone) }.timed.size}",
        )

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        println("screenshot sameRepo=${AppGraph.repository(activity) === repository}")
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        val width = (360 * density).toInt()
        val height = (720 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)

        val allDayContainer = activity.findViewById<android.widget.LinearLayout>(R.id.allDayContainer)
        val eventList = activity.findViewById<android.widget.LinearLayout>(R.id.eventListContainer)
        val timeline = activity.findViewById<DayTimelineView>(R.id.timeline)
        println(
            "screenshot state: allDay=${allDayContainer.childCount} list=${eventList.childCount} " +
                "timelineBars=${timeline.visualBarsForTest().size} timelineHeight=${timeline.height}",
        )

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)

        val output = File("build/screenshots")
        output.mkdirs()
        File(output, "day-view.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun renderDayViewAtTwoHundredPercentFont() {
        seedAllDay("公司团建")
        seed("产品评审", 9 * 60, 60)
        seed("客户电话", 10 * 60 + 30, 45)
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val activity = controller.get()
            val density = activity.resources.displayMetrics.density
            val root = activity.window.decorView.findViewById<View>(android.R.id.content)
            val width = (360 * density).toInt()
            val height = (720 * density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("build/screenshots")
            output.mkdirs()
            File(output, "day-view-200.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun renderExportEntryPoints() {
        seed("产品评审", 9 * 60, 60)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        capture(main, "export-main.png")

        val eventId = runBlocking { AppGraph.repository(context).loadAll() }.first().master.id
        val editor = Robolectric.buildActivity(EventEditActivity::class.java, EventEditActivity.editIntent(context, eventId))
            .setup()
            .get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        capture(editor, "export-edit.png")
    }

    @Test
    fun renderMonthView() {
        seedMonthEvents()
        capture(MonthActivity::class.java, "month-view.png")
    }

    @Test
    fun renderMonthViewAtTwoHundredPercentFont() {
        seedMonthEvents()
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            capture(MonthActivity::class.java, "month-view-200.png")
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun renderMonthViewDark() {
        seedMonthEvents()
        try {
            org.robolectric.RuntimeEnvironment.setQualifiers("+night")
            capture(MonthActivity::class.java, "month-view-dark.png")
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
        }
    }

    @Test
    fun renderWeekView() {
        seedMonthEvents()
        capture(WeekActivity::class.java, "week-view.png")
    }

    @Test
    fun renderWeekViewAtTwoHundredPercentFont() {
        seedMonthEvents()
        org.robolectric.RuntimeEnvironment.setFontScale(2f)
        try {
            capture(WeekActivity::class.java, "week-view-200.png")
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun renderYearView() {
        seedMonthEvents()
        capture(YearActivity::class.java, "year-view.png")
    }

    @Test
    fun renderYearViewDark() {
        seedMonthEvents()
        try {
            org.robolectric.RuntimeEnvironment.setQualifiers("+night")
            capture(YearActivity::class.java, "year-view-dark.png")
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-xhdpi")
        }
    }

    private fun seedMonthEvents() {
        seedAllDay("公司团建")
        seed("产品评审", 9 * 60, 60)
        seed("客户电话", 10 * 60 + 30, 45)
        seed("健身", 19 * 60, 60, dayOffset = 1)
        seed("一对一", 14 * 60, 30, dayOffset = 3)
    }

    private fun capture(activityClass: Class<out android.app.Activity>, name: String) {
        val activity = Robolectric.buildActivity(activityClass).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        capture(activity, name)
    }

    private fun capture(activity: android.app.Activity, name: String) {
        val density = activity.resources.displayMetrics.density
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        val width = (360 * density).toInt()
        val height = (720 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val output = File("build/screenshots")
        output.mkdirs()
        File(output, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        private const val DB_NAME = "screenshot-test.db"
    }
}
