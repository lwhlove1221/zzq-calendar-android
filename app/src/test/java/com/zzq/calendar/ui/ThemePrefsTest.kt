package com.zzq.calendar.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.zzq.calendar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class ThemePrefsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsAreSystemAndBlue() {
        assertEquals(ThemePrefs.MODE_SYSTEM, ThemePrefs.mode(context))
        assertEquals(ThemePrefs.ACCENT_BLUE, ThemePrefs.accent(context))
    }

    @Test
    fun choicesSurviveANewReader() {
        ThemePrefs.setAccent(context, ThemePrefs.ACCENT_PURPLE)
        val reopened = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        assertEquals(ThemePrefs.ACCENT_PURPLE, reopened.getInt("accent", ThemePrefs.ACCENT_BLUE))
        assertTrue(reopened.contains("accent"))
    }

    @Test
    fun modesMapToAppCompatNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemePrefs.nightMode(ThemePrefs.MODE_SYSTEM))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemePrefs.nightMode(ThemePrefs.MODE_LIGHT))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemePrefs.nightMode(ThemePrefs.MODE_DARK))
    }

    @Test
    fun accentsMapToOneOverlayEach() {
        val overlays = listOf(
            ThemePrefs.accentOverlay(ThemePrefs.ACCENT_BLUE),
            ThemePrefs.accentOverlay(ThemePrefs.ACCENT_GREEN),
            ThemePrefs.accentOverlay(ThemePrefs.ACCENT_PURPLE),
        )
        assertEquals(3, overlays.toSet().size)
        assertEquals(R.style.ThemeOverlay_Calendar_Accent_Purple, overlays[2])
    }
}
