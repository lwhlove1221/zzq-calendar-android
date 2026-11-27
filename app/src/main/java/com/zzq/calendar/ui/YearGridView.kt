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
import java.time.LocalDate
import java.time.YearMonth

/**
 * Twelve month tiles with an event count; a whole tile is the tap target.
 * Month cells never expand to days — that text was unreadable at 3 columns.
 */
class YearGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onMonthClick: ((YearMonth) -> Unit)? = null

    private val dpScale = resources.displayMetrics.density
    private val cellGap = 10f * dpScale

    private val monthTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        setTextSize(24f * dpScale)
        color = ContextCompat.getColor(context, R.color.on_surface)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val countTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        setTextSize(15f * dpScale)
        color = ContextCompat.getColor(context, R.color.primary)
        textAlign = Paint.Align.CENTER
    }
    private val emptyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        setTextSize(15f * dpScale)
        color = ContextCompat.getColor(context, R.color.hour_text)
        textAlign = Paint.Align.CENTER
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.grid_line)
        style = Paint.Style.STROKE
        strokeWidth = 1f * dpScale
    }
    private val currentMonthStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dpScale
    }
    private val currentMonthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.today_tint)
        style = Paint.Style.FILL
    }

    private var year: Int = LocalDate.now().year
    private var monthCounts: Map<Int, Int> = emptyMap()
    private var today: LocalDate = LocalDate.now()
    private val monthRects = mutableListOf<Pair<YearMonth, RectF>>()

    init {
        isClickable = true
        isFocusable = true
    }

    fun submit(newYear: Int, counts: Map<Int, Int>, todayDate: LocalDate) {
        year = newYear
        monthCounts = counts
        today = todayDate
        contentDescription = context.getString(R.string.year_description, newYear)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val desiredHeight = (4 * CARD_HEIGHT_DP * dpScale + 3 * cellGap).toInt() + paddingTop + paddingBottom
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
        val isCurrentMonth = month.year == today.year && month.monthValue == today.monthValue
        val radius = 12f * dpScale
        if (isCurrentMonth) canvas.drawRoundRect(rect, radius, radius, currentMonthPaint)
        canvas.drawRoundRect(rect, radius, radius, if (isCurrentMonth) currentMonthStrokePaint else cardStrokePaint)

        val count = monthCounts[month.monthValue] ?: 0
        val monthSize = monthTextPaint.textSize
        val countSize = countTextPaint.textSize
        val gap = 8f * dpScale
        val firstBaseline = rect.centerY() - (monthSize + gap + countSize) / 2f + monthSize
        monthTextPaint.color = ContextCompat.getColor(
            context,
            if (isCurrentMonth) R.color.primary else R.color.on_surface,
        )
        canvas.drawText(
            context.getString(R.string.year_month_label, month.monthValue),
            rect.centerX(),
            firstBaseline,
            monthTextPaint,
        )
        canvas.drawText(
            if (count > 0) {
                context.getString(R.string.year_month_count, count)
            } else {
                context.getString(R.string.year_month_empty)
            },
            rect.centerX(),
            firstBaseline + gap + countSize,
            if (count > 0) countTextPaint else emptyTextPaint,
        )
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

    private companion object {
        const val CARD_HEIGHT_DP = 96f
    }

    internal fun monthRectsForTest(): List<Pair<YearMonth, RectF>> = monthRects.toList()

    internal fun monthCountsForTest(): Map<Int, Int> = monthCounts
}
