package com.zzq.calendar.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.zzq.calendar.R

/** Light/dark + accent choice; stored in SharedPreferences so it survives a cold start. */
object ThemePrefs {

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2
    const val ACCENT_BLUE = 0
    const val ACCENT_GREEN = 1
    const val ACCENT_PURPLE = 2

    private const val KEY_MODE = "mode"
    private const val KEY_ACCENT = "accent"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    fun mode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_SYSTEM)

    fun accent(context: Context): Int = prefs(context).getInt(KEY_ACCENT, ACCENT_BLUE)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit { putInt(KEY_MODE, mode) }
        AppCompatDelegate.setDefaultNightMode(nightMode(mode))
    }

    fun setAccent(context: Context, accent: Int) {
        prefs(context).edit { putInt(KEY_ACCENT, accent) }
    }

    fun nightMode(mode: Int): Int = when (mode) {
        MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun accentOverlay(accent: Int): Int = when (accent) {
        ACCENT_GREEN -> R.style.ThemeOverlay_Calendar_Accent_Green
        ACCENT_PURPLE -> R.style.ThemeOverlay_Calendar_Accent_Purple
        else -> R.style.ThemeOverlay_Calendar_Accent_Blue
    }

    /** Must run before the layout is inflated so the accent reaches every view. */
    fun applyAccent(activity: AppCompatActivity) {
        activity.theme.applyStyle(accentOverlay(accent(activity)), true)
    }
}
