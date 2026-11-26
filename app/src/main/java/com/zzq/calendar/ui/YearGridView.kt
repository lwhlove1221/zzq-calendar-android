package com.zzq.calendar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.zzq.calendar.R
import com.zzq.calendar.model.MonthGrid
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.min

/** Twelve mini month calendars; a whole month cell is the tap target. */
class YearGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onMonthClick: ((YearMonth) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cellGap = 6f * density

    private val monthTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // dp text keeps the dense overview stable at 200% font.
        textSize = 11f * density
        color = ContextCompat.getColor(context, R.color.on_surface)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val weekdayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.5f * density
        color = ContextCompat.getColor(context, R.color.hour_text)
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        color = ContextCompat.getColor(context, R.color.on_surface)
        textAlign = Paint.Align.CENTER
    }
    private val eventDayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        color = context.themeColor(com.google.android.material.R.attr.colorPrimary)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val todayTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.5f * density
        color = context.themeColor(com.google.android.material.R.attr.colorOnPrimary)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val todayCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(com.google.android.material.R.attr.colorPrimary)
        style = Paint.Style.FILL
    }

    private var year: Int = LocalDate.now().year
    private var eventDays: Set<LocalDate> = emptySet()
    private var today: LocalDate = LocalDate.now()
    private val monthRects = mutableListOf<Pair<YearMonth, RectF>>()

    init {
        isClickable = true
        isFocusable = true
    }

    fun submit(newYear: Int, daysWithEvents: Set<LocalDate>, todayDate: LocalDate) {
        year = newYear
        eventDays = daysWithEvents
        today = todayDate
        contentDescription = context.getString(R.string.year_description, newYear)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val desiredHeight = (4 * 120 * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        monthRects.clear()
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        val cellWidth = (availableWidth - 2 * cellGap) / 3
        val cellHeight = (availableHeight - 3 * cellGap) / 4
        (1..12).forEach { monthValue ->
            val index = monthValue - 1
            val column = index % 3
            val row = index / 3
            val left = paddingLeft + column * (cellWidth + cellGap)
            val top = paddingTop + row * (cellHeight + cellGap)
            val rect = RectF(left, top, left + cellWidth, top + cellHeight)
            val month = YearMonth.of(year, monthValue)
            monthRects += month to rect
            drawMonth(canvas, month, rect)
        }
    }

    private fun drawMonth(canvas: Canvas, month: YearMonth, rect: RectF) {
        canvas.drawText(
            "${month.monthValue}月",
            rect.left + 2f * density,
            rect.top + monthTextPaint.textSize,
            monthTextPaint,
        )

        val gridLeft = rect.left
        val gridWidth = rect.width()
        val gridTop = rect.top + monthTextPaint.textSize + 4f * density
        val columnWidth = gridWidth / 7f
        MonthGrid.weekdayLabels.forEachIndexed { index, label ->
            canvas.drawText(
                label,
                gridLeft + (index + 0.5f) * columnWidth,
                gridTop + weekdayPaint.textSize,
                weekdayPaint,
            )
        }

        val daysTop = gridTop + weekdayPaint.textSize + 2f * density
        val rowHeight = (rect.bottom - daysTop) / MonthGrid.ROWS
        if (rowHeight <= 0f) return
        MonthGrid.cells(month).forEachIndexed { index, cell ->
            if (!cell.inMonth) return@forEachIndexed
            val column = index % 7
            val row = index / 7
            val centerX = gridLeft + (column + 0.5f) * columnWidth
            val centerY = daysTop + row * rowHeight + rowHeight / 2f
            val text = cell.date.dayOfMonth.toString()
            val baseline = centerY + dayPaint.textSize / 3f
            when {
                cell.date == today -> {
                    canvas.drawCircle(centerX, centerY, min(columnWidth, rowHeight) * 0.42f, todayCirclePaint)
                    canvas.drawText(text, centerX, baseline, todayTextPaint)
                }
                cell.date in eventDays -> canvas.drawText(text, centerX, baseline, eventDayPaint)
                else -> canvas.drawText(text, centerX, baseline, dayPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val month = monthAt(event.x, event.y)
            if (month != null) {
                onMonthClick?.invoke(month)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal fun monthAt(x: Float, y: Float): YearMonth? =
        monthRects.firstOrNull { it.second.contains(x, y) }?.first

    internal fun monthRectsForTest(): List<Pair<YearMonth, RectF>> = monthRects.toList()
}
