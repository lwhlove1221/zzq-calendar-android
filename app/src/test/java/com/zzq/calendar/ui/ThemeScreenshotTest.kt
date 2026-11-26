package com.zzq.calendar.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.AppGraph
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.model.WallTime
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Renders the 3x3 theme matrix and the view/settings screens for visual review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ThemeScreenshotTest {

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
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        seed()
    }

    @After
    fun tearDown() {
        AppGraph.resetForTest()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        RuntimeEnvironment.setQualifiers(BASE_QUALIFIERS)
    }

    @Test
    fun renderNineModeAccentCombos() {
        val modes = listOf(
            ThemePrefs.MODE_SYSTEM to "system",
            ThemePrefs.MODE_LIGHT to "light",
            ThemePrefs.MODE_DARK to "dark",
        )
        val accents = listOf(
            ThemePrefs.ACCENT_BLUE to "blue",
            ThemePrefs.ACCENT_GREEN to "green",
            ThemePrefs.ACCENT_PURPLE to "purple",
        )
        modes.forEach { (mode, modeName) ->
            accents.forEach { (accent, accentName) ->
                saveTheme(mode, accent)
                RuntimeEnvironment.setQualifiers(
                    if (mode == ThemePrefs.MODE_DARK) NIGHT_QUALIFIERS else BASE_QUALIFIERS,
                )
                capture(MonthActivity::class.java, "theme-$modeName-$accentName.png")
                RuntimeEnvironment.setQualifiers(BASE_QUALIFIERS)
            }
        }
    }

    @Test
    fun renderFourViewsInLightAndDark() {
        listOf(false to "light", true to "dark").forEach { (dark, name) ->
            saveTheme(if (dark) ThemePrefs.MODE_DARK else ThemePrefs.MODE_LIGHT, ThemePrefs.ACCENT_BLUE)
            RuntimeEnvironment.setQualifiers(if (dark) NIGHT_QUALIFIERS else BASE_QUALIFIERS)
            capture(MainActivity::class.java, "view-day-$name.png")
            capture(WeekActivity::class.java, "view-week-$name.png")
            capture(MonthActivity::class.java, "view-month-$name.png")
            capture(YearActivity::class.java, "view-year-$name.png")
            RuntimeEnvironment.setQualifiers(BASE_QUALIFIERS)
        }
    }

    @Test
    fun renderSettingsInLightAndDark() {
        listOf(false to "light", true to "dark").forEach { (dark, name) ->
            saveTheme(if (dark) ThemePrefs.MODE_DARK else ThemePrefs.MODE_LIGHT, ThemePrefs.ACCENT_BLUE)
            RuntimeEnvironment.setQualifiers(if (dark) NIGHT_QUALIFIERS else BASE_QUALIFIERS)
            capture(SettingsActivity::class.java, "settings-$name.png")
            RuntimeEnvironment.setQualifiers(BASE_QUALIFIERS)
        }
    }

    private fun saveTheme(mode: Int, accent: Int) {
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit()
            .putInt("mode", mode)
            .putInt("accent", accent)
            .commit()
    }

    private fun seed() {
        val today = LocalDate.now()
        listOf(
            Triple("产品评审", 9 * 60, 0L),
            Triple("客户电话", 10 * 60 + 30, 0L),
            Triple("健身", 19 * 60, 1L),
            Triple("一对一", 14 * 60, 3L),
        ).forEach { (title, minute, offset) ->
            val start = today.plusDays(offset).atTime(minute / 60, minute % 60)
            runBlocking {
                AppGraph.repository(context).create(
                    EventDraft(
                        title = title,
                        allDay = false,
                        startWall = WallTime.format(start),
                        endWall = WallTime.format(start.plusMinutes(45)),
                        tzid = zone.id,
                    ),
                )
            }
        }
    }

    private fun capture(activityClass: Class<out Activity>, name: String) {
        val activity = Robolectric.buildActivity(activityClass).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
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
        private const val BASE_QUALIFIERS = "w360dp-h640dp-xhdpi"
        private const val NIGHT_QUALIFIERS = "w360dp-h640dp-night-xhdpi"
        private const val DB_NAME = "theme-screenshot-test.db"
    }
}
