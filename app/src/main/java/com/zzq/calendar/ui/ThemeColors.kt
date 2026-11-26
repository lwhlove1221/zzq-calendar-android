package com.zzq.calendar.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.zzq.calendar.R

/** Theme attr lookup for code-drawn views, so the accent follows the selected overlay. */
fun Context.themeColor(attrRes: Int): Int =
    MaterialColors.getColor(this, attrRes, ContextCompat.getColor(this, R.color.on_surface))
