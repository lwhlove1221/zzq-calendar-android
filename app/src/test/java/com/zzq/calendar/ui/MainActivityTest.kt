package com.zzq.calendar.ui

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RepeatSpec
import java.time.LocalDate
import java.time.LocalDateTime
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MainActivityTest {

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

    private fun seed(title: String, start: LocalDateTime = LocalDateTime.now().withHour(9).withMinute(0)) =
        runBlocking {
            AppGraph.repository(context).create(
                EventDraft(
                    title = title,
                    allDay = false,
                    startWall = com.zzq.calendar.model.WallTime.format(start),
                    endWall = com.zzq.calendar.model.WallTime.format(start.plusHours(1)),
                    tzid = zone.id,
                ),
            )
        }

    private fun activityRoot(activity: MainActivity): View =
        activity.window.decorView.findViewById(android.R.id.content)

    private fun measureAndLayout(activity: MainActivity, widthDp: Int = 360, heightDp: Int = 720) {
        val density = activity.resources.displayMetrics.density
        val root = activityRoot(activity)
        root.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).toInt(), View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }

    @Test
    fun createdEventIsVisibleInDayViewAndAfterActivityRestart() {
        seed("产品评审")

        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = first.get()
        measureAndLayout(activity)

        val list = activity.findViewById<LinearLayout>(R.id.eventListContainer)
        assertEquals(1, list.childCount)
        val rowTitle = list.getChildAt(0).findViewById<TextView>(R.id.eventTitle)
        assertEquals("产品评审", rowTitle.text.toString())
        assertEquals(View.VISIBLE, rowTitle.visibility)
        assertTrue(rowTitle.height > 0)
        assertEquals(0, rowTitle.layout.getEllipsisCount(rowTitle.lineCount - 1))

        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(MainActivity::class.java).setup()
        val restarted = second.get()
        measureAndLayout(restarted)
        val restartedList = restarted.findViewById<LinearLayout>(R.id.eventListContainer)
        assertEquals(1, restartedList.childCount)
        assertEquals(
            "产品评审",
            restartedList.getChildAt(0).findViewById<TextView>(R.id.eventTitle).text.toString(),
        )
    }

    @Test
    fun emptyDayShowsEmptyStateAndNewEventButton() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        measureAndLayout(activity)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyView).visibility)
        assertNotNull(activity.findViewById<View>(R.id.newEventButton))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-night-xhdpi")
    fun darkThemeActivityStillRendersEventRow() {
        seed("夜间日程")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        measureAndLayout(activity)
        val list = activity.findViewById<LinearLayout>(R.id.eventListContainer)
        assertEquals(1, list.childCount)
    }

    companion object {
        private const val DB_NAME = "ui-test.db"
    }
}
