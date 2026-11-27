package com.zzq.calendar.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.R as MaterialR
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class SettingsActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTest()
        AppGraph.databaseName = DB_NAME
        AppGraph.ioDispatcher = Dispatchers.Unconfined
        AppGraph.mainDispatcher = Dispatchers.Unconfined
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        AppGraph.resetForTest()
        context.deleteDatabase(DB_NAME)
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun allFiveDataActionsAreListed() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val list = activity.findViewById<LinearLayout>(R.id.actionList)
        val labels = (0 until list.childCount).map { (list.getChildAt(it) as TextView).text.toString() }

        assertEquals(
            listOf(
                R.string.import_system_calendar,
                R.string.sync_conflicts,
                R.string.import_ics,
                R.string.export_ics_all,
                R.string.export_backup_all,
            ).map(activity::getString),
            labels,
        )
    }

    @Test
    fun settingsTabIsSelectedOnTheSettingsScreen() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val bar = activity.findViewById<LinearLayout>(R.id.tabBar)
        val settings = (0 until bar.childCount)
            .map(bar::getChildAt)
            .filterIsInstance<TextView>()
            .single { it.text == activity.getString(R.string.settings) }

        assertTrue(settings.isSelected)
    }

    @Test
    fun pickingAnAccentStoresItImmediately() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        activity.findViewById<android.widget.RadioButton>(R.id.accentGreenButton).performClick()

        assertEquals(ThemePrefs.ACCENT_GREEN, ThemePrefs.accent(activity))
    }

    @Test
    fun coldStartRestoresSavedChoices() {
        // Written straight to prefs: CalendarApp applies the night mode before any activity.
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit()
            .putInt("mode", ThemePrefs.MODE_DARK)
            .putInt("accent", ThemePrefs.ACCENT_PURPLE)
            .commit()

        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        assertTrue(activity.findViewById<android.widget.RadioButton>(R.id.accentPurpleButton).isChecked)
        assertTrue(activity.findViewById<android.widget.RadioButton>(R.id.themeDarkButton).isChecked)
        assertEquals(ThemePrefs.ACCENT_PURPLE, ThemePrefs.accent(activity))
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-night-xhdpi")
    fun accentOverlayAppliesInsideSettings() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        assertEquals(
            activity.getColor(R.color.accent_blue),
            activity.themeColor(MaterialR.attr.colorPrimary),
        )
    }

    companion object {
        private const val DB_NAME = "settings-test.db"
    }
}
